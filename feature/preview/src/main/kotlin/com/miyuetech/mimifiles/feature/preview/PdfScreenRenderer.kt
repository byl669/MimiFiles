package com.miyuetech.mimifiles.feature.preview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miyuetech.mimifiles.core.common.Formatters
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.preview.PreviewDispatchers
import com.miyuetech.mimifiles.feature.preview.databinding.ItemPdfPageBinding
import com.miyuetech.mimifiles.feature.preview.databinding.ViewPdfPreviewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * PDF 渲染器（系统 `PdfRenderer`，不引第三方引擎）。
 *
 * ## 为什么必须先落盘
 * `PdfRenderer` 的输入是 `ParcelFileDescriptor`，而它内部**大量随机访问**
 * （页对象、交叉引用表），所以必须是一个真正可 seek 的本机 fd。
 * 远端 PDF 因此要先经 `PreviewCache` 落到私有缓存（有容量上限，超限就放弃并提示）。
 * 这是"平台能力只吃 fd"的现实约束，不是设计失误——方案 §6.4 的"分页渲染"也建立在这个前提上。
 *
 * ## 内存控制
 * 一页渲染出来的位图在 1080 宽下约 `1080 × 1400 × 4 ≈ 6 MB`。列表里同时可见 2 页，
 * 加上预取最多 3~4 页。所以 [PageHolder.onViewRecycled] **必须**回收位图——
 * 不回收的话翻十页就是 60 MB。
 */
class PdfScreenRenderer(
    private val host: PreviewHost,
    private val path: FsPath,
) : PreviewRenderer {

    private var binding: ViewPdfPreviewBinding? = null

    private var descriptor: ParcelFileDescriptor? = null

    private var renderer: PdfRenderer? = null

    private var pageCount = 0

    private var loadJob: Job? = null

    private val adapter = PageAdapter()

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val created = ViewPdfPreviewBinding.inflate(inflater, parent, false)
        binding = created
        created.pdfPages.layoutManager = LinearLayoutManager(host.activity)
        created.pdfPages.adapter = adapter
        created.pdfPages.setItemViewCacheSize(CACHED_PAGES)
        start()
        return created.root
    }

    override val fullBleedContent: Boolean get() = false

    override val supportsZoom: Boolean get() = true

    override fun onResetZoom(): Boolean {
        binding?.pdfZoom?.resetZoom()
        return true
    }

    override fun release() {
        loadJob?.cancel()
        binding?.pdfZoom?.onScaleChanged = null
        binding?.pdfPages?.adapter = null
        binding = null
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    override fun infoRows(): List<InfoRow> = buildList {
        add(InfoRow(R.string.preview_info_path, path.toString()))
        if (pageCount > 0) add(InfoRow(R.string.preview_info_pages, pageCount.toString()))
    }

    private fun start() {
        loadJob = host.scope.launch {
            // 返回"失败原因的资源 id"，成功为 null。
            // 刻意不在 IO 线程里直接调 showFooter()：那是在非主线程碰 View。
            val failure = openRenderer()
            if (failure != null) {
                showFooter(host.activity.getString(failure))
                return@launch
            }
            host.setSubtitle(
                host.activity.getString(R.string.preview_pdf_page, 1, pageCount) +
                    "  " + path.displayName,
            )
            adapter.notifyDataSetChanged()
        }
    }

    /**
     * 准备渲染器。
     *
     * 两处容易踩的地方：
     *  1. **远端 PDF 必须先落盘**（见类注释）。[PreviewCache.materialize] 自带线程切换，
     *     所以这里不需要再包一层 `withContext`。
     *  2. **加密 PDF** 会让 `PdfRenderer` 构造抛 `SecurityException`，而不是"打不开"。
     *     不单独处理的话，用户只会看到一句无意义的"解析失败"。
     *
     * @return `null` 表示成功；否则是错误文案的字符串资源。
     */
    private suspend fun openRenderer(): Int? {
        val file = host.environment.cache.materialize(path)
            ?: return R.string.preview_failed_hint
        val fd = runCatching {
            withContext(PreviewDispatchers.io) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }.getOrNull() ?: return R.string.preview_failed_hint
        val opened = runCatching {
            withContext(PreviewDispatchers.io) { PdfRenderer(fd) }
        }.getOrNull()
        if (opened == null) {
            runCatching { fd.close() }
            return R.string.preview_pdf_encrypted
        }
        descriptor = fd
        renderer = opened
        pageCount = opened.pageCount
        return null
    }

    private fun showFooter(text: String) {
        val created = binding ?: return
        created.pdfFooter.visibility = View.VISIBLE
        created.pdfFooter.text = text
    }

    /**
     * 渲染一页到 [targetWidth] 宽的位图。
     *
     * ## 放大倍数上限 [MAX_RENDER_SCALE] 为什么是 3 而不是 2
     * 页面位图是在"未缩放"的尺寸下渲染的，之后由 `ZoomableContainer` 放大 ——
     * 也就是说**位图分辨率就是放大后的清晰度上限**。
     * A4 页原始宽度只有 595 pt：按 1080 px 屏渲染时比值是 1.8，
     * 放大到 2 倍就等于把 1080 px 的位图拉到 2160 px，小字立刻糊掉。
     * 取 3（A4 → 1785 px 宽，约 1785×2526 ≈ 18 MB）能把 2 倍放大维持在"看得出字形"，
     * 而同时把 [CACHED_PAGES] 收到 2 页，让最坏内存停在 2×18 MB ≈ 36 MB。
     *
     * ## 为什么不"放大后再重渲染更清晰的"
     * 那需要在手势结束后重绑当前页（重新 openPage + render，约 100 ms）并销毁旧位图，
     * 缩放过程中反复触发就会卡顿。相比之下"多花 18 MB 一次性渲染到位"更稳，
     * 也更符合本工程"功能与性能优先、内存只设正确性边界"的取舍（方案 §8.5）。
     */
    private suspend fun renderPage(index: Int, targetWidth: Int): Bitmap? =
        withContext(PreviewDispatchers.decode) {
            val current = renderer ?: return@withContext null
            runCatching {
                current.openPage(index).use { page ->
                    val ratio = (targetWidth.toFloat() / page.width).coerceIn(0.1f, MAX_RENDER_SCALE)
                    val width = (page.width * ratio).toInt().coerceAtLeast(1)
                    val height = (page.height * ratio).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PDF 页可能不透明；先刷白底，否则透明区在深色主题下会变成黑洞。
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.getOrNull()
        }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(position)
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.recycle()
        }
    }

    private inner class PageHolder(
        private val binding: ItemPdfPageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null

        private var bitmap: Bitmap? = null

        /** 当前位图对应的页号（-1 = 没有）。用于"同一页重绑时直接复用"。 */
        private var boundIndex = -1

        fun bind(index: Int) {
            // 同一页重新绑定（`notifyDataSetChanged` 之后会走到这里）：位图还在就直接用，
            // 否则会白白重渲染一次 —— 用户看到的是"当前页闪一下白"。
            if (boundIndex == index && bitmap?.isRecycled == false) return
            recycle()
            boundIndex = index
            binding.pdfPageImage.setImageDrawable(null)
            job = host.scope.launch {
                val metrics = host.activity.resources.displayMetrics
                val target = (metrics.widthPixels * RENDER_WIDTH_SLACK).toInt()
                // ① 先出一张粗解（约 1/4 边长）。
                //
                // ## 为什么要多花这一次渲染
                // 页位图的渲染是**异步**的（18 MB 那张要上百毫秒），而列表在放大后平移时
                // 会不断把新页滚进视口。若直接等全分辨率，用户看到的是"白纸闪一下再出内容"；
                // 放大后连续平移时就是一片闪频（真机反馈第 3 条）。
                // PDF 页的渲染成本与像素数成正比，粗解只要几毫秒，于是闪白变糊影。
                // 这是本工程对图片预览用过的同一套"渐进式"思路（见 `ImageGalleryRenderer`
                // 的三级清晰度）。
                val coarse = renderPage(index, target / COARSE_DIVISOR)
                if (coarse != null) setBitmap(coarse)
                // ② 再补全分辨率。
                val full = renderPage(index, target) ?: return@launch
                setBitmap(full)
            }
        }

        fun recycle() {
            job?.cancel()
            job = null
            // 位图必须显式回收：一页 6 MB，靠 GC 等到内存压力上来时已经晚了。
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = null
            boundIndex = -1
        }

        /**
         * 换上新的页位图并回收旧的。
         *
         * ⚠️ 顺序不能反：必须**先** `setImageBitmap` 再回收旧位图 ——
         * 反过来的话，`ImageView` 会有一瞬间引用着一张已回收的位图，
         * 那一帧会打出 `Canvas: trying to use a recycled bitmap` 并画不出东西。
         */
        private fun setBitmap(next: Bitmap) {
            val previous = bitmap
            bitmap = next
            binding.pdfPageImage.setImageBitmap(next)
            if (previous != null && previous !== next && !previous.isRecycled) {
                previous.recycle()
            }
        }
    }

    private companion object {
        /**
         * 列表外再缓存几页。配合 [MAX_RENDER_SCALE] = 3，最坏内存 ≈ 2 × 18 MB。
         * 取 2 而不是 3：翻页时多留一页的收益有限，而少 18 MB 是实打实的。
         */
        const val CACHED_PAGES = 2

        /** 渲染倍数上限，见 [renderPage] 的说明。 */
        const val MAX_RENDER_SCALE = 3f

        /**
         * 渲染宽度相对屏幕宽度的余量。
         *
         * 页面两侧本来就有 `content_padding` 的内边距，按"屏幕宽"渲染会略小于实际显示宽度；
         * 乘 1.2 抵消这部分，同时给放大留一点余量。
         */
        const val RENDER_WIDTH_SLACK = 1.2f

        /**
         * 粗解的分母：粗解宽度 = 目标宽度 / 4（像素数只有 1/16，耗时同比例下降）。
         *
         * 取 4 而不是 2 或 8：2 的粗解在 3 倍放大下仍然一眼能看出糊，
         * 8 则会让"文字块的排布"都看不清、失去"先给个形状"的意义。
         */
        const val COARSE_DIVISOR = 4
    }
}
