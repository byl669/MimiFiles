package com.miyuetech.mimifiles.feature.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.drawerlayout.widget.DrawerLayout

/**
 * 双栏形态下能**把触摸完全交出去**的 `DrawerLayout`。
 *
 * ## 为什么必须这么做（M3.1 真机反馈第 4 条的根因）
 * 横屏/平板时我们把抽屉锁成常开（`LOCK_MODE_LOCKED_OPEN`）当静态左树用。但
 * `DrawerLayout` 的"点内容区关闭抽屉"判定只看**抽屉露出屏幕的比例**（`onScreen`），
 * 它并不关心抽屉是不是被锁住的：
 *
 * ```java
 * // androidx.drawerlayout 1.1.1 · onInterceptTouchEvent（ACTION_DOWN 分支）
 * if (mScrimOpacity > 0f && (child = mLeftDragger.findTopChildUnder(x, y)) != null
 *         && isContentView(child)) {
 *     interceptForTap = true;          // ← mScrimOpacity = max(onScreen) = 1.0
 * }
 * return interceptForDrag || interceptForTap || hasPeekingDrawer() || mChildrenCanceledTouch;
 * ```
 *
 * 常开的抽屉 `onScreen` 恒为 **1.0** → `mScrimOpacity` 恒大于 0 → 只要按下的点落在
 * 内容列的布局盒里，`interceptForTap` 就成立，`onInterceptTouchEvent` 返回 `true`，
 * 事件**根本不会分发到内容列**；而它自己的 `onTouchEvent` 在锁定态下什么也不做
 * （`tryCaptureView` 对锁定的抽屉返回 `false`），于是这些触摸被**静默吞掉**。
 *
 * 症状正好就是真机看到的那样：
 * - **右半边内容列**：看得见、点不动、也滑不动（事件在 DrawerLayout 层就被截走了）；
 * - **左半边的树**：一切正常 —— 因为它位于抽屉内部，`findTopChildUnder` 在
 *   内容列那一侧找不到它，`interceptForTap` 不成立，事件照常往下发。
 *
 * ## 为什么是"关掉拦截"而不是"不用 DrawerLayout"
 * 抽屉的**几何**（`onLayout` 按 `onScreen` 定位、锁定常开、内容列让出窗格宽）
 * 与它的**触摸拦截**是两件独立的事。覆盖两个触摸方法只关掉后者，
 * 左树的静态定位、分隔线、系统栏内边距全部原样保留；换成兄弟布局则要把
 * 同一套几何再实现一遍，两种形态（单栏抽屉 / 双栏窗格）还会开始漂移。
 *
 * ## 单栏（手机竖屏）时**必须**保持原行为
 * 那时抽屉要能拉开、点遮罩要能关上。把触摸放行等于把抽屉废掉，
 * 所以这里用显式开关 [allowTouchPassthrough]，且只由 `twoPane` 分支打开。
 *
 * ⚠️ 注意 `onDraw` / `drawChild` 里的遮罩与裁剪逻辑**不受**这里影响 ——
 * 它们读的仍是 `onScreen`。真正的双栏视觉（无遮罩、无投影）由调用方
 * `setScrimColor(TRANSPARENT)` + `setDrawerElevation(0f)` 负责。
 */
class TwoPaneDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : DrawerLayout(context, attrs) {

    /**
     * `true` = 本控件不参与触摸分发，全部交给子视图。
     *
     * 只在"抽屉被锁成常开、当作静态左树"时打开；其余情况保持 `false`。
     */
    var allowTouchPassthrough: Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        if (allowTouchPassthrough) false else super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent): Boolean =
        if (allowTouchPassthrough) false else super.onTouchEvent(ev)
}
