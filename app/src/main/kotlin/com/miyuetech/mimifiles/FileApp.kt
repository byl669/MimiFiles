package com.miyuetech.mimifiles

import android.app.Application
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.datastore.SettingsRepository
import com.miyuetech.mimifiles.core.shareserver.ShareEngine
import com.miyuetech.mimifiles.core.ui.applyThemeMode
import com.miyuetech.mimifiles.core.update.UpdateEngine

/**
 * 密密文件管理 · Application。
 *
 * 冷启动铁律：`onCreate` 内**只做一件 IO**，就是读主题。
 * Keystore 解密、连接列表、缩略图缓存扫描全部延迟到首次真正需要时。
 */
class FileApp : Application() {

    /**
     * 全局设置入口。
     *
     * 各功能模块也可以自己 `SettingsRepository(context)` 建一份——DataStore 实例由
     * 顶层属性委托保证进程内唯一，所以多建几个包装对象只是多几个小壳子，不会出现
     * "两处写入互相覆盖"。这里保留一份纯粹是为了启动时读主题。
     */
    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // 分享引擎的装配。只在**进程启动时**做一次，且只做"记住 Context"这一件事
        // （真正建监听发生在用户点「启动分享」时）。放在这里而不是面板里，
        // 是为了让"从通知点回面板"这条路径也拿得到一个装好的引擎——
        // 那时进程可能是被通知重新拉起来的，onCreate 会先于 Activity 跑。
        ShareEngine.install(this)
        // 应用内自更新引擎的装配（M5）。同样只做"记住 Context + 清单地址"这一件事，
        // 真正发请求发生在用户点「检查更新」时。
        //
        // 放在 Application 里而不是设置页里，是为了让**下载活过页面**：
        // 用户在下载途中返回文件列表、或转屏重建设置页，都不该把下载掐断。
        // 附带好处是顺手清掉上一次运行留下的 `.part` 残留（见引擎的 cleanup）。
        UpdateEngine.install(this, BuildConfig.UPDATE_MANIFEST_URLS)
        // MimiLog 的环形缓冲默认只留在内存里（靠设置页"导出日志"取）。
        // debug 包额外镜像到 logcat —— 这不是为了开发者方便，而是**排障链路的必要条件**：
        // 网络存储失败时界面上只有"连不上"三个字，真正的原因（TLS 握手、
        // auth gave up after N attempts、方言协商失败、服务端 4xx）**只在日志里**。
        // M2 冒烟的 `app_log_dump()` 就是靠这条通道抓证据的；不打开它，
        // 抓到的永远是 0 行，失败复盘就只能靠猜。
        //
        // release 保持不镜像：内部侧载分发时，logcat 里出现明文主机名与路径没有益处，
        // 而缓冲导出仍然保留完整的现场能力。
        MimiLog.mirrorToLogcat = BuildConfig.DEBUG
        applyThemeFromSettings()
    }

    /**
     * 在任何 Activity 创建**之前**定下昼夜模式。
     *
     * 顺序很关键：`AppCompatDelegate.setDefaultNightMode` 只影响之后创建的 Activity，
     * 一旦 `SplashActivity` 先起来，用户就会看到一帧错误配色的白/黑闪。
     *
     * 读到什么就用什么：读取失败时 [SettingsRepository.themeModeBlocking] 会回落到
     * 「跟随系统」，主题错只是难看，崩了是打不开。
     */
    private fun applyThemeFromSettings() {
        applyThemeMode(settings.themeModeBlocking())
    }
}
