package com.miyuetech.mimifiles.feature.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 竖向进度条（M7.19）：给单手握持平板的人用拇指在屏幕边缘调进度。
 *
 * ## 为什么自绘，而不是把 SeekBar 转 90°
 * 旋转视图只转了**绘制**，触摸事件的局部坐标系仍是未旋转的，得在 `onTouchEvent`
 * 里手工做逆变换；再加上 SeekBar 的 thumb 热区、padding 补偿都按横向算，
 * 于是出现"拖不动 / 拖反了 / 手指偏半个滑块"这类难查的问题。
 * 这里只有一个 `y → ratio` 的线性映射，行为完全可预测。
 *
 * ## 方向约定
 * **向上拖动 = 前进**，与音量条同向（"向上即增加"），不跟阅读方向。
 *
 * ## 松手才生效
 * 拖动过程只回调 [onScrubMove]（调用方显示时间预览），抬指才回调 [onScrubStop]
 * 真正 seek。理由与 `PlayerGestures` 的水平快进完全一致：每一次 move 都 seek
 * 会把解复用器打成幻灯片。**点按轨道任意位置也会在抬指时生效**——拇指够不到
 * 滑块时这是唯一顺手的操作。
 */
class VerticalSeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 按下（开始拖动）。调用方可借此停掉控制条自动隐藏。 */
    var onScrubStart: (() -> Unit)? = null

    /** 进度变化（0=片头，1=片尾）。拖动中连续回调。 */
    var onScrubMove: ((Float) -> Unit)? = null

    /** 抬指，给出最终比例。调用方在这里 seek。 */
    var onScrubStop: ((Float) -> Unit)? = null

    /**
     * 当前播放比例（0~1）。
     *
     * 拖动期间**忽略外部赋值**：否则播放时钟每 200ms 把滑块弹回真实位置，
     * 表现为"拖到一半被拽回去"。
     */
    var ratio: Float
        get() = currentRatio
        set(value) {
            if (scrubbing) return
            val next = value.coerceIn(0f, 1f)
            if (next == currentRatio) return
            currentRatio = next
            invalidate()
        }

    private var currentRatio = 0f

    private var scrubbing = false

    private val density = resources.displayMetrics.density

    private fun dp(value: Float) = value * density

    private val trackWidth = dp(4f)

    private val thumbRadius = dp(6f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    /** 轨道顶端：留出滑块半径，免得滑块在 100% 时被 View 边界裁掉半个圆。 */
    private fun trackTop(): Float = paddingTop + thumbRadius

    private fun trackBottom(): Float = height - paddingBottom - thumbRadius

    private fun usableHeight(): Float = trackBottom() - trackTop()

    override fun onDraw(canvas: Canvas) {
        val usable = usableHeight()
        if (usable <= 0f) return
        val centerX = width / 2f
        val top = trackTop()
        val bottom = trackBottom()
        val radius = trackWidth / 2f

        canvas.drawRoundRect(
            centerX - trackWidth / 2f,
            top,
            centerX + trackWidth / 2f,
            bottom,
            radius,
            radius,
            trackPaint,
        )

        val thumbY = bottom - usable * currentRatio
        canvas.drawRoundRect(
            centerX - trackWidth / 2f,
            thumbY,
            centerX + trackWidth / 2f,
            bottom,
            radius,
            radius,
            progressPaint,
        )

        canvas.drawCircle(centerX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val usable = usableHeight()
        if (usable <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 竖条贴在屏幕边缘，父容器（PlayerView 的手势层）也在抢同一次
                // 竖直滑动；不明确请求独占的话，向下拖会变成调亮度。
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                onScrubStart?.invoke()
                updateFromY(event.y, usable)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                updateFromY(event.y, usable)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    updateFromY(event.y, usable)
                }
                scrubbing = false
                onScrubStop?.invoke(currentRatio)
                return true
            }
        }
        return false
    }

    /** 向上拖动 = 前进，所以 y 越小比例越大。 */
    private fun updateFromY(y: Float, usable: Float) {
        val bottom = trackBottom()
        val next = ((bottom - y) / usable).coerceIn(0f, 1f)
        if (next == currentRatio) return
        currentRatio = next
        invalidate()
        onScrubMove?.invoke(next)
    }
}
