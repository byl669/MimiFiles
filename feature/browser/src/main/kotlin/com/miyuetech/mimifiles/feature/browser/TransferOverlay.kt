package com.miyuetech.mimifiles.feature.browser

/** 搬运进度浮窗当前该显示成什么形态。 */
enum class TransferSurface {
    /** 没有搬运（或刚结束）：什么都不显示。 */
    NONE,

    /** 大浮窗（对话框）：完整进度 + 最小化 + 取消。 */
    DIALOG,

    /** 右下角圆形浮窗：只有进度环，点一下还原成 [DIALOG]。 */
    BUBBLE,
}

/**
 * 搬运进度浮窗的形态状态机（M7.28）。
 *
 * ## 为什么单独抽出来
 * 「最小化」不是「关掉」，而是同一份进度的另一种可见形态。这个判断一旦和
 * Activity 的生命周期回调纠缠在一起，就很容易退化成旧版那个 bug：
 * 用 `OnDismissListener` 推断"用户不想看了"，而系统在 Activity 退到后台时
 * detach 窗口**也会**触发它，于是切一次后台进度浮窗就永久消失，只剩通知栏。
 *
 * 抽成不依赖 Android 的状态机之后，只有**用户动作**（[minimize] / [restore]）
 * 能改变形态；系统销毁窗口、Activity 重建这些事根本进不来。
 * 搬运结束时 [clear]，下一次搬运从大浮窗重新开始。
 */
class TransferOverlay {

    var isMinimized: Boolean = false
        private set

    /** 用户点「最小化」/ 点浮窗外 / 按返回键。 */
    fun minimize() {
        isMinimized = true
    }

    /** 用户点圆形浮窗，还原成大浮窗。 */
    fun restore() {
        isMinimized = false
    }

    /** 一次搬运结束：忘掉最小化状态。 */
    fun clear() {
        isMinimized = false
    }

    /**
     * 当前该画哪个形态。
     *
     * @param hasProgress 是否存在进行中的搬运。
     */
    fun surface(hasProgress: Boolean): TransferSurface = when {
        !hasProgress -> TransferSurface.NONE
        isMinimized -> TransferSurface.BUBBLE
        else -> TransferSurface.DIALOG
    }
}
