package com.miyuetech.mimifiles.feature.preview

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.miyuetech.mimifiles.core.common.MimiLog
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 可缩放的容器 —— 给"内部可滚动的列表"（PDF 分页、长文）加双指缩放与双击缩放。
 *
 * ## 架构：变换挂在内层 [content]，容器自身恒为单位阵（v0.7.1 的关键修正）
 * 老版本把 `scaleX/translation` 设在**容器自己**身上，真机上"缩放不跟手、
 * 画面一瞬间放大一瞬间又还原"。根因是 Android 的触摸分发规则：
 * **ViewGroup 向子 View 分发事件时，会用子 View 自身变换的逆矩阵变换坐标**
 * （`ViewGroup.dispatchTouchEvent` → `transform(child.getInverseMatrix())`）。
 * 一旦容器缩放了自身，它收到的事件坐标就被除以了当前倍数，于是形成反馈循环：
 *
 * - 第 k 帧检测到的指间距 = 真实间距 ÷ 当前倍数；
 * - 推导可得相邻两帧的 factor 交替为 `r_k` 与 `r_k/r_{k-1}`（r 为真实间距比），
 *   scale 每帧上下振荡 —— 正是"一瞬间放大一瞬间又还原"的闪烁；
 * - 终值收敛到 **√(真实比例)**：指间距拉开 2.24 倍，画面只放大 1.5 倍，
 *   再叠加重采样噪声的对消，实测只剩 1.1 —— 正是"不跟手"。
 *
 * 把变换移到内层后，容器自身矩阵恒为单位阵，**事件坐标从头到尾稳定**，
 * 捏合引擎看到的间距就是真实间距，以上两个症状一起消失。
 * （对照：`ZoomableImageView` 用 canvas 自绘矩阵、自身矩阵恒为单位阵，
 * 所以同样的手势代码在图片预览上一直是正常的 —— 这就是旁证。）
 *
 * ## 为什么不能直接把 `ZoomableImageView` 复用到 PDF 上
 * `ZoomableImageView` 的输入是"一张位图 + 它代表原图的哪一块"，靠自绘实现，
 * 因为它要支持"手上只有原图一块"的窗口态。而 PDF 的输入是**一个会滚动的列表**：
 * 它自己管着页与页的切换、页位图的回收。把列表也塞进自绘模型里等于重写一个
 * 虚拟化滚动容器，得不偿失。
 *
 * 所以这里用另一种成熟做法：**变换整棵子树**（内层 [content] 的
 * `scaleX/scaleY/translationX/translationY`），把缩放/平移交给渲染层，
 * 把滚动/回收留给 RecyclerView 自己。
 *
 * ## 手势事件为什么在 `dispatchTouchEvent` 里分发，而不是 `onTouchEvent`
 * 有三个要求同时成立，缺一个就会破：
 *   ① 未缩放时单指拖动要能**滚动列表**（事件必须给到子 View）；
 *   ② 未缩放时双击要能**放大**（`GestureDetector` 必须看到完整的 DOWN→UP→DOWN→UP）；
 *   ③ 已缩放时单指拖动要能**平移**（事件必须由容器自己消费）。
 *
 * 只重写 `onTouchEvent`/`onInterceptTouchEvent` 满足不了②：一旦我们在某个中途事件上
 * 开始拦截，`GestureDetector` 就收不到**开头那个 DOWN**，双击永远不会被识别。
 * 因此这里在 `dispatchTouchEvent` **无条件喂给 `GestureDetector`**，再由"是否消费"
 * 决定要不要把事件交给子树 —— 检测器看到的始终是完整的事件流。
 *
 * ## 捏合引擎：为什么不用 `ScaleGestureDetector`
 * SGD 是"逐帧相对量"引擎：`factor = 本帧间距 / 上帧间距`，且手势在
 * `|span − 初始 span| > 2·touchSlop` 之前不启动 —— 起步的那一小段间距变化
 * 被直接吃掉，终值永远比真实比例小一截。这里改成**绝对基线**引擎：
 *
 * - 第二指落下的瞬间记录基准间距 [pinchBaseSpan] 与当时的倍数 [pinchBaseScale]；
 * - 激活阈值（`2·touchSlop`）只用来判定"用户确实在捏合"，**不吃掉间距**：
 *   激活后的目标倍数 = `pinchBaseScale × 本帧间距 / 基准间距`，
 *   起步段的变化照样计入 —— 终值精确等于指间距比例，逐帧严格跟手。
 *
 * 平移与缩放的复合（Fix A）：每帧先以**上一帧焦点**为锚缩放，再按焦点位移平移，
 * 两个量正好复合出恒等式 `t₁ = f₁ − f₀·s₁`（"初始中点下的内容点"全程钉在
 * 移动中的中点下），这是 P2 单测的精确断言依据。
 *
 * ## 双击提交延迟 150ms（Fix C）
 * 真机高频序列"轻点定位 → 立刻捏合"里，捏合第一指的 DOWN 会被 `GestureDetector`
 * 判成双击的第二下（双击判定**不要求**两次按点位置接近），立刻放大就是一次跳变。
 * 现在双击缩放**延迟提交**：期间出现第二根手指、手指拖出 slop、或捏合激活，
 * 都取消提交 —— 真双击（手指抬走后无人打扰）照常生效。
 *
 * ## 平移只能有**一个主人**（M3.1 真机反馈第 3 条的根因之一）
 * 老版本里 `GestureDetector.onScroll` 会调一次 `panBy`，`dispatchTouchEvent` 的
 * `ACTION_MOVE` 分支**又**调一次 —— 同一个事件把内容按两份位移量平移。
 * 现在 `GestureDetector` 只负责**双击**（不实现 `onScroll`），
 * 平移全部由 `dispatchTouchEvent` 按"多指平均焦点"统一驱动。
 *
 * ## 缩放与滚动的交棒
 * 放大后视口只能看到内容的一部分，而"再往上就是上一页"只有 RecyclerView 知道。
 * 所以平移先在本容器内消化，**溢出视口的部分换算成子视图的滚动量**（见
 * [ZoomTransform.handoff]）。少了这一步，放大 2 倍后就只能看到当前页的左上角，
 * 翻不到下一页 —— 那是个很常见的半成品状态。
 *
 * ⚠️ 但交棒**只在单指平移时**发生。多指（捏合）时交给列表滚动是灾难：
 * 列表会在缩放过程中不断滚进滚出、每翻出一页就重绑一次页位图（异步解码），
 * 屏幕上表现为画面一边缩放一边闪白 —— 那正是真机反馈里的"闪频"。
 * 捏合时视口平移仍被钳制在本容器内，松手后再单指拖即可继续翻页。
 */
class ZoomableContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 激活捏合的间距阈值：与 SGD 同口径（`2 × touchSlop`），只判定、不参与缩放。 */
    private val pinchStartSlop: Int =
        ViewConfiguration.get(context).scaledTouchSlop * 2

    /**
     * 变换的宿主：所有子 View 实际加在这里，缩放/平移也只作用在它身上。
     * 容器自身的变换矩阵必须恒为单位阵（类注释里的"触摸坐标反馈循环"）。
     */
    private val content = FrameLayout(context)

    /** 倍数变化回调（底栏"复位"按钮的可用状态、日志都用它）。 */
    var onScaleChanged: ((Float) -> Unit)? = null

    private var scale = ZoomTransform.MIN_SCALE

    private var tx = 0f

    private var ty = 0f

    /** 本次手势是否已被本容器接管（接管后不再把事件交给子树）。 */
    private var owning = false

    /** 上一次事件的"焦点"（多指取平均），既是平移的起算点，也是缩放的锚点。 */
    private var lastX = 0f

    private var lastY = 0f

    // ── 捏合引擎状态 ────────────────────────────────────────────

    /** 第二指落下时的指间距基准（绝对基线，激活后的缩放比例全部相对它计算）。 */
    private var pinchBaseSpan = 0f

    /** 第二指落下时的倍数（与 [pinchBaseSpan] 配对构成"基准变换"）。 */
    private var pinchBaseScale = ZoomTransform.MIN_SCALE

    /** 捏合已激活（间距变化超出 [pinchStartSlop]），此后的 MOVE 驱动缩放。 */
    private var pinchActive = false

    // ── 双击延迟提交（Fix C） ───────────────────────────────────

    private var pendingDoubleTap: Runnable? = null

    /** 双击判定点：提交前手指若移出 `touchSlop`，视为拖动，取消提交。 */
    private var doubleTapX = 0f

    private var doubleTapY = 0f

    /**
     * 本次手势的累计量，**只用于事后取证**（见 [finishGestureDiagnostics]）。
     *
     * 为什么值得专门记两个数：`adb input` 模拟不了双指捏合，而"平移有没有被算两遍"
     * 恰恰是上一版修掉的核心问题。把"手指走了多少"与"内容实际移了多少"一起打出来，
     * 一次单指拖动就能判定它们是否 1:1（不是 1:1 就说明还有第二个主人在改平移）。
     */
    private var fingerTravelX = 0f

    private var fingerTravelY = 0f

    private var panTravelX = 0f

    private var panTravelY = 0f

    val currentScale: Float get() = scale

    init {
        // 直接挂到 super 上：若走本类的 addView 会递归进 [content]。
        super.addView(
            content,
            -1,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** XML inflate 与代码 `addView` 都经由这里路由进 [content]。 */
    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        content.addView(child, index, params)
    }

    /**
     * **只**用来识别双击。
     *
     * 刻意**不**实现 `onScroll`：那是老版本"平移有两个主人"的来源。
     * `SimpleOnGestureListener.onScroll` 默认返回 `false`，`GestureDetector`
     * 于是永远不会进入"滚动中"状态，也就不会再回调它。
     */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 不立刻缩放：延迟 [DOUBLE_TAP_COMMIT_DELAY_MS] 再提交，
                // 期间来的 POINTER_DOWN / 拖动 / 捏合激活都会取消它（Fix C）。
                doubleTapX = e.x
                doubleTapY = e.y
                pendingDoubleTap?.let { removeCallbacks(it) }
                val commit = Runnable {
                    pendingDoubleTap = null
                    commitDoubleTapZoom()
                }
                pendingDoubleTap = commit
                postDelayed(commit, DOUBLE_TAP_COMMIT_DELAY_MS)
                return true
            }
        },
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (content.childCount == 0) return super.dispatchTouchEvent(ev)
        // ⚠️ 顺序不能反：先喂检测器（双击判定发生在 DOWN 上，必须先于本类的
        // DOWN 分支，否则延迟提交刚排上就被自己的取消逻辑杀掉），再决定要不要消费。
        gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rememberFocus(ev)
                fingerTravelX = 0f
                fingerTravelY = 0f
                panTravelX = 0f
                panTravelY = 0f
                pinchActive = false
                pinchBaseSpan = 0f
                // 已放大时立即接管（拖动 = 平移）；否则先让子 View 拿到 DOWN，
                // 这样单击/双击/滚动都还能正常工作。
                owning = isZoomed()
                if (!owning) return super.dispatchTouchEvent(ev)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                takeOver()
                // 手指根数变了 → 焦点会跳一次。这一跳**不是**用户拖出来的位移，
                // 所以只更新起算点，不产生平移（否则捏合的瞬间画面会弹一下）。
                rememberFocus(ev)
                // 捏合基线：第二指落下的瞬间定格。绝对基线让终值精确等于
                // 指间距比例（SGD 的相对量引擎做不到，见类注释）。
                pinchBaseSpan = spanOf(ev)
                pinchBaseScale = scale
                cancelPendingDoubleTap()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Fix D：基线必须排除**正在抬起**的那根手指，否则剩余手指的
                // 平移基线带着"半指距"，抬指瞬间画面横跳。
                // 快照在处理前定格：instrumentation 用"终态 − 此快照"断言
                // 抬指后的平移 1:1（事件锚定，对突发交付免疫）。
                debugTransformAtPointerUp = floatArrayOf(scale, tx, ty)
                rememberFocusExcluding(ev, ev.actionIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!owning) {
                    // 未接管时的单指移动交给子树；出现第二根手指就接管（兜底，
                    // 正常路径 POINTER_DOWN 已接管）。
                    if (ev.pointerCount > 1) {
                        takeOver()
                        rememberFocus(ev)
                        pinchBaseSpan = spanOf(ev)
                        pinchBaseScale = scale
                    } else {
                        return super.dispatchTouchEvent(ev)
                    }
                }
                if (pendingDoubleTap != null &&
                    hypot(ev.x - doubleTapX, ev.y - doubleTapY) > pinchStartSlop
                ) {
                    // 双击等待期内拖出了 slop：这是拖动，不是双击，取消提交。
                    cancelPendingDoubleTap()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) cancelPendingDoubleTap()
                // 注意 UP **不**取消延迟提交：真双击的提交发生在第二个 UP 之后。
                val wasOwning = owning
                owning = false
                pinchActive = false
                pinchBaseSpan = 0f
                if (wasOwning) logGestureDiagnostics()
                if (!wasOwning) return super.dispatchTouchEvent(ev)
            }
        }

        if (!owning) return super.dispatchTouchEvent(ev)

        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            // ① 缩放：双指以上且已激活。锚点用**上一帧焦点**（此刻 lastX/lastY
            // 还没被本帧更新），与 ② 的焦点位移正好复合出 t₁ = f₁ − f₀·s₁。
            if (ev.pointerCount > 1 && pinchBaseSpan >= MIN_USABLE_SPAN) {
                val spanNow = spanOf(ev)
                if (!pinchActive && abs(spanNow - pinchBaseSpan) > pinchStartSlop) {
                    pinchActive = true
                    cancelPendingDoubleTap()
                }
                if (pinchActive) {
                    val target = (pinchBaseScale * spanNow / pinchBaseSpan)
                        .coerceIn(ZoomTransform.MIN_SCALE, ZoomTransform.MAX_SCALE)
                    apply(
                        ZoomTransform.zoomAbout(
                            currentScale = scale,
                            currentTx = tx,
                            currentTy = ty,
                            target = target,
                            focusX = lastX,
                            focusY = lastY,
                            viewWidth = width,
                            viewHeight = height,
                        ),
                    )
                }
            }
            // ② 平移：**唯一**的平移入口。多指时按"多指平均位置"的位移平移，
            // 于是捏合过程中手指整体移动也能带动画面。
            val focusX = averagePointerX(ev)
            val focusY = averagePointerY(ev)
            val dx = focusX - lastX
            val dy = focusY - lastY
            lastX = focusX
            lastY = focusY
            fingerTravelX += dx
            fingerTravelY += dy
            panBy(dx, dy, allowHandoff = ev.pointerCount == 1)
        }
        return true
    }

    /** 双击缩放的延迟提交：真双击在第二个 UP 后 ~[DOUBLE_TAP_COMMIT_DELAY_MS] 生效。 */
    private fun commitDoubleTapZoom() {
        if (pinchActive || pinchBaseSpan != 0f) return
        takeOver()
        val target = if (scale > ZoomTransform.MIN_SCALE * 1.01f) {
            ZoomTransform.MIN_SCALE
        } else {
            ZoomTransform.DOUBLE_TAP_SCALE
        }
        apply(
            ZoomTransform.zoomAbout(
                currentScale = scale,
                currentTx = tx,
                currentTy = ty,
                target = target,
                focusX = doubleTapX,
                focusY = doubleTapY,
                viewWidth = width,
                viewHeight = height,
            ),
        )
        MimiLog.i(TAG, "double-tap commit at $doubleTapX,$doubleTapY -> scale=$scale")
    }

    private fun cancelPendingDoubleTap() {
        pendingDoubleTap?.let { removeCallbacks(it) }
        pendingDoubleTap = null
    }

    /**
     * 从这一刻起接管本次手势。
     *
     * 给子树补一个 `ACTION_CANCEL` 是必须的：它可能已经收到了 DOWN 并进入"滚动中"状态，
     * 突然收不到后续事件会让它停在滚动/按压状态（表现是列表之后会自己"滑一段"）。
     */
    private fun takeOver() {
        if (owning) return
        owning = true
        val cancel = MotionEvent.obtain(
            android.os.SystemClock.uptimeMillis(),
            android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            0f,
            0f,
            0,
        )
        runCatching { super.dispatchTouchEvent(cancel) }
        cancel.recycle()
    }

    /** 记下本次平移的起算点（多指取平均，与 [averagePointerX] 同一口径）。 */
    private fun rememberFocus(ev: MotionEvent) {
        lastX = averagePointerX(ev)
        lastY = averagePointerY(ev)
    }

    /** 同 [rememberFocus]，但排除第 [skipIndex] 根手指（POINTER_UP 的抬起指）。 */
    private fun rememberFocusExcluding(ev: MotionEvent, skipIndex: Int) {
        var sumX = 0f
        var sumY = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skipIndex) continue
            sumX += ev.getX(i)
            sumY += ev.getY(i)
            n++
        }
        if (n > 0) {
            lastX = sumX / n
            lastY = sumY / n
        }
    }

    private fun averagePointerX(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getX(i)
        return if (ev.pointerCount == 0) ev.x else sum / ev.pointerCount
    }

    private fun averagePointerY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return if (ev.pointerCount == 0) ev.y else sum / ev.pointerCount
    }

    /** 当前事件的多指间距（[ZoomTransform.spanOf] 的 MotionEvent 包装）。 */
    private fun spanOf(ev: MotionEvent): Float {
        val xs = FloatArray(ev.pointerCount)
        val ys = FloatArray(ev.pointerCount)
        for (i in 0 until ev.pointerCount) {
            xs[i] = ev.getX(i)
            ys[i] = ev.getY(i)
        }
        return ZoomTransform.spanOf(xs, ys)
    }

    /**
     * 平移。**这是本类里唯一会改动 `tx/ty` 的地方**（[resetZoom] 与缩放除外）。
     *
     * @param allowHandoff 是否允许把溢出的纵向平移交给列表滚动。单指时为 `true`
     *        （放大后要继续翻页只能靠它）；多指捏合时为 `false`（见类注释）。
     */
    private fun panBy(dx: Float, dy: Float, allowHandoff: Boolean) {
        if (width == 0 || height == 0) return
        var newTx = tx + dx
        var newTy = ty + dy
        // 纵向平移若溢出视口，溢出的部分交给列表滚动（否则放大后翻不到下一页）。
        if (allowHandoff) {
            val scrollLogical = ZoomTransform.handoff(scale, newTy, height)
            if (scrollLogical != 0f) {
                val list = content.getChildAt(0) as? RecyclerView
                if (list != null) {
                    val before = list.scrollY
                    list.scrollBy(0, scrollLogical.toInt())
                    val actual = (list.scrollY - before).toFloat()
                    // 只把**真的滚动掉**的那部分从溢出量里扣掉。列表已经滚到头时
                    // 实际滚动量为 0，剩下的溢出量就交给 clamp 钳住 ——
                    // 不扣的话，内容会在列表到底后继续跟手移动，松手瞬间弹回。
                    newTy += actual * scale
                }
            }
        }
        val clamped = ZoomTransform.clampTo(scale, newTx, newTy, width, height)
        panTravelX += clamped[1] - tx
        panTravelY += clamped[2] - ty
        apply(clamped)
    }

    /**
     * 手势结束时打一条"平移对账"日志。
     *
     * 判据：**内容位移 ≈ 手指位移**。差得远（尤其接近 2 倍）就说明平移被算了两次，
     * 或者有别的代码路径在改 `translationX/Y`。冒烟脚本 grep 这行来钉死这件事 ——
     * 双指捏合没法用 `adb input` 模拟，但"平移只有一个主人"这条性质
     * 用单指拖动就能完整验证。
     */
    private fun logGestureDiagnostics() {
        if (fingerTravelX == 0f && fingerTravelY == 0f) return
        MimiLog.i(
            TAG,
            "gesture end finger=(${fingerTravelX.toInt()},${fingerTravelY.toInt()}) " +
                "pan=(${panTravelX.toInt()},${panTravelY.toInt()}) scale=$scale",
        )
    }

    /**
     * 应用一组变换（[values] 为 `[scale, tx, ty]`）到**内层 [content]**。
     *
     * ⚠️ 不要改成把变换设到容器自身：那会重新引入"触摸坐标反馈循环"
     * （类注释开篇的根因），症状是缩放不跟手 + 画面逐帧放大/还原振荡。
     */
    private fun apply(values: FloatArray) {
        val newScale = values[0]
        // ⚠️ 支点必须显式设为 (0,0)：View 默认以**自身中心**为支点，
        // 那样 `translation` 的语义就变成"绕中心缩放后的偏移"，
        // [ZoomTransform] 里 `t = f - (f - t₀)·s₁/s₀` 这套推导立刻不再成立。
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = newScale
        content.scaleY = newScale
        content.translationX = values[1]
        content.translationY = values[2]
        val changed = newScale != scale
        scale = newScale
        tx = values[1]
        ty = values[2]
        if (changed) {
            MimiLog.i(TAG, "zoom scale=$newScale tx=${values[1].toInt()} ty=${values[2].toInt()}")
            onScaleChanged?.invoke(newScale)
        }
    }

    /** 复位到 1 倍（底栏"复位缩放"按钮）。 */
    fun resetZoom() {
        cancelPendingDoubleTap()
        if (scale == ZoomTransform.MIN_SCALE && tx == 0f && ty == 0f) return
        apply(floatArrayOf(ZoomTransform.MIN_SCALE, 0f, 0f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变了（旋转/分屏）就把之前的平移丢掉：它按旧尺寸算的，留着一定偏。
        resetZoom()
    }

    /** 让上层的"是否可以复位"判断可读。 */
    fun isZoomed(): Boolean = scale > ZoomTransform.MIN_SCALE * 1.001f

    /** 仅供 instrumentation 测试读取的当前变换 `[scale, tx, ty]`。不进任何 UI 路径。 */
    internal fun debugTransform(): FloatArray = floatArrayOf(scale, tx, ty)

    /** 仅供 instrumentation：最近一次 POINTER_UP 时刻的变换快照（事件锚定采样）。 */
    internal var debugTransformAtPointerUp: FloatArray = FloatArray(3)
        private set

    private companion object {
        const val TAG = "MimiZoomContainer"

        /** 双击缩放的延迟提交窗口。取 150ms：覆盖"轻点后立刻捏合"的第二指落下间隔。 */
        const val DOUBLE_TAP_COMMIT_DELAY_MS = 150L

        /** 低于此间距不驱动缩放（两指几乎重叠时 span 趋零，比例会爆炸）。 */
        const val MIN_USABLE_SPAN = 24f
    }
}
