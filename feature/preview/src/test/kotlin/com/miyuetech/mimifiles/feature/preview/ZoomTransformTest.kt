package com.miyuetech.mimifiles.feature.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ZoomTransform` 的性质测试。
 *
 * ## 为什么这几条断言值得写
 * 双指捏合**没法用 `adb` 测**（`input` 只支持单指），而"捏合时手指下的内容点
 * 不能跑"这条性质又恰恰是最容易被写错、写错了也不报错的：
 * 老版本 `t' = f·(1-s)` 在"从未平移过"时是对的，一拖过就整屏跳一下 ——
 * 真机上的表现是"缩放时画面抖动闪频"。把性质写成断言，这类退化就再也不能悄悄回来。
 */
class ZoomTransformTest {

    private val viewW = 1000
    private val viewH = 2000

    /** 内容点 `p` 在屏幕上的位置：`scale · p + t`。 */
    private fun screenX(p: Float, transform: FloatArray): Float = transform[0] * p + transform[1]

    private fun screenY(p: Float, transform: FloatArray): Float = transform[0] * p + transform[2]

    @Test
    fun `捏合焦点下的内容点保持不动（已有平移时也成立）`() {
        // 这一条是老版本失败的地方：先平移过（tx/ty ≠ 0）再缩放。
        val currentScale = 2f
        val currentTx = -300f
        val currentTy = -500f
        val focusX = 400f
        val focusY = 700f

        // 焦点下当前是哪个内容点？s·p + t = f → p = (f - t) / s
        val contentX = (focusX - currentTx) / currentScale
        val contentY = (focusY - currentTy) / currentScale

        val zoomedIn = ZoomTransform.zoomAbout(
            currentScale, currentTx, currentTy, target = 3f,
            focusX = focusX, focusY = focusY, viewWidth = viewW, viewHeight = viewH,
        )
        assertEquals(focusX, screenX(contentX, zoomedIn), 0.01f)
        assertEquals(focusY, screenY(contentY, zoomedIn), 0.01f)
    }

    @Test
    fun `倍数不变时平移量也不变`() {
        val result = ZoomTransform.zoomAbout(
            currentScale = 2f, currentTx = -300f, currentTy = -500f, target = 2f,
            focusX = 400f, focusY = 700f, viewWidth = viewW, viewHeight = viewH,
        )
        assertEquals(2f, result[0], 0f)
        assertEquals(-300f, result[1], 0.01f)
        assertEquals(-500f, result[2], 0.01f)
    }

    @Test
    fun `从适配状态放大时以焦点为中心`() {
        // t0 = 0 时新老公式等价：这条保证"双击/第一次捏合"的手感没被改坏。
        val result = ZoomTransform.zoomAbout(
            currentScale = 1f, currentTx = 0f, currentTy = 0f, target = 2f,
            focusX = 400f, focusY = 700f, viewWidth = viewW, viewHeight = viewH,
        )
        assertEquals(400f, screenX(400f, result), 0.01f)
        assertEquals(700f, screenY(700f, result), 0.01f)
    }

    @Test
    fun `倍数被钳制在上下限之间`() {
        val tooBig = ZoomTransform.zoomAbout(
            1f, 0f, 0f, target = 99f, focusX = 1f, focusY = 1f,
            viewWidth = viewW, viewHeight = viewH,
        )
        assertEquals(ZoomTransform.MAX_SCALE, tooBig[0], 0f)

        val tooSmall = ZoomTransform.zoomAbout(
            2f, -100f, -100f, target = 0.1f, focusX = 1f, focusY = 1f,
            viewWidth = viewW, viewHeight = viewH,
        )
        assertEquals(ZoomTransform.MIN_SCALE, tooSmall[0], 0f)
    }

    @Test
    fun `任何倍数下都不会露出空白边`() {
        // 合法区间：[尺寸 × (1 - s), 0]。超出即"内容脱离容器"= 露出底色。
        val cases = listOf(1f, 1.5f, 2f, 3f)
        for (s in cases) {
            val minTx = viewW * (1f - s)
            val minTy = viewH * (1f - s)
            val wild = ZoomTransform.clampTo(s, 99999f, -99999f, viewW, viewH)
            assertTrue("s=$s tx 越界：${wild[1]}", wild[1] in minTx..0f)
            assertTrue("s=$s ty 越界：${wild[2]}", wild[2] in minTy..0f)
        }
    }

    @Test
    fun `交棒量只在平移溢出视口时才非零`() {
        val s = 2f
        val minTy = viewH * (1f - s)  // = -2000
        assertEquals(0f, ZoomTransform.handoff(s, 0f, viewH), 0.001f)
        assertEquals(0f, ZoomTransform.handoff(s, -1000f, viewH), 0.001f)
        assertEquals(0f, ZoomTransform.handoff(s, minTy, viewH), 0.001f)
        // 往下溢出：内容要向上翻（scrollBy 正数），量 = 溢出量 / scale。
        assertEquals(-50f, ZoomTransform.handoff(s, 100f, viewH), 0.001f)
        // 往上溢出：内容要向下翻（scrollBy 负数）。
        assertEquals(50f, ZoomTransform.handoff(s, minTy - 100f, viewH), 0.001f)
    }

    @Test
    fun `平移量按倍数换算而不是按屏幕像素`() {
        // 这是"放大后翻页速度与手指一致"的依据：同样溢出 100 px，
        // 倍数越大，列表只需要滚越少（因为列表还要再被放大 s 倍）。
        val slow = ZoomTransform.handoff(1f, 100f, viewH)
        val fast = ZoomTransform.handoff(3f, 100f, viewH)
        assertTrue("scale=3 时交棒量应当更小：$fast vs $slow", kotlin.math.abs(fast) < kotlin.math.abs(slow))
    }

    // ── spanOf：捏合引擎的指间距口径 ─────────────────────────────

    @Test
    fun `spanOf 两指等高时等于水平距离`() {
        // 与 ScaleGestureDetector 同口径：两指等高时 span 就是 |x1 - x0|。
        val span = ZoomTransform.spanOf(floatArrayOf(390f, 690f), floatArrayOf(700f, 700f))
        assertEquals(300f, span, 0.001f)
    }

    @Test
    fun `spanOf 斜向两指等于欧氏距离`() {
        val span = ZoomTransform.spanOf(floatArrayOf(0f, 300f), floatArrayOf(0f, 400f))
        assertEquals(500f, span, 0.001f)
    }

    @Test
    fun `spanOf 空指针为零`() {
        assertEquals(0f, ZoomTransform.spanOf(FloatArray(0), FloatArray(0)), 0f)
    }

    // ── 组合不变量：容器逐帧复合逻辑的纯数学复现 ─────────────────
    //
    // 容器每个 MOVE 帧做两件事：① 以**上一帧焦点**为锚缩放到绝对目标
    // （本帧间距 / 基准间距）；② 按焦点位移平移。下面的模拟器逐帧复现
    // 这个复合，断言两个性质：
    //   A. 终值倍数 == 终态间距 / 基准间距（起步段 slop 不吃掉间距 —— 这是
    //      "不跟手"被修掉的数学表述；SGD 的相对量引擎做不到）。
    //   B. 恒等式 t₁ = f₁ − f₀·s₁：初始中点下的内容点全程钉在移动中的中点下
    //      （这是 instrumentation P2 的精确断言依据）。

    /** 视口取得足够大，让钳制分支全程不触发（模拟器断言的是无约束下的恒等式）。 */
    private val bigView = 100_000

    /**
     * 逐帧复现容器的捏合复合。
     * @param activationSlop 激活阈值（第 0 帧之后间距变化超出才开缩放）。
     */
    private fun simulatePinch(
        spans: List<Float>,
        fxs: List<Float>,
        fys: List<Float>,
        activationSlop: Float,
    ): FloatArray {
        require(spans.size == fxs.size && spans.size == fys.size)
        var s = ZoomTransform.MIN_SCALE
        var tx = 0f
        var ty = 0f
        val baseSpan = spans.first()
        var active = false
        for (k in spans.indices) {
            if (k == 0) continue // 基准帧：只记录焦点，不产生缩放/平移
            if (!active && kotlin.math.abs(spans[k] - baseSpan) > activationSlop) active = true
            if (active) {
                // ① 锚定**上一帧**焦点缩放（容器的 Fix A 复合）
                val r = ZoomTransform.zoomAbout(
                    s, tx, ty, target = baseSpan.let { spans[k] / it },
                    focusX = fxs[k - 1], focusY = fys[k - 1],
                    viewWidth = bigView, viewHeight = bigView,
                )
                s = r[0]; tx = r[1]; ty = r[2]
            }
            // ② 焦点位移平移（无钳制路径：轨迹远离边界）
            tx += fxs[k] - fxs[k - 1]
            ty += fys[k] - fys[k - 1]
        }
        return floatArrayOf(s, tx, ty)
    }

    @Test
    fun `组合不变量——终值等于指间距比例且内容跟住中点`() {
        // 间距 300 → 510（8 帧），同时中点向左上漂移。
        // 大激活阈值（100）模拟"起步段被吃掉"的最坏情形：即便如此，
        // 终值也必须精确等于 510/300 = 1.7（SGD 会给出 510/400 = 1.27）。
        val frames = 8
        val spans = (0 until frames).map { 300f + it * 30f }
        val fxs = (0 until frames).map { 500f - it * 5f }
        val fys = (0 until frames).map { 1000f - it * 8f }
        val end = simulatePinch(spans, fxs, fys, activationSlop = 100f)

        val sFinal = spans.last() / spans.first()
        assertEquals("终值倍数（性质 A）", sFinal, end[0], 0.001f)
        assertEquals("tx 恒等式（性质 B）", fxs.last() - fxs.first() * sFinal, end[1], 0.1f)
        assertEquals("ty 恒等式（性质 B）", fys.last() - fys.first() * sFinal, end[2], 0.1f)

        // 初始中点下的内容点（p = (f0 - t0)/s0 = f0）终态必须钉在终态中点下。
        assertEquals(fxs.last(), end[0] * fxs.first() + end[1], 0.1f)
        assertEquals(fys.last(), end[0] * fys.first() + end[2], 0.1f)
    }

    @Test
    fun `组合不变量在已缩放状态下继续成立（二次捏合）`() {
        // 先捏到 1.7（上一条用例的终态），从 (s=1.7, t≠0) 再捏 1.5：
        // 恒等式必须以"当前基准"继续成立 —— 这是 P5（调手势后终值连续）的数学依据。
        val first = simulatePinch(
            spans = (0 until 8).map { 300f + it * 30f },
            fxs = (0 until 8).map { 500f - it * 5f },
            fys = (0 until 8).map { 1000f - it * 8f },
            activationSlop = 100f,
        )
        var s = first[0]
        var tx = first[1]
        var ty = first[2]
        val baseScale = first[0] // 容器语义：基准倍数在第二指落下时定格，逐帧不变
        val baseSpan = 400f
        val spans2 = (0 until 6).map { baseSpan + it * 20f } // 400 → 500（×1.25）
        val fxs2 = (0 until 6).map { 465f + it * 3f }
        val fys2 = (0 until 6).map { 944f - it * 6f }
        for (k in 1 until spans2.size) {
            val r = ZoomTransform.zoomAbout(
                s, tx, ty, target = baseScale * spans2[k] / baseSpan,
                focusX = fxs2[k - 1], focusY = fys2[k - 1],
                viewWidth = bigView, viewHeight = bigView,
            )
            s = r[0]; tx = r[1]; ty = r[2]
            tx += fxs2[k] - fxs2[k - 1]
            ty += fys2[k] - fys2[k - 1]
        }
        val growth = spans2.last() / baseSpan
        assertEquals(1.7f * growth, s, 0.001f)
    }
}
