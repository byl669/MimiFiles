package com.miyuetech.mimifiles.feature.preview

import android.graphics.Rect
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.preview.MotionPhoto
import com.miyuetech.mimifiles.core.preview.PreviewImageSource
import com.miyuetech.mimifiles.feature.preview.databinding.ItemPreviewPageBinding
import com.miyuetech.mimifiles.feature.preview.databinding.ViewImageGalleryBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 图片画廊渲染器 —— 左右滑看同级图片，支持缩放/双击/分块补清晰度。
 *
 * ## 三级清晰度策略（这是"30 MB 照片 ≤ 500 ms 打开"与"1 GB 图放大不 OOM"能同时成立的原因）
 * ```
 * ① 缩略图（≤ 384 px，通常在磁盘缓存里）  → 立刻显示，用户看到"有东西"
 * ② 降采样整图（≈ 2 × 屏幕边长）          → 几百毫秒内替换，这就是静止时看到的那张
 * ③ 视口分块（只在放大后、且图够大时）      → 用户双指放大时才按需补一块，接近原图
 * ```
 * 关键是 ③ **只在手势停下来之后**才发起：滚动/缩放过程中发请求会立刻被下一个手势作废，
 * 白白把网络与 CPU 打满（现象是"越放大越卡"）。
 *
 * ## 为什么给"打不开的页"单独的提示而不是整页失败
 * 一个目录里有一张损坏的图是常态。让整屏变成错误页，用户就没法继续看别的照片了 ——
 * 所以错误落在**页级**（`page_error`），滑动照常。
 */
class ImageGalleryRenderer(private val host: PreviewHost) : PreviewRenderer {

    override val darkBackdrop: Boolean get() = true

    override val supportsChromeToggle: Boolean get() = true

    /**
     * 图片铺满全屏：相册惯例是"看不到界面边界"，而且单指单击是"切换沉浸"，
     * 补内边距反而会让照片被无谓地缩小。
     */
    override val fullBleedContent: Boolean get() = true

    override val supportsZoom: Boolean get() = true

    private var binding: ViewImageGalleryBinding? = null

    /**
     * 已打开的图片源（LRU，最多 [MAX_OPEN_SOURCES] 个）。
     *
     * 为什么要有上限：每个源都可能持有落盘缓存与分块解码器（含原生内存）。
     * 一屏算上左右预加载最多 3 个，留到 4 个是给"来回滑动"留余量。
     */
    private val openSources = object : LinkedHashMap<Int, PreviewImageSource>(6, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, PreviewImageSource>?,
        ): Boolean {
            if (size <= MAX_OPEN_SOURCES) return false
            eldest?.value?.close()
            return true
        }
    }

    private val adapter = PageAdapter()

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val created = ViewImageGalleryBinding.inflate(inflater, parent, false)
        binding = created
        created.galleryPager.adapter = adapter
        created.galleryPager.offscreenPageLimit = 1
        created.galleryPager.setCurrentItem(host.args.index, false)
        created.galleryPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    onPageChanged(position)
                }
            },
        )
        // 初次进入也算一次"翻页"，让副标题/动态照片检测对当前页生效。
        onPageChanged(host.args.index)
        return created.root
    }

    override fun onStart() = Unit

    override fun onStop() = Unit

    override fun release() {
        adapter.releaseAll()
        openSources.values.forEach { it.close() }
        openSources.clear()
        binding?.galleryPager?.adapter = null
        binding = null
    }

    override fun onResetZoom(): Boolean {
        val image = currentPageView() ?: return false
        image.resetZoom()
        return true
    }

    override fun infoRows(): List<InfoRow> = currentInfo ?: fallbackInfo

    /** 当前页的详情（加载完成后填充）。 */
    private var currentInfo: List<InfoRow>? = null

    private var fallbackInfo: List<InfoRow> = listOf(InfoRow(R.string.preview_info_path, "—"))

    private fun currentPageView(): ZoomableImageView? {
        val pager = binding?.galleryPager ?: return null
        val holder = (pager.getChildAt(0) as? RecyclerView)
            ?.findViewHolderForAdapterPosition(pager.currentItem) as? PageHolder
        return holder?.image
    }

    private fun onPageChanged(position: Int) {
        val path = host.args.paths.getOrNull(position) ?: return
        host.setSubtitle(
            host.activity.getString(R.string.preview_counter, position + 1, host.args.count) +
                "  " + path.displayName,
        )
        currentInfo = null
        // 动态照片检测只对"当前页"做：它要读文件尾 128 KB，逐页做等于滑动一次读 N 次。
        detectMotionPhoto(path, position)
    }

    // ── 动态照片（MotionPhoto）─────────────────────────────────

    private var motionJob: Job? = null

    private fun detectMotionPhoto(path: FsPath, position: Int) {
        motionJob?.cancel()
        motionJob = host.scope.launch {
            val detected = MotionPhoto.detect(path)
            if (detected.isMotionPhoto) {
                currentMotion = detected
                // 动态照片可能被命名成任意扩展名，所以标签文案用"动态照片"而不是具体来源。
                adapter.notifyItemChanged(position, PAYLOAD_MOTION)
                return@launch
            }
            // Apple Live Photo：视频在同目录的另一个文件里，要额外列一次目录，
            // 所以只在"看起来像"（HEIC/JPG）时才试。
            val ext = path.extension
            if (ext == "heic" || ext == "heif" || ext == "jpg" || ext == "jpeg") {
                val companion = MotionPhoto.findLivePhotoCompanion(path)
                if (companion != null) {
                    currentLiveCompanion = companion
                    adapter.notifyItemChanged(position, PAYLOAD_MOTION)
                }
            }
        }
    }

    private var currentMotion: MotionPhoto.Detected = MotionPhoto.Detected(MotionPhoto.Kind.NONE)

    private var currentLiveCompanion: FsPath? = null

    // ── 页 ──────────────────────────────────────────────────────

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = host.args.count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val inflater = LayoutInflater.from(parent.context)
            return PageHolder(ItemPreviewPageBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(host.args.paths[position], position)
        }

        /**
         * 局部刷新：只处理"动态照片徽标变了"这一种情况。
         *
         * 用 payload 而不是 `notifyItemChanged(position)` ——后者会让整页重绑，
         * 也就是**重新解码一次图片**。检测结果回来时用户很可能已经放大到某处，
         * 重绑会把他的缩放状态一起清掉。
         */
        override fun onBindViewHolder(holder: PageHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_MOTION)) {
                holder.showMotionBadge()
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.cancel()
        }

        fun releaseAll() {
            repeat(itemCount) { index ->
                openSources.remove(index)?.close()
            }
        }
    }

    private inner class PageHolder(
        private val binding: ItemPreviewPageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        val image: ZoomableImageView get() = binding.pageImage

        private var path: FsPath? = null

        private var position: Int = -1

        private var loadJob: Job? = null

        private var upgradeJob: Job? = null

        /** 本页自己解码产生的位图（可以安全回收；缩略图来自缓存，绝不能回收）。 */
        private var ownedBitmap: android.graphics.Bitmap? = null

        /**
         * 当前这张位图是否与分块器同一坐标系（见 `DecodedImage.regionDecodable`）。
         * 平台解码器路线为 `false`，此时不能按"原图坐标"去要分块。
         */
        private var regionDecodable = true

        fun bind(target: FsPath, targetPosition: Int) {
            cancel()
            path = target
            position = targetPosition
            regionDecodable = true
            binding.pageError.visibility = View.GONE
            binding.pageVideoOverlay.visibility = View.GONE
            binding.pageMotionBadge.visibility = View.GONE
            binding.pageProgress.visibility = View.VISIBLE
            binding.pageImage.setOrientation(0, false)

            if (isVideo(target)) {
                showVideoPoster(target)
                return
            }
            loadImage(target, targetPosition)
        }

        fun cancel() {
            loadJob?.cancel()
            upgradeJob?.cancel()
            loadJob = null
            upgradeJob = null
        }

        fun showMotionBadge() {
            val isCurrent = position == host.args.index
            if (!isCurrent) return
            binding.pageMotionBadge.visibility = View.VISIBLE
        }

        private fun showVideoPoster(video: FsPath) {
            loadJob = host.scope.launch {
                // `stat` 是挂起函数，必须在协程里调；顺带也解释了为什么
                // entryOf 不放在 bind()（bind 在主线程、非协程）。
                val entry = entryOf(video)
                val poster = host.environment.thumbnails.load(entry, POSTER_PX)
                if (poster == null) {
                    binding.pageProgress.visibility = View.GONE
                    binding.pageError.text = host.activity.getString(R.string.preview_failed_hint)
                    binding.pageError.visibility = View.VISIBLE
                } else {
                    binding.pagePoster.setImageBitmap(poster)
                    binding.pageProgress.visibility = View.GONE
                }
                binding.pageVideoOverlay.visibility = View.VISIBLE
            }
            binding.pagePlay.setOnClickListener { host.playFile(video) }
        }

        private fun loadImage(target: FsPath, targetPosition: Int) {
            loadJob = host.scope.launch {
                val entry = entryOf(target)
                // ① 缩略图先顶上。它通常已在磁盘缓存里（浏览页刚生成的），
                //    所以这一步往往在一帧内就完成，用户感觉是"瞬间出图"。
                val thumbnail = host.environment.thumbnails.load(entry, THUMB_PX)
                if (thumbnail != null) {
                    binding.pageImage.setWindow(
                        ImageWindow(
                            bitmap = thumbnail,
                            srcRect = RectF(0f, 0f, thumbnail.width.toFloat(), thumbnail.height.toFloat()),
                            contentWidth = thumbnail.width,
                            contentHeight = thumbnail.height,
                            sampleSize = Int.MAX_VALUE,
                        ),
                    )
                    binding.pageProgress.visibility = View.GONE
                }

                // ② 打开源 + 降采样整图
                val source = openSource(target, targetPosition) ?: run {
                    showPageError(R.string.preview_failed_hint)
                    return@launch
                }
                val meta = source.meta

                val target0 = baseDecodeTarget(meta.width, meta.height)
                val decoded = source.decodeForDisplay(target0)
                if (decoded == null) {
                    if (thumbnail == null) showPageError(R.string.preview_failed_hint)
                    return@launch
                }
                replaceOwnedBitmap(decoded.bitmap)
                // ⚠️ 顺序不能反：**先设方向再设窗口**。
                // `setOrientation` 会重建适配矩阵，而它读的是"当前窗口的内容尺寸"；
                // 若先设窗口再设方向，中间那一次重建会拿旧方向算一遍（虽然接着会被
                // 第二次重建覆盖，但会多做一次矩阵运算并触发一次无效 invalidate）。
                binding.pageImage.setOrientation(decoded.rotationDegrees, decoded.mirrored)
                binding.pageImage.setWindow(
                    ImageWindow(
                        bitmap = decoded.bitmap,
                        srcRect = decoded.sourceRect,
                        contentWidth = meta.width,
                        contentHeight = meta.height,
                        sampleSize = decoded.sample,
                    ),
                )
                regionDecodable = decoded.regionDecodable
                binding.pageProgress.visibility = View.GONE

                // 详情面板数据（EXIF 只在用户点开时读，这里只放不花钱的字段）
                if (targetPosition == host.args.index) {
                    currentInfo = buildInfo(target, meta)
                }

                // ③ 放大后补清晰度
                binding.pageImage.onSettled = { visible, currentSample ->
                    requestSharper(targetPosition, visible, currentSample)
                }
                // 单击切换"沉浸"：显隐工具栏与系统栏。这是相册的通用手势，
                // 也是"想看清整张图"最直接的诉求。
                binding.pageImage.onTap = {
                    host.setChromeVisible(!host.isChromeVisible)
                }
            }
        }

        private fun requestSharper(
            targetPosition: Int,
            visible: RectF,
            currentSample: Int,
        ) {
            val source = openSources[targetPosition] ?: return
            if (!source.meta.isLarge) return
            // 平台解码器（HEIC/AVIF）那条路的坐标系已被转正，与分块器的不同源，
            // 混用会把"转正的图"当"未转正的图"去切 —— 直接放弃补清晰度。
            if (!regionDecodable) return
            val desired = PreviewImageSource.sampleSizeFor(
                visible.width().toInt(),
                visible.height().toInt(),
                baseDecodeEdge(),
            )
            if (desired >= currentSample) return

            upgradeJob?.cancel()
            upgradeJob = host.scope.launch {
                // 手势刚停：等一小会儿再干活。停顿期间用户继续缩放的话，
                // 这个协程会被取消，不会有"解到一半白算"的浪费。
                delay(UPGRADE_DELAY_MS)
                val region = Rect(
                    visible.left.toInt(),
                    visible.top.toInt(),
                    visible.right.toInt(),
                    visible.bottom.toInt(),
                )
                val better = source.decodeRegion(region, desired) ?: return@launch
                replaceOwnedBitmap(better.bitmap)
                binding.pageImage.setWindow(
                    ImageWindow(
                        bitmap = better.bitmap,
                        srcRect = better.sourceRect,
                        contentWidth = source.meta.width,
                        contentHeight = source.meta.height,
                        sampleSize = better.sample,
                    ),
                    resetUserTransform = false,
                )
            }
        }

        /** 换掉上一张"自己解的"位图并回收它；缩略图不受影响（它属于缓存）。 */
        private fun replaceOwnedBitmap(next: android.graphics.Bitmap?) {
            val previous = ownedBitmap
            ownedBitmap = next
            if (previous != null && previous !== next && !previous.isRecycled) {
                previous.recycle()
            }
        }

        private fun showPageError(messageRes: Int) {
            binding.pageProgress.visibility = View.GONE
            binding.pageError.text = host.activity.getString(messageRes)
            binding.pageError.visibility = View.VISIBLE
        }

        /**
         * 基准解码的目标边长。
         *
         * ## 为什么不能只按"屏幕倍数"算（这是个真实的内存隐患）
         * 一开始写的是 `max(viewW,viewH) * 2`。在 1080×2400 屏上这是 4800 ——
         * 对一张 4200×4200 的图，求出的采样倍数是 **1**，也就是"按原尺寸解"：
         * `4200 × 4200 × 4 B ≈ 70 MB` 一张位图。而画廊最多同时持有 4 个页面源
         * （见 `MAX_OPEN_SOURCES`），最坏情况接近 300 MB —— 低内存设备必崩。
         *
         * 加上 [MAX_BASE_EDGE] 这个硬上限后，基准位图的峰值被钉死在
         * `2560² × 4 ≈ 26 MB`，与源图尺寸**无关**；而"放大看细节"由分块解码负责
         * （那正是它存在的意义）。两条路线配合起来才是"内存有界 + 放大清晰"。
         */
        private fun baseDecodeTarget(contentWidth: Int, contentHeight: Int): Int =
            minOf(baseDecodeEdge(contentWidth, contentHeight) * CLEAR_EDGE_FACTOR, MAX_BASE_EDGE)

        /**
         * 用于算采样倍数的"视图长边"。
         *
         * ## ⚠️ 为什么不能直接用 `viewPixelSize()`（M3.1 真机反馈第 2 条的根因）
         * `RecyclerView` 的绑定时序是 **`onBindViewHolder` → `addView` → `measure`**：
         * 绑定发生时新建的页面视图**宽度还是 0**。`viewPixelSize()` 会把 0 兜成 1，
         * 于是 `baseDecodeTarget` 算出 **2**，`sampleSizeFor` 为了"长边不超过 2"
         * 会把 4000 px 的图一路折半到 **2048 倍采样** —— 解出来是一张 **1×1 的位图**。
         * 而 [ZoomableImageView] 会把它按"整张原图"的比例铺满屏幕，
         * 屏幕上出现的就正好是**一块纯色色块**（真机反馈的原话："显示的单色色块"）。
         *
         * 真机复现的关键是 **ViewPager2 的预加载**：`offscreenPageLimit = 1` 会
         * 提前把左右邻页建出来并绑定一次。那一轮绑定时视图必然还没测量，
         * 于是邻页被解成 1×1 并**留在内存里**；等用户真的滑过去，`RecyclerView`
         * 不会重绑同一个位置，那块纯色就一直挂在那里 —— 这就是"翻 3~4 张之后
         * 接下来的每一张都是色块"的来源（前几张是首屏那批已测量的页面）。
         *
         * 为什么"往前滑几页再往后翻就好了"：滑回去会触发一次真正的重绑，
         * 那时视图已经测量过，读到的是真实尺寸。
         *
         * 修法：把明显不可能是真实尺寸的值判定为"尚未测量"，改用**屏幕长边**兜底。
         * 屏幕长边 ⊇ 页面视图长边（页面铺满内容区），所以兜底只会略微多解一点，
         * 永远不会解不足；也不需要在布局完成后重解一次。
         */
        private fun baseDecodeEdge(contentWidth: Int = 0, contentHeight: Int = 0): Int {
            val (viewW, viewH) = binding.pageImage.viewPixelSize()
            val viewLongest = maxOf(viewW, viewH)
            val screen = screenLongestEdge()
            val unmeasured = viewLongest < MIN_MEASURED_EDGE
            val edge = if (unmeasured) screen else viewLongest
            // 这条日志是**取证**，不是调试残留：`sample` 一旦变成 512 / 2048 这种量级，
            // 解出来的位图就只有 1~2 个像素，铺满屏幕就是一块纯色。
            // 冒烟脚本 grep 这一行，断言 `target` 恒 ≥ 屏幕长边的一半 ——
            // 也就是说：**不管视图测没测出来，都不允许退化成色块**。
            MimiLog.i(
                TAG,
                "decode target=$edge view=${viewW}x$viewH screen=$screen " +
                    "unmeasured=$unmeasured" +
                    if (contentWidth > 0) {
                        " content=${contentWidth}x$contentHeight " +
                            "sample=${PreviewImageSource.sampleSizeFor(contentWidth, contentHeight, edge)}"
                    } else {
                        ""
                    },
            )
            return edge
        }

        /** 屏幕长边（含系统栏）。未测量时的兜底值，比任何真实页面视图都大。 */
        private fun screenLongestEdge(): Int {
            val metrics = host.activity.resources.displayMetrics
            return maxOf(metrics.widthPixels, metrics.heightPixels)
        }

        private fun isVideo(target: FsPath): Boolean =
            MimeTypes.isVideo(MimeTypes.of(target.displayName))

        /**
         * 把路径变成 [FsEntry]。
         *
         * 之所以要多做一次 `stat`：缩略图缓存的键包含 **大小 + 修改时间**
         * （见 `ThumbnailKey` 的说明）。浏览页列表里的 `FsEntry` 已经带着这两个值，
         * 若这里用 `-1/0` 去取，缓存键对不上，就会**为同一张图再解一遍**。
         * 一次 stat 换一次缓存命中，是划算的。
         */
        private suspend fun entryOf(target: FsPath): FsEntry {
            val stat = runCatching { FileSystems.registry.resolve(target).stat(target) }.getOrNull()
            return stat ?: FsEntry(path = target, name = target.displayName, kind = FsKind.FILE)
        }

        private fun buildInfo(target: FsPath, meta: com.miyuetech.mimifiles.core.preview.ImageMeta): List<InfoRow> {
            val rows = mutableListOf<InfoRow>()
            rows += InfoRow(
                R.string.preview_info_size,
                "${meta.displayWidth} × ${meta.displayHeight}",
            )
            if (meta.byteSize > 0) {
                rows += InfoRow(
                    R.string.preview_info_bytes,
                    com.miyuetech.mimifiles.core.common.Formatters.size(meta.byteSize),
                )
            }
            rows += InfoRow(R.string.preview_info_path, target.toString())
            return rows
        }

        private suspend fun openSource(target: FsPath, targetPosition: Int): PreviewImageSource? {
            openSources[targetPosition]?.let { return it }
            val opened = PreviewImageSource.open(
                context = host.activity,
                path = target,
                cache = host.environment.cache,
                documentAuthority = host.documentAuthority,
            ) ?: return null
            openSources[targetPosition] = opened
            return opened
        }
    }

    private companion object {

        const val TAG = "MimiGallery"

        const val MAX_OPEN_SOURCES = 4

        /** 缩略图请求边长：略大于列表用值，因为它会被放大填满一屏的一部分。 */
        const val THUMB_PX = 384

        const val POSTER_PX = 720

        /**
         * 降采样整图的目标边长 = 屏幕长边 × [CLEAR_EDGE_FACTOR]，但不超 [MAX_BASE_EDGE]。
         *
         * 取 2 倍而不是 1：用户放大一点点就要补块的话，会频繁触发分块解码。
         * 2 倍意味着"放大到 2× 之前都不用补"。
         */
        const val CLEAR_EDGE_FACTOR = 2

        /**
         * 基准位图的边长硬上限。
         *
         * 2560 的依据：`2560² × 4 B ≈ 26 MB`，在 minSdk 24 的设备上（堆常常只有
         * 128~256 MB）属于"能安稳持有 2~3 张"的量级。再往上，画廊的预加载就会
         * 把内存顶到不可控。
         */
        const val MAX_BASE_EDGE = 2560

        /**
         * "视图真的测量过了"的下限。
         *
         * 320 px 的依据：任何屏宽下的一个页面视图都远大于它（最小的手机屏宽也有
         * 720 px），而"未测量"这个状态的典型取值恰好是 1（`viewPixelSize` 把 0 兜底）。
         * 取 320 是给"分屏 / 小窗"留足余量，同时把 1 这种"没有尺寸"的值排除掉。
         */
        const val MIN_MEASURED_EDGE = 320

        const val UPGRADE_DELAY_MS = 220L

        const val PAYLOAD_MOTION = "motion"
    }
}
