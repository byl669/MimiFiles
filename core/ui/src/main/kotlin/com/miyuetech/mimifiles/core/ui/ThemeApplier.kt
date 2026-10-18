package com.miyuetech.mimifiles.core.ui

import androidx.appcompat.app.AppCompatDelegate
import com.miyuetech.mimifiles.core.model.ThemeMode

/**
 * 把 [ThemeMode] 映射到 AppCompat 的昼夜模式。
 *
 * 放在 `:core:ui` 而不是 `:core:model`：`core:model` 是纯 Kotlin（能在 JVM 单测里跑），
 * 一旦引入 `AppCompatDelegate` 就绑死了 Android 框架。
 */
fun ThemeMode.toNightMode(): Int = when (this) {
    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}

/**
 * 应用昼夜模式。
 *
 * ## 为什么要先比较再设置
 * `AppCompatDelegate.setDefaultNightMode` 在值**发生变化**时会触发所有 Activity
 * `recreate()`。若每次 `onCreate` 都无脑调一遍，用户在设置页切一次主题就会看到
 * 界面连续重建好几次。这里做等值判断，把"只在真正变化时重建"变成结构上的保证，
 * 而不是靠调用方自觉。
 */
fun applyThemeMode(mode: ThemeMode) {
    val target = mode.toNightMode()
    if (AppCompatDelegate.getDefaultNightMode() != target) {
        AppCompatDelegate.setDefaultNightMode(target)
    }
}
