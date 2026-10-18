package com.miyuetech.mimifiles.core.fswebdav

/**
 * WebDAV 连接的非敏感调参键。键名是持久化契约（落在 `connections.options_json`）。
 */
object WebDavOptionKeys {

    /**
     * 认证方式：`auto`（默认）/ `basic` / `digest`。
     *
     * 默认 `auto`：先按 Basic 预置，被 401 挑战且服务端给出 Digest 时自动切 Digest。
     * 手动指定只在少数"服务端同时宣布两种但只接受其一"的场景下需要。
     */
    const val AUTH_METHOD = "authMethod"

    /**
     * 是否允许在**明文 HTTP** 上发送凭据。
     *
     * 默认 `false`。Basic 认证在 HTTP 上是 base64，等同明文；即使在家里局域网，
     * 同一网段的任何设备都能嗅到。要让用户明确点头才开启（连接页会警告）。
     * 匿名访问不受此限制。
     */
    const val ALLOW_INSECURE = "allowInsecure"

    /**
     * 信任的自签证书 SHA-256 指纹（十六进制，可含冒号）。
     *
     * 用于群晖/自建 Nextcloud 这类用自签证书的场景。**不做全局 TrustAll**：
     * 只对"指纹完全一致的那张证书"放行，换一张就得重新确认——这样中间人
     * 换证书会被立刻发现，而"信任所有证书"的实现是把这个能力直接交出去了。
     */
    const val TRUST_FINGERPRINT = "trustFingerprint"

    /** 连接超时毫秒。 */
    const val TIMEOUT_MS = "timeoutMs"

    /** 是否跳过 `PROPFIND` 的 `Depth: 1` 用 `Depth: 0` 逐条问（极少数服务端不支持）。 */
    const val NO_DEPTH_ONE = "noDepthOne"

    const val DEFAULT_TIMEOUT_MS = 15_000

    const val AUTH_AUTO = "auto"
    const val AUTH_BASIC = "basic"
    const val AUTH_DIGEST = "digest"
}

/** 解析后的 WebDAV 参数。 */
data class WebDavSettings(
    val authMethod: String,
    val allowInsecure: Boolean,
    val trustFingerprint: String?,
    val timeoutMs: Int,
) {
    val secureTransportRequired: Boolean get() = !allowInsecure

    companion object {

        fun parse(options: Map<String, String>): WebDavSettings = WebDavSettings(
            authMethod = options[WebDavOptionKeys.AUTH_METHOD]
                ?.trim()?.lowercase()
                ?.takeIf { it in VALID_AUTH_METHODS }
                ?: WebDavOptionKeys.AUTH_AUTO,
            allowInsecure = options[WebDavOptionKeys.ALLOW_INSECURE].toFlag(default = false),
            trustFingerprint = options[WebDavOptionKeys.TRUST_FINGERPRINT]
                ?.takeIf { it.isNotBlank() }
                // 归一化：用户从浏览器/OpenSSL 复制指纹时格式五花八门
                // （带冒号、带空格、大小写混用），统一成"大写无分隔"再比对。
                ?.replace(":", "")?.replace(" ", "")?.uppercase(),
            timeoutMs = options[WebDavOptionKeys.TIMEOUT_MS]?.toIntOrNull()
                ?.coerceIn(3_000, 120_000)
                ?: WebDavOptionKeys.DEFAULT_TIMEOUT_MS,
        )

        private val VALID_AUTH_METHODS = setOf(
            WebDavOptionKeys.AUTH_AUTO,
            WebDavOptionKeys.AUTH_BASIC,
            WebDavOptionKeys.AUTH_DIGEST,
        )

        private fun String?.toFlag(default: Boolean): Boolean = when (this?.trim()?.lowercase()) {
            null, "" -> default
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }
}
