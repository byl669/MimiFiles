package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.FsNotSupportedException
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.network.NetworkDispatchers
import com.miyuetech.mimifiles.core.network.ParallelGate
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * WebDAV 客户端后端。
 *
 * ## 两套 HTTP 通道，各司其职
 * | 操作 | 走谁 | 为什么 |
 * |---|---|---|
 * | `PROPFIND`（列目录）、`MKCOL`、`DELETE`、`MOVE`、`COPY` | **sardine** | 207 Multi-Status 的 XML 解析很容易写错，sardine 已经处理过各家服务端的差异 |
 * | `GET` + `Range`（随机读） | **OkHttp 直连** | 需要看**状态码**判断服务端是否真的支持 Range（206 vs 200）；sardine 的 `get` 只返回流，丢掉了这个信息 |
 * | `PUT`（上传） | **OkHttp 直连** | sardine 0.9 没有 `InputStream` 版的 `put`，见 [WebDavWriteSink] |
 *
 * 三个通道用的是**同一个 `OkHttpClient`**，所以认证、超时、证书钉扎只配一遍。
 *
 * ## 服务端差异的收敛点
 * 方案第 5.3 节列的坑（`Depth: infinity` 的 DELETE、`MOVE` 的 `Overwrite`、
 * 中文路径编码）全部在本类内部消化，对外只表现为 [FsCapability] 与标准异常。
 * UI 不需要知道对面是群晖、坚果云还是 Nextcloud。
 */
class WebDavFileSystem internal constructor(
    private val profile: ConnectionProfile,
    private val password: String?,
    private val io: CoroutineDispatcher = NetworkDispatchers.io,
) : FileSystem {

    override val scheme: String = SCHEME

    override val displayName: String get() = profile.displayName

    /**
     * WebDAV 的能力集合。
     *
     * 没有 `SEEK` 的**条件申报**问题：我们总是返回可用的 [SeekableSource]（窗口缓冲），
     * 所以如实申报。真正"服务端不支持 Range"的情况在 [openRead] 时才会暴露，
     * 由 [WebDavSeekableSource] 决定是镜像还是明确报错。
     *
     * 没有 `WATCH`、没有 `THUMBNAIL`、没有 `SET_ATTRIBUTES`——WebDAV 都没有可靠对应物。
     */
    override val capabilities: Int = CAPABILITIES

    private val settings = WebDavSettings.parse(profile.options)

    private val baseUrl: String = WebDavPaths.baseUrl(profile)

    private val gate = ParallelGate(MAX_CONCURRENCY)

    private val clientLock = Mutex()

    private var clientRef: OkHttpClient? = null

    private var sardineRef: Sardine? = null

    @Volatile
    private var username: String? = profile.username?.takeIf { it.isNotBlank() }

    // ── 只读路径 ────────────────────────────────────────────────────────

    override suspend fun roots(): List<FsEntry> {
        val root = profile.rootPath.trim().trim('/')
        if (root.isEmpty()) return emptyList()
        return listOf(
            FsEntry(
                path = FsPath.of(scheme, profile.id, if (root.isEmpty()) "" else "/$root"),
                name = profile.displayName,
                kind = FsKind.DIRECTORY,
            ),
        )
    }

    override suspend fun list(dir: FsPath): List<FsEntry> = withContext(io) {
        val url = WebDavPaths.urlOf(baseUrl, dir, asDirectory = true)
        val resources = guard(dir) { sardine().list(url) }
        val out = ArrayList<FsEntry>(resources.size)
        for (resource in resources) {
            val entry = resource.toFsEntry() ?: continue
            // PROPFIND Depth:1 会把**被请求的那个集合自身**也放进结果里，
            // 不过滤的话列表里会多出一条"点进去还在原地"的项。
            if (entry.path == dir) continue
            out += entry
        }
        MimiLog.d(TAG, "list $dir -> ${out.size} items")
        out
    }

    override suspend fun stat(path: FsPath): FsEntry = withContext(io) {
        val url = WebDavPaths.urlOf(baseUrl, path, asDirectory = isProbablyDirectory(path))
        val resources = guard(path) {
            // Depth:0 只问自己。比 exists() + 一次 list 便宜一半。
            sardine().list(url)
        }
        val self = resources.firstOrNull { candidate ->
            WebDavPaths.segmentsOf(candidate.href?.toString().orEmpty(), baseUrl) == path.segments
        } ?: resources.firstOrNull()
        ?: throw FsNotFoundException(path)
        self.toFsEntry() ?: throw FsNotFoundException(path)
    }

    /**
     * 路径是否存在。
     *
     * ## 为什么**不用** `sardine().exists(...)`
     * sardine 内部发的是 `HEAD`，而实测的 WebDAV 服务端（wsgidav / Cheroot）会对 HEAD
     * **也回一个带 `Content-Length` 的 HTML body**：
     *
     * ```
     * HEAD /RenamedDir/  →  HTTP/1.1 404 Not Found
     *                       Content-Type: text/html
     *                       Content-Length: 393          ← 而且真的发了 393 字节 body
     * ```
     *
     * OkHttp 严格遵守规范、**对 HEAD 不读 body**，于是那 393 字节 HTML 会**残留在
     * keep-alive 连接上**，紧接着复用同一条连接的下一个请求就从这段 HTML 开始解析响应：
     *
     * ```
     * IOException: Unexpected status line: <!DOCTYPE HTML PUBLIC '-//W3C//DTD HTML 4.01//EN' …
     * ```
     *
     * 触发链极其具体，也因此极难归因：`FsOps.rename` 先 `exists(目标)`（HEAD 404）
     * 再 `MOVE`，两者复用同一条连接 —— 于是"重命名"在服务端**明明成功**
     * （201、宿主磁盘上旧名已消失新名已出现），应用却抛异常判成失败：列表不刷新、
     * 还弹一条"失败"提示；用户再点一次又会因"目标已存在"而**真的**失败。
     *
     * ## 改用 `PROPFIND Depth: 0`
     * 它本来才是 RFC 4918 里"查这个资源存不存在"的正道（HEAD 对集合的语义各家实现
     * 并不一致），而且响应**有** body，会被正常读走，不会在连接上留下任何残留字节。
     */
    override suspend fun exists(path: FsPath): Boolean = withContext(io) {
        if (path.isRoot) return@withContext true
        val url = WebDavPaths.urlOf(baseUrl, path, asDirectory = isProbablyDirectory(path))
        guard(path) {
            val request = Request.Builder()
                .url(url)
                .header("Depth", "0")
                .method("PROPFIND", null)
                .build()
            val response = try {
                client().newCall(request).execute()
            } catch (e: IOException) {
                throw FsIoException("PROPFIND $url failed: ${e.message}", e)
            }
            response.use { res ->
                // 显式读掉 body 再关：不读完就 close 会让 OkHttp 直接丢弃整条连接，
                // 读掉则能复用（PROPFIND Depth:0 的响应很小，代价可忽略）。
                runCatching { res.body?.string() }
                when {
                    res.isSuccessful -> true
                    res.code == 404 || res.code == 410 -> false
                    else -> throw httpStatusToFsException(
                        status = res.code,
                        path = path,
                        cause = IOException("HTTP ${res.code} ${res.message}"),
                    )
                }
            }
        }
    }

    /**
     * 打开随机读。
     *
     * 先用 `PROPFIND Depth:0` 拿大小——[WebDavSeekableSource] 需要它来：
     * ① 判断 EOF；② 计算 `Range` 的右端点；③ 决定退化时能否镜像。
     * 拿不到大小也能工作，只是退化路径会变保守。
     */
    override suspend fun openRead(path: FsPath): SeekableSource = withContext(io) {
        val url = WebDavPaths.urlOf(baseUrl, path, asDirectory = false)
        val size = runCatching { stat(path).size }.getOrDefault(SeekableSource.SIZE_UNKNOWN)
        guard(path) {
            WebDavSeekableSource(size) { start, end ->
                openRange(url, start, end).let { (partial, stream) ->
                    RangeResponse(partial, stream)
                }
            }
        }
    }

    // ── 写入 ────────────────────────────────────────────────────────────

    override suspend fun openWrite(
        path: FsPath,
        append: Boolean,
        expectedSize: Long,
    ): WriteSink = withContext(io) {
        if (append) {
            // WebDAV 的 PUT 是"整份替换"，没有追加语义。与其自己实现
            // "GET 全文 + 拼接 + PUT 回去"（对大文件等于自杀），不如明确不支持。
            throw FsNotSupportedException(scheme, "append")
        }
        val url = WebDavPaths.urlOf(baseUrl, path, asDirectory = false)
        WebDavWriteSink(
            client = client(),
            url = url,
            contentType = MimeTypes.of(path.displayName).toMediaType(),
            expectedSize = expectedSize,
        )
    }

    override suspend fun mkdir(path: FsPath) = withContext(io) {
        // MKCOL 的目标必须是**目录 URL**（带尾斜杠）。有些服务端（尤其 IIS）
        // 不带尾斜杠会直接 405，另一些（Nextcloud）反而无所谓——统一带上最保险。
        val url = WebDavPaths.urlOf(baseUrl, path, asDirectory = true)
        guard(path) { sardine().createDirectory(url) }
        Unit
    }

    override suspend fun delete(path: FsPath, recursive: Boolean) = withContext(io) {
        if (path.isRoot) throw FsIoException("refusing to delete the mount root: $path")
        val url = WebDavPaths.urlOf(baseUrl, path, asDirectory = isProbablyDirectory(path))
        try {
            guard(path) { sardine().delete(url) }
        } catch (e: FsNotSupportedException) {
            // 见 WebDavErrors 的说明：部分服务端对集合的 DELETE 不实现
            // `Depth: infinity`，回 405/501。RFC 4918 §9.6.1 允许服务端只删空集合，
            // 所以回退成"递归列举 + 逐个删"。
            if (!recursive) throw e
            MimiLog.i(TAG, "server rejects recursive DELETE, falling back to manual recursion: $path")
            deleteRecursively(path)
        }
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = withContext(io) {
        val parent = path.parent ?: throw FsIoException("cannot rename a mount root: $path")
        val target = parent.child(newName)
        moveTo(path, target)
        target
    }

    /**
     * 同后端内移动。
     *
     * WebDAV 的 `MOVE` 天然支持跨目录（`Destination` 头就是完整目标 URL），
     * 所以同源移动是一步到位的原子操作，不需要 `FsOps` 的"复制 + 删除"。
     */
    override suspend fun move(src: FsPath, destDir: FsPath): FsPath = withContext(io) {
        val target = destDir.child(src.displayName)
        moveTo(src, target)
        target
    }

    /** 同后端内复制。用服务端 `COPY`，数据不经过手机（`FsOps` 只在被拒绝时才走流式）。 */
    override suspend fun copy(src: FsPath, destDir: FsPath): FsPath = withContext(io) {
        val target = destDir.child(src.displayName)
        val isDir = isProbablyDirectory(src)
        val srcUrl = WebDavPaths.urlOf(baseUrl, src, asDirectory = isDir)
        val destUrl = WebDavPaths.urlOf(baseUrl, target, asDirectory = isDir)
        guard(src) {
            statelessMethod("COPY", srcUrl, mapOf("Destination" to destUrl, "Overwrite" to "T"))
        }
        target
    }

    // ── 生命周期 ────────────────────────────────────────────────────────

    /** 连接被删除时释放。OkHttp 的连接池会自动回收，这里只需丢掉引用。 */
    fun close() {
        runCatching { clientRef?.dispatcher?.executorService?.shutdown() }
        clientRef = null
        sardineRef = null
    }

    /** 供连接页显示"当前用的是 Basic 还是 Digest"。 */
    fun authLabel(): String? = authRef?.let { if (it.isDigesting()) "Digest" else "Basic" }

    // ── 内部 ────────────────────────────────────────────────────────────

    @Volatile
    private var authRef: WebDavAuth? = null

    private suspend fun client(): OkHttpClient {
        clientRef?.let { return it }
        return clientLock.withLock {
            clientRef ?: buildClient().also { clientRef = it }
        }
    }

    private suspend fun buildClient(): OkHttpClient {
        val auth = username?.takeIf { it.isNotEmpty() }?.let { user ->
            WebDavAuth(
                username = user,
                password = password.orEmpty(),
                allowInsecure = settings.allowInsecure,
            ).also { authRef = it }
        }
        return WebDavClientFactory.create(settings, auth)
    }

    private suspend fun sardine(): Sardine {
        sardineRef?.let { return it }
        // ⚠️ `client()` 必须**在锁外**调用。
        //
        // `clientLock` 是 kotlinx 的 `Mutex`，**不可重入**：`withLock` 里再调
        // `client()`（它自己也要 `clientLock.withLock`）等于把当前协程排到"自己后面"，
        // 而这把锁永远不会被释放 —— 协程永久挂起。
        //
        // 这个死锁的症状极具迷惑性，M2 实测被它骗了很久：
        //   · 服务端**一个字节都没收到**（死锁发生在发请求之前）；
        //   · 没有异常，所以界面上既没有提示也没有崩溃；
        //   · `ConnectionTester` 的 `setBusy(false)` 再也执行不到，
        //     于是「测试 / 保存 / 高级选项」三个按钮**永久变灰**。
        // 表现成"按钮点了没反应、保存也不回列表"，很容易被误判成 UI 或网络问题。
        //
        // 现在：先幂等地把 client 拿到（它内部自己会加锁），再单独用锁保护 sardine 的
        // 一次性构造。两层锁变成串行、不嵌套。
        val ready = client()
        return clientLock.withLock {
            sardineRef ?: OkHttpSardine(ready).also { sardineRef = it }
        }
    }

    /**
     * 打开一个 `Range` 请求。
     *
     * @return `partial to stream`；`partial == true` 表示服务端回了 206，
     *         流里的内容确实从 [start] 开始。
     */
    private fun openRange(url: String, start: Long, end: Long): Pair<Boolean, java.io.InputStream> {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .get()
            .build()
        return try {
            // 注意：这里**不能**用 `use {}`——流要交给调用方。
            // OkHttp 的 ResponseBody 在被 close 时会自动归还连接，所以由持有流的一方负责关闭。
            val response = clientRef!!.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val message = response.message
                response.close()
                // 复用与 sardine 异常同一张状态码映射表，避免两处判据分叉。
                throw httpStatusToFsException(
                    status = code,
                    path = FsPath.of(scheme, profile.id),
                    cause = IOException("HTTP $code $message"),
                )
            }
            val body = response.body
                ?: run {
                    response.close()
                    throw FsIoException("empty response body for $url")
                }
            (response.code == 206) to body.byteStream()
        } catch (e: FsException) {
            throw e
        } catch (e: IOException) {
            throw FsIoException("range request failed: ${e.message}", e)
        }
    }

    private suspend fun moveTo(src: FsPath, target: FsPath) {
        val isDir = isProbablyDirectory(src)
        val srcUrl = WebDavPaths.urlOf(baseUrl, src, asDirectory = isDir)
        val destUrl = WebDavPaths.urlOf(baseUrl, target, asDirectory = isDir)
        guard(src) {
            // Overwrite: T —— 语义与 `ConflictPolicy.REPLACE` 一致。
            // 上层若需要"保留两者"会先自己改名，走到这里时目标已不存在。
            statelessMethod("MOVE", srcUrl, mapOf("Destination" to destUrl, "Overwrite" to "T"))
        }
    }

    /**
     * 发一个"没有请求体、只看状态码"的 WebDAV 方法（`MOVE` / `COPY`）。
     *
     * ## 为什么这两个方法不复用 sardine
     * sardine-android 0.9 对它们**只把 `204 No Content` 当作成功**，而 RFC 4918
     * 明确允许返回 **`201 Created`**（目标是这次新建出来的），wsgidav 正是回 201。
     *
     * 后果非常隐蔽，M2 实测踩到过：
     *   · 服务端**已经改好了**（旧名消失、新名出现，宿主磁盘可验证）；
     *   · 而 sardine 抛异常 → 应用判成"重命名失败" → 列表不刷新 + 弹一条失败提示。
     * 用户看到"改名失败"，再点一次却又会因"目标已存在"而**真的**失败——极难归因。
     *
     * 与流式 `PUT` 属同一类问题（sardine 0.9 的覆盖面不够，见 [WebDavWriteSink]），
     * 处置也一致：**直连 OkHttp，按 2xx 判定**，异常映射复用 [httpStatusToFsException]。
     *
     * 认证不用另做：这里用的就是那个配好 `PreemptiveAuthInterceptor` 与
     * `Authenticator` 的共享 client（与 `MOVE` 需要的 method 级 Digest HA2 天然一致）。
     */
    private suspend fun statelessMethod(method: String, url: String, headers: Map<String, String>) {
        val builder = Request.Builder().url(url).method(method, null)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val response = try {
            client().newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw FsIoException("$method $url failed: ${e.message}", e)
        }
        response.use {
            // 显式读掉 body：这些方法的成功响应也带 HTML body（wsgidav 回 201 + 一段
            // 说明页）。不读完就 close 会让 OkHttp 丢弃整条连接；读掉才能复用。
            runCatching { it.body?.string() }
            if (!it.isSuccessful) {
                throw httpStatusToFsException(
                    status = it.code,
                    path = FsPath.of(scheme, profile.id),
                    cause = IOException("HTTP ${it.code} ${it.message}"),
                )
            }
        }
    }

    private suspend fun deleteRecursively(path: FsPath) {
        val children = list(path)
        for (child in children) {
            if (child.isDirectory) {
                deleteRecursively(child.path)
            } else {
                val url = WebDavPaths.urlOf(baseUrl, child.path, asDirectory = false)
                guard(child.path) { sardine().delete(url) }
            }
        }
        val selfUrl = WebDavPaths.urlOf(baseUrl, path, asDirectory = true)
        guard(path) { sardine().delete(selfUrl) }
    }

    /**
     * 猜测这个路径是不是目录。
     *
     * 用来决定 URL 要不要带尾斜杠。**猜错的代价不对称**：
     *  - 目录 URL 漏了尾斜杠 → 部分服务端 301/404，表现为"目录打不开"；
     *  - 文件 URL 多了尾斜杠 → 大多数服务端会忽略（`/a.txt/` 仍解析到 `a.txt`），
     *    少数会 404。
     *
     * 所以判据取"扩展名是否为空"：无扩展名当目录。这与文件管理器的直观一致，
     * 且真判错时 [guard] 会把 404 变成明确的 [FsNotFoundException]，
     * 调用方（`FsOps`）本就会在失败后重试另一种形态。
     */
    private fun isProbablyDirectory(path: FsPath): Boolean = path.extension.isEmpty()

    private suspend fun <T> guard(path: FsPath, block: suspend () -> T): T = gate.withPermit {
        try {
            block()
        } catch (t: Throwable) {
            throw if (t is FsException) t else t.toWebDavException(path)
        }
    }

    private fun DavResource.toFsEntry(): FsEntry? {
        val hrefValue = href?.toString() ?: return null
        val segments = WebDavPaths.segmentsOf(hrefValue, baseUrl)
        if (segments.isEmpty()) return null
        val path = FsPath.of(scheme, profile.id, "/" + segments.joinToString("/"))
        val directory = isDirectory
        val length = runCatching { contentLength }.getOrNull()
        val mtime = runCatching { modified?.time }.getOrNull() ?: FsEntry.TIME_UNKNOWN
        return FsEntry(
            path = path,
            name = segments.last(),
            kind = if (directory) FsKind.DIRECTORY else FsKind.FILE,
            size = if (directory) FsEntry.SIZE_UNKNOWN else (length ?: FsEntry.SIZE_UNKNOWN),
            lastModified = mtime,
            mimeType = runCatching { contentType }.getOrNull(),
            isHidden = segments.last().startsWith("."),
        )
    }

    /** 用到的 `SardineException` 成员借这个扩展显式暴露，避免误用内部构造器。 */
    private fun SardineException.toWebDavException(path: FsPath): FsException =
        (this as Throwable).toWebDavException(path)

    @Suppress("unused")
    private fun keepSardineExceptionImport() = SardineException::class

    companion object {        const val SCHEME = "webdav"

        private const val TAG = "MimiDav"

        /** 每个 WebDAV 连接的并发上限。与 SMB 取同一个值，让"慢"变得可预测。 */
        private const val MAX_CONCURRENCY = 2

        internal val CAPABILITIES: Int =
            FsCapability.LIST or FsCapability.READ or FsCapability.WRITE or
                FsCapability.CREATE_DIRECTORY or FsCapability.DELETE or FsCapability.RENAME or
                FsCapability.SEEK or FsCapability.METADATA
    }
}
