package com.miyuetech.mimifiles.core.shareserver.http

import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.fs.FsNotSupportedException
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.shareserver.AuthMode
import com.miyuetech.mimifiles.core.shareserver.RelPath
import com.miyuetech.mimifiles.core.shareserver.ServerConfig
import com.miyuetech.mimifiles.core.shareserver.ShareScope
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

/**
 * HTTP 分享的协议实现：URI → 文件系统动作。
 *
 * ## 线程模型：为什么这里可以放心 `runBlocking`
 * NanoHTTPD 每来一个连接就起一个线程（`DefaultAsyncRunner`），而 `serve()` 就在
 * **那个连接自己的线程**上被调用。也就是说，在 `serve` 里阻塞只影响这一个连接，
 * 不会拖住别人。既有的 [com.miyuetech.mimifiles.core.fs.FileSystem] 是 suspend 的，
 * 与之对接最省事、也最不容易写错的做法就是 `runBlocking`——而不是把整条
 * 文件访问链路再包一层"回调转 Future"的适配器（那是纯增复杂度）。
 *
 * ## 关于 Gzip 与 Range 的相互干扰（一个真实的坑）
 * NanoHTTPD 的 `useGzipWhenAccepted()` 对 `text/*`、`*/json` 会自动开 gzip。
 * 但 gzip 会**改变字节**：一旦对 `text/plain` 的 Range 响应做了压缩，
 * 我们返回的 `Content-Range: bytes 100-199/total` 就与客户端收到的字节完全对不上，
 * 视频/文本拖动会花屏或错位。所以**凡是我们自己管字节范围的响应，
 * 都显式 `addHeader("Content-Length", …)`** —— NanoHTTPD 见到这个头就关掉 gzip。
 *
 * ## 关于 `+` 被当成空格
 * NanoHTTPD 用 `URLDecoder` 解 URI 与查询串，它把 `+` 解成空格。这是 HTTP 表单编码
 * 的规则，对**路径**并不适用。我们的网页用 `encodeURIComponent`（会把 `+` 编成 `%2B`），
 * 所以自洽；但用浏览器地址栏手敲一个含 `+` 的文件名会找不到——这是已知限制，
 * 写入 [ShareWebPage] 的注释里，不在这里做有损的"猜回来"。
 */
class HttpShareHandler(
    private val config: ServerConfig,
    private val scope: ShareScope,
    private val serverName: String,
    /**
     * 一次性会话令牌。二维码里带 `?t=<它>`，用于**扫码即用**（见
     * [com.miyuetech.mimifiles.core.shareserver.ServerState.Running.ticket]）。
     *
     * 为空表示不启用令牌路径（只认 Basic）——那样扫码会撞上登录框，
     * 但功能仍然正确，属于可接受的降级。
     */
    private val ticket: String? = null,
) {

    /** 入口。所有异常都在这里收口并翻成 HTTP 状态码。 */
    fun handle(session: IHTTPSession): Response = runBlocking { dispatch(session) }

    private suspend fun dispatch(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized()
        val uri = session.uri?.takeIf { it.isNotBlank() } ?: "/"
        val method = session.method ?: Method.GET
        val response = try {
            when {
                uri == "/" || uri == "/index.html" -> page()
                uri == "/favicon.ico" -> NanoHTTPD.newFixedLengthResponse(Status.NO_CONTENT, "text/plain", "")
                uri.startsWith("/api/") -> api(uri, method, session)
                uri.startsWith("/f/") -> serveFile(uri.substring(3), method, session, inline = true)
                uri.startsWith("/d/") -> serveFile(uri.substring(3), method, session, inline = false)
                else -> plain(Status.NOT_FOUND, "未找到：$uri")
            }
        } catch (e: FsNotSupportedException) {
            MimiLog.w(TAG, "写操作被拒（只读或后端不可写）：${e.message}")
            plain(Status.FORBIDDEN, "只读分享，不允许此操作")
        } catch (e: FsAccessDeniedException) {
            // 越狱与"不在可见范围"都落到这里。回一句"路径非法"等于告诉探测者
            // 他的语法已经被解析了，所以文案刻意与"不存在"一致。
            MimiLog.w(TAG, "拒绝访问：${e.message}")
            plain(Status.FORBIDDEN, "不允许访问")
        } catch (e: FsNotFoundException) {
            plain(Status.NOT_FOUND, "文件不存在")
        } catch (e: FsException) {
            MimiLog.w(TAG, "文件操作失败：${e.message}")
            plain(Status.INTERNAL_ERROR, "读取失败：${e.message}")
        } catch (e: java.io.IOException) {
            // 客户端中途断开（拖进度条时非常常见）不该刷成 ERROR，但要留痕。
            MimiLog.d(TAG, "连接中断：${e.message}")
            plain(Status.INTERNAL_ERROR, "连接中断")
        } catch (e: Exception) {
            MimiLog.e(TAG, "处理请求失败：${session.uri}", e)
            plain(Status.INTERNAL_ERROR, "服务器内部错误")
        }

        // 令牌校验通过 → 下发会话 Cookie。
        //
        // 这一步是"扫码即用"能成立的**关键**：扫码后浏览器发出的第一个请求带的是
        // `?t=<token>`，而页面里的 `fetch('/api/list')` 是**新的请求**，不带那个查询串。
        // 没有 Cookie 的话，扫码进来只能看到外壳，列表全是 401。
        //
        // 放在这里而不是 [page] 里：用户可能直接把带令牌的地址粘到某个子路径上，
        // 任何一次带令牌的请求都该把会话建立起来。
        if (ticket != null && session.parameters?.get("t")?.any { it == ticket } == true) {
            runCatching { session.cookies?.set(COOKIE_NAME, ticket, COOKIE_DAYS) }
        }
        return response
    }

    // ── 页面 ────────────────────────────────────────────────────────────

    private suspend fun page(): Response {
        val count = runCatching { scope.list(RelPath.ROOT)?.size ?: 0 }.getOrDefault(0)
        val html = ShareWebPage.render(
            serverName = serverName,
            root = scope.root.toString(),
            readOnly = !scope.writable,
            itemCount = count,
        )
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", html)
    }

    // ── JSON 接口 ───────────────────────────────────────────────────────

    private suspend fun api(uri: String, method: Method, session: IHTTPSession): Response = when (uri) {
        "/api/info" -> json(infoJson())

        "/api/list" -> {
            val rel = param(session, "path").orEmpty()
            val nodes = scope.list(rel) ?: return plain(Status.NOT_FOUND, "路径不存在")
            val arr = JSONArray()
            for (n in nodes) {
                arr.put(
                    JSONObject()
                        .put("name", n.name)
                        .put("rel", n.rel)
                        .put("dir", n.isDirectory)
                        .put("size", n.size)
                        .put("mtime", n.lastModified)
                        .put("mime", n.mimeType ?: MimeTypes.of(n.name)),
                )
            }
            json(
                JSONObject()
                    .put("path", RelPath.normalize(rel) ?: RelPath.ROOT)
                    .put("entries", arr)
                    .put("readOnly", !scope.writable),
            )
        }

        "/api/mkdir" -> requirePost(method) {
            val parent = param(session, "path").orEmpty()
            val name = param(session, "name")?.trim().orEmpty()
            if (name.isEmpty()) return@requirePost plain(Status.BAD_REQUEST, "缺少目录名")
            val created = scope.mkdir(parent, name)
            json(okJson("已新建", created))
        }

        "/api/rename" -> requirePost(method) {
            val rel = param(session, "path") ?: return@requirePost plain(Status.BAD_REQUEST, "缺少 path")
            val name = param(session, "name")?.trim().orEmpty()
            if (name.isEmpty()) return@requirePost plain(Status.BAD_REQUEST, "缺少新名称")
            val newRel = scope.rename(rel, name)
            json(okJson("已重命名", newRel))
        }

        "/api/move" -> requirePost(method) {
            val rel = param(session, "path") ?: return@requirePost plain(Status.BAD_REQUEST, "缺少 path")
            val dir = param(session, "dir").orEmpty()
            json(okJson("已移动", scope.move(rel, dir)))
        }

        "/api/delete" -> requirePost(method) {
            val rel = param(session, "path") ?: return@requirePost plain(Status.BAD_REQUEST, "缺少 path")
            val recursive = param(session, "recursive") == "1" || param(session, "recursive") == "true"
            scope.delete(rel, recursive)
            json(okJson("已删除", rel))
        }

        else -> plain(Status.NOT_FOUND, "未知接口：$uri")
    }

    private fun infoJson(): JSONObject = JSONObject()
        .put("name", serverName)
        .put("type", "HTTP")
        .put("root", scope.root.toString())
        .put("readOnly", !scope.writable)
        .put("auth", config.auth.describe)
        .put("version", APIVERSION)

    // ── 文件（含 Range 206）─────────────────────────────────────────────

    private suspend fun serveFile(
        relRaw: String,
        method: Method,
        session: IHTTPSession,
        inline: Boolean,
    ): Response {
        when (method) {
            Method.GET, Method.HEAD -> Unit
            Method.PUT -> return upload(relRaw, session)
            Method.DELETE -> {
                scope.delete(relRaw, recursive = true)
                return json(okJson("已删除", relRaw))
            }

            else -> return plain(Status.METHOD_NOT_ALLOWED, "不支持的方法：$method")
        }

        val node = scope.stat(relRaw) ?: return plain(Status.NOT_FOUND, "文件不存在")
        if (node.isDirectory) return plain(Status.NOT_FOUND, "这是一个目录")

        val mime = node.mimeType ?: MimeTypes.of(node.name)
        val total = node.size

        // 大小未知（极少数后端）时退化成"整段流"，不开 Range——给出错误的
        // Content-Range 比不支持 Range 更糟（客户端会按错位解析）。
        if (total < 0) {
            val src = scope.openRead(relRaw) ?: return plain(Status.NOT_FOUND, "文件不存在")
            val head = NanoHTTPD.newFixedLengthResponse(
                Status.OK,
                mime,
                if (method == Method.HEAD) null else SourceStream(src, 0L),
                if (method == Method.HEAD) 0L else -1L,
            )
            return head.apply {
                addHeader("Accept-Ranges", "none")
                if (!inline) addHeader("Content-Disposition", disposition(node.name))
            }
        }

        val header = session.headers?.get("range")
        val range = RangeSpec.parse(header, total)
        if (range is RangeSpec.Unsatisfiable) {
            return plain(Status.RANGE_NOT_SATISFIABLE, "请求的范围无法满足").apply {
                addHeader("Content-Range", "bytes */$total")
                addHeader("Accept-Ranges", "bytes")
            }
        }

        val start: Long
        val length: Long
        val status: Status
        if (range is RangeSpec.Satisfiable) {
            start = range.start
            length = range.endInclusive - range.start + 1
            status = Status.PARTIAL_CONTENT
        } else {
            start = 0L
            length = total
            status = Status.OK
        }

        // HEAD 不返回 body。NanoHTTPD 2.3.1 在 HEAD 下仍会把 data 流写完
        // （`sendBody` 没有判 Method.HEAD），所以这里必须给 null 的 data
        // 并显式写 Content-Length，否则一次"探测文件大小"的 HEAD 会白读整个文件。
        val body: InputStream? = if (method == Method.HEAD) {
            null
        } else {
            val src = scope.openRead(relRaw) ?: return plain(Status.NOT_FOUND, "文件不存在")
            SourceStream(src, start)
        }

        return NanoHTTPD.newFixedLengthResponse(status, mime, body, if (method == Method.HEAD) 0L else length)
            .apply {
                // 显式写 Content-Length：既让 HEAD 正确，也顺手关掉 NanoHTTPD 的
                // 自动 gzip（见类注释"关于 Gzip 与 Range"）。
                addHeader("Content-Length", length.toString())
                addHeader("Accept-Ranges", "bytes")
                if (status == Status.PARTIAL_CONTENT) {
                    addHeader("Content-Range", "bytes $start-${start + length - 1}/$total")
                }
                if (!inline) addHeader("Content-Disposition", disposition(node.name))
                // 分享服务端的内容随时可能在手机上被改动，让中间任何缓存都别留档。
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
            }
    }

    /** `PUT /f/<rel>`：上传。裸 body、流式落盘，不经过临时文件。 */
    private suspend fun upload(relRaw: String, session: IHTTPSession): Response {
        val declared = session.headers?.get("content-length")?.toLongOrNull()
        val input = session.inputStream ?: return plain(Status.BAD_REQUEST, "无法读取请求体")

        var sink: com.miyuetech.mimifiles.core.fs.WriteSink? = null
        return try {
            sink = scope.openWrite(relRaw, declared ?: -1L)
            val out = sink.stream()
            val buffer = ByteArray(UPLOAD_BUFFER)
            var written = 0L
            while (true) {
                val n = if (declared != null) {
                    if (written >= declared) break
                    input.read(buffer, 0, minOf(buffer.size.toLong(), declared - written).toInt())
                } else {
                    input.read(buffer)
                }
                if (n < 0) break
                if (n == 0) continue
                out.write(buffer, 0, n)
                written += n
            }
            out.flush()
            MimiLog.i(TAG, "上传完成：${session.uri} ($written 字节)")
            json(
                JSONObject()
                    .put("ok", true)
                    .put("message", "已上传")
                    .put("path", RelPath.normalize(relRaw) ?: RelPath.ROOT)
                    .put("size", written),
            )
        } catch (e: Exception) {
            // 上传中断会留下半个文件。**刻意不删**：目标可能原本就存在，
            // 我们无法区分"新建失败"与"覆盖失败"，删掉等于把用户原有的文件带走。
            // HTTP PUT 与 FTP STOR 的标准语义也都是"失败后可能留下不完整文件"，
            // 客户端（我们的网页）会提示失败并允许重传。
            MimiLog.w(TAG, "上传失败：${session.uri} —— ${e.message}")
            throw e
        } finally {
            runCatching { sink?.close() }
        }
    }

    // ── 认证 ────────────────────────────────────────────────────────────

    /**
     * 三种通过方式，按"最省事"排序：
     *  1. 匿名模式 → 直接过；
     *  2. **会话 Cookie** → 页面里的 `fetch('/api/list')` 走这条（同源请求自动带 Cookie）；
     *  3. **`Authorization: Basic`** → 用户手动在浏览器登录框里输了账号密码时走这条。
     *
     * 扫码进来的第一次请求带的是 `?t=<token>`（在 [page] 里落地成 Cookie），
     * 所以"扫码即用"的完整路径是：扫码 → 带令牌的 GET / → 下发 Cookie →
     * 页面里的所有后续请求靠 Cookie 通过。
     */
    private fun authorized(session: IHTTPSession): Boolean {
        val mode = config.auth
        if (mode is AuthMode.Anonymous) return true

        // 令牌路径。只在配置了令牌时启用；用定长比较避免时序侧信道。
        val presented = ticket?.let { expected ->
            session.parameters?.get("t")?.firstOrNull { it != null }?.takeIf {
                HttpAuth.constantTimeEquals(it, expected)
            }
        }
        if (presented != null) return true

        val cookie = session.cookies?.read(COOKIE_NAME)
        if (cookie != null && ticket != null && HttpAuth.constantTimeEquals(cookie, ticket)) return true

        val raw = session.headers?.get("authorization") ?: return false
        val decoded = decodeBasic(raw) ?: return false
        return HttpAuth.verify(decoded, mode)
    }

    private fun decodeBasic(header: String): String? {
        if (!header.startsWith("Basic ", ignoreCase = true)) return null
        val payload = header.substring(6).trim()
        return try {
            String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun unauthorized(): Response {
        val body = "需要登录（账号密码见手机上的分享面板）"
        return NanoHTTPD.newFixedLengthResponse(Status.UNAUTHORIZED, "text/plain; charset=utf-8", body).apply {
            addHeader("WWW-Authenticate", "Basic realm=\"${config.realm}\", charset=\"UTF-8\"")
            addHeader("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString())
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    /**
     * 取查询参数。
     *
     * NanoHTTPD 的 `getParameters()` 在"键存在但没值"时会塞一个 `null` 进列表
     * （`?path=` 就是这种情况），所以这里要过滤掉 null，否则返回类型里会冒出
     * 一个伪装成非空的 null，到 `RelPath.normalize` 里才炸。
     */
    private fun param(session: IHTTPSession, name: String): String? =
        session.parameters?.get(name)?.firstOrNull { it != null }

    private inline fun requirePost(method: Method, block: () -> Response): Response {
        if (method != Method.POST && method != Method.PUT) return plain(Status.METHOD_NOT_ALLOWED, "请用 POST")
        return block()
    }

    private fun okJson(message: String, rel: String): JSONObject =
        JSONObject().put("ok", true).put("message", message).put("path", rel)

    private fun json(obj: JSONObject): Response {
        val body = obj.toString()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", body)
            .apply { addHeader("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString()) }
    }

    private fun plain(status: Status, message: String): Response {
        val body = message
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", body)
            .apply { addHeader("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString()) }
    }

    /**
     * `Content-Disposition`。
     *
     * 同时给 `filename=`（ASCII 退化）与 `filename*=`（RFC 5987，UTF-8 百分号编码）：
     * 只给后者时老客户端会得到一个没有文件名的下载；只给前者时中文名会变乱码。
     * 两者都给，现代浏览器优先用 `filename*`，老的回落到 `filename=`。
     */
    private fun disposition(fileName: String): String {
        val ascii = fileName.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }
            .joinToString("")
        val encoded = fileName.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() || c in "-_.~") c.toString()
            else "%" + "%02X".format(b.toInt() and 0xFF)
        }
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    /**
     * 把 [SeekableSource] 暴露成 [InputStream]，并在流关闭时**一并关闭源**。
     *
     * NanoHTTPD 在写完 body 后会 `safeClose(this.data)`，所以这是资源释放的
     * 唯一时机；`SeekableSource.stream()` 契约上**不**关闭底层（那样调用方就没法
     * 再对同一个源做第二次 Range 请求），因此必须在这里补上。
     */
    private class SourceStream(private val source: SeekableSource, position: Long) : InputStream() {

        private val delegate = source.stream(position)

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun skip(n: Long): Long = delegate.skip(n)

        override fun available(): Int = delegate.available()

        override fun close() {
            runCatching { delegate.close() }
            runCatching { source.close() }
        }
    }

    companion object {
        private const val TAG = "MimiShareHttp"
        private const val APIVERSION = 1
        private const val UPLOAD_BUFFER = 64 * 1024

        /** 会话 Cookie 名。带 `mimi_` 前缀，避免与用户在同一主机上别的服务的 Cookie 撞名。 */
        const val COOKIE_NAME = "mimi_t"

        /**
         * Cookie 有效期（天）。
         *
         * 取 1 天而不是"会话级"：NanoHTTPD 的 `CookieHandler.set` 只接受"天数"，
         * 写 0 会得到一个立刻过期的 Cookie（等于没设）。而分享服务端的实际寿命
         * 由会话有效期（默认 5 分钟）与进程决定，**远短于** 1 天，
         * 所以这个偏长的值不会带来额外风险；反过来，若浏览器重启导致 Cookie 丢失，
         * 用户重扫一次即可。
         */
        const val COOKIE_DAYS = 1
    }
}

/**
 * `Range: bytes=…` 的解析（RFC 7233）。
 *
 * 三种形态：
 * | 形态 | 含义 |
 * |---|---|
 * | `bytes=100-199` | 闭区间，两端都含 |
 * | `bytes=100-` | 从 100 到文件末尾 |
 * | `bytes=-500` | **最后** 500 字节（这是最容易写反的一条：`-500` 不是"从 -500 开始"） |
 *
 * 非法语法按 RFC 要求**忽略**（当作无 Range 处理，返回 200 全文）而不是报错——
 * 报错会让"某个客户端偶尔发了个奇怪的头"变成"视频完全播不了"。
 * 只有语法合法但范围落在文件之外时才 416。
 */
sealed interface RangeSpec {

    data class Satisfiable(val start: Long, val endInclusive: Long) : RangeSpec

    data object Unsatisfiable : RangeSpec

    /** 没有 Range 头，或语法非法（按 RFC 忽略）。 */
    data object Ignored : RangeSpec

    companion object {

        fun parse(header: String?, total: Long): RangeSpec {
            if (header.isNullOrBlank()) return Ignored
            val trimmed = header.trim()
            if (!trimmed.startsWith("bytes=", ignoreCase = true)) return Ignored
            // 多段 Range（`bytes=0-99,200-299`）刻意只取第一段：返回 multipart/byteranges
            // 要拼 boundary、要精确算每段的头长度，而浏览器在播放媒体时**只发单段**。
            val spec = trimmed.substring(6).substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return Ignored
            val startRaw = spec.substring(0, dash).trim()
            val endRaw = spec.substring(dash + 1).trim()

            if (startRaw.isEmpty()) {
                // `-N`：最后 N 字节
                val suffix = endRaw.toLongOrNull() ?: return Ignored
                if (suffix <= 0) return Unsatisfiable
                if (total <= 0) return Unsatisfiable
                val start = (total - suffix).coerceAtLeast(0)
                return Satisfiable(start, total - 1)
            }

            val start = startRaw.toLongOrNull() ?: return Ignored
            if (start < 0) return Ignored
            if (start >= total) return Unsatisfiable
            val end = if (endRaw.isEmpty()) total - 1 else (endRaw.toLongOrNull() ?: return Ignored)
            if (end < start) return Unsatisfiable
            return Satisfiable(start, minOf(end, total - 1))
        }
    }
}

/**
 * HTTP Basic 的凭据校验 —— **独立成纯函数**，好在 JVM 单测里把
 * "冒号在密码里""空密码""用户名大小写"这些情况一条条钉死。
 */
object HttpAuth {

    /**
     * @param decoded 已 Base64 解码的 `user:password`（原始字节按 UTF-8 解释）
     */
    fun verify(decoded: String, mode: AuthMode): Boolean {
        val credentials = mode as? AuthMode.Credentials ?: return true
        val idx = decoded.indexOf(':')
        if (idx < 0) return false
        val user = decoded.substring(0, idx)
        val password = decoded.substring(idx + 1)
        // 两次比较都做完再合并，不要 `&&` 短路：短路会让"用户名错"比"密码错"
        // 稍快一点点地返回，理论上可被用来枚举用户名。局域网里这是吹毛求疵，
        // 但改正的代价是零，所以顺手改掉。
        return constantTimeEquals(user, credentials.user) and
            constantTimeEquals(password, credentials.password)
    }

    /** 定长比较。用 [java.security.MessageDigest.isEqual]，它在长度不等时也会遍历完。 */
    fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
