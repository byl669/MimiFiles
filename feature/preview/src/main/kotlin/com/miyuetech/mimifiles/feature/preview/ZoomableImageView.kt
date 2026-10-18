package com.miyuetech.mimifiles.feature.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.miyuetech.mimifiles.core.common.MimiLog
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 「要显示哪一块图」。
 *
 * [bitmap] 只覆盖 [srcRect] 这一块（原始图像坐标系），而 [contentWidth]/[contentHeight]
 * 是**整张图**的原始尺寸。视图的坐标系恒为"整张图"，于是同一套手势/变换逻辑
 * 既能显示整图（`srcRect` = 全图），也能显示某个高清局部（`srcRect` = 视口那块）。
 *
 * 这就是"分块大图"最省的落地方式：不需要瓦片调度器、不需要异步加载框架，
 * 只要把"当前手上这张位图代表原图的哪一块"这件事说清楚。
 */
data class ImageWindow(
    val bitmap: Bitmap,
    val srcRect: RectF,
    val contentWidth: Int,
    val contentHeight: Int,
    /** 与 [bitmap] 对应的采样倍数，用于判断"再放大是否需要补一块更清晰的"。 */
    val sampleSize: Int = 1,
)

/**
 * 可缩放平移的图片视图 —— 自研（不引 `subsampling-scale-image-view`）。
 *
 * ## 为什么自研而不引库
 * 需求只有三条：① 双指缩放 + 拖动 + 双击放大；② 能按视口回报"我现在在看原图的哪一块"；
 * ③ 支持"手上只有原图的一块"这种窗口态。第三条是自研的直接原因——
 * 通用库都把"一张完整的 Bitmap"当成输入契约，要塞局部窗口进去得绕过它的内部状态。
 * 而这里用 `Matrix + 两个 RectF` 就表达完了，还省掉一个第三方依赖（约 200 KB）。
 *
 * ## 坐标系的约定（读这段能省很多时间）
 * ```
 * 原始图像坐标（contentWidth × contentHeight，即解码后的像素尺寸）
 *        │  baseMatrix：适配屏幕 + EXIF 旋转/镜像
 *        ▼
 * 视图坐标（本 View 的 0..width, 0..height）
 *        │  suppMatrix：用户手势累积（缩放/平移）
 * ```
 * 真正的绘制变换是 `drawMatrix = suppMatrix × baseMatrix`。
 * [visibleSourceRect] 就是把视图矩形经 `drawMatrix` 的**逆**映射回原始坐标 ——
 * 上层拿它去请求"这一块的高清版本"。
 *
 * ## 为什么旋转交给自己而不是预先旋转位图
 * 预旋转意味着每次换窗口（补一块清晰的）都要重新旋转一遍，而窗口请求是高频的。
 * 把旋转放进 [baseMatrix] 是一次性的矩阵运算，代价可以忽略。
 * 另外它让 [visibleSourceRect] 恒为**原始坐标**，上层做分块请求时不必再去想方向。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private val baseMatrix = Matrix()

    private val suppMatrix = Matrix()

    private val drawMatrix = Matrix()

    private val inverseMatrix = Matrix()

    /** 复用的矩形对象：`onDraw` 里一次分配都不能有。 */
    private val sourceRect = RectF()

    /**
     * 给 `drawBitmap` 用的整数源矩形。
     *
     * `Canvas.drawBitmap` 没有 `(Bitmap, RectF, RectF, Paint)` 这个重载
     * （只有 `Rect→Rect` 与 `Rect→RectF`），所以源矩形必须转成整数 `Rect`。
     * 分块解码的区域本来就是整像素，取整不会引入误差。
     */
    private val sourceRectInt = Rect()

    private val destRect = RectF()

    private val viewRect = RectF()

    private val fullSourceRect = RectF()

    /** 平移钳制用的复用矩形（每次滚动都会跑，不能分配）。 */
    private val mappedFullRect = RectF()

    /** 缩略/视口反算用的复用矩形。 */
    private val mappedVisibleRect = RectF()

    private var window: ImageWindow? = null

    private var rotationDegrees = 0

    private var mirrored = false

    /** 缩放上限相对"适配屏幕"的倍数。 */
    var maxScaleFactor: Float = 6f

    /**
     * 双击放大到的倍数（相对"适配屏幕"）。
     *
     * ## 为什么是 4 而不是 2.5
     * 一开始写的 2.5，实测后改大：2.5× 在大图上**看不出细节改善**，双击就失去了意义
     * （用户双击的意图就是"我想看清这里"）。更具体地说，它决定了分块补清晰度
     * 这条链路会不会被真正触发 —— 基准位图的采样由
     * `baseDecodeTarget = min(2 × 屏幕长边, 2560)` 决定，8000 px 的图会降到 1/4
     * 采样；而 2.5× 时视口所需的采样恰好还是 1/4（2 的幂取整的结果），
     * 于是"不需要更清晰的那一块"—— 逻辑正确，但双击对大图毫无帮助。
     * 4× 之后视口所需采样降到 1/2，分块解码被真正触发，双击才有"看清"的效果。
     */
    var doubleTapScaleFactor: Float = 4f

    /** 单击回调（用于显隐顶栏/底栏）。 */
    var onTap: (() -> Unit)? = null

    /**
     * 手势结束时回调：参数是"当前可视区域对应的原图矩形"与"当前手上位图的采样倍数"。
     * 上层据此决定要不要补一块更清晰的进来。**不做任何防抖**——防抖是上层的责任
     * （它才知道自己能不能承受频繁请求）。
     */
    var onSettled: ((visibleSource: RectF, currentSample: Int) -> Unit)? = null

    private var minScale = 1f

    // ── 双击延迟提交（Fix C，与 ZoomableContainer 同款） ─────────

    private var pendingDoubleTap: Runnable? = null

    private var doubleTapX = 0f

    private var doubleTapY = 0f

    /** 双击延迟提交窗口：覆盖"轻点后立刻捏合"的第二指落下间隔。 */
    private val doubleTapCommitDelayMs = 150L

    /**
     * 缩放检测器。**必须关掉 quick scale**（API 19+ 按targetSdk 默认开）。
     *
     * quick scale 是"双击后不抬指继续拖动即缩放"的单指模式：开启时 SGD 会在
     * 判定出双击的那一下把缩放驱动源从指间距切换成纵向拖动距离
     * （锚定模式）。本视图的双击缩放由 [gestureDetector] 自己负责，
     * 两者的手势模型互相冲突 —— 双击后继续拖动会同时触发两套缩放。
     *
     * 本视图自身矩阵恒为单位阵（缩放画在 canvas 里，见 [onDraw]），
     * 所以触摸坐标稳定，SGD 的逐帧 factor 是可信的 —— 这与
     * `ZoomableContainer` 必须把变换挪到内层的原因正好互为印证。
     */
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isNaN() || factor <= 0f) return false
                val current = currentScaleFactor()
                val target = (current * factor).coerceIn(minScale, maxScaleFactor)
                val applied = target / current
                suppMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                clampTranslation()
                invalidate()
                return true
            }
        },
    ).apply { setQuickScaleEnabled(false) }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 只有"没被拖动/双击打断"的单击才会走到这里（GestureDetector 自己保证）。
                onTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 不立刻缩放：延迟提交（与 ZoomableContainer 的 Fix C 同类）。
                // "轻点定位 → 立刻捏合"里捏合第一指的 DOWN 会被判成双击的第二下
                // （双击判定不要求两次按点位置接近），立刻放大就是一次跳变；
                // 期间出现第二根手指或拖出 slop 都取消提交，真双击照常生效。
                doubleTapX = e.x
                doubleTapY = e.y
                pendingDoubleTap?.let { removeCallbacks(it) }
                val commit = Runnable {
                    pendingDoubleTap = null
                    commitDoubleTapZoom()
                }
                pendingDoubleTap = commit
                postDelayed(commit, doubleTapCommitDelayMs)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                suppMatrix.postTranslate(-distanceX, -distanceY)
                clampTranslation()
                invalidate()
                return true
            }
        },
    )

    /** 双击缩放的延迟提交：真双击在第二个 UP 后 ~150ms 生效。 */
    private fun commitDoubleTapZoom() {
        val current = currentScaleFactor()
        val target = if (current > minScale * 1.01f) minScale else doubleTapScaleFactor
        val factor = target / current
        suppMatrix.postScale(factor, factor, doubleTapX, doubleTapY)
        clampTranslation()
        invalidate()
        // 双击是"一次性把视口换了个地方"，必须通知上层补图。
        notifySettled()
    }

    private fun cancelPendingDoubleTap() {
        pendingDoubleTap?.let { removeCallbacks(it) }
        pendingDoubleTap = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (window == null) return false
        // 放大之后拖动不该翻页；适配状态下的水平拖动交给 ViewPager2 翻页。
        parent?.requestDisallowInterceptTouchEvent(
            event.pointerCount > 1 || currentScaleFactor() > minScale * 1.01f,
        )
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN ->
                // 捏合开始：等待中的双击提交必然是误判，取消。
                cancelPendingDoubleTap()

            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (pendingDoubleTap != null &&
                    hypot(event.x - doubleTapX, event.y - doubleTapY) > slop
                ) {
                    // 双击等待期内拖出了 slop：这是拖动，不是双击，取消提交。
                    cancelPendingDoubleTap()
                }
            }

            MotionEvent.ACTION_CANCEL -> cancelPendingDoubleTap()
            // 注意 UP **不**取消提交：真双击的提交发生在第二个 UP 之后。
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            notifySettled()
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变了（旋转屏幕/分屏）必须重算适配矩阵，否则图会偏在一角。
        rebuildBaseMatrix(resetUserTransform = true)
    }

    // ── 公开 API ────────────────────────────────────────────────

    /**
     * 设置内容。
     *
     * [resetUserTransform] 传 `false` 正是"补一块高清"该有的行为：用户放大到某处，
     * 画面变清晰但**不该跳回原位**。
     */
    fun setWindow(newWindow: ImageWindow, resetUserTransform: Boolean = true) {
        val previous = window
        val sizeChanged = previous == null ||
            previous.contentWidth != newWindow.contentWidth ||
            previous.contentHeight != newWindow.contentHeight
        window = newWindow
        rebuildBaseMatrix(resetUserTransform = resetUserTransform || sizeChanged)
        invalidate()
    }

    fun setOrientation(rotationDegrees: Int, mirrored: Boolean) {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (this.rotationDegrees == normalized && this.mirrored == mirrored) return
        this.rotationDegrees = normalized
        this.mirrored = mirrored
        rebuildBaseMatrix(resetUserTransform = true)
        invalidate()
    }

    /** 当前手势累积的缩放倍数（1.0 = 适配屏幕）。 */
    fun currentScaleFactor(): Float {
        val values = FloatArray(9)
        suppMatrix.getValues(values)
        return values[Matrix.MSCALE_X].takeIf { it > 0f } ?: 1f
    }

    /** 当前手上位图的采样倍数。 */
    val currentSample: Int get() = window?.sampleSize ?: 1

    /**
     * 当前可视区域对应的原图矩形（原始坐标系，已裁剪到内容范围内）。
     *
     * 返回矩形在缩得很小时可能**小于**内容，在"图比屏幕小"时可能被裁剪成整张图；
     * 两种情况调用方都只需按"这是要显示的原图区域"理解。
     */
    fun visibleSourceRect(): RectF? {
        val w = window ?: return null
        if (width == 0 || height == 0) return null
        updateDrawMatrix()
        if (!drawMatrix.invert(inverseMatrix)) return null
        viewRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val source = mappedVisibleRect
        inverseMatrix.mapRect(source, viewRect)
        val result = RectF(
            source.left.coerceIn(0f, w.contentWidth.toFloat()),
            source.top.coerceIn(0f, w.contentHeight.toFloat()),
            source.right.coerceIn(0f, w.contentWidth.toFloat()),
            source.bottom.coerceIn(0f, w.contentHeight.toFloat()),
        )
        if (result.width() <= 1f || result.height() <= 1f) return null
        return result
    }

    /** 复位到"适配屏幕"。 */
    fun resetZoom() {
        suppMatrix.reset()
        clampTranslation()
        invalidate()
        notifySettled()
    }

    // ── 内部 ────────────────────────────────────────────────────

    private fun updateDrawMatrix() {
        drawMatrix.set(suppMatrix)
        drawMatrix.postConcat(baseMatrix)
    }

    private fun rebuildBaseMatrix(resetUserTransform: Boolean) {
        val w = window ?: return
        if (width == 0 || height == 0) return
        if (resetUserTransform) suppMatrix.reset()

        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val boundsW = if (rotated) w.contentHeight.toFloat() else w.contentWidth.toFloat()
        val boundsH = if (rotated) w.contentWidth.toFloat() else w.contentHeight.toFloat()
        if (boundsW <= 0f || boundsH <= 0f) return
        val fit = min(width / boundsW, height / boundsH)

        // 组合顺序（post* = 后乘，最终作用于点的顺序与书写顺序一致）：
        //   ① 把内容中心移到原点 → ② 旋转/镜像（因此**天然绕内容中心**，与缩放无关）
        //   → ③ 缩放 → ④ 平移到视图中心
        //
        // ⚠️ 这里曾经写成"先缩放，再按 contentSize/2 平移回来再旋转"，那等于假设
        // `fit == 1`：旋转的支点用成了**未缩放的**中心 `c/2`，而缩放后的中心其实是
        // `fit·c/2`，两者相差 `(1-fit)·c/2` —— 对 4000 px 的照片就是上千像素的偏移。
        // 当时之所以没暴露，是因为 `clampTranslation()` 在"图片能塞进视图"时会强制居中，
        // 恰好把这个偏移盖住了；但只要用户缩放/平移过（`suppMatrix` 非单位阵、
        // 走"补一块高清"的 `resetUserTransform = false` 分支），盖不住就会露出偏移。
        // 正确的组合对任意 `fit` 都成立，不依赖 clamp 兜底。
        baseMatrix.reset()
        baseMatrix.postTranslate(-w.contentWidth / 2f, -w.contentHeight / 2f)
        baseMatrix.postRotate(rotationDegrees.toFloat())
        if (mirrored) baseMatrix.postScale(-1f, 1f)
        baseMatrix.postScale(fit, fit)
        baseMatrix.postTranslate(width / 2f, height / 2f)

        minScale = 1f
        clampTranslation()
        // 几何自检日志：图片"位置不对/比例不对"这类问题，靠截图很难定位到底是
        // 内容尺寸、旋转角、还是视图尺寸错了。这三个量一起打出来，一眼就能判定。
        // 只在窗口/尺寸变化时输出（不是 `onDraw`），代价可忽略。
        MimiLog.d(
            TAG,
            "base view=${width}x${height} content=${w.contentWidth}x${w.contentHeight} " +
                "rot=$rotationDegrees mirror=$mirrored fit=$fit " +
                "bounds=${boundsW}x${boundsH} sample=${w.sampleSize} " +
                "bitmap=${w.bitmap.width}x${w.bitmap.height} srcRect=$sourceRectDebug",
        )
    }

    /** 仅供日志：当前窗口代表的原图区域。 */
    private val sourceRectDebug: String
        get() = window?.srcRect?.let {
            "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}"
        } ?: "-"

    /**
     * 把平移限制在"图片不脱离视野"。
     *
     * 规则：某一轴上图片的显示尺寸大于视图时，禁止露出空白边；
     * 小于视图时，强制居中。少了这一步，用户可以把图片拖到屏幕外再也找不回来。
     */
    private fun clampTranslation() {
        val w = window ?: return
        if (width == 0 || height == 0) return
        updateDrawMatrix()
        fullSourceRect.set(0f, 0f, w.contentWidth.toFloat(), w.contentHeight.toFloat())
        val full = mappedFullRect
        drawMatrix.mapRect(full, fullSourceRect)

        var dx = 0f
        var dy = 0f
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        if (full.width() <= viewW) {
            dx = (viewW - full.width()) / 2f - full.left
        } else {
            if (full.left > 0f) dx = -full.left
            if (full.right < viewW) dx = viewW - full.right
        }
        if (full.height() <= viewH) {
            dy = (viewH - full.height()) / 2f - full.top
        } else {
            if (full.top > 0f) dy = -full.top
            if (full.bottom < viewH) dy = viewH - full.bottom
        }
        if (dx != 0f || dy != 0f) suppMatrix.postTranslate(dx, dy)
    }

    private fun notifySettled() {
        val rect = visibleSourceRect() ?: return
        onSettled?.invoke(rect, currentSample)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = window ?: return
        if (w.bitmap.isRecycled) return
        if (w.bitmap.width <= 0 || w.bitmap.height <= 0) return
        updateDrawMatrix()
        sourceRect.set(w.srcRect)
        // ⚠️ 源矩形必须是**整张位图**，不是 `srcRect`。
        //
        // `Canvas.drawBitmap(bitmap, src, dst, paint)` 里的 `src` 是**位图像素坐标**，
        // 而 `ImageWindow.srcRect` 是**原图坐标**（"这张位图代表原图的哪一块"）。
        // 两者只在**采样倍数为 1** 时数值相等 —— 一旦降采样（长边 > 基准上限 2560
        // 的照片，采样倍数就是 2），`srcRect` 会越出位图边界，Skia 只能按越界处理。
        //
        // M3 收口时的实测症状：**4000×3000 的照片被画成 539×403 并贴在屏幕左边缘**
        // （正是"被挤到屏幕左半部分且尺寸过小"）。原因是越界后 Skia 实际只画出了
        // 位图左上角那一小块，而它的位置仍按整张 `srcRect` 的变换来定。
        // 800×600 的图采样=1，所以看不出问题 —— 这就是"部分图片异常"的原因。
        //
        // 正确写法：把**整张位图**映射到 `srcRect` 变换后的矩形上。
        // 这样任何采样倍数、任何窗口都能正确绘制，语义也正好是 `srcRect` 的文档含义
        // （"这张位图覆盖原图的这一块"）。
        sourceRectInt.set(0, 0, w.bitmap.width, w.bitmap.height)
        destRect.set(w.srcRect)
        drawMatrix.mapRect(destRect, sourceRect)
        if (destRect.width() <= 0f || destRect.height() <= 0f) return
        canvas.drawBitmap(w.bitmap, sourceRectInt, destRect, paint)
    }

    /** 供冒烟脚本与排障读取的当前状态（不含位图内容）。 */
    internal fun debugState(): String = buildString {
        append("scale=").append(currentScaleFactor())
        append(" min=").append(minScale)
        append(" rot=").append(rotationDegrees)
        val w = window
        append(" content=").append(w?.contentWidth).append('x').append(w?.contentHeight)
        append(" sample=").append(w?.sampleSize)
        append(" src=").append(w?.srcRect?.let { "${it.width().toInt()}x${it.height().toInt()}" })
    }

    internal fun hasContent(): Boolean = window != null

    /** 让上层把"必须至少覆盖一屏像素"这类判断写得可读。 */
    fun viewPixelSize(): Pair<Int, Int> = max(width, 1) to max(height, 1)

    private companion object {
        const val TAG = "MimiZoomView"
    }
}
