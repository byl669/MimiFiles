package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.network.PrivateNetworkProxy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 构建 WebDAV 用的 HTTP 客户端。
 *
 * ## 认证怎么接进去
 * 关键技巧是 **`OkHttpSardine` 有一个接受 `OkHttpClient` 的构造器**。我们把配好
 * 认证的 client 交给 sardine，于是：
 *  - sardine 的 `list`/`get`/`delete`/`move`/… 自动带上认证；
 *  - 我们自己发的**流式 PUT**用同一个 client，也自动带上认证。
 *
 * 如果反过来（让 sardine 自己用 `setCredentials` 建 client），我们就拿不到那个
 * 带认证的 client，上传就只剩 `put(File)` 这条"先落临时文件"的路——大文件会吃满手机存储。
 *
 * ## 自签证书：按指纹信任，不做 TrustAll
 * 群晖的 WebDAV Server、自建 Nextcloud 大多用自签证书。常见做法是
 * `TrustManager` 全部放行（`checkServerTrusted` 空实现），但那等于**关掉了 TLS 的
 * 身份验证**——同网段的攻击者可以拿任意证书做中间人，而用户的账号密码会直接送出去。
 *
 * 这里改成指纹钉扎：用户从浏览器里看到证书指纹并填进连接配置，我们只对
 * **恰好是那张证书**放行。换证书（包括中间人换证书）会立刻握手失败。
 */
internal object WebDavClientFactory {

    private const val TAG = "MimiDav"

    /**
     * @param auth 认证器；`null` 表示匿名（不发任何 `Authorization`）
     */
    fun create(settings: WebDavSettings, auth: WebDavAuth?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // 连接超时短、读超时长：WebDAV 的 PROPFIND 在大目录上可能跑很久，
            // 但"连不上"必须快速失败，否则用户要盯着转圈等半分钟。
            .connectTimeout(settings.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            // 不跟随重定向：WebDAV 的 MOVE/COPY 语义依赖精确的目标 URL，
            // 自动跟随会把请求打到意料之外的位置（有些服务端会 301 到另一个协议端口）。
            .followRedirects(false)
            .followSslRedirects(false)
            // 私网直连、公网跟随系统代理。
            //
            // 不加这一句的话，开发机上实测到的现象会原样发生在用户家里：
            // 公司电脑开着系统代理（PAC/环境变量），回家连 NAS 的 WebDAV，
            // 请求先被送去公司代理 → 必然失败，而连接配置每一项都是对的。
            // 详见 [PrivateNetworkProxy] 的说明。
            .proxySelector(PrivateNetworkProxy.selector())

        if (auth != null) {
            builder.addInterceptor(PreemptiveAuthInterceptor(auth))
            builder.authenticator(auth)
        }

        settings.trustFingerprint?.let { fingerprint ->
            // 指纹已归一化为"大写无冒号"，这里再校验一次长度，免得用户填了半截
            // 导致一个永远匹配不上的钉扎（那时现象是"怎么都连不上"，很难归因）。
            if (fingerprint.length == FINGERPRINT_LENGTH) {
                installPinnedTrust(builder, fingerprint)
            } else {
                MimiLog.w(
                    TAG,
                    "ignoring malformed certificate fingerprint (length=${fingerprint.length}, " +
                        "expected $FINGERPRINT_LENGTH)",
                )
            }
        }

        return builder.build()
    }

    private fun installPinnedTrust(builder: OkHttpClient.Builder, fingerprint: String) {
        try {
            val delegate = systemTrustManager()
            val pinned = PinnedTrustManager(delegate, fingerprint)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<X509TrustManager>(pinned), SecureRandom())
            builder.sslSocketFactory(context.socketFactory, pinned)
            MimiLog.i(TAG, "certificate pinning enabled for ${fingerprint.take(16)}…")
        } catch (t: Throwable) {
            // 装不上就退回系统信任链：宁可连不上自签服务端（用户会看到明确的握手失败），
            // 也不要为了"能连上"而放宽校验。
            MimiLog.w(TAG, "failed to install pinned trust: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrThrow {
            IllegalStateException("no X509TrustManager in default TrustManagerFactory")
        }
    }

    private fun <T> List<T>.firstOrThrow(error: () -> Throwable): T =
        firstOrNull() ?: throw error()

    /**
     * 每个请求补一个预置的 `Authorization` 头。
     *
     * 为什么放在 interceptor 而不是只在 `Authenticator` 里做：见 [WebDavAuth] 的注释——
     * `Authenticator` 只在 401 之后被调用，光靠它会让每个请求都多一轮往返。
     */
    private class PreemptiveAuthInterceptor(private val auth: WebDavAuth) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val header = auth.preemptiveHeader(
                method = request.method,
                url = request.url.toString(),
                secure = request.url.isHttps,
            )
            val outgoing = if (header == null) {
                request
            } else {
                request.newBuilder().header("Authorization", header).build()
            }
            return chain.proceed(outgoing)
        }
    }

    /**
     * 指纹钉扎的 TrustManager。
     *
     * 判定顺序刻意是"**先系统链，后指纹**"而不是反过来：
     *  - 系统链通过 → 这是一张受信任的证书，正常放行（此时钉扎形同没配，不构成负担）；
     *  - 系统链失败但指纹一致 → 放行（自签场景）；
     *  - 都不行 → 抛 [CertificateException]。
     *
     * 反过来写（指纹优先、命中就直接放行）会有个隐患：用户换了证书但没更新配置时，
     * 旧指纹会一直放行旧证书。虽然实际影响很小，但"证书校验的逻辑越简单越好"。
     */
    private class PinnedTrustManager(
        private val delegate: X509TrustManager,
        private val expectedFingerprint: String,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                delegate.checkServerTrusted(chain, authType)
                return
            } catch (_: CertificateException) {
                // 落到指纹比对
            }
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("empty certificate chain")
            val actual = sha256Of(leaf)
            if (actual != expectedFingerprint) {
                throw CertificateException(
                    "certificate fingerprint mismatch: expected ${expectedFingerprint.take(16)}…, " +
                        "got ${actual.take(16)}…",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        private fun sha256Of(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return digest.joinToString("") { "%02X".format(it) }
        }
    }

    /**
     * SNI 用的地址解析。
     *
     * 保留成显式实现（而不是删掉）：OkHttp 默认会用系统 DNS，在部分国内网络下
     * 解析 `*.myqnapcloud.com` 这类域名会超时。将来若遇到用户反馈"填域名连不上、
     * 填 IP 能连"，就是这里需要接自定义解析的入口。
     */
    internal fun resolveAddress(host: String, port: Int): List<InetAddress> =
        runCatching { InetAddress.getAllByName(host).toList() }
            .getOrDefault(emptyList())

    /** 供测试与排障：把一个 socket 的证书指纹打出来（不会在正常路径上调用）。 */
    internal fun peekFingerprint(socket: Socket): String? = runCatching {
        val ssl = socket as? SSLSocket ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(ssl.session.peerCertificates.first().encoded)
        digest.joinToString("") { "%02X".format(it) }
    }.getOrNull()

    private const val READ_TIMEOUT_MS = 30_000L
    private const val WRITE_TIMEOUT_MS = 60_000L

    /** SHA-256 十六进制串长度。 */
    private const val FINGERPRINT_LENGTH = 64
}
