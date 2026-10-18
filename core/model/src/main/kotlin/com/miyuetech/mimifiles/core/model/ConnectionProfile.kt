package com.miyuetech.mimifiles.core.model

/**
 * 一条远程存储连接（SMB / WebDAV）。
 *
 * **安全约定（冻结）**：
 *  - 本类**不承载密码**。`id` 是凭据在 Keystore 加密库里的键，密码只在 `:core:datastore`
 *    解密后当场交给后端建立会话，绝不进入本对象、绝不写日志、绝不进桌面快捷方式 extras。
 *  - [options] 只放非敏感调参（编码、方言、超时、是否匿名）。
 */
data class ConnectionProfile(
    val id: String,
    val scheme: String,
    val displayName: String,
    val host: String,
    val port: Int = 0,
    val username: String? = null,
    val domain: String? = null,
    /** 连接建立后的挂载点（SMB 为共享名，WebDAV 为基准目录）。 */
    val rootPath: String = "",
    val options: Map<String, String> = emptyMap(),
) {
    /** 该连接对应的 `FsPath` 根。 */
    fun rootFsPath(): FsPath = FsPath.of(scheme, id, rootPath)

    /** UI 展示用端点串，如 `192.168.1.100:445`。 */
    val endpointLabel: String get() = if (port > 0) "$host:$port" else host

    /** 去重键：同 host+port+共享+账号视为同一连接（避免列表里出现两条一模一样的记录）。 */
    val dedupeKey: String
        get() = listOf(scheme, host, port.toString(), rootPath, username.orEmpty(), domain.orEmpty())
            .joinToString("|")

    companion object {
        /** 不写死端口：让后端按 scheme 决定（SMB 445 / WebDAV 80·443）。 */
        const val PORT_DEFAULT = 0
    }
}
