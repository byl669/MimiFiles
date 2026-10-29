package com.miyuetech.mimifiles.feature.player

import android.app.Presentation
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import android.view.TextureView
import com.miyuetech.mimifiles.core.bootstrap.Backends
import com.miyuetech.mimifiles.core.common.AppScope
import com.miyuetech.mimifiles.core.common.Formatters
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.datastore.MimiDatabase
import com.miyuetech.mimifiles.core.datastore.PlaybackRepository
import com.miyuetech.mimifiles.core.datastore.SettingsRepository
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fs.FsMutations
import com.miyuetech.mimifiles.core.fs.toFsException
import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.model.has
import com.miyuetech.mimifiles.core.preview.ExternalOpen
import com.miyuetech.mimifiles.core.preview.PreviewArgs
import com.miyuetech.mimifiles.core.preview.PreviewUri
import com.miyuetech.mimifiles.core.preview.SeekableSourceDataSource
import com.miyuetech.mimifiles.core.ui.applySystemBarIconAppearance
import com.miyuetech.mimifiles.core.ui.enableEdgeToEdge
import com.miyuetech.mimifiles.core.ui.padForSystemBars
import com.miyuetech.mimifiles.feature.player.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 视频播放页。
 *
 * ## 这一页存在的意义：把"网络存储上的视频能拖进度条"变成真的
 * 数据源是 [SeekableSourceDataSource]（M3 在 `:core:preview` 里落地）。
 * 播放器说"给我第 2 GB 起的这一帧"，我们就向后端要那一段——不下载整个文件。
 * 于是"NAS 上一个 4 GB 的片子"和"手机本地一个 100 MB 的片子"在体验上没有区别。
 *
 * ## 回退链路（方案 §6.3 那张表）
 * ```
 * ① 硬解直出（MediaCodec，Media3 默认）
 * ② 系统软解（`c2.android.*` / `OMX.google.*`）—— M6 落地，见 [softwareFirstSelector]
 * ③ 明确的失败提示 + 「用其他应用打开」
 * ```
 * ⚠️ **v1.6 更正**：这里原先写的理由是"它不符合轻量化"（FFmpeg 扩展要把几 MB 原生库
 * 打进包里），而**体积约束已由用户放宽（功能完整 > 运行性能 > 体积）**，该理由不再成立，
 * 于是**决定也一并改判**：第 ② 档列入补强，选型与体积代价见方案 §8.1.1。
 *
 * 留下这段更正的用意是方法论：**别用会过期的理由支撑决定**——约束一变，旧理由会把
 * 后来人引向错误结论（"体积放开了 → 那就该引 LibVLC"），而真正的判据应该是
 * "是否沿用现有架构、能否只编所需解码器"这类不随预算变化的东西。
 *
 * M6 落地时在三条候选路径（方案 §8.1.1）里选了**零体积代价**的那一档：
 * 系统软件解码器。它救的是"厂商硬解不吃这个码流"，救不了"设备上根本没有该编码的
 * 解码器"——后者要自带解码器（`media3-decoder-ffmpeg` / LibVLC），
 * 而方案的选型前提是"先拿到真实硬解失败样本"，目前没有样本，所以不付那份代价。
 * 这条边界写在 [softwareFirstSelector] 的注释里，不藏着。
 *
 * ## 断点续播
 * 上层是 [PlaybackRepository]（Room）。写入时机只有三个：暂停、离开页面、销毁进程前。
 * **不按时间轮询保存**：那会让闪存写入放大到无意义的程度，而且播放中频繁写库
 * 还会与读取冲突。
 */
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding

    private lateinit var args: PreviewArgs

    private var player: ExoPlayer? = null

    private lateinit var playback: PlaybackRepository

    /** 全局设置（M7.6：读「默认视频播放速度」）。 */
    private lateinit var settings: SettingsRepository

    /** 当前位置是否真的是"续播"上来的（用于提示一次，且只提示一次）。 */
    private var resumedFrom: Long = 0L

    private var resumeApplied = false

    /** 本次页面会话里是否已经提示过续播（避免旋转屏幕后重复提示）。 */
    private var resumeNoticeShown = false

    // ── M7：实际解码器跟踪（角标 + 信息面板的数据源）──────────────
    //
    // 为什么判"实际用了谁"而不是"有没有发生过软解重试"：重试之后 Media3 会按
    // 排序后的列表继续挑，如果软解解不了它仍会落回硬解——那时用户看到的角标
    // 若还亮着就是撒谎。所以以 `onDecoderInitialized` 报上来的名字为准，
    // 每次换片清空重算（见 [play]）。
    @Volatile
    private var videoDecoderName: String? = null

    @Volatile
    private var audioDecoderName: String? = null

    // ── M7.19：左右竖向进度条 ────────────────────────────────────

    /** 竖条拖动中：此时不让播放时钟把滑块位置改回去。 */
    private var scrubbingVertically = false

    /** 控制条原本的自动隐藏时长（拖动竖条期间会被临时置 0）。 */
    private var controllerShowTimeoutMs = 0

    /**
     * 竖条位置时钟：只在竖条可见时跑（控制条收起就停）。
     * 控制条自带的时间轴是 Media3 内部在推，我们这层没有别的钩子可用，
     * 200ms 一次足够跟上肉眼，也远低于 Media3 自己的刷新代价。
     */
    private val verticalSeekTicker = object : Runnable {
        override fun run() {
            syncVerticalSeek()
            binding.root.postDelayed(this, VERTICAL_SEEK_TICK_MS)
        }
    }

    // ── M7.14：外接显示器播放 ────────────────────────────────────
    //
    // 与 VLC-android 同一条技术路线（其 gui/video/DisplayManager.kt）：
    // 副屏用 android.app.Presentation 承载（挂在指定 Display 上的独立窗口），
    // 播放器只把**视频输出 Surface** 切过去 —— 解码管线、进度、倍速、字幕选择
    // 仍然是主屏这唯一的 ExoPlayer 实例，主屏控制条天然继续可用。
    //
    // 镜像型外接（多数电视盒子/同屏线）由系统直接复制主屏画面，不需要本功能；
    // 这里处理的是**扩展型**显示（HDMI 桌面模式 / 部分投屏），系统会把它列为
    // DISPLAY_CATEGORY_PRESENTATION —— 此时视频画在副屏、手机屏黑屏出控制。
    private var displayManager: DisplayManager? = null

    /** 当前副屏窗口；`null` = 视频在主屏正常渲染。 */
    private var presentation: VideoPresentation? = null

    private var presentationDisplayId = -1

    /** 换片/软解重试重建 player 后，把副屏 Surface 重新接给新实例用。 */
    @Volatile
    private var presentationHolder: SurfaceHolder? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            // overlay/投屏 display 刚上报时窗口链路可能还没就绪（实测：立即
            // attach 会 show() 失败且之后没有 added 事件可再触发），延一拍再挂。
            binding.root.postDelayed({ refreshExternalDisplay() }, 800)
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == presentationDisplayId) detachExternalDisplay()
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val parsed = PreviewArgs.parse(intent)
        if (parsed == null || parsed.count == 0) {
            finish()
            return
        }
        args = parsed
        // 与预览页同理：防御性装配（通常已由 `MimiDocumentsProvider.onCreate` 完成）。
        // 少了它，播放页在"provider 不在"的环境里会一直缓冲不播，且看不出原因。
        Backends.install(this)
        playback = PlaybackRepository(MimiDatabase.get(this))
        settings = SettingsRepository(this)

        enableEdgeToEdge()
        binding.playerTop.padForSystemBars(top = true, bottom = false)
        applySystemBarIconAppearance(lightBackground = false)

        binding.playerBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.playerPrev.setOnClickListener { playNeighbour(-1) }
        binding.playerNext.setOnClickListener { playNeighbour(1) }
        binding.playerRotate.setOnClickListener { toggleOrientation() }
        binding.playerSubtitle.setOnClickListener { showSubtitleDialog() }
        // 长按回到"跟随系统"。之所以要给这个出口：点了横屏之后就被锁住了，
        // 没有出口的话用户只能靠退出页面来恢复自动旋转 —— 那是很坏的手感。
        binding.playerRotate.setOnLongClickListener {
            rotationJob?.cancel()
            // 渲染旋转兜底若在，一并撤掉 —— "恢复自动跟随"必须回到干净的系统语义
            clearRenderRotation()
            setForcedOrientation(null)
            toast(getString(R.string.player_rotate_auto))
            true
        }
        // 把横竖屏按钮与字幕按钮从顶栏搬进控制条（齿轮设置按钮旁边）。详见该方法的长注释。
        moveButtonsBesideSettings()
        // 控制条自带的全屏黑色遮罩会整体压暗视频（M7.19 用户反馈）。放到最后统一清掉。
        stripControllerScrim()
        binding.playerInfo.setOnClickListener { showVideoInfo() }
        // 删除（M7.24）：二次确认面板锚在这颗按钮正下方，见 showDeleteConfirm。
        binding.playerDelete.setOnClickListener { toggleDeleteConfirm() }
        binding.playerDeleteOk.setOnClickListener { performDelete() }
        binding.playerDeleteCancel.setOnClickListener { dismissDeleteConfirm() }
        // 点面板外面 = 取消。用全屏透明 View 而不用 PopupWindow 的理由见布局注释。
        binding.playerDeleteScrim.setOnClickListener { dismissDeleteConfirm() }

        binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        gestures = PlayerGestures(
            this,
            { player },
            binding.playerHud,
            host = binding.playerView,
            // 缩放对象 = PlayerView 内装画面的容器（AspectRatioFrameLayout）。
            // 控制条是它的兄弟层，缩放不影响控制条；查不到时返回 null，
            // 手势层据此整体降级为"无缩放"（与齿轮按钮的降级策略一致）。
            zoomTarget = { zoomContentView },
        )
        binding.playerView.setOnTouchListener(gestures)
        // 控制条显隐与顶部条联动：控制条收起时顶部条也该收，否则画面永远压着一条黑边。
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.playerTop.visibility = visibility
                // 左右竖向进度条同一节奏显隐（M7.19）。理由见 updateVerticalSeekBars。
                updateVerticalSeekBars(visibility == View.VISIBLE)
                // 控制条重新出现时补一次图标描边（M7.20）—— 收起期间状态可能变过。
                // 必须 post 到下一帧：在 Media3 的显隐回调里同步 setImageDrawable
                // 会 requestLayout，把正在跑的显示动画打断（实测控制条再也不出现）。
                if (visibility == View.VISIBLE) {
                    binding.root.post { applyIconOutline() }
                }
            },
        )
        setupVerticalSeekBars()

        updateRotateButton()
        // 外接显示器监听（M7.14）：要在 buildPlayer 之前 —— onResume 里
        // refreshExternalDisplay 挂副屏 Surface 时需要 player 已存在，
        // 但监听本身越早越好（播放页开着时插线也该立即响应）。
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(
            displayListener,
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
        buildPlayer()
        applyDefaultSpeedOnce()
        play(args.index)
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        savePosition()
        player?.pause()
        super.onStop()
    }

    // ── M7.7/M7.8：重力感知（旋转按钮 = "画面转到手机此刻的方向"）──
    //
    // ## 为什么必须自己读传感器
    // 系统会不会跟随转屏，取决于「自动旋转」开关。开关关着时（真机正是
    // 这种用法，否则根本轮不到按按钮），`windowManager.defaultDisplay.rotation`
    // 永远停在旧值——它只反映"系统已经把屏幕转成了什么样"，不反映
    // "手机此刻物理上怎么拿"。唯一可靠的来源是加速度计。
    //
    // ## M7.8 为什么把"请求方式"做成升级链（真机的关键证据）
    // M7.6 真机截图：App 已请求锁横屏（精确常量）、toast 都弹了，画面**仍是竖屏**
    // ——部分国产 ROM 把播放器的显式转向请求**拦下了**（国产 ROM 的旋转锁定比
    // 原生 Android 强硬，会无视 App 的 requestedOrientation）。而模拟器/原生
    // 系统上精确常量从来都生效 —— 这就是"模拟器全绿、真机不动"的根源。
    // 没有任何一种常量在所有 ROM 上都保证生效，所以点击后**按顺序换着法子请求**，
    // 每一步都检查画面有没有真的转过去，没转就换下一种（见 [toggleOrientation]）：
    // ```
    // ① SENSOR_* 变体   —— 部分 ROM 只认这种（顺带解决"锁死侧面倒置"）
    // ② 精确常量        —— 原生 Android 的标准路径
    // ```
    // 同时识别结果直接写进 toast —— 再出问题，用户复述 toast 内容即可精确定位。
    //
    // ## M7.9 教训：验收要有耐心，核弹不能上（荣耀V6 折叠屏内屏）
    // M7.8 曾有第③级 FULL_SENSOR（官方文档"完全无视用户锁定"），且每级只等
    // 800ms 单点验收。荣耀 V6 内屏实测：按按钮转对了，**之后却四向自动跟随**。
    // 推理：内屏转屏动画+配置分发 >1.6s（折叠屏比直板机慢），①②两级都被
    // 800ms 误判"没转"而升级到 FULL_SENSOR —— 转过去了，但 FULL_SENSOR
    // 本身就是四向跟随传感器 = "自动旋转"，恰好违背"手动锁定不自动转"的核心。
    // 外屏转得快（<800ms），①级成功即收手，所以正常。
    // 修复：验收改 250ms 轮询、最长 2s（转过去立即收手）；FULL_SENSOR 移除
    // （宁可失败且明说，也不给用户一个"自动旋转"状态）；成功后 1.5s 再确认
    // 锁还在（防个别 ROM 把 sensor 常量当全向跟随执行，被改写就用精确常量钉死）。

    /** 最近一次加速度计样本（设备坐标系，m/s²）；`null` = 还没收到过事件。 */
    @Volatile
    private var lastGravity: FloatArray? = null

    private var gravityListenerRegistered = false

    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 只留最新值（按钮点击那一刻读的是"此刻姿态"，不需要历史）
            lastGravity = floatArrayOf(event.values[0], event.values[1], event.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onResume() {
        super.onResume()
        refreshExternalDisplay()
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm != null && acc != null && !gravityListenerRegistered) {
            // SENSOR_DELAY_GAME：点按钮那一刻读的是"最新姿态"，采样越新越准
            sm.registerListener(gravityListener, acc, SensorManager.SENSOR_DELAY_GAME)
            gravityListenerRegistered = true
        }
    }

    override fun onPause() {
        if (gravityListenerRegistered) {
            (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                ?.unregisterListener(gravityListener)
            gravityListenerRegistered = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        savePosition()
        binding.root.removeCallbacks(verticalSeekTicker)
        displayManager?.unregisterDisplayListener(displayListener)
        detachExternalDisplay()
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    // ── M7.19：控制条遮罩 / 左右竖向进度条 ──────────────────────

    /**
     * 清掉 Media3 控制条自带的全屏遮罩。
     *
     * ## 问题
     * 点击屏幕唤出控制条时，`exo_controls_background` 会展开成**铺满整个
     * PlayerView** 的一层 `exo_black_opacity_60`（60% 黑）。用户看到的是
     * "一按屏幕整个视频就暗下去"，而不是"按钮浮在画面上"。
     * 底部 `exo_bottom_bar` 自己也有一层底色，加起来就是底部一条更黑的带子。
     *
     * ## 做法
     * 两者都设成透明。**不改 Media3 的默认布局文件、也不换 `controller_layout_id`**：
     * 复制一份控制条布局意味着以后每次升级 media3 都要重新对齐内部结构，
     * 而这里只是改两个 View 的背景色，失败了也只是"画面仍旧偏暗"，不会崩。
     *
     * `PlayerControlView` 在**构造时**就把 `exo_player_control_view` inflate 进来了
     * （不是首次显示时才建），所以这里 `findViewById` 一定拿得到；
     * 与 [moveButtonsBesideSettings] 找齿轮是同一个前提。仍保留 null 检查和日志，
     * 万一 media3 换了大版本改了 id，日志能直接指出来。
     */
    private fun stripControllerScrim() {
        val scrim = binding.playerView.findViewById<View>(internalId("exo_controls_background"))
        val bottomBar = binding.playerView.findViewById<View>(internalId("exo_bottom_bar"))
        if (scrim == null) {
            MimiLog.w(TAG, "未找到控制条遮罩 exo_controls_background，画面仍会偏暗")
        } else {
            scrim.setBackgroundColor(Color.TRANSPARENT)
        }
        bottomBar?.setBackgroundColor(Color.TRANSPARENT)
        MimiLog.i(TAG, "控制条遮罩已清除（scrim=${scrim != null} bottomBar=${bottomBar != null}）")
        // 遮罩没了，白色图标在高亮画面上就没了衬托，靠描边立住（M7.20）。
        applyIconOutline()
    }

    // ── M7.20：控制条图标的描边 ────────────────────────────────

    /**
     * 给控制条的白色图标描一圈黑边，并给时间文字加投影。
     *
     * ## 为什么是"事件驱动"而不是逐帧检查
     * 第一版用了 `OnPreDrawListener` 每帧补描边，结果**控制条再也唤不出来**：
     * `setImageDrawable` 会 `requestLayout()`，而 Media3 又会在 `onLayout` 里
     * 重新 `setImageResource` 换回原图标 → 下一帧再包 → 无限循环，
     * UI 线程被 invalidate 风暴占满，点击事件根本轮不到处理。
     *
     * 所以改成只在**图标真的可能变**的时机重包一次：控制条显示、播放/暂停切换、
     * 循环/随机/字幕设置变化。这些覆盖了 Media3 全部换图入口，且都是主线程回调，
     * 不会有重入问题。幂等仍由 [PlayerIconOutline.outlineIcon] 的 tag 判断兜住。
     */
    private fun applyIconOutline() {
        // 中央控件（上一个/快退15/播放暂停/快进30/下一个）与右下角一排
        // （齿轮、全屏、以及我们搬进来的横竖屏/字幕）都在内。
        val targets = listOf(
            internalId("exo_center_controls"),
            internalId("exo_basic_controls"),
        ).mapNotNull { binding.playerView.findViewById<ViewGroup>(it) }

        targets.forEach { group ->
            for (i in 0 until group.childCount) {
                when (val child = group.getChildAt(i)) {
                    // ImageButton（上一个 / 播放暂停 / 下一个 / 齿轮 / 全屏…）
                    is android.widget.ImageView -> PlayerIconOutline.outlineIcon(child)
                    // Button（快退 15 秒 / 快进 30 秒）：图标在 compound drawable 上，
                    // 只认 ImageView 会把这两个整整齐齐漏掉。
                    is TextView -> {
                        // 快退/快进把图标画在 background 上，样式与其余按钮完全不同。
                        PlayerIconOutline.outlineBackgroundIcon(child)
                        PlayerIconOutline.outlineCompoundIcon(child)
                        PlayerIconOutline.shadowText(child)
                    }
                    else -> Unit
                }
            }
        }
        // 自己那两颗横竖屏/字幕：无论搬没搬进控制条，都按 binding 直接处理。
        PlayerIconOutline.outlineIcon(binding.playerRotate)
        PlayerIconOutline.outlineIcon(binding.playerSubtitle)

        // 时间文字：底栏底色去掉后，白字在高亮画面上同样会糊。
        binding.playerView.findViewById<TextView>(internalId("exo_position"))
            ?.let(PlayerIconOutline::shadowText)
        binding.playerView.findViewById<TextView>(internalId("exo_duration"))
            ?.let(PlayerIconOutline::shadowText)
    }

    /**
     * 左右竖向进度条的接线（M7.19）。
     *
     * 两条完全等价：左手持、右手持都能用拇指够到屏幕边缘。
     * 拖动期间只更新 HUD 预览，抬指才 seek —— 与 `PlayerGestures` 的水平快进一致。
     */
    private fun setupVerticalSeekBars() {
        listOf(binding.playerSeekLeft, binding.playerSeekRight).forEach { bar ->
            bar.onScrubStart = { onVerticalScrubStart() }
            bar.onScrubMove = { ratio -> onVerticalScrubMove(ratio) }
            bar.onScrubStop = { ratio -> onVerticalScrubStop(ratio) }
        }
        // 记住控制条原本的自动隐藏时长，拖完要还原。
        // 拖动期间置 0 = 不自动隐藏，否则拖到一半竖条会随控制条一起消失。
        // 读回值兜个底：万一拿到 0（语义是"永不隐藏"），还原时会把控制条永久钉在屏幕上。
        controllerShowTimeoutMs = binding.playerView.controllerShowTimeoutMs
            .takeIf { it > 0 } ?: 5000
    }

    /**
     * 竖条显隐。
     *
     * ## 为什么必须跟控制条同进同退（不能常驻）
     * 两条竖条各宽 44dp 且贴在左右边缘，正好压在 `PlayerGestures` 的热区上：
     * 左半边上下滑 = 亮度、右半边上下滑 = 音量。竖条一旦常驻，边缘这两个最常用的
     * 手势就永久失效。让它们只在控制条可见时出现，就把"边缘手势"和"拇指调进度"
     * 按用户意图分开了：想沉浸观看时（控制条收起）边缘滑动手势照旧；
     * 点一下唤出控件时，拇指就地上下滑 = 调进度。
     */
    private fun updateVerticalSeekBars(show: Boolean) {
        val target = if (show && !isInPictureInPictureMode) View.VISIBLE else View.GONE
        binding.playerSeekLeft.visibility = target
        binding.playerSeekRight.visibility = target
        binding.root.removeCallbacks(verticalSeekTicker)
        if (target == View.VISIBLE) {
            syncVerticalSeek()
            binding.root.postDelayed(verticalSeekTicker, VERTICAL_SEEK_TICK_MS)
        }
    }

    /** 把播放位置同步到两条竖条。控制条收起时时钟已停，不产生额外开销。 */
    private fun syncVerticalSeek() {
        if (scrubbingVertically) return
        val exo = player ?: return
        val duration = exo.duration
        val ratio = if (duration > 0L) {
            (exo.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
        } else {
            0f
        }
        binding.playerSeekLeft.ratio = ratio
        binding.playerSeekRight.ratio = ratio
    }

    private fun onVerticalScrubStart() {
        scrubbingVertically = true
        // 0 = 不自动隐藏（Media3 语义）。不这样做，5 秒后控制条连同竖条一起消失，
        // 手指还在拖、控件却没了。
        binding.playerView.controllerShowTimeoutMs = 0
        binding.playerView.showController()
    }

    private fun onVerticalScrubMove(ratio: Float) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0L } ?: return
        // 两条同步：拖左边时右边也要跟着动，否则松手前看到的是两个不同位置。
        binding.playerSeekLeft.ratio = ratio
        binding.playerSeekRight.ratio = ratio
        val target = (duration * ratio).toLong()
        binding.playerHud.text = getString(
            R.string.player_scrub_preview,
            Formatters.duration(target),
            Formatters.duration(duration),
        )
        binding.playerHud.visibility = View.VISIBLE
        binding.playerHud.removeCallbacks(hudHideRunnable)
    }

    private fun onVerticalScrubStop(ratio: Float) {
        scrubbingVertically = false
        val exo = player
        val duration = exo?.duration?.takeIf { it > 0L }
        if (exo != null && duration != null) {
            exo.seekTo((duration * ratio).toLong())
        }
        binding.playerView.controllerShowTimeoutMs = controllerShowTimeoutMs
        // 抬指后留一小会儿让人看清落点，再收 HUD。
        binding.playerHud.removeCallbacks(hudHideRunnable)
        binding.playerHud.postDelayed(hudHideRunnable, 800L)
    }

    // ── 横竖屏按钮的位置 ────────────────────────────────────────

    /**
     * 把横竖屏按钮与字幕按钮搬进 `PlayerView` 控制条的**齿轮设置按钮旁边**。
     *
     * ## 为什么（M3.1 真机反馈第 1 条 + M7.13 沿用）
     * 横竖屏按钮原先在顶栏，紧挨着"上一个/下一个"。三个按钮挤在一起、图标又都是
     * 单色的，手指在"切视频"与"转屏"之间会互相点错 —— 用户给的要求很具体：
     * 挪到齿轮设置按钮旁边。M7.13 的字幕按钮沿用同一套搬法，与"播放中要摸的
     * 功能按钮"聚在一处（最终顺序：横竖屏 → 字幕 → 齿轮）。
     *
     * ## 为什么只能运行期注入
     * 齿轮在 Media3 的**控制条内部**（`exo_bottom_bar` 里的 `exo_basic_controls`），
     * 那一行由 `PlayerView` 自己 inflate，我们的布局文件插不进去。
     * 唯一能"把控件放进那个 LinearLayout"的办法就是在运行期加进去。
     *
     * ## 为什么用 `getIdentifier` 而不是 `R.id.exo_settings`
     * media3-ui 的 `public.txt` 是**空的** —— 它的资源对外全是私有，编译期拿不到符号
     * （`android.nonTransitiveRClass` 下更不可能）。但本工程是**非命名空间**打包
     * （AGP 默认），依赖的资源会被合并进应用自己的资源表，
     * 于是按"名字 + 自己的包名"就能查到 id。
     *
     * ## 失败必须是降级，不能是崩溃
     * 这类"依赖库内部结构"的写法有天然风险：media3 换个大版本就可能改名。
     * 所以找不到齿轮时**什么都不做** —— 按钮留在顶栏最右侧（见布局注释）继续可用，
     * 只是位置退回到老样子。`adb` 里也打一条日志，便于事后确认走的是哪条路。
     */
    private fun moveButtonsBesideSettings() {
        val settingsButton = binding.playerView.findViewById<View>(internalId("exo_settings"))
        val row = settingsButton?.parent as? android.view.ViewGroup
        if (settingsButton == null || row == null) {
            MimiLog.w(TAG, "未找到控制条齿轮按钮，横竖屏/字幕按钮留在顶栏（布局见 activity_video_player.xml）")
            return
        }
        moveIntoControlRow(row, binding.playerRotate, settingsButton)
        moveIntoControlRow(row, binding.playerSubtitle, settingsButton)
        MimiLog.i(TAG, "横竖屏/字幕按钮已并入控制条（齿轮左侧），容器=${row.javaClass.simpleName}")
    }

    /**
     * 把单个按钮搬进控制条容器 [row]，插在锚点（齿轮）左边。
     *
     * 顶栏的父容器用 `android:gravity` 居中，按钮自己没设 gravity；
     * 搬进控制条后如果不显式声明，默认的 top 对齐会让它比齿轮高出一截
     * （实测：齿轮 126 px 高、我们的按钮 105 px，顶部对齐时下沿差 21 px）。
     * 用 `addView(child, index)` 而不自己造 LayoutParams：ViewGroup 会用
     * `generateLayoutParams` 把顶栏那套参数转成目标容器需要的那一种，
     * 宽高（select_action_size）原样保留。
     */
    private fun moveIntoControlRow(row: ViewGroup, button: View, anchor: View) {
        (button.parent as? ViewGroup)?.removeView(button)
        (button.layoutParams as? android.widget.LinearLayout.LayoutParams)
            ?.gravity = android.view.Gravity.CENTER_VERTICAL
        row.addView(button, row.indexOfChild(anchor))
    }

    /**
     * 按名字查资源 id。
     *
     * @return 查不到返回 `0` —— `findViewById(0)` 会返回 `null`，调用方据此降级。
     */
    private fun internalId(name: String): Int = resources.getIdentifier(name, "id", packageName)

    /** 捏合缩放的作用对象：PlayerView 内装画面的容器（M7.6）。查不到 = 无缩放。 */
    private val zoomContentView: View? by lazy {
        binding.playerView.findViewById<View>(internalId("exo_content_frame"))
    }

    private lateinit var gestures: PlayerGestures

    /**
     * 设置里的「默认视频播放速度」（M7.6）。起播与每次 `STATE_READY` 都对齐到它，
     * 所有视频打开即按这个速度播，不用每次手动调。
     */
    private var defaultSpeed = 1f

    /** 异步读一次「默认视频播放速度」并落到当前播放器（onCreate 里调用）。 */
    private fun applyDefaultSpeedOnce() {
        lifecycleScope.launch {
            val speed = runCatching { settings.playerSpeed.first() }.getOrDefault(1f)
            defaultSpeed = speed
            player?.setPlaybackSpeed(speed)
            MimiLog.i(TAG, "默认播放速度=$speed")
        }
    }

    // ── 横竖屏切换 ──────────────────────────────────────────────

    /**
     * 手动锁定方向。`null` = 跟随系统（即 `requestedOrientation = UNSPECIFIED`）。
     *
     * ## 为什么要"记状态"而不是每次读 `requestedOrientation`
     * 读回来的值会被系统加工（部分 ROM 会把 `UNSPECIFIED` 归一化成其它常量），
     * 于是"当前是不是我锁的"就判不准。自己记一份是最省事也最可靠的做法。
     */
    private var forcedOrientation: Int? = null

    /** 升级链的取消柄：用户再点一次 / 长按恢复跟随 / 离开页面时作废旧链。 */
    private var rotationJob: Job? = null

    /**
     * 渲染旋转兜底（M7.10）的当前状态：`null` = 未启用；[Surface.ROTATION_90] /
     * [Surface.ROTATION_270] = 已把整个播放页内容旋转到对应方向。
     *
     * 为什么需要它：荣耀折叠屏**内屏**把升级链两级请求（SENSOR_*、精确常量）
     * 全部拦下（0.7.9 真机实锤：toast 弹了"系统没响应"画面不动），而这台 ROM 又只
     * 放行 FULL_SENSOR —— 那玩意等于自动旋转，M7.9 已明令禁用。系统不转，那就
     * **App 自己转**：把播放页整层 View 旋转 90° 画出来，画面横过来铺满屏幕。
     * 这层完全在应用手里，任何 ROM 都拦不住；且是静态锁定，天生不会"自动跟随"。
     */
    private var renderRotation: Int? = null

    /**
     * 渲染旋转的"代数"：每次 [applyRenderRotation]/[clearRenderRotation] 都自增。
     *
     * [applyRenderRotation] 里有一个 `post`（等布局走完再设 rotation/translation），
     * 而 [clearRenderRotation] 可能在这个 post 执行**之前**就被调用了
     * （ROM 的 config 假翻转在兜底启用后几十 ms 内到达 → onConfigurationChanged
     * → clear —— 折叠屏内屏上这不是理论，是常态路径）。没有代数守卫的话，
     * 排队的 post 会在"撤除完成"之后又把 rotation/translation 设回去，
     * 得到一个"布局已复原、变换却残留"的鬼状态（0.7.10 真机截图里
     * "画面没转过来、比例却变了"的候选根因之一）。
     */
    private var renderGeneration = 0

    /**
     * 一次重力识别的结果。
     *
     * @param landscape   手机此刻是横着拿还是竖着拿
     * @param upsideDown  竖屏倒握（gy 显著为负，锁 REVERSE_PORTRAIT 用）
     * @param screenRotation 重力推算的物理朝向（Surface.ROTATION_*，映射表见 readGrip）。
     *   渲染旋转兜底（M7.10）要用**精确侧面**：锁 90° 还是 270°，只有这里知道。
     */
    private data class GripReading(
        val landscape: Boolean,
        val upsideDown: Boolean,
        val screenRotation: Int,
    )

    /**
     * 点一下 = 把画面转到**手机此刻的物理方向**（M7.7 按产品需求重定语义）。
     *
     * ## 语义演进史（三代才对齐，留着引以为戒）
     * * 原始版：点击读 `configuration.orientation` 再取反 —— 转屏异步、config 滞后，
     *   连点两下算出同一个目标，看起来"按钮不生效"。
     * * M7.6 翻转式：自有记账、每次点击必翻转 —— "不生效"修掉了，但语义仍是翻转，
     *   不是"跟着我的手"。
     * * M7.7 同步式：读加速度计 → 推断此刻物理朝向 → 锁过去 —— 语义对了，
     *   但真机仍不动（见 M7.8 头注：ROM 拦请求，模拟器测不出来）。
     * * **M7.8 同步式 + 请求升级链**：识别结果不变，"怎么请求"变成逐级升级，
     *   哪种常量在这个 ROM 上有效就用哪种 —— 用户永远只关心"按一下就转到"。
     *
     * ## 流程
     * 1. 读重力 → 判横/竖（`null` = 平放/无样本 → 退化为翻转，不乱猜）；
     * 2. 画面已在该方向 → 锁住并明说（"手机正横着拿，画面已是横屏"），
     *    不让用户把"没变化"当成"按钮坏了"；
     * 3. 否则按升级链请求（SENSOR_* → 精确常量），250ms 轮询最长 2s 验收，
     *    转过去立即收手，没转才换下一种（M7.9：折叠屏内屏转得慢，急了会误判）；
     * 4. 识别结果写进 toast —— 再出问题，用户复述 toast 内容即可精确定位。
     */
    private fun toggleOrientation() {
        rotationJob?.cancel()
        when (val grip = readGrip()) {
            null -> {
                // 平放/无样本：识别不了"你的方向"，退化为翻转兜底（不乱猜）
                setForcedOrientation(fallbackToggleTarget())
                toast(getString(R.string.player_rotate_flat))
            }
            else -> {
                // "画面此刻是横是竖"必须把渲染旋转算进去：兜底生效时 config 仍是
                // 竖屏，但用户看到的画面是横的 —— 按 config 判会说"已是竖屏"，
                // 把用户看到的横屏说没了。
                val landscapeNow = effectiveLandscape()
                if (grip.landscape == landscapeNow) {
                    // 渲染旋转兜底在、画面也是横的，但手机换到了**另一侧**
                    // （兜底 90° 时顶朝右）→ 兜底转到新侧面，别拿"已是横屏"糊弄
                    if (renderRotation != null && grip.landscape &&
                        grip.screenRotation != renderRotation
                    ) {
                        setForcedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                        applyRenderRotation(grip.screenRotation)
                        toast(getString(R.string.player_rotate_synced_landscape))
                    } else {
                        // 手机此刻的方向和画面一致：锁住它，并明说，别让用户干等
                        setForcedOrientation(
                            if (grip.landscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            },
                        )
                        toast(
                            getString(
                                if (grip.landscape) {
                                    R.string.player_rotate_already_landscape
                                } else {
                                    R.string.player_rotate_already_portrait
                                },
                            ),
                        )
                    }
                } else {
                    requestOrientationWithEscalation(grip)
                }
            }
        }
    }

    /**
     * 按升级链请求方向。
     *
     * ## 验收为什么用轮询而不是单点检查（M7.9 教训）
     * M7.8 每级只等 800ms 查一次：折叠屏内屏的转屏动画+配置分发 >1.6s
     * （荣耀 V6 实测），两级都被误判"没转"而升级到 FULL_SENSOR —— 那玩意
     * 四向跟随传感器，把"锁定"变成了"自动旋转"。现在 250ms 轮询、最长 2s，
     * **转过去的瞬间立即收手**，慢设备不再被冤枉。
     *
     * ## 为什么没有 FULL_SENSOR
     * 它虽是"无视锁定"的核弹，但生效后**四向跟随传感器 = 自动旋转**，
     * 恰好违背"手动锁定、不自动转"的核心需求。两级都被拦时宁可失败并明说
     * （toast 告知），也不给用户一个静默的"自动旋转"状态。
     */
    private fun requestOrientationWithEscalation(grip: GripReading) {
        val attempts = if (grip.landscape) {
            intArrayOf(
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            )
        } else {
            // 竖屏倒握（gy 显著为负）先试 REVERSE_PORTRAIT，跟手持姿态一致
            if (grip.upsideDown) {
                intArrayOf(
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                )
            } else {
                intArrayOf(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                )
            }
        }
        toast(
            getString(
                if (grip.landscape) {
                    R.string.player_rotate_synced_landscape
                } else {
                    R.string.player_rotate_synced_portrait
                },
            ),
        )
        rotationJob = lifecycleScope.launch {
            for (i in attempts.indices) {
                val req = attempts[i]
                setForcedOrientation(req)
                var waited = 0
                while (waited < 2000) {
                    delay(250)
                    waited += 250
                    // 验收看窗口几何（windowIsLandscape），config 会被"假翻转"骗（见其注释）
                    if (windowIsLandscape() == grip.landscape) {
                        MimiLog.i(TAG, "方向请求第 ${i + 1} 级生效 → $req（等了 ${waited}ms）")
                        // 系统真的转过去了 → 渲染旋转兜底没用了，撤掉，
                        // 否则系统横屏 + 内容旋转会双重旋转、画面倒置。
                        if (renderRotation != null) clearRenderRotation()
                        // 转过去之后 1.5s 再确认一次"锁还在"（检查**在本协程内**：
                        // 用户再点/长按会 cancel 掉本 Job，检查随之作废，不会跟新手势打架）。
                        delay(1500)
                        if (forcedOrientation == null) return@launch // 长按恢复跟随了
                        if (windowIsLandscape() != grip.landscape) {
                            val pin = when (req) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ->
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                else -> req // 已是精确/REVERSE 常量
                            }
                            MimiLog.i(TAG, "锁被 ROM 改写成跟随，换精确常量钉死：$pin")
                            setForcedOrientation(pin)
                        }
                        return@launch
                    }
                }
                if (i < attempts.lastIndex) {
                    MimiLog.i(TAG, "方向请求第 ${i + 1} 级 2s 未生效（$req），升级重试")
                }
            }
            // 两级都被拦（折叠屏内屏的实测行为）：系统不肯转，App 自己转。
            if (grip.landscape) {
                // 二次确认：等待期间窗口可能已自己变横（letterbox 类兼容行为慢到货，
                // M7.10 模拟器实测 25ms 内变窗）→ 此时用户已看到横向画面，兜底不启用
                if (windowIsLandscape()) {
                    toast(getString(R.string.player_rotate_already_landscape))
                } else {
                    // 目标横屏 → 渲染旋转到重力指定的侧面（顶朝左 90° / 顶朝右 270°）
                    applyRenderRotation(grip.screenRotation)
                    toast(getString(R.string.player_rotate_render_fallback))
                }
            } else {
                // 目标竖屏 → 兜底若在，撤掉即回竖屏；本来就没有兜底（系统竖屏
                // 也拦？从未见过），只能明说失败
                if (renderRotation != null) {
                    clearRenderRotation()
                    toast(getString(R.string.player_rotate_synced_portrait))
                } else {
                    toast(getString(R.string.player_rotate_failed))
                }
            }
        }
    }

    /**
     * 由最新重力样本判断手机此刻是横着拿还是竖着拿。
     *
     * 返回 `null` = 识别不了，调用方退化为翻转：无样本（传感器缺失/还没来过事件）、
     * 合矢量太小（甩动中）、或接近平放（重力几乎全在 z 轴上，横竖没有意义）。
     *
     * ## 阈值 0.30 的依据
     * 手机后仰 α 角时水平分量占比 = cosα，0.30 ↔ 后仰 72.5°。躺着把手机支起来
     * 看片的常见姿势（后仰 30~50°，占比 0.64~0.87）离阈值很远。M7.7 用 0.45
     * （后仰 63° 就识别不了 → 走翻转兜底 → 转错方向），过于保守，是真机
     * "识别失败"的候选根因之一，M7.8 放宽。
     */
    private fun readGrip(): GripReading? {
        val g = lastGravity ?: return null
        val gx = g[0]
        val gy = g[1]
        val gz = g[2]
        val total = sqrt(gx * gx + gy * gy + gz * gz)
        // 传感器数据不可信（甩动/跌落中）→ 别猜
        if (total < SensorManager.GRAVITY_EARTH * 0.5f) return null
        val horizontal = sqrt(gx * gx + gy * gy)
        // 平放：重力几乎全在 z 轴，横竖分不清 → 别猜
        if (horizontal < total * 0.30f) return null
        val landscape = abs(gx) >= abs(gy)
        val screenRotation = when {
            abs(gx) >= abs(gy) ->
                if (gx > 0) Surface.ROTATION_90 else Surface.ROTATION_270
            gy > 0 -> Surface.ROTATION_0
            else -> Surface.ROTATION_180
        }
        MimiLog.i(TAG, "重力样本 gx=$gx gy=$gy gz=$gz → ${if (landscape) "横" else "竖"} ROTATION_$screenRotation")
        return GripReading(landscape, upsideDown = !landscape && gy < 0f, screenRotation)
    }

    private fun configIsLandscape(): Boolean = resources.configuration.orientation ==
        Configuration.ORIENTATION_LANDSCAPE

    /**
     * **用户真实看到的**画面方向：播放页根布局的实际尺寸（宽 > 高）。
     *
     * ## 为什么 config 和 currentWindowMetrics 都不能用（M7.10 两次冒烟抓包）
     * 模拟器 `wm set-ignore-orientation-request true`（复现折叠屏内屏拦截）
     * 实测：App 请求横屏后，`configuration.orientation` **和**
     * `currentWindowMetrics.bounds` 都会"假翻转"——系统给 App 的所有"方向感知
     * API"都是虚拟的（config 变 landscape、度量报横屏尺寸），但窗口纹丝不动、
     * 画面根本没转。升级链拿它们验收会立刻"假成功"，兜底永远不启用。
     * 唯一骗不了人的是**真实布局**：根 View 实际被 layout 成多大就是多大——
     * 真转屏 → 内容真铺在横向窗口里（宽>高）；被拦截 → 布局仍是竖的。
     */
    private fun windowIsLandscape(): Boolean {
        val w = binding.root.width
        val h = binding.root.height
        return w > 0 && h > 0 && w > h
    }

    /** 用户实际看到的画面方向：系统转屏**或**渲染旋转兜底，任一生效即为横。 */
    private fun effectiveLandscape(): Boolean = renderRotation != null || windowIsLandscape()

    /**
     * 渲染旋转兜底：把播放页整层内容旋转到 [rotation]（90°/270°），画面横过来铺满。
     *
     * ## 几何
     * `binding.root` 的 parent 是 `android.R.id.content`（全屏 FrameLayout，尺寸
     * P×Q = 屏宽×屏高）。做法：把 root 的布局尺寸对调成 Q×P（宽=屏高、高=屏宽），
     * 再绕中心旋转 ±90° —— 旋转后的外接框恰好是 P×Q，铺满全屏。旋转绕中心，
     * 两种方向平移量相同：中心对齐修正 `tx=(P−Q)/2, ty=(Q−P)/2`。
     *
     * ## 为什么要 post
     * pivot/平移依赖**新尺寸**（pivot 取新宽高的一半），必须等 requestLayout
     * 走完一帧再设；旋转与平移本身要同时生效，一并放进 post 里原子化。
     *
     * ## 触摸与手势
     * View 层的 `rotation` 由框架在触摸分发时做逆变换（M7.1 的 ZoomableContainer
     * 教训的反向利用：把变换放在 View 系统里，框架替你换算坐标），手势/按钮照常。
     *
     * ## 与系统转屏的关系
     * 这层是**静态锁定**：不监听传感器变化，手机再怎么转画面都不动 —— 天生满足
     * "手动锁定、不自动旋转"。若之后系统真把屏幕转过去了（onConfigurationChanged），
     * 会主动撤掉这层，避免双重旋转。
     */
    private fun applyRenderRotation(rotation: Int) {
        val host = binding.root.parent as? ViewGroup ?: return
        val hostW = host.width
        val hostH = host.height
        if (hostW == 0 || hostH == 0) {
            // 布局还没走完（理论少见：点按钮时页面早已渲染）。等一帧再来。
            binding.root.post { applyRenderRotation(rotation) }
            return
        }
        renderRotation = rotation
        val gen = ++renderGeneration
        val lp = binding.root.layoutParams
        lp.width = hostH
        lp.height = hostW
        binding.root.layoutParams = lp
        binding.root.requestLayout()
        binding.root.post {
            // 代数守卫：等待期间被 clear（或又 apply 了一次）就放弃，
            // 不把过期的变换盖到新状态上（见 renderGeneration 注释）。
            if (renderRotation == null || gen != renderGeneration) return@post
            binding.root.pivotX = binding.root.width / 2f
            binding.root.pivotY = binding.root.height / 2f
            binding.root.rotation = if (rotation == Surface.ROTATION_90) 90f else -90f
            binding.root.translationX = (hostW - hostH) / 2f
            binding.root.translationY = (hostH - hostW) / 2f
        }
        // 旋转后可视几何全变了，缩放/平移余量作废
        gestures.resetZoom()
        MimiLog.i(TAG, "渲染旋转兜底启用 → ROTATION_$rotation（host ${hostW}x$hostH）")
    }

    /** 撤掉渲染旋转兜底，恢复常规布局。 */
    private fun clearRenderRotation() {
        if (renderRotation == null) return
        renderRotation = null
        renderGeneration++ // 作废排队中的 apply post，防"撤除后变换复活"（见其注释）
        binding.root.rotation = 0f
        binding.root.translationX = 0f
        binding.root.translationY = 0f
        val lp = binding.root.layoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        binding.root.layoutParams = lp
        binding.root.requestLayout()
        gestures.resetZoom()
        MimiLog.i(TAG, "渲染旋转兜底撤除")
    }

    /** 重力识别不了时的兜底：按 M7.6 的记账翻转。 */
    private fun fallbackToggleTarget(): Int = when (forcedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else ->
            if (windowIsLandscape()) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
    }

    private fun setForcedOrientation(orientation: Int?) {
        // ⚠️ M7.8 起传进来的常量来自**升级链**（SENSOR_* → 精确常量 → FULL_SENSOR），
        // 不再是单一的锁定式常量。M3.1 时"精确常量必生效"的假设在部分国产 ROM 上
        // 被真机截图证伪（请求了横屏、画面没动），所以"用哪种常量"不再由我们
        // 预设，而是运行期逐级试出来 —— 哪种能转就用哪种（详见 toggleOrientation）。
        //
        // 语义不变的部分：这里是"用户明确意图"的锁（不是跟随系统），锁定后
        // 手机怎么拿都不变；想跟随了走长按出口（恢复 UNSPECIFIED）。
        forcedOrientation = orientation
        requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // 诊断：确认"点击触发了"与"系统是否接受这个方向请求"。
        // 真机上"点了不转"有两种完全不同的原因（点击没到 / 系统忽略请求），
        // 不打这条日志就只能猜。读回值还能看出 ROM 有没有偷偷改写常量。
        MimiLog.i(
            TAG,
            "setForcedOrientation=$orientation 读回=${requestedOrientation} " +
                "当前config=${resources.configuration.orientation}",
        )
        updateRotateButton()
    }

    /**
     * 按钮图标表示"再点一下会发生什么"，而不是"当前是什么"。
     *
     * 这条约定很重要：图标若表示当前状态，用户会以为按钮坏掉了（"我明明是横屏，
     * 图标却是横屏，点一下没变化"）。表示动作则永远有反馈。
     */
    private fun updateRotateButton() {
        // 有效方向含渲染旋转兜底（M7.10）：兜底生效时 config 仍是竖屏，
        // 按 config 算图标会显示"转横屏"，误导用户。
        val landscapeNow = effectiveLandscape()
        // 跟随系统时，下一步是"切到相反方向"；已锁定时，下一步是"切回来"，
        // 而这两个判断的分支恰好相同 —— 都是"当前方向的相反面"。
        val toLandscape = !landscapeNow
        binding.playerRotate.setImageResource(
            if (toLandscape) R.drawable.ic_rotate_landscape else R.drawable.ic_rotate_portrait,
        )
        binding.playerRotate.contentDescription = getString(
            if (toLandscape) R.string.player_rotate_landscape else R.string.player_rotate_portrait,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        MimiLog.i(TAG, "onConfigurationChanged orientation=${newConfig.orientation}")
        super.onConfigurationChanged(newConfig)
        // 本页声明了 `configChanges`，所以旋转屏幕**不会**重建 Activity
        // （重建会打断播放，用户看到的是"一转屏就从头开始"）。
        // 代价是图标要自己刷新，否则横屏后图标仍停在"转横屏"。
        updateRotateButton()
        // 窗口尺寸变了，缩放/平移的夹紧余量全部作废，直接复位（M7.6）。
        gestures.resetZoom()
        // 渲染旋转兜底在、系统又真的转屏了 → 立刻撤掉兜底，否则双重旋转画面倒置
        // （M7.10）：兜底期间用户开了系统自动旋转、或 ROM 延迟放行了请求，都走这里。
        if (renderRotation != null) {
            MimiLog.i(TAG, "系统已自行转屏，撤除渲染旋转兜底防双重旋转")
            clearRenderRotation()
        }
        // 删除确认面板是按"锚点相对 root 的坐标"摆的，窗口尺寸一变必须重算，
        // 否则横竖屏切换后面板会飘在离删除键很远的地方。
        if (binding.playerDeletePanel.visibility == View.VISIBLE) {
            binding.playerDeletePanel.post { positionDeletePanel() }
        }
    }

    private fun toast(text: CharSequence) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 切到后台/离开时自动进画中画（API 26+，且确实在播）。 */
    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            player?.isPlaying == true &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPip()
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // 画中画里不该有控制条与顶部条（它们会占掉一半的缩略画面）。
        binding.playerTop.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.playerView.useController = !isInPictureInPictureMode
        // 竖条同进退（M7.19）：小窗里放这两条会把画面切掉三分之一。
        updateVerticalSeekBars(!isInPictureInPictureMode)
        // 渲染旋转兜底若在，进 PiP 前撤掉：小窗按窗口几何重排，旋转 90° 的内容
        // 在小窗里是侧躺的（M7.10）。
        if (isInPictureInPictureMode) {
            clearRenderRotation()
            // 删除确认面板同理：小窗里既不该留着"要不要删"的询问，
            // 全屏遮罩也会把缩略画面整块吃掉。
            dismissDeleteConfirm()
        }
    }

    // ── M7.13：字幕选择 ──────────────────────────────────────────
    //
    // ## 默认策略（产品指定）：有中文就上中文，简体 > 繁体
    // 内置多国字幕的片子，Media3 的默认选轨只认「语言」不认「简繁」——两轨
    // language 都是 zh 时它按自己的规则随便挑，用户极可能拿到繁体。所以在
    // STATE_READY 时自己打分选一次（见 [maybeAutoSelectSubtitle]）：
    // ```
    // 简体（Hans/简体/zh-CN…）4 分 > 繁体（Hant/繁體/zh-TW/zh-HK…）3 分
    //   > 其它中文 2 分；打平取轨道顺序在前的。
    // ```
    // 打分的输入是 language + label **两处拼在一起**扫：MKV 老式语言标签
    // （chi/zho）归一化后都只是 "zh"，**简繁的区分信息几乎全在轨道名里**
    // （Media3 1.11 的 MatroskaExtractor 会把 MKV 的 TrackName 元素写进
    // `Format.label`，已反汇编确认）。没有中文轨时不动选轨参数 —— Media3
    // 默认不选无标记的文本轨，即"没有中文字幕就不自动开"，用户可从弹窗手动开。
    //
    // ## 手动选择
    // 控制条字幕按钮 → 弹窗单选「关闭字幕 + 每条文本轨」（见 [showSubtitleDialog]）。
    // 手动选中用 [TrackSelectionOverride] 精确钉住那条轨道，跨 seek/重缓冲都保持；
    // 换下一个视频时在 [play] 里清掉 override 并重新走自动优选 ——
    // 手动选择是"给这一部片子的"，新片子回到"优先简中"的默认策略。

    /** 本次媒体项是否已做过字幕自动优选（[play] 里复位，软解重试重建后也会重选）。 */
    private var subtitleAutoApplied = false

    /**
     * 自动优选：对文本轨打分，把最优的中文字幕选上（每个媒体项只做一次）。
     *
     * 守卫 [subtitleAutoApplied] 必须有：READY 在每次 seek/重缓冲后都会再触发，
     * 没有守卫的话用户手动选了别的字幕，一动进度条就被打回"简体"。
     */
    private fun maybeAutoSelectSubtitle() {
        val exo = player ?: return
        if (subtitleAutoApplied) return
        subtitleAutoApplied = true
        val textGroups = exo.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        if (textGroups.isEmpty()) return
        var bestGroup: Tracks.Group? = null
        var bestTrack = -1
        var bestScore = 0
        var bestDesc = ""
        for (group in textGroups) {
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val score = subtitleScore(f)
                // `>` 而非 `>=`：打平保留先出现的轨道（轨道顺序即封装顺序，
                // 制作组通常把主要语言放前面，顺序是合理的次级判据）。
                if (score > bestScore) {
                    bestScore = score
                    bestGroup = group
                    bestTrack = i
                    bestDesc = "label=${f.label ?: "-"} lang=${f.language ?: "-"}"
                }
            }
        }
        if (bestGroup == null) {
            MimiLog.i(
                TAG,
                "字幕自动优选：${textGroups.sumOf { it.length }} 条文本轨均非中文，保持默认（不开字幕）",
            )
            return
        }
        selectTextTrack(bestGroup, bestTrack)
        MimiLog.i(TAG, "字幕自动优选：$bestDesc → 选中（简体>繁体>其它中文）")
    }

    /**
     * 单条字幕轨的"中文程度"打分（简体 4 / 繁体 3 / 其它中文 2 / 非中文 0）。
     *
     * language 与 label 拼成一个小写串一起扫：标记出现在任何一处都算数。
     * 简繁的写法尽量收全（简/繁两系字形 + Hans/Hant + 地区变体 + simplified/
     * traditional），漏一种标记的代价是"退化为按中文选"，不会选错语言。
     */
    private fun subtitleScore(f: Format): Int {
        val hay = "${f.language?.lowercase(Locale.US) ?: ""} ${f.label?.lowercase(Locale.US) ?: ""}"
        val simplified = hay.containsAnyOf(
            "hans", "简体", "簡體", "简中", "簡中", "simplified", "zh-cn", "zh_cn", "zh-sg", "zh_sg",
        )
        val traditional = hay.containsAnyOf(
            "hant", "繁體", "繁体", "繁中", "traditional", "zh-tw", "zh_tw", "zh-hk", "zh_hk", "zh-mo", "zh_mo",
        )
        return when {
            simplified -> 4
            traditional -> 3
            hay.containsAnyOf("zh", "chi", "zho", "chinese", "中文") -> 2
            else -> 0
        }
    }

    private fun String.containsAnyOf(vararg needles: String): Boolean = needles.any { contains(it) }

    /** 把字幕选到指定文本轨（清旧 override + 确保文本轨未被禁用）。 */
    private fun selectTextTrack(group: Tracks.Group, trackIndex: Int) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    /** 关闭字幕：禁用整个文本轨类型（override 一并清掉）。 */
    private fun disableTextTracks() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /**
     * 弹出手动字幕选择：第一项「关闭字幕」，其后每条文本轨一项。
     *
     * 没有文本轨时不弹空框，直接说明。单选项点击即生效并关框（区别于
     * "选中后还要按确定"的两步式——播放中改字幕要的就是一步到位）。
     * 当前选中项由选轨参数反推：文本轨被禁用 = 停在「关闭字幕」上；
     * 否则找 `isTrackSelected` 为真的那条（用户手动选的 override 或自动优选的结果）。
     */
    private fun showSubtitleDialog() {
        val exo = player ?: return
        data class Option(val group: Tracks.Group?, val trackIndex: Int, val title: String)

        val options = mutableListOf(Option(null, -1, getString(R.string.player_subtitle_off)))
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                options += Option(group, i, subtitleDisplay(f))
            }
        }
        if (options.size == 1) {
            toast(getString(R.string.player_subtitle_none))
            return
        }
        var checked = -1
        // media3 1.11 的 TrackSelectionParameters 没有 isTrackTypeDisabled 之类的
        // 读取方法，禁用状态就是公开字段 disabledTrackTypes 这个集合。
        if (C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes) {
            checked = 0
        } else {
            for (i in 1 until options.size) {
                val o = options[i]
                if (o.group != null && o.group.isTrackSelected(o.trackIndex)) {
                    checked = i
                    break
                }
            }
        }
        val names = options.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.player_subtitle_title)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                dialog.dismiss()
                val o = options[which]
                if (o.group == null) {
                    disableTextTracks()
                    MimiLog.i(TAG, "字幕手动选择 → 关闭")
                    showHud(getString(R.string.player_subtitle_off))
                } else {
                    selectTextTrack(o.group, o.trackIndex)
                    MimiLog.i(TAG, "字幕手动选择 → ${o.title}")
                    showHud(getString(R.string.player_subtitle_now, o.title))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 弹窗里一条字幕轨的显示名：优先轨道名（label），退化到语言显示名。 */
    private fun subtitleDisplay(f: Format): String {
        f.label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val lang = f.language
        if (lang.isNullOrBlank()) return getString(R.string.player_subtitle_track)
        return Locale.forLanguageTag(lang).displayName.ifBlank { lang }
    }

    /**
     * 复用续播提示那行 HUD：字幕切换给一行反馈，不用打断式 toast。
     *
     * 用**可取消**的 [hudHideRunnable] 而不是每次 `postDelayed` 一个新 lambda：
     * M7.19 起竖条拖动要连续刷新同一行 HUD，旧写法会堆起一串延时任务，
     * 表现是"松手后 HUD 又被上一次的超时提前收走"。
     */
    private fun showHud(text: CharSequence) {
        binding.playerHud.removeCallbacks(hudHideRunnable)
        binding.playerHud.text = text
        binding.playerHud.visibility = View.VISIBLE
        binding.playerHud.postDelayed(hudHideRunnable, 2500L)
    }

    private val hudHideRunnable = Runnable { binding.playerHud.visibility = View.GONE }

    // ── M7.14：外接显示器（副屏画面 + 主屏控制）───────────────────
    //
    // ## 生命周期策略
    // - 进入页面 / 插线 / 拔线：DisplayListener + onResume 各触发一次
    //   [refreshExternalDisplay]，按"有无 presentation display"自动挂/断。
    // - 不在 onPause 拆：Presentation 是独立窗口，Activity 短暂离场
    //   （按 Home、锁屏）回来时窗口还在、播放进度还在 —— 拆了再挂反而闪断。
    //   副屏播放中切走 = 副屏停在最后一帧（onStop 已 pause），可当暂停海报看。
    // - onDestroy / 拔线：[detachExternalDisplay] 归还主屏。

    /**
     * 按"系统当前有没有可用的 presentation display"对齐挂/断状态。
     * 三个入口共用这一个函数（onResume / onDisplayAdded / onDisplayRemoved），
     * 状态机只有两张牌：有副屏且未挂 → 挂；无副屏且挂着 → 断。
     */
    private fun refreshExternalDisplay() {
        val dm = displayManager ?: return
        val all = dm.getDisplays()
        val candidates = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
        MimiLog.i(TAG, "refreshExternalDisplay: all=${all.joinToString { "${it.displayId}" }} pres=${candidates.joinToString { "${it.displayId}" }}")
        // 多块副屏（罕见）：给面积最大的那块
        val target = candidates
            .maxByOrNull { it.mode.physicalWidth * it.mode.physicalHeight }
        when {
            target != null && presentation == null -> attachExternalDisplay(target)
            target == null && presentation != null -> detachExternalDisplay()
        }
    }

    private fun attachExternalDisplay(display: Display) {
        val p = VideoPresentation(this, display)
        // 系统拔线等场景会主动 dismiss 我们的窗口 —— 这里只做状态复位，
        // surface 归还交给 [detachExternalDisplay]（displayRemoved 路径）。
        p.setOnDismissListener {
            if (presentation === p) {
                presentation = null
                presentationDisplayId = -1
                presentationHolder = null
                extCueListener?.let { player?.removeListener(it) }
                extCueListener = null
                restoreMainVideoSurface()
                binding.playerView.subtitleView?.visibility = View.VISIBLE
                binding.playerExtHint.visibility = View.GONE
            }
        }
        try {
            p.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            // 挂上的瞬间显示被拔（竞态）：Presentation.show 的官方约定是抛这个。
            // 静默放弃会让"插线"从此失联（display ready 后不会再发 added 事件），
            // 所以安排一次重试 —— display 真没了时 refresh 是空操作，无害。
            MimiLog.w(TAG, "presentation show 失败（display 未就绪或已移除）: ${e.message}")
            binding.root.postDelayed({ refreshExternalDisplay() }, 1_000)
            return
        }
        presentation = p
        presentationDisplayId = display.displayId
        binding.playerExtHint.visibility = View.VISIBLE
        toast(getString(R.string.player_ext_attached))
    }

    private fun detachExternalDisplay() {
        val p = presentation ?: return
        p.setOnDismissListener(null)
        if (p.isShowing) p.dismiss()
        presentation = null
        presentationDisplayId = -1
        presentationHolder = null
        extCueListener?.let { player?.removeListener(it) }
        extCueListener = null
        restoreMainVideoSurface()
        binding.playerView.subtitleView?.visibility = View.VISIBLE
        binding.playerExtHint.visibility = View.GONE
    }

    /** 把视频输出 Surface 还给主屏 PlayerView 内部的 TextureView（M7.11 起是它）。 */
    private fun restoreMainVideoSurface() {
        val exo = player ?: return
        exo.setVideoSurface(null)
        (binding.playerView.videoSurfaceView as? TextureView)?.let {
            exo.setVideoTextureView(it)
        }
    }

    /**
     * 把播放器接到副屏 Surface 上（挂副屏 / 副屏 surface 重建 / 播放器重建共用）。
     * `setVideoSurface` 只换输出口，不动播放状态 —— 切换过程不断流、不重解。
     *
     * ## 为什么先查 surface 有效性（0.7.14 模拟器实测的血泪）
     * 播放中挂副屏时，若把一个**已 release** 的 Surface 传给 ExoPlayer，
     * MediaCodec 动态切换（setOutputSurface）会直接抛
     * `IllegalArgumentException: The surface has been released` → 播放失败。
     * overlay/投屏 display 在就绪过程中 surface 会快速重建，"看起来拿到了"
     * 的 holder.surface 可能下一毫秒就失效。无效就整体跳过 ——
     * SurfaceHolder 的 surfaceCreated 回调稍后会带着新 surface 再进来。
     */
    private fun hookPlayerToPresentationSurface(holder: SurfaceHolder) {
        val exo = player ?: return
        if (holder.surface == null || !holder.surface.isValid) return
        // 先显式断开主屏 TextureView：两个 surface 同时挂着时 ExoPlayer 只画
        // 最后设置的那个，但断开主屏能保证主屏画面区立刻变黑（而不是冻结在
        // 最后一帧），用户不会误以为手机屏还在播。
        exo.setVideoSurfaceView(null)
        exo.setVideoSurface(holder.surface)
        // 副屏字幕（M7.13 的顺延）：cue 走 Player.Listener.onCues 分发给副屏
        // SubtitleView；主屏字幕同时隐藏 —— 主屏已是"仅控制"状态，黑底上再画
        // 字幕会让人误以为手机屏还在播。
        extCueListener?.let(exo::removeListener)
        val listener = object : Player.Listener {
            override fun onCues(cues: List<Cue>) {
                presentation?.setCues(cues)
            }
        }
        extCueListener = listener
        exo.addListener(listener)
        binding.playerView.subtitleView?.visibility = View.GONE
        presentation?.syncVideoSize(exo)
    }

    /** 副屏字幕桥（hook 时挂、detach 时摘，避免泄漏到已销毁的窗口）。 */
    private var extCueListener: Player.Listener? = null

    /**
     * 副屏窗口：纯画面 + 字幕，没有任何控制 —— 控制全部留在手机屏。
     *
     * ## 为什么不用 PlayerView
     * PlayerView 自带控制条，在副屏上会出现第二套进度条，而副屏**摸不到**
     * （副屏没有触摸），死控件反而误导。副屏要的只是"显示"，一根 Surface
     * 外加一层 SubtitleView 就是全部。
     */
    private inner class VideoPresentation(context: Context, display: Display) :
        Presentation(context, display) {

        private lateinit var aspectFrame: AspectRatioFrameLayout
        private lateinit var surfaceView: SurfaceView
        private lateinit var subtitleView: SubtitleView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // Presentation 窗口默认底是灰的：黑根布局盖住内容区，但首帧前
            // 窗口背景仍可能闪一下灰 —— 窗口背景统一压成黑。
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            setContentView(R.layout.presentation_external_display)
            aspectFrame = findViewById(R.id.ext_aspect)
            surfaceView = findViewById(R.id.ext_surface)
            subtitleView = findViewById(R.id.ext_subtitle)
            // 副屏通常很远（电视），默认 18sp 看不清
            subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }

        override fun onStart() {
            super.onStart()
            // 窗口真正落在 display 上后才拿得到 surface；也处理
            // "surface 在 onCreate 与 onStart 之间已创建"的竞态。
            surfaceView.holder.addCallback(surfaceCallback)
            if (surfaceView.holder.surface != null) hook(surfaceView.holder)
        }

        private val surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = hook(holder)

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // 归还主屏由 detach/OnDismiss 负责；这里只防"surface 已销毁
                // 还被播放器引用"（exoplayer 对失效 surface 会自动黑屏，不炸）。
            }
        }

        fun hook(holder: SurfaceHolder) {
            presentationHolder = holder
            hookPlayerToPresentationSurface(holder)
        }

        fun setCues(cues: List<Cue>) {
            if (this::subtitleView.isInitialized) subtitleView.setCues(cues)
        }

        /** 视频尺寸 → 副屏 letterbox。与主屏 PlayerView 的 fit 模式一致。 */
        fun syncVideoSize(exo: Player, size: VideoSize? = null) {
            val v = size ?: exo.videoSize
            if (v.width == 0 || v.height == 0) return
            aspectFrame.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)
        }
    }

    // ── 播放 ────────────────────────────────────────────────────

    /**
     * 本页会话是否已经退到"优先软件解码"。
     *
     * 一旦置位就**不再自动重试**：软解本来也解不了的格式（设备上根本没有该编码的
     * 任何解码器，如某些机型的 HEVC 10-bit / AV1）重试一万次也一样，
     * 只会让用户看到"闪一下又开始转圈"。第二次失败直接给出提示与出口。
     */
    private var preferSoftwareDecoder = false

    /**
     * 挑解码器时**把系统软件解码器排到前面**。
     *
     * ## 为什么这条路值得走（这是"软解兜底"里零代价的那一档）
     * 厂商固件的**硬解**实现质量参差：有的只认 Main Profile 的 H.264、有的在
     * HEVC 上遇到非 8-bit 就 `ERROR_CODE_DECODER_INIT_FAILED`。而 AOSP 自带的
     * `c2.android.*` / `OMX.google.*` 是**纯软件解码器**，与厂商固件无关，
     * 在绝大多数设备上都存在且可用。于是"硬解不吃这个码流"这件事，
     * 往往靠一个系统软解就能救回来 —— **不需要往包里塞 FFmpeg**。
     *
     * ## 它救不了什么（诚实说清楚，免得把它当成万能药）
     * 设备上**根本没有**该编码的解码器时（很多老机型遇到 AV1、部分机型遇到
     * HEVC 10-bit），软解列表里也是空的 —— 那一档必须靠
     * `media3-decoder-ffmpeg` 之类自带解码器的方案，代价 +4~40 MB。
     * 方案 §8.1.1 把它列为可选项，且**选型要以真实的"硬解失败样本"为据**；
     * 在没有样本之前付这个体积代价是不划算的。所以这里只做零代价的那一档，
     * 并保留第三档（明确的提示 + 用其他应用打开）作为最终出口。
     *
     * ## 为什么是"排序"而不是"过滤"
     * 过滤掉硬解会同时废掉"硬解明明能用"的绝大多数场景（软解 4K 必掉帧）。
     * 排序则保留全部候选：软解排在前面，但它解不了（`createDecoderByType` 失败）
     * 时 Media3 会**继续往下列表找**，自动落回硬解 —— 这正是我们要的语义。
     */
    private fun softwareFirstSelector(): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            MediaCodecSelector.DEFAULT
                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                .sortedByDescending { info ->
                    when {
                        info.name.startsWith("c2.android.") -> 2
                        info.name.startsWith("OMX.google.") -> 1
                        else -> 0
                    }
                }
        }

    private fun buildPlayer() {
        val slice = if (args.hasSlice) args.sliceOffset else -1L
        val sliceLength = if (args.hasSlice) args.sliceLength else -1L
        // NextRenderersFactory 是 DefaultRenderersFactory 的直系子类（一比一兼容）：
        // 平台 MediaCodec 硬解仍是第一优先，FFmpeg 只在"平台没有该编码的解码器"
        // 时才顶上（EXTENSION_RENDERER_MODE_ON）——即 AC3/EAC3/DTS/TrueHD 这类
        // 安卓不自带解码器的杜比/DTS 系音频；视频硬解路径完全不受影响。
        // setEnableDecoderFallback 让"硬解初始化失败"也能逐级回落，不留死角。
        val renderers = NextRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
            if (preferSoftwareDecoder) setMediaCodecSelector(softwareFirstSelector())
        }
        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(renderers)
            // 前后跳帧按钮的增量：前 15 秒 / 后 30 秒（M7.6，产品指定）。
            // ⚠️ media3 1.11 里这对增量在 **Builder** 上，不在 PlayerControlView ——
            // 控制条的快退/快进按钮走 player.seekBack()/seekForward()，增量由播放器持有。
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    SeekableSourceDataSource.Factory(
                        sliceOffset = slice,
                        sliceLength = sliceLength,
                    ),
                ),
            )
            .build()
        player = exo
        binding.playerView.player = exo
        // 软解重试会走到这里**重建播放器**（M7.14）：主屏 surface 由上面这行
        // setPlayer 自动重绑，副屏的 Presentation Surface 不会 —— 手动补接，
        // 否则"播放失败→软解重试"在副屏场景下表现为大屏黑屏只剩手机控制条。
        presentationHolder?.takeIf { it.surface != null && it.surface.isValid }?.let {
            hookPlayerToPresentationSurface(it)
        }
        exo.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        applyResumeIfNeeded()
                        // 默认倍速兜底：playbackParameters 理论上跨 prepare 保留，
                        // 但软解重试会**重建播放器**（新实例回到 1x），这里对齐。
                        player?.takeIf {
                            it.playbackParameters.speed != defaultSpeed
                        }?.setPlaybackSpeed(defaultSpeed)
                        // 字幕自动优选（M7.13）：轨道表在 READY 才有效。
                        maybeAutoSelectSubtitle()
                        // 副屏比例同步（M7.14）：换片后 videoSize 变了，
                        // 若 onVideoSizeChanged 早于 READY 已发生过也会在这里补上。
                        player?.let { presentation?.syncVideoSize(it) }
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    player?.let { presentation?.syncVideoSize(it, videoSize) }
                }

                // 下面四个回调是 Media3 **换控制条图标**的全部入口（播放/暂停图标、
                // 循环/随机、字幕开关）。每次变化后重包一次描边（M7.20）。
                // 不做逐帧检查的理由见 applyIconOutline 的注释 —— 那会死循环。
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // 播放/暂停图标是 Media3 换得最勤的一个（updatePlayPauseButton 会在
                    // 多个时机被调），同步重包一次常常还是被它盖掉 —— 实测 exo_play_pause
                    // 是唯一会漏描边的按钮。再延后一帧补一次，把它彻底压住。
                    applyIconOutline()
                    binding.root.post { applyIconOutline() }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    applyIconOutline()
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    applyIconOutline()
                }

                override fun onTrackSelectionParametersChanged(
                    parameters: androidx.media3.common.TrackSelectionParameters,
                ) {
                    applyIconOutline()
                }

                override fun onPlayerError(error: PlaybackException) {
                    onPlaybackError(error)
                }
            },
        )
        // 解码器初始化事件：角标与信息面板的唯一事实源。
        // media3 1.11 没有统一的 onDecoderInitialized(…, trackType, …)，视频/音频
        // 各走各的回调；两者都来自渲染器的 onCodecInitialized 钩子，换片、
        // 软解重试都会重新触发，天然就是"当前是谁在解"的实时信号。
        exo.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializationTimestampMs: Long,
                ) {
                    MimiLog.i(TAG, "video decoder init: $decoderName")
                    videoDecoderName = decoderName
                    updateSwBadge()
                }

                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializationTimestampMs: Long,
                ) {
                    MimiLog.i(TAG, "audio decoder init: $decoderName")
                    audioDecoderName = decoderName
                }
            },
        )
    }

    /**
     * 角标 = 「视频轨道此刻是否在用软件解码器」。
     *
     * 只看视频轨道：软解兜底保的是"播得出来"，而用户关心角标是怕"4K 软解放不动"，
     * 音频软解的代价可以忽略。解码器名还没报上来（刚换片）时隐藏——
     * 宁可晚两秒出现，也不要显示一个"猜的"状态。
     */
    private fun updateSwBadge() {
        val sw = videoDecoderName?.let(DecoderNames::isSoftware) == true
        binding.playerSwBadge.visibility = if (sw) View.VISIBLE else View.GONE
    }

    /**
     * 播放器出错的统一入口：**先试软解，再谈失败**。
     *
     * 顺序是硬编码的（不需要配置项）：能播 > 播得流畅。软解只在硬解已经
     * 明确失败之后才启用，所以"平时偷偷降级到软解、4K 掉帧"的情况不会发生。
     */
    private fun onPlaybackError(error: PlaybackException) {
        // 副屏自愈（M7.14）：挂副屏过程中若发生播放错误（典型：display 就绪期
        // surface 快速重建，动态切 surface 失败），先把视频面收回落回主屏再走
        // 重试链 —— 重试在一个确定有效的 surface 上进行，用户最终看到的是
        // "回到手机屏继续播"，而不是一个莫名的失败对话框。
        if (presentation != null) {
            MimiLog.w(TAG, "副屏播放中出错（errorCode=${error.errorCode}），收回落回主屏后重试")
            detachExternalDisplay()
        }
        if (isDecoderIssue(error) && !preferSoftwareDecoder) {
            // 记下位置再重建播放器：重试必须**从用户看到的那一帧继续**，
            // 否则用户点开一个 40 分钟的视频、在 39 分处碰上解码错误，
            // 重试后回到 0 分 —— 那不是"兜底"，那是把体验变得更糟。
            val resumeAt = player?.currentPosition?.takeIf { it > 0L } ?: 0L
            preferSoftwareDecoder = true
            MimiLog.w(
                TAG,
                "硬解失败（errorCode=${error.errorCode}），改用系统软件解码器重试；" +
                    "位置=${resumeAt}ms 文件=${args.current}",
            )
            releasePlayer()
            buildPlayer()
            play(args.index, resumeAt = resumeAt)
            toast(getString(R.string.player_sw_decode_retry))
            return
        }
        showPlaybackError(error)
    }

    /** 这个错误是不是"解码器不吃这个码流"。 */
    private fun isDecoderIssue(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

    private fun releasePlayer() {
        binding.playerView.player = null
        player?.release()
        player = null
    }

    private fun play(index: Int, resumeAt: Long? = null) {
        val exo = player ?: return
        val path = args.paths.getOrNull(index) ?: return
        args = args.copy(index = index)
        resumeApplied = false
        resumedFrom = 0L
        // 显式给了位置（软解重试）时**不再**提示"已从 xx 继续播放"：
        // 那会让用户以为是自己上次退出时的进度，而实际是刚刚失败的那一帧。
        resumeNoticeShown = resumeAt != null
        binding.playerTitle.text = path.displayName
        binding.playerPrev.visibility = if (hasNeighbour(index, -1)) View.VISIBLE else View.GONE
        binding.playerNext.visibility = if (hasNeighbour(index, 1)) View.VISIBLE else View.GONE
        // 换片后旧解码器的名字不再成立：清空并隐藏角标，等新片子的
        // onDecoderInitialized 重新报上来（宁可晚亮两秒，不显示猜的状态）。
        videoDecoderName = null
        audioDecoderName = null
        updateSwBadge()
        // 换片后画面缩放也一并复位：它是"看这一部"的临时操作，带着放大状态
        // 换下一个视频，用户会以为新片子"坏了"。
        gestures.resetZoom()
        // M7.13：新片子重置字幕状态 —— 清掉上一部的手动 override（其引用的
        // TrackGroup 已随旧媒体项失效，留着虽无效但语义含糊），恢复"文本轨可选"，
        // 让新片子重新走一遍"优先简中"的自动优选（READY 时执行）。
        subtitleAutoApplied = false
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()

        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(PreviewUri.of(path))
                // 必须显式给 mime：我们的 scheme 是 `mimifs:`，Media3 无法据此后缀推断容器，
                // 不给就会落到错误的 MediaSource 类型上（表现为"一直缓冲不播"）。
                .setMimeType(MimeTypes.of(path.displayName))
                .build(),
        )
        exo.prepare()
        exo.playWhenReady = true
        if (resumeAt != null) {
            resumedFrom = resumeAt
        } else {
            lifecycleScope.launch {
                resumedFrom = runCatching { playback.positionOf(path.toString()) }.getOrDefault(0L)
            }
        }
    }

    /**
     * 续播要等 `STATE_READY`：`seekTo` 在准备完成前调用会被播放器忽略或抛
     * `IllegalSeekPositionException`（取决于容器是否已解析出时长）。
     */
    private fun applyResumeIfNeeded() {
        if (resumeApplied) return
        resumeApplied = true
        val from = resumedFrom
        if (from <= 0L) return
        player?.seekTo(from)
        if (!resumeNoticeShown) {
            resumeNoticeShown = true
            binding.playerHud.text = getString(R.string.player_resume_from, Formatters.duration(from))
            binding.playerHud.visibility = View.VISIBLE
            binding.playerHud.postDelayed({ binding.playerHud.visibility = View.GONE }, 2500L)
        }
    }

    private fun savePosition() {
        val exo = player ?: return
        val current = args.current
        // 刚删掉的那个不写：删除成功后到"切到下一个"之间会经历 onStop/onDestroy，
        // 不加这一条就会把刚 forget 掉的记录原样写回去（文件没了，进度留下了）。
        if (current == deletedPath) return
        val path = current.toString()
        val position = exo.currentPosition
        if (position <= 0L) return
        val duration = exo.duration.takeIf { it > 0L } ?: 0L
        // 走进程级作用域：[AppScope] 不随页面销毁而取消，而"离开时的位置"正是唯一
        // 值得保存的那一次（用 lifecycleScope 会在 onDestroy 时被取消，永远写不进去）。
        AppScope.scope.launch {
            runCatching { playback.remember(path, position, duration) }
        }
    }

    private fun hasNeighbour(from: Int, step: Int): Boolean = neighbourIndex(from, step) != null

    /**
     * 找下一个/上一个**视频**。
     *
     * 同级列表里混着图片与文本，直接 `index ± 1` 会跳到一个"播不了的东西"上，
     * 而且用户看到的是"点了下一个，画面黑了一下又回来"。所以按 mime 过滤。
     */
    private fun neighbourIndex(from: Int, step: Int): Int? {
        var index = from + step
        while (index in args.paths.indices) {
            val candidate = args.paths[index]
            if (MimeTypes.isPlayableMedia(MimeTypes.of(candidate.displayName))) return index
            index += step
        }
        return null
    }

    private fun playNeighbour(step: Int) {
        // 删除进行中：`args.paths` 还没同步（见 deleting 的注释），此刻换片会让下标错位。
        if (deleting) return
        val next = neighbourIndex(args.index, step)
        if (next == null) {
            android.widget.Toast.makeText(this, R.string.player_no_more, android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }
        savePosition()
        play(next)
    }

    // ── M7.24：审片时直接删片 ────────────────────────────────────
    //
    // ## 为什么删除要放在播放页里
    // 审片的动作序列是"看几秒 → 决定去留 → 下一部"。若要删就得退回列表、长按、确认、
    // 再点回播放器，一百个片子就是几百次往返。所以删除必须就地做，且删完**接着播**。
    //
    // ## 删完播谁
    // 删掉的是第 i 个，剩下的同目录视频里取**原来第 i+1 个**（现在落在 i 位）接着播 ——
    // 与"下一个"按钮的语义完全一致，不会因为前面删过几个就把顺序记错。
    // 后面没有视频了就退出页面，回到刚才那个文件夹（删过谁由
    // [com.miyuetech.mimifiles.core.fs.FsMutations] 的标记告诉浏览页，让它重列一次）。

    /** 刚删掉的路径。留在会话里当"墓碑"，防止 onStop 的 [savePosition] 把它写回进度表。 */
    private var deletedPath: FsPath? = null

    /**
     * 正在删（网络盘上可能要一两秒）。
     *
     * 这期间要挡住"上一个/下一个/再点删除"：删掉的是**按下那一刻**的第 i 个，
     * 而 `advanceAfterDelete` 之后才去改列表。中途换片会让下标与列表对不上，
     * 结果是"删了 A、界面跳到 B、实际播 C"这种最难复现的事故。
     */
    private var deleting = false

    private fun toggleDeleteConfirm() {
        if (deleting) return
        if (binding.playerDeletePanel.visibility == View.VISIBLE) {
            dismissDeleteConfirm()
        } else {
            showDeleteConfirm()
        }
    }

    private fun showDeleteConfirm() {
        binding.playerDeleteName.text = args.current.displayName
        binding.playerDeleteScrim.visibility = View.VISIBLE
        binding.playerDeletePanel.visibility = View.VISIBLE
        // 面板开着时不能让控制条自己收起来：它一收，顶栏（含删除键）也跟着 GONE，
        // 面板就悬在一片没有锚点的画面上了。
        binding.playerView.controllerShowTimeoutMs = 0
        binding.playerView.showController()
        // 等一帧再量坐标：showController 之后顶栏才真正完成布局。
        binding.playerDeletePanel.post { positionDeletePanel() }
    }

    /**
     * 把面板挪到删除键正下方、右边缘对齐。
     *
     * 用 `getLocationInWindow` 两两相减而不是自己拼 padding 链：顶栏吃了状态栏 inset
     * （`padForSystemBars`），按钮的 y 不是任何常量的函数，量出来才是对的。
     * 右对齐（Gravity.END + rightMargin）保证面板向左展开，不会顶出屏幕右侧——
     * 而 rightMargin 只跟 root 右缘和锚点右缘有关，**不需要**面板自己的宽度。
     *
     * `attempt` 是给"刚 showController、顶栏还没走完布局"那种一帧之内的竞态用的：
     * 量不到就下一帧再量，最多 3 次，实在不行就停在布局里默认的 `top|end` 位置
     * （视觉上仍在顶栏右下方，只是没有对齐到删除键）。
     */
    private fun positionDeletePanel(attempt: Int = 0) {
        val anchor = binding.playerDelete
        if (anchor.width <= 0) {
            if (attempt < 3) binding.playerDeletePanel.post { positionDeletePanel(attempt + 1) }
            return
        }
        val anchorLoc = IntArray(2)
        val rootLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        binding.root.getLocationInWindow(rootLoc)
        val params = binding.playerDeletePanel.layoutParams as? FrameLayout.LayoutParams ?: return
        params.gravity = Gravity.TOP or Gravity.END
        params.topMargin = (anchorLoc[1] - rootLoc[1]) + anchor.height + dp(4)
        params.rightMargin = (rootLoc[0] + binding.root.width) - (anchorLoc[0] + anchor.width)
        binding.playerDeletePanel.layoutParams = params
    }

    private fun dismissDeleteConfirm() {
        binding.playerDeletePanel.visibility = View.GONE
        binding.playerDeleteScrim.visibility = View.GONE
        binding.playerView.controllerShowTimeoutMs = controllerShowTimeoutMs
    }

    private fun performDelete() {
        if (deleting) return
        val path = args.current
        val index = args.index
        dismissDeleteConfirm()

        // 只读后端（无删除位的 SAF 授权树等）当场说清楚，不做"点了没反应"。
        if (!FileSystems.registry.capabilitiesOf(path.scheme).has(FsCapability.DELETE)) {
            toast(getString(R.string.player_delete_unsupported))
            return
        }

        deleting = true
        lifecycleScope.launch {
            showHud(getString(R.string.player_deleting))
            // ⚠️ 必须先松开读取句柄再删。播放器此刻正握着这个文件，而 SMB/WebDAV
            // 服务端对"被别的句柄打开着"的文件常常直接拒绝删除（共享冲突），
            // 表现是"本地盘能删、NAS 上删不掉"。stop() 让 ExoPlayer 释放数据源，
            // clearMediaItems() 连 MediaSource 一起放掉 —— 删完要播下一个，
            // 反正会重新 setMediaItem。
            player?.stop()
            player?.clearMediaItems()
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    FileSystems.registry.resolve(path).delete(path)
                    // 进度记录跟着文件走：留着它，同名新文件会"莫名从前任的 37 分钟继续"。
                    runCatching { playback.forget(path.toString()) }
                }.exceptionOrNull()
            }
            if (failure != null) {
                val reason = runCatching { failure.toFsException(path).message }
                    .getOrNull() ?: failure.message.orEmpty()
                toast(getString(R.string.player_delete_failed, reason))
                deleting = false
                // 画面已经在上面被停掉了：不重新起播就是一片黑，
                // 用户会以为"点了删除，播放器坏了"。重新 play 会顺带恢复到原进度。
                play(index)
                return@launch
            }
            deletedPath = path
            // 让浏览页回到列表时重新列一次这个目录。
            // ⚠️ 不用 `setResult(RESULT_OK)`：播放器是**预览页**拉起来的，而预览页
            // 转手之后已经 finish 了，结果会落到一个销毁了的 Activity 上被 Android
            // 丢掉。这条标记走进程内共享状态，跟中间隔了几跳无关。
            FsMutations.markChanged(path.parent)
            advanceAfterDelete(index)
            deleting = false
        }
    }

    /**
     * 删掉 [index] 之后接着播。
     *
     * ## 顺序上有个坑，必须按这个顺序写
     * "找不到下一个就收场"这条分支**不能**先把 `args.index` 挪到别的条目上再 finish：
     * `onDestroy` 里的 [savePosition] 读的是 `exo.currentPosition`（此刻仍然是**刚删掉
     * 那个片子**的进度）而写的是 `args.current`。一旦先把 index 改到前一条，就会把
     * 已删文件的进度写到另一部片子身上 —— 下次打开那部会从莫名其妙的位置开始。
     * 所以：先算"删掉之后原位置现在是谁"，算不出来就保持 args 原样直接退出
     * （[deletedPath] 这道闸门会让 savePosition 跳过它）。
     */
    private fun advanceAfterDelete(index: Int) {
        val next = DeleteAdvance.nextAfterDelete(args.paths.map { it.displayName }, index)
        if (next < 0) {
            toast(getString(R.string.player_delete_done_last))
            finish()
            return
        }
        val remaining = ArrayList(args.paths)
        if (index in remaining.indices) remaining.removeAt(index)
        args = args.copy(paths = remaining, index = next)
        showHud(getString(R.string.player_delete_done_next))
        play(next)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            android.widget.Toast.makeText(
                this,
                R.string.player_pip_unsupported,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        enterPipInternal()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipInternal() {
        val exo = player ?: return
        val size = if (exo.videoSize.height > 0) {
            Rational(exo.videoSize.width.coerceAtLeast(1), exo.videoSize.height.coerceAtLeast(1))
        } else {
            Rational(16, 9)
        }
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(size).build(),
            )
        }
    }

    // ── M7：视频信息面板 ─────────────────────────────────────────
    //
    // 它存在的唯一理由是排障：判断"这个片子为什么播不了/在软解"需要三个事实——
    // 码流是什么编码（精确到 profile）、此刻是谁在解、本机还有谁可解。
    // 面板正文刻意用纯文本：一按「复制」就能整段发回来，比截图少一轮转写。

    /**
     * 弹信息面板。
     *
     * 守卫只看**轨道表是否为空**，刻意不看 `playbackState`：STATE_ENDED
     * （片子播完）时轨道表依然有效，而"播完的视频为什么解不了"恰恰是
     * 最需要面板的时刻——按 READY 过滤会把它拦在门外（冒烟实测钓出）。
     * 轨道表为空（还没 prepare 完成 / 起播失败）才提示稍后再试。
     */
    private fun showVideoInfo() {
        val exo = player
        if (exo == null || exo.currentTracks.groups.isEmpty()) {
            toast(getString(R.string.player_info_not_ready))
            return
        }
        lifecycleScope.launch {
            val text = buildInfoText(exo)
            AlertDialog.Builder(this@VideoPlayerActivity)
                .setTitle(R.string.player_info_title)
                .setMessage(text)
                .setPositiveButton(R.string.player_info_copy) { _, _ -> copyToClipboard(text) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * 组装面板正文。
     *
     * 文件大小走一次 `stat`（SMB/WebDAV 上是真网络请求），所以包在
     * `withTimeoutOrNull(4s)` 里：拿不到就显示 `--`，**不能让它把整个面板卡住**——
     * 面板的价值在解码器信息，大小只是锦上添花。
     */
    private suspend fun buildInfoText(exo: ExoPlayer): String {
        val path = args.current
        val size = withContext(Dispatchers.IO) {
            runCatching {
                withTimeoutOrNull(4000L) {
                    FileSystems.registry.resolveOrNull(path)?.stat(path)?.size
                        ?: FsEntry.SIZE_UNKNOWN
                } ?: FsEntry.SIZE_UNKNOWN
            }.getOrDefault(FsEntry.SIZE_UNKNOWN)
        }

        // 实际选中的轨道：从 currentTracks 里挑每类第一条"被选中"的。
        var video: Format? = null
        var audio: Format? = null
        // 音频是否真的在出声。false = 音频轨道存在但没有任何解码器能选它
        // （无声的根源）；此时 Format 从 TrackGroup 里照常取出，给透明化展示。
        var audioRendered = true
        for (group in exo.currentTracks.groups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> if (video == null) video = group.getTrackFormat(i)
                    C.TRACK_TYPE_AUDIO -> if (audio == null) audio = group.getTrackFormat(i)
                }
            }
        }
        if (audio == null) {
            for (group in exo.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO || group.length == 0) continue
                audio = group.getTrackFormat(0)
                audioRendered = false
                break
            }
        }

        val sw = getString(R.string.player_dec_sw)
        val hw = getString(R.string.player_dec_hw)

        return buildString {
            // ── 文件 ──
            append("文件：").append(path.displayName)
            if (size != FsEntry.SIZE_UNKNOWN) append("（").append(Formatters.size(size)).append('）')
            append('\n')
            append("位置：").append(path)
            append("（").append(FileSystems.registry.displayNameOf(path.scheme)).append("）\n")
            append("容器：").append(MimeTypes.of(path.displayName)).append('\n')
            append("时长：").append(Formatters.duration(exo.duration)).append('\n')
            // ── 流信息 ──
            video?.let { f ->
                append("\n视频流：").append(DecoderNames.labelOf(f.sampleMimeType))
                if (!f.codecs.isNullOrEmpty()) append(" [").append(f.codecs).append(']')
                if (f.width > 0 && f.height > 0) {
                    append('，').append(f.width).append('×').append(f.height)
                    if (f.frameRate > 0f) append(" @").append(f.frameRate.toInt()).append("fps")
                    append('，').append(bitrateLabel(f.bitrate))
                }
                append('\n')
            }
            audio?.let { f ->
                append("音频流：").append(DecoderNames.labelOf(f.sampleMimeType))
                if (!f.codecs.isNullOrEmpty()) append(" [").append(f.codecs).append(']')
                if (f.sampleRate > 0) {
                    append('，').append(f.sampleRate).append("Hz")
                    if (f.channelCount > 0) append('，').append(f.channelCount).append("声道")
                }
                // 透明化：无声不再无声地无声——编码明示 + 原因一句话说清。
                if (!audioRendered) append("，⚠ 本机无可用解码器（静音播放）")
                append('\n')
            }
            // ── 字幕（M7.13）──
            // 排障高价值行："字幕怎么是繁体的/怎么没字幕"是选轨问题的全部现场。
            append("\n字幕：").append(subtitleSummaryLine(exo)).append('\n')
            // ── 实际解码器 ──
            append("\n实际解码器：\n")
            append("视频：").append(decoderLine(videoDecoderName, sw, hw)).append('\n')
            append(
                "音频：",
            ).append(
                when {
                    audioRendered -> decoderLine(audioDecoderName, sw, hw)
                    // 音频轨道没被选中时"未上报"是谜语；直接给结论。
                    else -> "（无——该编码本机与内置 FFmpeg 均不支持）"
                },
            ).append('\n')
            // ── 本机可用解码器（排障判据）──
            appendAvailable("\n本机可用的视频解码器", video?.sampleMimeType, sw, hw)
            appendAvailable("本机可用的音频解码器", audio?.sampleMimeType, sw, hw)
        }
    }

    private fun decoderLine(name: String?, sw: String, hw: String): String {
        name ?: return "（未上报）"
        return "$name（${if (DecoderNames.isSoftware(name)) sw else hw}）"
    }

    /**
     * 信息面板的「字幕」行：当前**实际选中**的字幕轨（label 优先，退化到语言名）。
     *
     * 刻意读"哪条被选中"而不是"参数里 override 了哪条"：override 只是意愿，
     * 选轨器可能因轨道无效而没选上 —— 面板要的是事实。也是冒烟脚本在 release
     * 包上验证选轨状态的窗口（MimiLog 不进 logcat，这是可直接读的现场）。
     */
    private fun subtitleSummaryLine(exo: ExoPlayer): String {
        if (C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes) {
            return "已关闭"
        }
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val f = group.getTrackFormat(i)
                return f.label?.trim()?.takeIf { it.isNotEmpty() }
                    ?: f.language?.takeIf { it.isNotBlank() }
                        ?.let { Locale.forLanguageTag(it).displayName }
                    ?: "字幕轨"
            }
        }
        val count = exo.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .sumOf { it.length }
        return if (count > 0) "未显示（本片有 $count 条字幕轨）" else "无字幕轨"
    }

    private fun StringBuilder.appendAvailable(
        title: String,
        mime: String?,
        sw: String,
        hw: String,
    ) {
        if (mime.isNullOrEmpty()) return
        val options = DecoderNames.available(mime)
        append(title).append("（").append(DecoderNames.labelOf(mime)).append("）：\n")
        if (options.isEmpty()) {
            // M7.5 起包里自带 FFmpeg 音频软解：系统列表为空不再等于"救不了"。
            append("（系统不自带该编码的解码器——将由内置 FFmpeg 软解兜底；若仍无声说明 FFmpeg 也不支持）\n")
            return
        }
        val hard = options.filter { !it.software }.joinToString("、") { it.name }
        val soft = options.filter { it.software }.joinToString("、") { it.name }
        if (hard.isNotEmpty()) append(hw).append('：').append(hard).append('\n')
        if (soft.isNotEmpty()) append(sw).append('：').append(soft).append('\n')
    }

    private fun bitrateLabel(bps: Int): String = when {
        bps <= 0 -> ""
        bps >= 1_000_000 -> String.format(Locale.US, "%.1fMbps", bps / 1_000_000.0)
        else -> "${bps / 1000}kbps"
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mimifiles-video-info", text))
        toast(getString(R.string.player_info_copied))
    }

    private fun showPlaybackError(error: PlaybackException) {
        // 走到这里意味着**软解也已经试过了**（见 `onPlaybackError` 的顺序），
        // 所以文案要说"硬解与系统软解都没能播放"，而不是"设备不支持这个编码"
        // —— 后者会让用户以为是自己设备太差，而实际上我们还有第三条路没走
        // （自带解码器，方案 §8.1.1），只是现在没付那份体积代价。
        val hint = if (isDecoderIssue(error)) {
            getString(R.string.player_error_decoder)
        } else {
            getString(R.string.player_error_generic)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.player_error_title)
            .setMessage(hint)
            .setPositiveButton(R.string.player_open_with) { _, _ ->
                val path = args.current
                if (!ExternalOpen.open(this, path, MimeTypes.of(path.displayName))) {
                    android.widget.Toast.makeText(
                        this,
                        R.string.player_error_title,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 供冒烟脚本读取的当前状态（把"能不能拖"变成可观测事实）。 */
    internal fun debugState(): String {
        val exo = player ?: return "player=null"
        return "state=${exo.playbackState} pos=${exo.currentPosition} dur=${exo.duration} " +
            "playing=${exo.isPlaying} sw=${videoDecoderName?.let(DecoderNames::isSoftware)} " +
            "vdec=$videoDecoderName path=${args.current}"
    }

    private companion object {
        const val TAG = "MimiPlayer"

        /** 竖条位置刷新间隔：200ms 肉眼已连续，再快只是白烧 CPU。 */
        const val VERTICAL_SEEK_TICK_MS = 200L
    }
}
