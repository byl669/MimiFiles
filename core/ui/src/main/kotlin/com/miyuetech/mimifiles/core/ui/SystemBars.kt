package com.miyuetech.mimifiles.core.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 * 系统栏（状态栏 / 导航栏）适配。
 *
 * ## 为什么必须自己做
 * `targetSdk` 一旦到 35+，Android 15 会**强制** edge-to-edge：窗口内容默认铺到
 * 状态栏与手势条底下，主题里的 `statusBarColor` 不再起作用。于是老办法
 * （主题设颜色 + `fitsSystemWindows`）在部分系统上会失效，表现出来就是
 * 标题栏被状态栏压住、最后一行被手势条盖住。
 *
 * 可靠做法只有一种：显式声明"我要铺满"，再让每个区域**自己**把 insets 转成 padding。
 * 这里统一封装，避免每个页面各写一遍。
 */

/** 声明铺满全屏。必须在 `setContentView` 之前或紧随其后调用。 */
fun Activity.enableEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
}

/**
 * 把系统栏占位加成本 View 的 padding。
 *
 * [top] / [bottom] 控制要不要吃掉对应方向的 insets：
 * 通常内容根两者都要，而抽屉里的窗格要单独再来一次（它铺在另一侧、有独立的边距）。
 *
 * ## 基准值只取一次
 * `paddingTop` 在**设置监听时**读一次作为基准，之后的每次回调都用
 * `基准 + insets`。若每次回调都从当前 `paddingTop` 累加，旋转一次屏幕就会
 * 把状态栏高度叠两遍——这是自己写 insets 最常见的坑。
 */
fun View.padForSystemBars(top: Boolean = true, bottom: Boolean = true) {
    if (!top && !bottom) return
    val baseTop = paddingTop
    val baseBottom = paddingBottom
    val type = when {
        top && bottom -> WindowInsetsCompat.Type.systemBars()
        top -> WindowInsetsCompat.Type.statusBars()
        else -> WindowInsetsCompat.Type.navigationBars()
    }
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(type)
        view.updatePadding(
            top = baseTop + if (top) bars.top else 0,
            bottom = baseBottom + if (bottom) bars.bottom else 0,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * 系统栏图标的明暗。
 *
 * @param lightBackground 背景是否偏亮。`true` → 深色图标（浅色模式）；
 *                        `false` → 浅色图标（深色模式）。
 * 传反了的后果是"深色模式下状态栏图标是黑的"，在纯黑背景上等于消失。
 */
fun Activity.applySystemBarIconAppearance(lightBackground: Boolean) {
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightBackground
        isAppearanceLightNavigationBars = lightBackground
    }
}

/**
 * 当前**实际生效**的昼夜状态。
 *
 * 刻意读 `resources.configuration`，而不是问 `AppCompatDelegate.getDefaultNightMode()`：
 * 后者返回的是我们**请求**的模式，在「跟随系统」时它只说"跟随系统"，
 * 并没有告诉我们系统此刻到底是深是浅。用它来判断颜色，会让跟随系统的深色设备
 * 永远拿到"浅色"的答案。
 */
fun Context.isNightModeActive(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
