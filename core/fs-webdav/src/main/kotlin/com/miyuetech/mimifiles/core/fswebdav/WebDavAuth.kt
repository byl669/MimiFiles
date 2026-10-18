package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.common.MimiLog
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebDAV 认证：Basic（预置）+ Digest（挑战应答）。
 *
 * ## 为什么必须自己写
 * 方案第 5.3 节原本写的是「Digest 用 OkHttp 内置的 `DigestAuthenticator`」。
 * **实测推翻了这个假设**：okhttp 4.12 里已经没有 `okhttp3.DigestAuthenticator`
 * （它在 3.x 后期就被移除了，官方立场是"用 `Authenticator` 自己实现"）。
 * 同款依赖 `sardine-android` 也只提供了 `BasicAuthenticator`。
 * 所以 Digest 只能靠这段代码——好在协议本身不长，且判据都是公开的 RFC。
 *
 * ## 为什么 Basic 要"预置"而 Digest 不能
 * OkHttp 的 `Authenticator` **只在收到 401 之后**才会被调用。如果什么都不预置，
 * 那每一个请求都要先吃一次 401 再重发——列一个 50 个目录的树就要多 50 次往返，
 * 在公网 WebDAV 上直接慢一倍。
 *
 * Basic 可以预置（凭据是静态的）；Digest 不能（响应值依赖服务端每次给的 nonce），
 * 但**可以把 nonce 缓存下来**，在同一个 nonce 有效期内预置。"预置 → 401 → 用新挑战重算"
 * 这条路径同时覆盖两种情况，也是 [preemptiveHeader] 与 [authenticate] 分工的原因。
 *
 * ## 安全边界
 * 这里**不会**因为服务端要求就把 Basic 发到明文 HTTP 上：调用方
 * （[WebDavClientFactory]）保证只有 `https` 或"用户明确勾了允许明文"时才启用凭据。
 * 见 [WebDavAuth] 的 `allowInsecure` 参数。
 */
internal class WebDavAuth(
    private val username: String,
    private val password: String,
    /** 允许在明文 HTTP 上发送 Basic 凭据。默认关——局域网内也是明文可嗅探的。 */
    private val allowInsecure: Boolean,
) : Authenticator {

    private val lock = Any()

    /** 最近一次解析到的 Digest 挑战。为空表示还没遇到过 Digest。 */
    private var challenge: DigestChallenge? = null

    private val nonceCounter = AtomicInteger(0)

    private val random = SecureRandom()

    /** 预置认证头；返回 `null` 表示"这次请求不带凭据，等着被挑战"。 */
    fun preemptiveHeader(method: String, url: String, secure: Boolean): String? {
        if (username.isEmpty()) return null
        if (!secure && !allowInsecure) return null
        val current = synchronized(lock) { challenge }
        return if (current != null) {
            // 有 Digest 挑战时优先用 Digest：同一个服务端上两种都用没有意义，
            // 而 Digest 是服务端主动要求的，说明它不接受 Basic。
            runCatching { digestHeader(method, url, current, nonceCounter.incrementAndGet()) }
                .getOrNull()
                ?: basicHeader()
        } else {
            basicHeader()
        }
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // 防重试死循环：OkHttp 会对每个 401 反复调用本方法，若我们每次都返回新请求，
        // 就成了无限循环。用"这条响应链上已经试过几次"来截断。
        if (response.retryCount() >= MAX_ATTEMPTS) {
            MimiLog.w(TAG, "auth gave up after ${response.retryCount()} attempts")
            return null
        }
        if (username.isEmpty()) return null

        val url = response.request.url.toString()
        val secure = response.request.url.isHttps
        if (!secure && !allowInsecure) {
            MimiLog.w(TAG, "refusing to send credentials over plain HTTP")
            return null
        }

        val methods = response.headers("WWW-Authenticate")
        val digestRaw = methods.firstOrNull { it.startsWith(DIGEST, ignoreCase = true) }
        if (digestRaw != null) {
            val parsed = DigestChallenge.parse(digestRaw) ?: return null
            synchronized(lock) {
                challenge = parsed
                // RFC 7616 要求 `nc` 对每个 nonce 独立从 1 开始计数。
                // 换个 nonce 还沿用旧计数，有些服务端会判为"重放攻击"直接 401，
                // 而现象是"登录成功但偶尔随机失败"——这类 bug 极难复现。
                nonceCounter.set(0)
            }
            val header = runCatching {
                digestHeader(
                    method = response.request.method,
                    url = url,
                    challenge = parsed,
                    nc = nonceCounter.incrementAndGet(),
                )
            }.getOrElse {
                MimiLog.w(TAG, "digest header failed: ${it.message}")
                return null
            }
            return response.request.newBuilder()
                .removeHeader(HEADER_AUTH)
                .header(HEADER_AUTH, header)
                .build()
        }

        // 服务端只给了 Basic（或没给任何我们认识的方式）
        if (methods.any { it.startsWith(BASIC, ignoreCase = true) } || methods.isEmpty()) {
            synchronized(lock) { challenge = null }
            return response.request.newBuilder()
                .removeHeader(HEADER_AUTH)
                .header(HEADER_AUTH, basicHeader())
                .build()
        }

        return null
    }

    /** 是否已经成功协商过 Digest（供调用方在日志里说明"用的是哪种认证"）。 */
    fun isDigesting(): Boolean = synchronized(lock) { challenge != null }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun basicHeader(): String = Credentials.basic(username, password)

    /**
     * 计算 Digest 响应值。
     *
     * 严格按 RFC 7616 §3.4：
     * ```
     * HA1 = H(username:realm:password)                       // 或 MD5-sess 时再套一层
     * HA2 = H(method:uri)                                    // qop=auth 时不含 body
     * response = H(HA1:nonce:nc:cnonce:qop:HA2)              // qop 存在时
     * response = H(HA1:nonce:HA2)                            // qop 缺失（RFC 2069 兼容）
     * ```
     */
    private fun digestHeader(
        method: String,
        url: String,
        challenge: DigestChallenge,
        nc: Int,
    ): String {
        val algorithm = challenge.algorithm ?: "MD5"
        val digestName = when {
            algorithm.startsWith("SHA-256", ignoreCase = true) -> "SHA-256"
            algorithm.startsWith("SHA-512-256", ignoreCase = true) -> "SHA-512-256"
            else -> "MD5"
        }
        val uri = pathOf(url)
        val cnonce = newCnonce()
        val ncValue = "%08x".format(nc)

        var ha1 = hash(digestName, "$username:${challenge.realm}:$password")
        if (algorithm.endsWith("-sess", ignoreCase = true)) {
            ha1 = hash(digestName, "$ha1:${challenge.nonce}:$cnonce")
        }
        val ha2 = hash(digestName, "$method:$uri")

        val response = if (challenge.qop != null && challenge.qop.split(',').any { it.trim() == "auth" }) {
            hash(digestName, listOf(ha1, challenge.nonce, ncValue, cnonce, "auth", ha2).joinToString(":"))
        } else {
            // 没有 qop 时是 RFC 2069 的老式算法。服务端给 qop="auth-int" 时我们
            // 不支持（需要哈希整个请求体），此时按无 qop 计算——部分老服务端会接受，
            // 不接受就会再次 401，由 retryCount 截断，不会死循环。
            hash(digestName, "$ha1:${challenge.nonce}:$ha2")
        }

        return buildString(160) {
            append("Digest username=\"").append(username).append("\", ")
            append("realm=\"").append(challenge.realm).append("\", ")
            append("nonce=\"").append(challenge.nonce).append("\", ")
            append("uri=\"").append(uri).append("\", ")
            append("response=\"").append(response).append("\", ")
            append("algorithm=").append(algorithm)
            challenge.opaque?.let { append(", opaque=\"").append(it).append('"') }
            if (challenge.qop != null) {
                append(", qop=auth, nc=").append(ncValue).append(", cnonce=\"").append(cnonce).append('"')
            }
        }
    }

    private fun hash(algorithm: String, value: String): String {
        val md = MessageDigest.getInstance(algorithm)
        val bytes = md.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun newCnonce(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return "%016x".format(bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) })
    }

    /** Digest 的 `uri` 是**路径 + 查询串**，不含 scheme/host。 */
    private fun pathOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val hostStart = schemeEnd + 3
        val pathStart = url.indexOf('/', hostStart)
        return if (pathStart < 0) "/" else url.substring(pathStart)
    }

    /** 响应链上已经重试了几次（含本次）。OkHttp 不直接给这个数，只能自己数。 */
    private fun Response.retryCount(): Int {
        var count = 0
        var cursor: Response? = this
        while (cursor != null && count < MAX_ATTEMPTS + 2) {
            count++
            cursor = cursor.priorResponse
        }
        return count
    }

    private companion object {
        const val TAG = "MimiDav"
        const val HEADER_AUTH = "Authorization"
        const val BASIC = "Basic"
        const val DIGEST = "Digest"

        /** 最多试 2 次认证：一次 Basic 预置失败、一次 Digest 挑战。再多就是死循环。 */
        const val MAX_ATTEMPTS = 2
    }
}

/**
 * `WWW-Authenticate: Digest ...` 的解析结果。
 *
 * 手写解析而不是找库：这个头的格式简单到用几个 `indexOf` 就能吃下来，
 * 而引入一个 HTTP 认证库只为此一处，不划算。
 */
internal data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val opaque: String?,
    val algorithm: String?,
    val qop: String?,
) {
    companion object {

        private const val PREFIX = "Digest"

        /** 解析失败返回 `null`（调用方会退回 Basic 或直接放弃）。 */
        fun parse(header: String): DigestChallenge? {
            if (!header.startsWith(PREFIX, ignoreCase = true)) return null
            val body = header.substring(PREFIX.length).trim()
            val params = parseParams(body)
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            return DigestChallenge(
                realm = realm,
                nonce = nonce,
                opaque = params["opaque"],
                algorithm = params["algorithm"],
                qop = params["qop"],
            )
        }

        /**
         * 拆 `k=v, k="v", k="v,q"`。
         *
         * 关键点：**不能直接 `split(',')`**——`qop="auth,auth-int"` 的值里带逗号，
         * 那样拆会把 `auth-int"` 当成一个独立的键。所以要按引号状态扫描。
         */
        internal fun parseParams(input: String): Map<String, String> {
            val out = HashMap<String, String>(8)
            var i = 0
            val n = input.length
            while (i < n) {
                while (i < n && (input[i] == ',' || input[i].isWhitespace())) i++
                val keyStart = i
                while (i < n && input[i] != '=' && input[i] != ',') i++
                val key = input.substring(keyStart, i).trim().lowercase()
                if (i >= n || input[i] != '=') {
                    if (key.isEmpty()) break
                    out[key] = ""
                    continue
                }
                i++ // 跳过 '='
                val value = if (i < n && input[i] == '"') {
                    i++
                    val sb = StringBuilder()
                    while (i < n && input[i] != '"') {
                        if (input[i] == '\\' && i + 1 < n) i++
                        sb.append(input[i])
                        i++
                    }
                    i++ // 跳过收尾引号
                    sb.toString()
                } else {
                    val start = i
                    while (i < n && input[i] != ',') i++
                    input.substring(start, i).trim()
                }
                if (key.isNotEmpty()) out[key] = value
            }
            return out
        }
    }
}
