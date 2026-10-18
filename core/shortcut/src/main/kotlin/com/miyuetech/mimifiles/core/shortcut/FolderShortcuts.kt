package com.miyuetech.mimifiles.core.shortcut

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.security.MessageDigest

/**
 * 文件夹 → 桌面快捷方式。
 *
 * ## 双路策略（与参考应用同款，逆向证据 `defpackage.g47.d`）
 *
 * | 环境 | 做法 | 说明 |
 * |---|---|---|
 * | API ≥ 26 且桌面声明支持 pin | `ShortcutManagerCompat.requestPinShortcut` | 官方流程，桌面弹确认对话框，**无需任何权限** |
 * | API ≥ 26 但桌面不支持 pin | 直接返回 [ShortcutResult.Unsupported] | 与参考应用一致（不会静默失败） |
 * | API 24 / 25 | `com.android.launcher.action.INSTALL_SHORTCUT` 广播 | 需 `com.android.launcher.permission.INSTALL_SHORTCUT`（已在本模块 Manifest 声明，`maxSdkVersion=25`） |
 *
 * ## 为什么不用 `Intent.ShortcutIconResource` / `Icon.createWithResource` 之外的方案
 * 参考应用就是用 `Icon.createWithResource`（新版路径）与 `ShortcutIconResource.fromContext`
 * （旧版路径）各一份，本模块与之一一对应，不另起思路。
 *
 * ## 与启动器 Activity 的关系
 * pin 出来的图标最终由**桌面进程**启动，因此 Intent 的落点必须是**已导出**的 Activity。
 * 本模块用 [resolveEntryActivity] 从 `PackageManager` 解析本应用的 LAUNCHER 组件
 * （本项目即 `SplashActivity`，本就 exported），由它读取 extras 后再路由到真正的浏览界面；
 * `DocumentsActivity` 保持 `exported=false` 不对系统外露。模块因此**不需要反向依赖 `:app`**。
 */
object FolderShortcuts {

    /** 快捷方式 Intent 携带的目标文件系统 id（`local` / `smb:<id>` / `webdav:<id>`）。 */
    const val EXTRA_FS_ID = "com.miyuetech.mimifiles.intent.extra.SHORTCUT_FS_ID"

    /** 快捷方式 Intent 携带的目标路径。 */
    const val EXTRA_PATH = "com.miyuetech.mimifiles.intent.extra.SHORTCUT_PATH"

    /** 快捷方式 Intent 携带的显示名。 */
    const val EXTRA_NAME = "com.miyuetech.mimifiles.intent.extra.SHORTCUT_NAME"

    /** 由 [ShortcutPinResultReceiver] 发出的「用户已确认添加」应用内广播。 */
    const val ACTION_PIN_RESULT = "com.miyuetech.mimifiles.intent.action.SHORTCUT_PIN_RESULT"

    /** [ACTION_PIN_RESULT] 携带的快捷方式 id。 */
    const val EXTRA_PINNED_SHORTCUT_ID = "com.miyuetech.mimifiles.intent.extra.PINNED_SHORTCUT_ID"

    /** 快捷方式 Intent 的动作（自定义，避免与 ACTION_VIEW 的数据约定冲突）。 */
    const val ACTION_OPEN_TARGET = "com.miyuetech.mimifiles.intent.action.OPEN_TARGET"

    private const val ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
    private const val EXTRA_SHORTCUT_INTENT = "android.intent.extra.shortcut.INTENT"
    private const val EXTRA_SHORTCUT_NAME = "android.intent.extra.shortcut.NAME"
    private const val EXTRA_SHORTCUT_ICON_RESOURCE = "android.intent.extra.shortcut.ICON_RESOURCE"
    private const val PERMISSION_INSTALL_SHORTCUT = "com.android.launcher.permission.INSTALL_SHORTCUT"

    private const val REQ_PIN_RESULT = 0x51F0

    /**
     * 稳定的快捷方式 id。
     *
     * 同一路径重复创建会**更新**既有快捷方式而不是产生重复图标；
     * 路径可能很长，故固定取 `sha256(fsId + NUL + path)` 前 24 位十六进制，长度可控且无碰撞担忧。
     */
    fun shortcutId(target: ShortcutTarget): String =
        "folder-${target.fsId.take(12)}-${sha256Hex("${target.fsId}\u0000${target.path}").take(24)}"

    /** 当前桌面是否支持官方 pin 流程（Android 8.0+ 才有意义）。 */
    fun isPinSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** 快捷方式图标（矢量，随模块走；参考应用同款 `createWithResource` 用法）。 */
    fun iconFor(context: Context): IconCompat =
        IconCompat.createWithResource(context, R.drawable.ic_shortcut_folder)

    /**
     * 解析快捷方式落点：本应用**已导出的 LAUNCHER Activity**（从 PackageManager 取，零手工接线）。
     *
     * 用解析而非硬编码类名，模块不必反向依赖 `:app`；解析不到时创建流程会返回
     * [ShortcutResult.Failed]，不会静默产出点不开的图标。
     */
    fun resolveEntryActivity(context: Context): Class<*>? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.component?.className
            ?.let { runCatching { Class.forName(it) }.getOrNull() }

    /**
     * 构造快捷方式的落点 Intent——**只带定位信息，不带凭据**。
     *
     * @param entryActivity 已导出的入口 Activity；传 `null` 时自动解析（推荐）。
     */
    fun buildTargetIntent(
        context: Context,
        target: ShortcutTarget,
        entryActivity: Class<*>? = null,
    ): Intent {
        val entry = entryActivity ?: resolveEntryActivity(context)
            ?: error("找不到可用的入口 Activity：应用缺少 LAUNCHER 组件")
        return Intent(context, entry).apply {
            action = ACTION_OPEN_TARGET
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_FS_ID, target.fsId)
            putExtra(EXTRA_PATH, target.path)
            putExtra(EXTRA_NAME, target.displayName)
        }
    }

    /** 从（快捷方式）Intent 还原目标；非快捷方式启动返回 `null`。 */
    fun readTarget(intent: Intent?): ShortcutTarget? {
        if (intent == null) return null
        val fsId = intent.getStringExtra(EXTRA_FS_ID) ?: return null
        val path = intent.getStringExtra(EXTRA_PATH) ?: return null
        return ShortcutTarget(
            fsId = fsId,
            path = path,
            displayName = intent.getStringExtra(EXTRA_NAME).orEmpty(),
        )
    }

    /**
     * 请求在桌面创建/更新指向 [target] 的快捷方式。
     *
     * 调用方应在 UI 线程调用（内部无阻塞 IO），并根据返回的 [ShortcutResult] 给出对应提示。
     */
    fun requestCreate(
        context: Context,
        target: ShortcutTarget,
        entryActivity: Class<*>? = null,
    ): ShortcutResult {
        if (target.path.isBlank()) {
            return ShortcutResult.Failed("empty target path")
        }
        val label = target.displayName.ifBlank { target.path.trimEnd('/').substringAfterLast('/') }
            .ifBlank { context.getString(R.string.shortcut_default_folder_name) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                return ShortcutResult.Unsupported
            }
            return runCatching {
                val intent = buildTargetIntent(context, target, entryActivity)
                val info = ShortcutInfoCompat.Builder(context, shortcutId(target))
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(iconFor(context))
                    .setIntent(intent)
                    .build()
                ShortcutManagerCompat.requestPinShortcut(context, info, buildPinResultSender(context, info))
            }.fold(
                onSuccess = { requested ->
                    if (requested) ShortcutResult.PinRequested else ShortcutResult.Unsupported
                },
                onFailure = { ShortcutResult.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }

        // ── API 24 / 25：legacy 广播（参考应用在老系统上走的就是这条路）────
        return runCatching {
            sendLegacyInstall(context, buildTargetIntent(context, target, entryActivity), label)
        }.fold(
            onSuccess = { ShortcutResult.LegacyRequested },
            onFailure = { ShortcutResult.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    /**
     * pin 结果的 `IntentSender`。
     *
     * 桌面**只在用户确认添加后**回调，因此 [ShortcutPinResultReceiver] 收到广播即代表成功；
     * 用户取消时不会有回调（与官方语义一致）。
     */
    private fun buildPinResultSender(context: Context, info: ShortcutInfoCompat): IntentSender? {
        val resultIntent = ShortcutManagerCompat.createShortcutResultIntent(context, info) ?: return null
        val reply = Intent(context, ShortcutPinResultReceiver::class.java)
            .putExtra(EXTRA_PINNED_SHORTCUT_ID, info.id)
            .putExtras(resultIntent)
        return android.app.PendingIntent.getBroadcast(
            context,
            REQ_PIN_RESULT,
            reply,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
    }

    private fun sendLegacyInstall(context: Context, target: Intent, label: String) {
        val icon = android.content.Intent.ShortcutIconResource.fromContext(
            context.applicationContext,
            R.drawable.ic_shortcut_folder,
        )
        context.sendBroadcast(
            Intent(ACTION_INSTALL_SHORTCUT).apply {
                putExtra(EXTRA_SHORTCUT_INTENT, target)
                putExtra(EXTRA_SHORTCUT_NAME, label)
                putExtra(EXTRA_SHORTCUT_ICON_RESOURCE, icon)
                // 同一目录重复添加时覆盖，避免桌面堆积重复图标
                putExtra("duplicate", false)
            },
        )
    }

    /** 当前是否具备 legacy 通路（权限已授予 + 系统里存在接收器），仅 Android 8.0 以下会走到。 */
    fun isLegacyPathAvailable(context: Context): Boolean {
        val granted = context.checkSelfPermission(PERMISSION_INSTALL_SHORTCUT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val receiver = context.packageManager
            .queryBroadcastReceivers(Intent(ACTION_INSTALL_SHORTCUT), 0)
            .isNotEmpty()
        return granted && receiver
    }

    /** 取 SHA-256 摘要前 16 字节转 32 位十六进制字符串（长度稳定，碰撞概率可忽略）。 */
    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        val hex = CharArray(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            hex[i * 2] = digits[b ushr 4]
            hex[i * 2 + 1] = digits[b and 0x0F]
        }
        return String(hex)
    }
}
