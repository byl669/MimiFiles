package com.miyuetech.mimifiles.feature.preview

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.miyuetech.mimifiles.core.bootstrap.Backends
import com.miyuetech.mimifiles.core.common.Authorities
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.preview.ContentProbe
import com.miyuetech.mimifiles.core.preview.ExternalOpen
import com.miyuetech.mimifiles.core.preview.PreviewArgs
import com.miyuetech.mimifiles.core.preview.PreviewEnvironment
import com.miyuetech.mimifiles.core.preview.PreviewKind
import com.miyuetech.mimifiles.core.preview.PreviewRoute
import com.miyuetech.mimifiles.core.preview.PreviewRouter
import com.miyuetech.mimifiles.core.ui.FeatureRoutes
import com.miyuetech.mimifiles.core.ui.applySystemBarIconAppearance
import com.miyuetech.mimifiles.core.ui.enableEdgeToEdge
import com.miyuetech.mimifiles.core.ui.padForSystemBars
import com.miyuetech.mimifiles.feature.preview.databinding.ActivityPreviewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 统一预览入口 —— 方案 §6.1 那张"三重判定 → 三条出路"的落点。
 *
 * ## 一个 Activity 承载四种渲染器 + 一条外发路径
 * ```
 * 点开文件 → ContentProbe（扩展名 + 512 字节魔数）
 *      ├─ BuiltIn   → ImageGallery / Text / PDF / Audio 渲染器（或转给视频播放器）
 *      ├─ External  → ACTION_VIEW + 我们 DocumentsProvider 的 content://
 *      └─ Unsupported → 明确说明打不开，并给"用其他应用打开"的按钮
 * ```
 *
 * ## 为什么外发走"自己的 DocumentsProvider"而不是 FileProvider
 * 本机文件的 `file://` 在 targetSdk ≥ 24 上会触发 `FileUriExposedException`，
 * 而远端文件根本没有 `file://`。我们的 `:provider` 已经把 `local` / `smb` / `webdav`
 * 全部注册进同一张注册表（见 `Backends.install`），于是 `documentId = FsPath.toString()`
 * 这一条约定就能同时覆盖本机与网络——**一个 provider 顶两个**，不必再引 FileProvider。
 *
 * ## 顶栏/底栏与"沉浸"
 * 图片/视频的背景是纯黑（相册惯例），此时工具栏图标必须是白色；
 * 文本/PDF 是浅底，图标要用主题色。由渲染器的 [PreviewRenderer.darkBackdrop] 决定。
 */
class PreviewActivity : AppCompatActivity(), PreviewHost {

    private lateinit var binding: ActivityPreviewBinding

    private var renderer: PreviewRenderer? = null

    override lateinit var args: PreviewArgs
        private set

    override lateinit var environment: PreviewEnvironment
        private set

    override val activity: AppCompatActivity get() = this

    override val documentAuthority: String get() = Authorities.DOCUMENTS

    override val scope: CoroutineScope get() = lifecycleScope

    /** 顶栏/底栏是否可见（供"点一下沉浸"切换）。 */
    private var chromeVisible = true

    /**
     * 为"交给外部应用"落地后的本地路径。
     *
     * 只可能从 `null` 变成非空（这次页面生命周期内不会再变回去）：转屏重建后重新算一次
     * 也只会有"命中缓存"这一次廉价操作，因为暂存文件是按内容指纹命名的。
     */
    private var stagedPath: FsPath? = null

    override val isChromeVisible: Boolean get() = chromeVisible

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val parsed = PreviewArgs.parse(intent)
        if (parsed == null) {
            toast(getString(R.string.preview_invalid_args))
            finish()
            return
        }
        args = parsed
        // 防御性装配后端。实测中这一步通常是**空操作**：应用启动时系统会先创建
        // `MimiDocumentsProvider`，它的 `onCreate` 已经把全部后端注册好了
        // （logcat 里能看到 "backends already installed, skipping"）。
        // 保留它的理由是"不依赖那个巧合"：provider 一旦被裁剪、禁用或被
        // `tools:node="remove"` 掉，预览页就会面对一张空注册表，而症状是
        // 「图片打不开、缩略图不生成」加上一句泛泛的失败提示 —— 极难归因。
        // `Backends.install` 幂等，代价是一次 AtomicBoolean 判断。
        Backends.install(this)
        environment = PreviewEnvironment.of(this, Authorities.DOCUMENTS)

        enableEdgeToEdge()
        binding.previewToolbar.padForSystemBars(top = true, bottom = false)
        binding.previewBottom.padForSystemBars(top = false, bottom = true)

        binding.previewToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.previewBtnInfo.setOnClickListener { showInfo() }
        binding.previewBtnOpenWith.setOnClickListener { requestExternalOpen() }
        binding.previewBtnReset.setOnClickListener {
            renderer?.onResetZoom()?.let { handled ->
                if (!handled) toast(getString(R.string.preview_action_reset_zoom))
            }
        }

        route()
    }

    override fun onStart() {
        super.onStart()
        renderer?.onStart()
    }

    override fun onStop() {
        renderer?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        renderer?.release()
        renderer = null
        super.onDestroy()
    }

    // ── 路由 ────────────────────────────────────────────────────

    private fun route() {
        showLoading(true)
        scope.launch {
            val path = args.current
            val probe = runCatching { environment.probe.probe(entryOf(path)) }.getOrNull()
            if (probe == null) {
                showMessage(R.string.preview_failed_title, R.string.preview_failed_hint, false)
                return@launch
            }
            if (probe.corrected) {
                // 魔数与扩展名不一致时说一句。不说的话，用户会以为"这个应用把我的
                // .bin 当图片打开了"，而实际上它只是比扩展名更懂文件内容。
                toast(
                    getString(
                        R.string.preview_magic_corrected,
                        probe.magic ?: probe.kind.name,
                    ),
                )
            }
            when (val route = PreviewRouter.route(probe)) {
                is PreviewRoute.BuiltIn -> showBuiltIn(route.kind, probe)
                is PreviewRoute.External -> {
                    openExternally(
                        path = path,
                        mime = PreviewRouter.mimeFor(probe, path.displayName),
                        needsStaging = route.kind == PreviewKind.PACKAGE,
                    )
                }

                is PreviewRoute.Unsupported -> showMessage(
                    R.string.preview_unsupported_title,
                    R.string.preview_unsupported_hint,
                    actionVisible = true,
                )
            }
        }
    }

    /**
     * 交给其它应用打开 —— 本页**唯一**的外发出口。
     *
     * ## 为什么要先落地再外发
     * 外发走的是我们自己的 `DocumentsProvider`：远端文件在 provider 里是**按需流式读**的。
     * 这对"看一眼"的消费者没问题，但系统安装器不是"看一眼"——它要把整包顺序读完、
     * 算摘要、再随机读几段去解析签名块。每一次随机读在远端都是一次网络往返，
     * 于是 30 MB 的包变成上千次往返，安装器会一直卡在「正在准备安装应用」，
     * 直到它自己的 session 超时。**而网络其实一直是好的**，所以纯看现象会误判成网络问题。
     *
     * 落地之后安装器读的是本地文件，毫秒级读完。代价是"弹出安装界面之前先等一次下载"，
     * 但那段时间在我们自己的进程里：有进度、失败可提示、可重试。
     *
     * @param needsStaging 是否值得落地。由**路由结论**给出（`PreviewKind.PACKAGE`），
     *        而不是在这里重新嗅探一遍类型——那会让"决策"有两个来源。
     */
    private suspend fun openExternally(path: FsPath, mime: String?, needsStaging: Boolean) {
        val staging = stageForExternal(path, needsStaging)
        if (staging is Staging.Failed) {
            showMessage(
                R.string.preview_staging_failed,
                R.string.preview_staging_failed_hint,
                actionVisible = true,
            )
            return
        }
        val target = (staging as? Staging.Ready)?.path ?: path
        // 先收掉等待层：成功的话这个页面马上会被外部应用盖住，失败的话 showMessage 会自己处理。
        showLoading(false)
        if (!ExternalOpen.open(this, target, mime)) {
            showMessage(
                R.string.preview_unsupported_title,
                R.string.preview_no_external_app,
                actionVisible = true,
            )
        }
    }

    /**
     * 外发前的落地结论。
     *
     * 做成 sealed 而不是"返回可空路径 + 一个布尔标记"，是因为三件事必须被区分开：
     * 「不需要落地」「已经就绪」「想落地但失败了」。前者与后者的处置**完全相反**——
     * 一个照常外发，另一个必须拦住（拦不住的话用户只会看到一个注定超时的安装界面）。
     */
    private sealed interface Staging {
        /** 不必落地：本地文件，或本来就不是安装包。外发继续用原路径。 */
        data object NotNeeded : Staging

        /** 可以外发了，用这个路径（落地后的本地文件，或已经是本地文件的原路径）。 */
        data class Ready(val path: FsPath) : Staging

        /** 该落地却没成。调用方应给提示，而不是把注定超时的外发发出去。 */
        data object Failed : Staging
    }

    /**
     * 按需把文件抓到本机。
     *
     * 本机文件直接跳过（`materializeForShare` 内部也会短路，但在这里拦住能省掉一次
     * 协程切换与一次 `File.isFile`）；非安装包不落地，因为这条路上唯一的"整包读取者"
     * 就是安装器，其它阅读器都是懒加载，落地反而让用户多等。
     */
    private suspend fun stageForExternal(path: FsPath, needed: Boolean): Staging {
        stagedPath?.let { return Staging.Ready(it) }
        if (!needed) return Staging.NotNeeded
        if (path.scheme == LOCAL_SCHEME) return Staging.NotNeeded

        showLoading(true, R.string.preview_staging_installer)
        // 不预先 stat 拿大小：那要多一次远端往返，而 `materializeForShare` 在写入过程中
        // 会用**真实读到的字节数**逐块校验上限，那份校验才是可信的。
        val file = runCatching { environment.cache.materializeForShare(path) }.getOrNull()
        if (file == null) {
            showLoading(false)
            // ⚠️ 这里**不**退回"直连流式外发"。那句话曾经是这条日志的文案，
            // 后来落地失败的分支改成了**直接拦住**（见本函数的 KDoc），文案却留了下来——
            // 一条撒谎的日志比没有日志更糟：会让人以为"只是降级了、还是能装的"。
            MimiLog.w(TAG, "暂存失败：整段读取未能完成，已拦住外发（不把注定超时的 URI 交给安装器）：$path")
            return Staging.Failed
        }
        val local = FsPath.of(LOCAL_SCHEME, "", file.absolutePath)
        stagedPath = local
        MimiLog.d(TAG, "已暂存：$path -> ${file.absolutePath}（${file.length() / 1024} KB）")
        return Staging.Ready(local)
    }

    private fun showBuiltIn(kind: PreviewKind, probe: com.miyuetech.mimifiles.core.preview.ProbeResult) {
        val path = args.current
        val created: PreviewRenderer = when (kind) {
            // 视频：不在这里渲染，直接交给播放器（它有手势、画中画、断点续播）。
            // 传整个同级列表，让它也能"上一个/下一个"。
            PreviewKind.VIDEO -> {
                launchPlayer(args)
                return
            }

            PreviewKind.IMAGE, PreviewKind.ANIMATED_IMAGE -> ImageGalleryRenderer(this)
            PreviewKind.TEXT -> TextRenderer(this, path)
            PreviewKind.PDF -> PdfScreenRenderer(this, path)
            PreviewKind.AUDIO -> AudioRenderer(this, path)
            PreviewKind.ARCHIVE -> ArchiveRenderer(this, path)
            else -> {
                showMessage(
                    R.string.preview_unsupported_title,
                    R.string.preview_unsupported_hint,
                    actionVisible = true,
                )
                return
            }
        }

        showLoading(false)
        binding.previewMessage.visibility = View.GONE
        renderer = created
        val view = created.createView(layoutInflater, binding.previewContainer)
        binding.previewContainer.removeAllViews()
        binding.previewContainer.addView(view)
        // ⚠️ 非铺满型渲染器必须躲开状态栏与顶栏 —— 预览页是 edge-to-edge 的，
        // 而工具栏是**叠在内容之上**的（同层 FrameLayout），不会自动把内容推下去。
        // 真机实测：TXT 正文顶部与 PDF 首页都顶到状态栏里、并被标题压住。
        if (!created.fullBleedContent) {
            padContentBelowTopChrome(view)
        }
        if (created.darkBackdrop) {
            binding.previewContainer.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, com.miyuetech.mimifiles.core.ui.R.color.gallery_bg),
            )
            applyChromeColors(dark = true)
        } else {
            binding.previewContainer.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, com.miyuetech.mimifiles.core.ui.R.color.bg_window),
            )
            applyChromeColors(dark = false)
        }
        // 底栏的"复位缩放"对可缩放的渲染器有意义（图片画廊与 PDF）。
        binding.previewBtnReset.visibility =
            if (created.supportsZoom) View.VISIBLE else View.GONE
        created.onStart()
        if (probe.mime.isNotEmpty()) {
            binding.previewToolbar.title = path.displayName
        }
    }

    /**
     * 顶栏/底栏的图标与文字颜色。
     *
     * 深色背景（图片/视频的纯黑底）必须用白色图标；浅色背景用主题色。
     * 传反了的后果是"深色背景上出现深色图标"，等于看不见。
     */
    private fun applyChromeColors(dark: Boolean) {
        val color = if (dark) android.graphics.Color.WHITE else
            androidx.core.content.ContextCompat.getColor(this, com.miyuetech.mimifiles.core.ui.R.color.text_primary)
        binding.previewToolbar.setTitleTextColor(color)
        binding.previewToolbar.setSubtitleTextColor(
            if (dark) 0xB3FFFFFF.toInt() else
                androidx.core.content.ContextCompat.getColor(this, com.miyuetech.mimifiles.core.ui.R.color.text_secondary),
        )
        binding.previewToolbar.navigationIcon?.setTint(color)
    }

    /**
     * 让内容躲开顶栏（顶栏本身已经吃掉状态栏的 insets）。
     *
     * ## 为什么偏移量就是"顶栏的高度"，而不是"状态栏 + 顶栏高度"
     * 顶栏是 `layout_gravity="top"` 的同层 View，它的布局盒从 y=0 开始，
     * 并且**自己**用 `padForSystemBars` 把状态栏占位加成了内部 padding ——
     * 所以它的盒高（实测 168 px）里**已经包含**状态栏（63 px）。
     *
     * 于是"再额外加一次状态栏高度"就会多算 63 px：内容会比顶栏低一截，
     * 看起来像是无谓的留白。本工程实测就是这么发现的（先写了 `statusInset + barHeight`，
     * 量出来内容比顶栏低 63 px）。
     *
     * **更稳的写法是只用顶栏的布局盒下边界**：无论顶栏将来改高度、还是 insets 策略变化，
     * "内容从顶栏下边界开始"这句话永远成立，不需要跟着调公式。
     *
     * ## 为什么要等一次布局
     * `showBuiltIn` 发生在 `onCreate` 里，此时顶栏还没测量（`height == 0`）。
     * 所以先注册监听（拿到 insets 机会），再在顶栏布局完成后主动重新应用一次。
     */
    private fun padContentBelowTopChrome(view: View) {
        val baseTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            target.updatePadding(top = baseTop + topChromeHeight())
            insets
        }
        binding.previewToolbar.doOnLayout {
            // 顶栏量出来了 → 重新走一遍上面的监听，用真实高度替换保守估计值。
            ViewCompat.requestApplyInsets(view)
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 顶栏占位高度：优先用实测值；尚未布局时退回"状态栏 + actionBarSize"。 */
    private fun topChromeHeight(): Int {
        val measured = binding.previewToolbar.height
        if (measured > 0) return measured
        val statusBar = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        return statusBar + resolveActionBarHeight()
    }

    /** `?attr/actionBarSize` 的像素值（顶栏还没布局时的估算用）。 */
    private fun resolveActionBarHeight(): Int {
        val typed = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, typed, true)) {
            android.util.TypedValue.complexToDimensionPixelSize(typed.data, resources.displayMetrics)
        } else {
            0
        }
    }

    // ── PreviewHost ─────────────────────────────────────────────

    override fun setSubtitle(text: CharSequence?) {
        binding.previewToolbar.subtitle = text
    }

    override fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        binding.previewToolbar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.previewBottom.visibility = if (visible) View.VISIBLE else View.GONE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun toast(text: CharSequence) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 渲染器要求"交给其它应用打开"时走这里（同步语义，接口是同步的）。
     *
     * 落地**不在这里做**——那是 IO，接口给不了它挂起的机会。真正落地发生在
     * [openExternally] 里，落好之后把路径记进 [stagedPath]，这里只负责用它。
     */
    override fun openWithExternalApp(mime: String?): Boolean =
        ExternalOpen.open(this, stagedPath ?: args.current, mime)

    override fun playFile(path: FsPath, sliceOffset: Long, sliceLength: Long) {
        launchPlayer(PreviewArgs.single(path, sliceOffset, sliceLength))
    }

    private fun launchPlayer(playerArgs: PreviewArgs) {
        val target = FeatureRoutes.videoPlayer(this) ?: run {
            toast(getString(R.string.preview_unsupported_title))
            return
        }
        PreviewArgs.putExtras(target, playerArgs)
        startActivity(target)
        // 视频交给播放器之后，这个页面就没有存在意义了 —— 否则返回时会看到
        // 一个只剩海报的"预览页"，与用户心理模型不符。
        finish()
    }

    // ── 辅助 ────────────────────────────────────────────────────

    private suspend fun entryOf(path: FsPath): FsEntry =
        runCatching { FileSystems.registry.resolve(path).stat(path) }.getOrNull()
            ?: FsEntry(path = path, name = path.displayName, kind = FsKind.FILE)

    private fun showLoading(loading: Boolean, textRes: Int = 0) {
        binding.previewProgress.visibility = if (loading) View.VISIBLE else View.GONE
        // 文案与转圈同生共死：留下一个孤零零的"正在准备安装包…"比什么都不显示更糟，
        // 因为那时页面已经可以正常操作了。
        val showText = loading && textRes != 0
        binding.previewProgressText.visibility = if (showText) View.VISIBLE else View.GONE
        if (showText) binding.previewProgressText.setText(textRes)
    }

    private fun showMessage(titleRes: Int, hintRes: Int, actionVisible: Boolean) {
        showLoading(false)
        binding.previewMessage.visibility = View.VISIBLE
        binding.previewMessageTitle.setText(titleRes)
        binding.previewMessageHint.setText(hintRes)
        binding.previewMessageAction.visibility = if (actionVisible) View.VISIBLE else View.GONE
        binding.previewMessageAction.setOnClickListener { requestExternalOpen() }
        setChromeVisible(true)
    }

    /**
     * 「用其他应用打开」按钮（以及"不支持预览"兜底页上的同一颗按钮）的入口。
     *
     * 走 [openExternally] 而不是直接调 `ExternalOpen.open`：用户在这条路上点开一个
     * 远程安装包时，同样需要"先落地"那一步——不然症状与从列表点开完全一样。
     */
    private fun requestExternalOpen() {
        val path = args.current
        scope.launch {
            openExternally(path, null, needsStaging = isInstallerPackage(path))
        }
    }

    /**
     * 按后缀判断是不是安装包。
     *
     * 这里不复用 `ContentProbe` 的结论：那条路要么已经在 [route] 里跑过（结论就是
     * `needsStaging`），要么（"不支持预览"的兜底页）根本拿不到像样的结论，
     * 再探一次只是让用户多点一次 IO。后缀判断的误判方向也是安全的——
     * 一个改了名字的 `.apk` 最多是"多落地了一次"，不会打不开。
     */
    private fun isInstallerPackage(path: FsPath): Boolean =
        MimeTypes.of(path.displayName) == MIME_INSTALLER

    /** 详情面板。数据由当前渲染器给出——它才知道自己有哪些字段（编码、页数、尺寸…）。 */
    private fun showInfo() {
        val rows = renderer?.infoRows().orEmpty()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad, pad, pad)
        }
        container.addView(
            TextView(this).apply {
                text = args.current.displayName
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        for (row in rows) {
            val line = TextView(this).apply {
                text = if (row.labelRes != 0) {
                    getString(row.labelRes) + "：" + row.value
                } else {
                    row.value
                }
                textSize = 14f
                setPadding(0, (resources.displayMetrics.density * 8).toInt(), 0, 0)
            }
            container.addView(line)
        }
        if (rows.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    setText(R.string.preview_loading)
                    textSize = 14f
                },
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.preview_info_title)
            .setView(container)
            .setPositiveButton(R.string.preview_info_close, null)
            .show()
    }

    private companion object {
        const val TAG = "MimiPreview"

        const val LOCAL_SCHEME = "local"

        const val MIME_INSTALLER = "application/vnd.android.package-archive"
    }
}
