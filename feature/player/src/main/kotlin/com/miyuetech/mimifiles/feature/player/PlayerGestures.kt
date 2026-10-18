package com.miyuetech.mimifiles.feature.player

import android.content.Context
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.miyuetech.mimifiles.core.common.Formatters
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 播放页手势：左侧上下调亮度、右侧上下调音量、水平拖快进/快退、
 * 双指捏合缩放画面（M7.6，VLC 同款）、放大后单指拖平移画面。
 *
 * ## 为什么自己写而不是用库
 * 这套交互只有一两百行，且**每一条规则都直接对应硬件行为**（窗口亮度、音频流音量）。
 * 引一个"播放器手势库"要连同它的 UI 一起接受，而我们的手势是"无 UI 的（除了一行 HUD）"。
 *
 * ## 必须处理的细节
 *  1. **初始亮度要先从系统读**：`window.attributes.screenBrightness` 默认是
 *     `BRIGHTNESS_OVERRIDE_NONE`（-1），表示"跟随系统"。若直接拿 -1 当起点，
 *     用户往上一划就会把亮度设成负数（等于全黑）。
 *  2. **起点必须在 `ACTION_DOWN` 冻结**：移动过程中反复读取当前值再叠加，
 *     会让手势"加速"（每帧都在放大上一次的增量），表现为轻轻一划就到底。
 *  3. **水平/垂直的判定只看第一次超过触摸阈值的那个方向**，之后锁定。
 *     否则斜着划会同时改亮度与进度，用户会以为界面坏了。
 *  4. ⚠️ **`ACTION_DOWN` 必须返回 `false`，把"单击"让给 PlayerView**。
 *     见 [onTouch] 里的说明 —— 这一条曾经写错，后果是控制条一旦自动隐藏就再也唤不出来。
 *
 * ## 缩放手势（M7.6）
 * - 缩放对象是 `exo_content_frame`（PlayerView 内装画面的容器），控制条是它的兄弟层，
 *   缩放不影响控制条触摸；等比缩放，画面不会变形。
 * - **间距基线用绝对值**：第二指落下那一刻定格 `baseSpan/baseScale`，之后
 *   `scale = baseScale × span/baseSpan` —— 终值精确等于指间距比，跟手性与
 *   M7.1 重写 ZoomableContainer 时是同一条铁律（相对 factor 累乘会把注入/
 *   抖动误差也累乘进去，且起点段的间距会被"吃掉"）。
 * - 锚点用捏合中点（焦点锚定公式 `t₁ = f−c−(f−c−t₀)·r`，与 ZoomTransform.zoomAbout
 *   同源），画面始终贴着手指中点缩放。
 * - **分工照抄 VLC**：放大后（>1.0）单指拖动 = 平移画面，优先于亮度/音量/跳帧；
 *   缩回 1.0 恢复原有手势。捏合中缩小越过 1.0 直接吸附回 1.0 并清零平移。
 */
class PlayerGestures(
    private val activity: AppCompatActivity,
    private val currentPlayer: () -> androidx.media3.exoplayer.ExoPlayer?,
    private val hud: TextView,
    private val host: View,
    private val zoomTarget: () -> View?,
) : View.OnTouchListener {

    private val audioManager: AudioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var mode = Mode.NONE

    private var downX = 0f

    private var downY = 0f

    private var startBrightness = 0.5f

    private var startVolume = 0

    private var maxVolume = 1

    private var seekDeltaMs = 0L

    private var downPositionMs = 0L

    // ── 缩放/平移状态 ───────────────────────────────────────────────
    private var zoomScale = 1f

    private var zoomTransX = 0f

    private var zoomTransY = 0f

    /** 第二指落下那一刻定格的指间距与当时的倍数（绝对基线，见类注释）。 */
    private var pinchBaseSpan = 0f

    private var pinchBaseScale = 1f

    /** 平移手势的起点（手指位置 + 当时画面位移）。 */
    private var panStartX = 0f

    private var panStartY = 0f

    private var panStartTransX = 0f

    private var panStartTransY = 0f

    private enum class Mode { NONE, PENDING, BRIGHTNESS, VOLUME, SEEK, PAN, PINCH }

    /**
     * 换片 / 转屏后调用：画面缩放是"看这一部"的临时操作，不该带进下一个视频，
     * 也不该带进新尺寸的窗口。
     */
    fun resetZoom() {
        zoomScale = 1f
        zoomTransX = 0f
        zoomTransY = 0f
        applyZoomTransform()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                mode = Mode.PENDING
                startBrightness = currentBrightness()
                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    .coerceAtLeast(1)
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                downPositionMs = currentPlayer()?.currentPosition ?: 0L
                seekDeltaMs = 0L
                // ⚠️ 这里必须返回 `false`（**不**消费），把这一串事件让给 PlayerView。
                //
                // 为什么：Media3 的 `PlayerView` 靠 `onTouchEvent` 处理"单击切换控制条"。
                // `setOnTouchListener` 的返回值优先级**高于** View 自己的 `onTouchEvent`：
                // 一旦在 DOWN 上返回 true，PlayerView 永远收不到这次手势，控制条就
                // 只会自己超时隐藏、**再也无法唤出** —— 播放/暂停、进度条、快进按钮
                // 全部用不了。这是本工程实测踩到的真实缺陷（真机上表现为"点屏幕没反应"）。
                //
                // 返回 false 之后：轻点 → PlayerView 处理（显隐控制条）；
                // 拖动超过阈值 → 我们在 ACTION_MOVE 里接管（见下）。
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下 = 捏合开始（不管第一根手指此前想干什么）。
                if (event.pointerCount >= 2) {
                    mode = Mode.PINCH
                    pinchBaseSpan = spanOf(event)
                    pinchBaseScale = zoomScale
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.PINCH || event.pointerCount >= 2) {
                    applyPinch(event)
                    return true
                }
                val dx = event.x - downX
                val dy = event.y - downY
                if (mode == Mode.PENDING) {
                    // 阈值内不接管，仍然让 PlayerView 有机会把它当成点击。
                    if (maxOf(abs(dx), abs(dy)) < TOUCH_SLOP) return false
                    mode = when {
                        // VLC 同款分工：画面放大后单指拖 = 平移画面（优先级最高），
                        // 此时亮度/音量/跳帧让路；缩回 1.0 恢复原有手势。
                        zoomScale > 1f + ZOOM_SNAP_EPSILON -> Mode.PAN
                        abs(dx) > abs(dy) -> Mode.SEEK
                        downX < v.width / 2f -> Mode.BRIGHTNESS
                        else -> Mode.VOLUME
                    }
                }
                when (mode) {
                    Mode.SEEK -> applySeek(dx, v.width)
                    Mode.BRIGHTNESS -> applyBrightness(dy, v.height)
                    Mode.VOLUME -> applyVolume(dy, v.height)
                    Mode.PAN -> applyPan(event)
                    else -> Unit
                }
                // 到这里才接管：从这一步起 PlayerView 收不到后续事件，
                // 它那次"按下"也就不会升级成点击（不会误切控制条）。
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 捏合中抬掉一根手指：缩放结束。若画面仍放大，剩下的那根手指
                // 无缝转成平移（VLC 行为：捏完直接拖着画面挪）；缩回 1.0 则复位。
                if (mode == Mode.PINCH) {
                    if (zoomScale > 1f + ZOOM_SNAP_EPSILON) {
                        val remaining = if (event.actionIndex == 0) 1 else 0
                        mode = Mode.PAN
                        panStartX = event.getX(remaining)
                        panStartY = event.getY(remaining)
                        panStartTransX = zoomTransX
                        panStartTransY = zoomTransY
                    } else {
                        resetZoom()
                        mode = Mode.NONE
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 拖动期间只预览、松手才真正跳：否则每一次 move 都 seek，
                // 播放器会被几十次 seek 打成一帧一帧的卡顿。
                if (mode == Mode.SEEK && seekDeltaMs != 0L) {
                    val target = (downPositionMs + seekDeltaMs).coerceAtLeast(0L)
                    currentPlayer()?.seekTo(target)
                }
                // 只有真的拖动过才需要收 HUD 并消费这个 UP；没拖动时是"点击"，
                // 必须让 PlayerView 收尾（否则它不会触发控制条切换）。
                val dragged = mode != Mode.PENDING && mode != Mode.NONE
                mode = Mode.NONE
                hud.visibility = View.GONE
                return dragged
            }
        }
        return false
    }

    // ── 缩放 / 平移 ─────────────────────────────────────────────────

    private fun applyPinch(event: MotionEvent) {
        val content = zoomTarget() ?: return
        val span = spanOf(event)
        if (span < MIN_SPAN || pinchBaseSpan < MIN_SPAN) return
        val newScale = (pinchBaseScale * span / pinchBaseSpan)
            .coerceIn(1f, MAX_ZOOM)
        // 焦点锚定：捏合中点保持不动（与 ZoomTransform.zoomAbout 同一公式）。
        // content 与 host 坐标系一致（content 铺满 host），pivot 取视图中心。
        val fx = (event.getX(0) + event.getX(1)) / 2f
        val fy = (event.getY(0) + event.getY(1)) / 2f
        val cx = content.width / 2f
        val cy = content.height / 2f
        val r = newScale / zoomScale
        zoomTransX = fx - cx - (fx - cx - zoomTransX) * r
        zoomTransY = fy - cy - (fy - cy - zoomTransY) * r
        zoomScale = newScale
        clampPan(content)
        applyZoomTransform()
        showHud(activity.getString(R.string.player_zoom, (zoomScale * 100).roundToInt()))
    }

    private fun applyPan(event: MotionEvent) {
        val content = zoomTarget() ?: return
        zoomTransX = panStartTransX + (event.x - panStartX)
        zoomTransY = panStartTransY + (event.y - panStartY)
        clampPan(content)
        applyZoomTransform()
    }

    /**
     * 平移夹紧：画面任何一边都不许离开 host（不然会出现"画面拖丢了"的黑边）。
     * host 比 content 大的部分（letterbox 黑边）按 (s·cw − hw)/2 换算进余量。
     */
    private fun clampPan(content: View) {
        val maxX = ((content.width * zoomScale - host.width) / 2f).coerceAtLeast(0f)
        val maxY = ((content.height * zoomScale - host.height) / 2f).coerceAtLeast(0f)
        zoomTransX = zoomTransX.coerceIn(-maxX, maxX)
        zoomTransY = zoomTransY.coerceIn(-maxY, maxY)
    }

    private fun applyZoomTransform() {
        val content = zoomTarget() ?: return
        content.scaleX = zoomScale
        content.scaleY = zoomScale
        content.translationX = zoomTransX
        content.translationY = zoomTransY
    }

    private fun spanOf(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    // ── 亮度 / 音量 / 跳帧 ──────────────────────────────────────────

    private fun applySeek(dx: Float, viewWidth: Int) {
        if (viewWidth <= 0) return
        // 整屏宽度对应 SEEK_RANGE_MS：M7.6 由 90s 加倍到 180s（产品要求加快）。
        seekDeltaMs = (dx / viewWidth * SEEK_RANGE_MS).toLong()
        val text = if (seekDeltaMs >= 0) {
            activity.getString(R.string.player_seek_forward, Formatters.duration(seekDeltaMs))
        } else {
            activity.getString(R.string.player_seek_backward, Formatters.duration(-seekDeltaMs))
        }
        val target = (downPositionMs + seekDeltaMs).coerceAtLeast(0L)
        showHud("$text  →  ${Formatters.duration(target)}")
    }

    private fun applyBrightness(dy: Float, viewHeight: Int) {
        if (viewHeight <= 0) return
        // 向上划 = 变亮：所以用 `-dy`。
        val value = (startBrightness - dy / viewHeight).coerceIn(MIN_BRIGHTNESS, 1f)
        val attrs = activity.window.attributes
        attrs.screenBrightness = value
        activity.window.attributes = attrs
        showHud(activity.getString(R.string.player_brightness, (value * 100).roundToInt()))
    }

    private fun applyVolume(dy: Float, viewHeight: Int) {
        if (viewHeight <= 0) return
        val delta = (-dy / viewHeight * maxVolume).roundToInt()
        val value = (startVolume + delta).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        val percent = (value * 100f / maxVolume).roundToInt()
        showHud(activity.getString(R.string.player_volume, percent))
    }

    /**
     * 当前亮度（0~1）。
     *
     * `screenBrightness` 为负数时表示"跟随系统"，此时去读系统设置里的值；
     * 读不到（部分 ROM 限制）就退回 0.5 —— 一个不会让屏幕全黑的中庸值。
     */
    private fun currentBrightness(): Float {
        val override = activity.window.attributes.screenBrightness
        if (override >= 0f) return override
        val system = runCatching {
            android.provider.Settings.System.getInt(
                activity.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault(-1)
        return if (system in 0..255) system / 255f else 0.5f
    }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
    }

    private companion object {

        const val TOUCH_SLOP = 24f

        /** 整屏横向滑动对应的最大快进/快退毫秒数（M7.6 起 180s）。 */
        const val SEEK_RANGE_MS = 180_000f

        /** 不要把屏幕调到全黑：用户会以为应用崩了。 */
        const val MIN_BRIGHTNESS = 0.05f

        /** 画面缩放上限：再大就没意义了（VLC 也差不多这个量级）。 */
        const val MAX_ZOOM = 5f

        /** 缩回这个差值以内视为"已归位"，吸附回 1.0 并清零平移。 */
        const val ZOOM_SNAP_EPSILON = 0.05f

        /** 指间距小于这个值不参与计算（两指并拢时的 span 比是无意义噪声）。 */
        const val MIN_SPAN = 40f
    }
}
