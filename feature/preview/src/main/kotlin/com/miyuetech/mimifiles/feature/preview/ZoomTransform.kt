package com.miyuetech.mimifiles.feature.preview

import kotlin.math.abs
import kotlin.math.hypot

/**
 * "缩放 + 平移"的纯数学部分（不碰任何 View，因此可以被单测覆盖）。
 *
 * ## 为什么把它单独抽出来
 * 手势代码本身没法用 `adb` 自动测（`input` 只支持单指，模拟不了双指捏合），
 * 而这类公式又恰好是最容易写错、写错了还"看起来能用"的地方 ——
 * 典型如"以捏合焦点为中心缩放"写成了"以视图中心为中心缩放"，手感立刻变怪，
 * 但没有任何报错。把公式抽成纯函数后，就可以用断言把"焦点保持不动"这条性质钉死。
 *
 * ## 坐标约定（读这段能省很多时间）
 * ```
 * 子视图坐标（RecyclerView 自己的逻辑坐标，含它内部的滚动）
 *        │  × scale 然后 + (tx, ty)
 *        ▼
 * 容器坐标（= 屏幕坐标，容器自身不做变换）
 * ```
 * 于是子视图上的一点 `p` 在屏幕上的位置是 `scale * p + t`。
 */
object ZoomTransform {

    /** 最小倍数恒为 1（不允许缩小到比"适配"还小，那样只会露出空白）。 */
    const val MIN_SCALE = 1f

    /** 缩放上限。3 倍足够看清 A4 正文的小字，再大只会让位图糊成一团。 */
    const val MAX_SCALE = 3f

    /** 双击放大到的倍数（再双击一次回到 1）。 */
    const val DOUBLE_TAP_SCALE = 2f

    /**
     * 把倍数改成 [target]，并**保持捏合焦点下的那个内容点不动**。
     *
     * ## 公式推导（这版把老版本的一个真错误改掉了）
     * 设当前 `(s0, t0)`，目标 `s1`，焦点 `f`（屏幕坐标）。
     *
     * - 焦点下当前的内容点：`p = (f - t0) / s0`；
     * - 要求变换后它仍在 `f`：`s1 · p + t1 = f`；
     * - 解得：**`t1 = f - (f - t0) · s1 / s0`**。
     *
     * ### ⚠️ 老版本写的是 `t1 = f · (1 - s1)`（M3.1 真机反馈第 3 条的根因之一）
     * 那个式子隐含假设了 `t0 = 0`（从未平移过）。用户捏合之前只要**拖过一次**
     * （`t0 ≠ 0`），算出来的 `t1` 就会让画面**整体跳一下** —— 而且每一次
     * `onScale` 事件都用当时最新的 `focus` 重算一遍，手指略动、倍数微抖都会
     * 转化成一次平移跳变，观感就是"双指缩放时画面抖动闪频"
     * （双击之所以正常，是因为双击发生时 `t0` 恰好为 0，两个公式等价）。
     *
     * 新式子对任意 `t0` 都成立，且 `s1 == s0` 时退化为 `t1 = t0`（不改变平移）。
     *
     * @return 新的 `(scale, tx, ty)`。倍数会被钳制在 [MIN_SCALE]..[maxScale]，
     *         并且平移量会被钳制到"内容不能脱离容器"（见 [clampTo]）。
     */
    fun zoomAbout(
        currentScale: Float,
        currentTx: Float,
        currentTy: Float,
        target: Float,
        focusX: Float,
        focusY: Float,
        viewWidth: Int,
        viewHeight: Int,
        maxScale: Float = MAX_SCALE,
    ): FloatArray {
        val s0 = currentScale.coerceIn(MIN_SCALE, maxScale.coerceAtLeast(MIN_SCALE))
        val s1 = target.coerceIn(MIN_SCALE, maxScale.coerceAtLeast(MIN_SCALE))
        val ratio = s1 / s0
        val tx = focusX - (focusX - currentTx) * ratio
        val ty = focusY - (focusY - currentTy) * ratio
        return clampTo(s1, tx, ty, viewWidth, viewHeight, maxScale)
    }

    /**
     * 把一组变换钳制到合法范围。
     *
     * 公开出来是因为调用方在"把溢出的平移交棒给列表滚动"之后需要**重新钳制一次**
     * （滚动抵消掉了一部分溢出量，钳制条件随之改变）。
     */
    fun clampTo(
        scale: Float,
        tx: Float,
        ty: Float,
        viewWidth: Int,
        viewHeight: Int,
        maxScale: Float = MAX_SCALE,
    ): FloatArray {
        val s = scale.coerceIn(MIN_SCALE, maxScale.coerceAtLeast(MIN_SCALE))
        // 因为 s ≥ 1，内容的显示尺寸恒 ≥ 容器尺寸，所以合法区间就是
        // [容器尺寸 × (1 - s), 0]：上界 0 = 内容顶端对齐容器顶端，
        // 下界 = 内容底端对齐容器底端。**不存在"内容比容器小"的分支**，
        // 这也是"不会露出空白边"的保证。
        val minTx = viewWidth * (1f - s)
        val minTy = viewHeight * (1f - s)
        return floatArrayOf(
            s,
            tx.coerceIn(minTx, 0f),
            ty.coerceIn(minTy, 0f),
        )
    }

    /**
     * 多指"指间距"（span）——与 `ScaleGestureDetector` 完全同口径：
     * 以多指平均焦点为圆心，`hypot(2·平均|x−f|, 2·平均|y−f|)`，
     * 即"平均偏离量的直径"。两指等高时就是两指的水平距离。
     *
     * 抽成纯函数是因为它同时被两处使用：容器捏合引擎（每帧调用）与
     * "组合不变量"单测（必须能在 JVM 上脱离 MotionEvent 复现同一套数学）。
     *
     * @param xs 各指针 x 坐标；[ys] 与之等长配对。
     */
    fun spanOf(xs: FloatArray, ys: FloatArray): Float {
        val n = xs.size
        if (n == 0) return 0f
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until n) {
            sumX += xs[i]
            sumY += ys[i]
        }
        val fx = sumX / n
        val fy = sumY / n
        var devX = 0f
        var devY = 0f
        for (i in 0 until n) {
            devX += abs(xs[i] - fx)
            devY += abs(ys[i] - fy)
        }
        return hypot(2f * devX / n, 2f * devY / n)
    }

    /**
     * 把"平移量超出视口"的那部分换算成子视图应该滚动的逻辑像素。
     *
     * @return 子视图应当滚动的量（正数 = 内容向上翻，即 `scrollBy(0, +value)`）；
     *         调用方仍需按子视图的可滚范围自行截断（那需要真实的滚动范围，不在这里算）。
     */
    fun handoff(scale: Float, ty: Float, viewHeight: Int): Float {
        val minTy = viewHeight * (1f - scale.coerceAtLeast(MIN_SCALE))
        return when {
            ty > 0f -> -ty / scale.coerceAtLeast(MIN_SCALE)
            ty < minTy -> (minTy - ty) / scale.coerceAtLeast(MIN_SCALE)
            else -> 0f
        }
    }
}
