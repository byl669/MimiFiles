package com.miyuetech.mimifiles.core.ui

import android.content.Context
import android.content.Intent

/**
 * 跨功能模块的页面跳转地址。
 *
 * ## 为什么要有这么个"字符串字典"
 * 功能模块之间**刻意不互相依赖**（`feature:browser` 不依赖 `feature:settings`）。
 * 好处是单个功能模块能被独立编译、独立测试，也不会因为它们互相 import 而织成一张网，
 * 最后谁也拆不动。代价就是"浏览页要打开设置页"这件事不能直接写 `SettingsActivity::class.java`。
 *
 * 折中办法是：把目标类的全限定名放在两边都已经依赖的 `:core:ui` 里，
 * 由**调用方**构造 Intent。这样依赖方向仍然是单向的（feature → core），
 * 而模块清单（`settings.gradle.kts`）本身就是唯一事实来源——改模块名会同时改这里，
 * 漏改的后果是跳转失败而不是编译不过，所以这里配了 [resolve] 做可用性判断。
 *
 * ## 为什么不用 `setComponent` 硬编码包名
 * debug 变体带 `.debug` 后缀，硬编码包名会在其中一个变体上失效。
 * [Intent.setClassName] 走 `Context` 的包名，两个变体都对。
 */
object FeatureRoutes {

    const val SETTINGS_ACTIVITY = "com.miyuetech.mimifiles.feature.settings.SettingsActivity"

    const val CONNECTIONS_ACTIVITY = "com.miyuetech.mimifiles.feature.connection.ConnectionsActivity"

    /** 图片 / 文本 / PDF / 音频预览。 */
    const val PREVIEW_ACTIVITY = "com.miyuetech.mimifiles.feature.preview.PreviewActivity"

    /** 视频播放器（Media3）。 */
    const val VIDEO_PLAYER_ACTIVITY = "com.miyuetech.mimifiles.feature.player.VideoPlayerActivity"

    /** 无线分享面板（M4）。 */
    const val SHARE_ACTIVITY = "com.miyuetech.mimifiles.feature.share.SharePanelActivity"

    /**
     * 进入分享面板的入参键 —— 与 `feature:share` 的 `ShareArgs` **必须一致**。
     *
     * 这里重复一份字符串常量是"模块不互相依赖"这条约定的代价：
     * `:core:ui` 不能 import `:feature:share`（方向是 feature → core）。
     * 之所以放在这里而不是让调用方各自写字符串：浏览页有**三处**入口
     * （侧栏、菜单、多选分享），三处各写一遍"share.root"迟早会有一处拼错，
     * 而拼错的表现是"点了分享却什么都没分享"——静默失败。
     * 两侧各留一份、并在此注明对照，是这类跨模块契约的常见折中。
     */
    const val SHARE_EXTRA_ROOT = "share.root"

    const val SHARE_EXTRA_SELECTED = "share.selected"

    const val SHARE_EXTRA_SELECTED_IS_DIR = "share.selected_is_dir"

    /** 构造跳转设置的 Intent；目标类不存在时返回 `null`（调用方负责给一句提示）。 */
    fun settings(context: Context): Intent? = intentFor(context, SETTINGS_ACTIVITY)

    /** 网络连接管理（SMB / WebDAV 的增删改与连通性测试）。 */
    fun connections(context: Context): Intent? = intentFor(context, CONNECTIONS_ACTIVITY)

    /** 预览（图片/文本/PDF/音频）。 */
    fun preview(context: Context): Intent? = intentFor(context, PREVIEW_ACTIVITY)

    /** 视频播放器。 */
    fun videoPlayer(context: Context): Intent? = intentFor(context, VIDEO_PLAYER_ACTIVITY)

    /**
     * 无线分享面板。
     *
     * 三个入口共用它（方案 §10.4「入口一致」）：
     *  - 侧栏/导航「无线分享」→ 只给 [root]（分享当前目录）；
     *  - 文件多选 → 分享 → 给 [root] + [selected]；
     *  - 通知点回面板 → 什么都不给（面板沿用进程里暂存的请求）。
     */
    fun share(context: Context, root: String? = null, selected: List<String>? = null, selectedIsDir: Boolean = false): Intent? {
        val intent = intentFor(context, SHARE_ACTIVITY) ?: return null
        if (root != null) intent.putExtra(SHARE_EXTRA_ROOT, root)
        if (!selected.isNullOrEmpty()) {
            intent.putExtra(SHARE_EXTRA_SELECTED, selected.toTypedArray())
            intent.putExtra(SHARE_EXTRA_SELECTED_IS_DIR, selectedIsDir)
        }
        // 从此不再复用同一个 Intent 实例（`intentFor` 每次新建，这里只是把
        // "同一份 extras 会被后续跳转沿用"这件事说明白——若将来改成缓存 Intent，
        // 必须记得清 extras，否则第二次从别处进来会带着上一次的选中项）。
        return intent
    }

    /** 打开分享面板。`false` 时调用方应提示"功能不可用"。 */
    fun openShare(
        context: Context,
        root: String? = null,
        selected: List<String>? = null,
        selectedIsDir: Boolean = false,
    ): Boolean = open(context, share(context, root, selected, selectedIsDir))

    /** 直接跳转；目标缺失时静默返回 `false`，不抛异常也不崩。 */
    fun openSettings(context: Context): Boolean = open(context, settings(context))

    /** @return 是否真的跳过去了。`false` 时调用方应给一句"功能不可用"的提示。 */
    fun openConnections(context: Context): Boolean = open(context, connections(context))

    private fun intentFor(context: Context, className: String): Intent? {
        val intent = Intent().setClassName(context, className)
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    private fun open(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
