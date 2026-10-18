package com.miyuetech.mimifiles.core.shareserver

/**
 * 无线分享支持的服务端类型。
 *
 * M4 只落地 HTTP 与 FTP（方案 §10.1 的 S-1 / S-2）；FTPS 是同一套代码加 TLS 配置，
 * 排在 M5（S-3），所以这里**先留出枚举位**：`ServerConfig.tls` 已经是它的入口，
 * 到时不需要动 `when` 的穷举结构（加了枚举位却不加分支，编译期就会报错）。
 */
enum class ServerType {

    /** 内置网页：PC 浏览器扫码即用，支持浏览 / 下载 / 上传 / 视频在线播放拖动。 */
    HTTP,

    /** FTP 服务端：Windows 资源管理器可直接挂载为网络位置，读写皆可。 */
    FTP;

    /** 端口被占用时从这里开始向上探测（方案 §10.5「端口被占用」一条）。 */
    val preferredPort: Int
        get() = when (this) {
            // 8080 是"最像 HTTP 服务"的端口，用户与截图里都一眼认得出。
            HTTP -> 8080
            // 2121 而非 21：1024 以下需要 root，Android 上不可能；
            // 2121 也是各大 FTP 服务端 App 的默认值，PC 端用户见得最多。
            FTP -> 2121
        }

    /** 在 URL 里出现的 scheme。 */
    val scheme: String
        get() = when (this) {
            HTTP -> "http"
            FTP -> "ftp"
        }

    /** 界面上 Tab / 卡片标题用的名字。 */
    val label: String
        get() = when (this) {
            HTTP -> "HTTP"
            FTP -> "FTP"
        }

    companion object {

        /** 从持久化字符串还原；未知值回落到 [HTTP]（默认 Tab）。 */
        fun fromName(raw: String?): ServerType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: HTTP
    }
}
