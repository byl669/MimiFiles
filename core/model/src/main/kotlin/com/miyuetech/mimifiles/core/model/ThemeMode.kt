package com.miyuetech.mimifiles.core.model

/**
 * 外观模式三态。
 *
 * 放在 `:core:model` 而不是 `:core:datastore`：它是**领域概念**（设置项的取值），
 * 而不是存储实现细节。这样 `:core:ui`（负责把它落到 AppCompat 上）
 * 和 `:core:datastore`（负责持久化）都可以依赖它，谁都不用反向依赖谁。
 *
 * [id] 是持久化用的稳定键，**改动会丢用户的设置**，所以用字符串而不是 ordinal。
 */
enum class ThemeMode(val id: String) {
    /** 跟随系统（默认）。 */
    SYSTEM("system"),

    LIGHT("light"),

    DARK("dark");

    companion object {
        val DEFAULT = SYSTEM

        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
