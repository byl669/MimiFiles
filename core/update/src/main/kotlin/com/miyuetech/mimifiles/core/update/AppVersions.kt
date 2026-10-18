package com.miyuetech.mimifiles.core.update

import android.content.Context
import android.os.Build

/**
 * 读取**已安装**应用的版本号。
 *
 * ## 为什么不用 `BuildConfig.VERSION_CODE`
 * 本项目的每个模块都有自己的 `BuildConfig`，而那里的 `VERSION_CODE` 是**该模块**
 * 的版本（Android 库模块默认 1），不是应用的版本。用它来比对会得到一个恒定的
 * 错误答案：远端任何版本都"比 1 大"，于是每次检查都提示有更新。
 *
 * 走 `PackageManager` 还有个额外好处：拿到的是**系统认为已经装上的那个版本**，
 * 这正是"要不要升级"这个问题真正要对比的东西（而不是"这份代码编译时是多少"）。
 */
object AppVersions {

    fun read(context: Context): InstalledVersion = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        InstalledVersion(
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            },
            versionName = info.versionName ?: "?",
        )
    }.getOrElse {
        // 理论上不可达（自己的包一定查得到）。真到了这里，用 0 会让"检查更新"
        // 把任何清单都当成新版 —— 于是校验阶段会以"不是新版本"拒绝，
        // 结果是一次看得见原因的失败，而不是一次静默的错误升级。
        InstalledVersion(versionCode = 0L, versionName = "?")
    }
}
