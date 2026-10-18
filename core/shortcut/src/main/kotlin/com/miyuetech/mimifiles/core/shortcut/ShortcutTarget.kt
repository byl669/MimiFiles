package com.miyuetech.mimifiles.core.shortcut

/**
 * 桌面快捷方式指向的目标。
 *
 * 设计要点：**只携带「可重建定位」的最小信息**（文件系统 id + 目录路径 + 显示名），
 * 不携带任何凭据；SMB / WebDAV 连接的账号密码仍由数据层加密保存，桌面图标只负责「回来」。
 *
 * @param fsId        文件系统注册表中的 id，例如 `local` / `smb:<profileId>` / `webdav:<profileId>`
 * @param path        后端路径（本机为绝对路径；SMB / WebDAV 为共享内相对路径）
 * @param displayName 桌面图标上显示的名字（创建时可由用户改名）
 */
data class ShortcutTarget(
    val fsId: String,
    val path: String,
    val displayName: String,
)

/**
 * 创建桌面快捷方式的结果。
 *
 * 与参考应用（`defpackage.g47.d`，双路策略）一致，产生不同形态的结果，
 * 调用方据此给出精确文案，而不是笼统的「成功 / 失败」。
 */
sealed interface ShortcutResult {

    /**
     * 已向桌面发起 pin 请求（API 26+），桌面会弹出确认对话框；
     * 用户点「添加」后图标才真正出现，可通过 [FolderShortcuts] 的结果广播观察。
     */
    data object PinRequested : ShortcutResult

    /** 桌面不支持 pin，已改用 legacy 广播直接添加（多数国产桌面 / Android 7.x 生效） */
    data object LegacyRequested : ShortcutResult

    /** 当前桌面既不支持 pin、也收不到 legacy 广播（部分定制 TV 桌面） */
    data object Unsupported : ShortcutResult

    /** 异常兜底；[reason] 仅用于本地日志，不直接展示给用户 */
    data class Failed(val reason: String) : ShortcutResult
}
