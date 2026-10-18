package com.miyuetech.mimifiles.core.update

import com.miyuetech.mimifiles.core.common.MimiLog
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 更新链路的网络传输：抓清单、下包。
 *
 * ## 零遥测
 * 两个请求都**不带任何参数、不带设备标识、不写 `User-Agent` 里的个人信息**，
 * 只有一次普通的静态文件 GET。"检查更新"这件事没有任何理由让服务端知道是谁在问。
 *
 * ## 为什么不用 OkHttp
 * 更新链路只有两个短线请求。为此引一个 HTTP 客户端（+ 它的连接池、拦截器体系、
 * 以及 R8 要保留的一大堆反射入口）不值得 —— 而本项目的核心需求里
 * "体积"虽然已经不是门槛，但"少一个供给链面"仍然是收益。平台自带的
 * `HttpURLConnection` 对"GET 一个静态文件"完全够用。
 */
internal object UpdateTransport {

    private const val TAG = "MimiUpdate"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** 下载的读超时给得更宽：慢速上行网络里一个 6 MB 的包可能读得比较慢。 */
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * 单个安装包的大小上限。
     *
     * 本应用的 release 包约 6 MB。设 200 MB 是**防止一份坏清单把手机流量打光**：
     * `sha256` 校验能拦住"内容不对"，但拦不住"内容很大"——那时流量已经花掉了。
     * 只在清单**声明了** size 时才用它；声明为 0（未知）时靠下载过程中的硬上限兜。
     */
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    // ── 清单 ───────────────────────────────────────────────────────────────

    /**
     * 拉取清单文本。
     *
     * ## 必须带 `no-cache`
     * 9998 端口上是一个静态文件服务。静态服务的默认行为是允许缓存，而缓存对
     * **恰好这一条 URL**（清单地址永远不变）是致命的：浏览器/中间层的缓存会让
     * 用户永远看到上一次的清单，也就是"新版本发布了但没人能升上去"。
     * `Cache-Control: no-cache` 是让服务端跳过缓存、每次都回源。
     */
    fun fetchManifestText(manifestUrl: String): String {
        val conn = open(manifestUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).apply {
            setRequestProperty("Cache-Control", "no-cache")
            // 老代理只认 Pragma。两个都带上，代价为零。
            setRequestProperty("Pragma", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw UpdateException(httpMessage(code, manifestUrl))
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: UpdateException) {
            throw e
        } catch (t: Throwable) {
            throw UpdateException(readableNetworkReason(t), t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ── 下载 ───────────────────────────────────────────────────────────────

    /**
     * 流式下载到 [target]，边下边报进度。
     *
     * ## 为什么要 `.part` 中转
     * 直接写目标文件的话，中途断网会留下一个"看起来存在但内容不全"的 apk。
     * 而 `verify()` 的第一件事是算 sha256 —— 它会正确地拒绝这个文件，但**报的是
     * "SHA-256 不符"**，指向"传输损坏"，而真实原因可能只是"上次下载没下完"。
     * 用 `.part` 中转再原子改名，目标文件就永远要么不存在、要么完整。
     *
     * ## 为什么不做断点续传
     * 包只有 6 MB 左右，而续传要引入"上次的 part 还能不能用"这一整类判断
     * （服务端换了包怎么办、ETag 怎么存、part 是谁写的）。这点收益换不来那类风险，
     * 所以每次下载都从头开始，并在开始前清掉旧 part。
     *
     * @param expectedSize 清单声明的字节数；0 表示未知（不参与判断，只影响进度算法）
     */
    fun download(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
    ): File {
        if (expectedSize > MAX_APK_BYTES) {
            throw UpdateException("清单声明的安装包过大（${expectedSize / 1024 / 1024} MB），已中止")
        }

        val dir = target.parentFile ?: throw UpdateException("下载目录不可用")
        if (!dir.exists() && !dir.mkdirs()) {
            throw UpdateException("无法创建下载目录：$dir")
        }
        // 清掉所有旧残留（包括上一次没下完的 part）
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val part = File(dir, "${target.name}.part")
        val conn = open(url, CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw UpdateException(httpMessage(code, url))
            }

            // 优先信服务端给的 Content-Length（清单可能没写 size，也可能写错）。
            val declared = conn.contentLengthLong
            val total = when {
                declared > 0 -> declared
                expectedSize > 0 -> expectedSize
                else -> 0L
            }
            if (total > MAX_APK_BYTES) {
                throw UpdateException("服务端返回的安装包过大（${total / 1024 / 1024} MB），已中止")
            }

            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) {
                            throw UpdateException("下载内容超过大小上限，已中止（可能是清单或服务端异常）")
                        }
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            // 只在整数百分比变化时上报：一次下载会读上千个 64 KB 块，
                            // 每块都推一次状态会让 UI 线程被无意义的重组淹没。
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            onProgress(100)
        } catch (e: UpdateException) {
            runCatching { part.delete() }
            throw e
        } catch (t: Throwable) {
            runCatching { part.delete() }
            throw UpdateException(readableNetworkReason(t), t)
        } finally {
            runCatching { conn.disconnect() }
        }

        if (part.length() <= 0L) {
            runCatching { part.delete() }
            throw UpdateException("下载到的文件是空的")
        }
        if (!part.renameTo(target)) {
            runCatching { part.delete() }
            throw UpdateException("下载完成但重命名失败")
        }
        MimiLog.i(TAG, "更新包下载完成：${target.name} ${target.length()} bytes")
        return target
    }

    // ── 公共 ───────────────────────────────────────────────────────────────

    private fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection =
        try {
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                // 注意参数名不能叫 `connectTimeout`：那样 `connectTimeout = connectTimeout`
                // 会被解析成"属性赋给自己"，而右侧的形参被彻底遮蔽 —— 编译期只报一句
                // `'val' cannot be reassigned`（`requestMethod` 那行才是真正的只读属性），
                // 排查时很容易看错地方。
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // `requestMethod` 是**只读属性**（只有 getter），必须用这个 setter。
                setRequestMethod("GET")
                instanceFollowRedirects = true
                // 显式设成不缓存：`HttpURLConnection` 默认的 `useCaches=true`
                // 会在**同一进程内**复用响应（我们的应用进程可能活很久，
                // 期间用户点两次检查更新，第二次就该拿到新清单）。
                useCaches = false
            }
        } catch (t: Throwable) {
            throw UpdateException(readableNetworkReason(t), t)
        }

    private fun httpMessage(code: Int, url: String): String = when (code) {
        403, 404 -> "服务器上没有这个文件（HTTP $code）。可能是新版本刚发布、文件还没传完，稍后再试"
        401 -> "服务器拒绝了访问（HTTP 401）"
        in 500..599 -> "服务器出错（HTTP $code）"
        else -> "服务器返回了意外状态（HTTP $code）"
    }

    /**
     * 把网络异常翻成"用户能照做的话"。
     *
     * ## 为什么这一步不能省
     * `UnknownHostException: Unable to resolve host "update.example.com"` 这种消息
     * 直接显示给用户，等于什么也没说 —— 它既没说是谁的问题，也没说能做什么。
     * 而这些异常的类型**恰好足够区分**常见原因（断网 / DNS / 被防火墙拦 / 证书），
     * 所以翻一遍的成本很低，收益是"用户看一眼就知道要不要开 WiFi"。
     */
    private fun readableNetworkReason(t: Throwable): String = when (t) {
        is UnknownHostException ->
            "无法解析更新服务器地址，请检查网络连接"
        is SocketTimeoutException ->
            "连接更新服务器超时，请检查网络后重试"
        is ConnectException ->
            "无法连接更新服务器（连接被拒绝）"
        is SSLException ->
            "与更新服务器的安全连接失败：${t.message ?: "TLS 握手错误"}"
        is IOException ->
            "网络错误：${t.message ?: t.javaClass.simpleName}"
        else ->
            t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }
}
