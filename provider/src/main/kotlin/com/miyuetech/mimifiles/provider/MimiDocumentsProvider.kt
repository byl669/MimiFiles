package com.miyuetech.mimifiles.provider

import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.annotation.RequiresApi
import com.miyuetech.mimifiles.core.bootstrap.Backends
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.model.has
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * 把网络存储挂给**系统**的 SAF 提供者。
 *
 * ## 它解决的是什么问题
 * 配好一条 SMB 连接之后，用户想要的往往不只是"在本应用里能看见"——而是
 * **在别的应用里也能选到它**：微信发一个 NAS 上的 PDF、剪辑软件导入一段网络上的视频、
 * 相册直接存到共享里。做到这件事的唯一途径就是实现 [DocumentsProvider]，
 * 让系统"文件"应用把我们的连接当作一个存储卷。
 *
 * ## documentId 就是 [FsPath] 的文本形式
 * 这个选择不是随手定的：
 *  - 它**自描述**（`smb://conn-id/share/photo/a.jpg`），任何一条 id 都能独立还原出
 *    scheme、连接、路径三件事，provider 无需维护额外的 id ↔ 路径映射表；
 *  - 它是 M1 冻结的持久化契约（见 [FsPath] 类注释），系统会把 documentId 长期
 *    存在自己的数据库里（"最近使用"、书签），**换一种编码就等于让这些记录全部失效**。
 *
 * ## 线程模型
 * SAF 的接口全是**同步**的，所以每次操作都要在 Binder 线程上阻塞到网络往返结束——
 * 这是所有云盘类 provider 的固有限制，绕不过去。我们能做的只有两件事：
 *  1. 该拒绝的地方早拒绝（不许删挂载点、不许把挂载点改名）；
 *  2. **把"列根"做成不碰网络的操作**（见 [queryRoots] 与 [queryDocument] 的注释），
 *     这样 NAS 离线时系统"文件"应用里不会整个卡住。
 *
 * ## 与 `:feature:connection` 的分工
 * 这里**不做连接管理**：没有连接就没有根，仅此而已。用户要加连接得回应用里加；
 * 一个只读的挂载视图比一个能在系统界面里改服务器地址的入口安全得多。
 */
class MimiDocumentsProvider : DocumentsProvider() {

    /**
     * 代理 fd 的回调线程。
     *
     * 惰性创建、进程内共享：每个被打开的文件都新建一个线程的话，系统同时扫缩略图
     * 时会有几十个线程在各自的 socket 上等。
     */
    @Volatile
    private var callbackThread: HandlerThread? = null

    private val defaultDocumentProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SUMMARY,
    )

    private val defaultRootProjection = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_AVAILABLE_BYTES,
    )

    // ══════════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // 系统**可以单独拉起本进程**：第三方应用通过 SAF 选文件时，我们一个 Activity
        // 都没跑过。所以后端注册不能寄望于浏览页先执行——必须在这里自己保证。
        // Backends.install 是幂等的，正常路径（浏览页已经装过）下它会直接返回。
        Backends.install(ctx)
        MimiLog.i(TAG, "DocumentsProvider ready, schemes=${FileSystems.registry.schemes()}")
        return true
    }

    // ══════════════════════════════════════════════════════════════════════
    // 根与文档查询
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 列出存储根：**每条连接一个**。
     *
     * 这里刻意**不调用**后端的 `roots()`——那要求先把会话握起来。系统在弹出
     * "选择文件"界面时会立刻问所有 provider 的根，若这里做网络动作，
     * 只要有一条连接指向离线的 NAS，整个选择界面就会卡到超时。
     *
     * 所以根信息全部来自**本地数据库里的连接档案**：离线与否留到用户真的点进去再说。
     */
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: defaultRootProjection
        val cursor = MatrixCursor(cols, 0)

        for (profile in profiles()) {
            val documentId = rootDocumentId(profile)
            val caps = FileSystems.registry.capabilitiesOf(profile.scheme)
            // 只给 IS_CHILD：系统据此用"路径前缀"快速判断父子关系，省掉一次
            // `queryChildDocuments` 全量列举。**没有**"让它别出现在首屏"的标志
            // （`Root` 里没有 FLAG_ADVANCED 这个常量），网络位置就是会出现在
            // "文件"应用侧栏——这其实是对的，配好的 NAS 本来就应该在那儿。
            var flags = Root.FLAG_SUPPORTS_IS_CHILD
            if (caps.has(FsCapability.CREATE_DIRECTORY)) flags = flags or Root.FLAG_SUPPORTS_CREATE

            row(
                cursor, cols,
                Root.COLUMN_ROOT_ID to documentId,
                Root.COLUMN_DOCUMENT_ID to documentId,
                Root.COLUMN_TITLE to profile.displayName.ifBlank { profile.endpointLabel },
                Root.COLUMN_SUMMARY to summaryOf(profile),
                Root.COLUMN_FLAGS to flags,
                Root.COLUMN_ICON to R.drawable.ic_root_network,
                Root.COLUMN_MIME_TYPES to MIME_ALL,
                // 容量未知：网络存储的真实剩余空间要问服务端，而这里不能做网络动作。
                // 报 null 让系统不显示容量条，好过报一个瞎编的数字。
                Root.COLUMN_AVAILABLE_BYTES to null,
            )
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: defaultDocumentProjection
        val cursor = MatrixCursor(cols, 1)
        val path = pathOf(documentId)

        // 挂载点（连接级根）用档案**合成**一条记录，不碰网络——理由同 queryRoots。
        val rootProfile = findRootProfile(documentId)
        if (rootProfile != null) {
            val synthetic = FsEntry(
                path = path,
                name = rootProfile.displayName.ifBlank { rootProfile.endpointLabel },
                kind = FsKind.DIRECTORY,
            )
            addDocumentRow(cursor, cols, synthetic, isRoot = true)
            return cursor
        }

        val entry = runCatching { runBlocking { using(path) { it.stat(path) } } }
            .getOrElse { throw failure(documentId, it) }
        addDocumentRow(cursor, cols, entry, isRoot = false)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: defaultDocumentProjection
        val cursor = MatrixCursor(cols, 0)
        val parent = pathOf(parentDocumentId)

        // sortOrder 刻意忽略：排序在 SAF 这一层没有意义（系统会按用户选择重排），
        // 而后端自己排一遍要付一次全量比较的代价。
        val entries = runCatching { runBlocking { using(parent) { it.list(parent) } } }
            .getOrElse { throw failure(parentDocumentId, it) }

        for (entry in entries) addDocumentRow(cursor, cols, entry, isRoot = false)
        return cursor
    }

    /** API 26+ 会走这个重载。排序参数交给上面同一个实现。 */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        queryArgs: Bundle?,
    ): Cursor = queryChildDocuments(
        parentDocumentId,
        projection,
        // 排序键的常量在 `ContentResolver` 上，不在 `DocumentsContract` 上——
        // `DocumentsContract` 那边的 QUERY_ARG_* 全是筛选条件（名字、大小、时间）。
        queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
    )

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = FsPath.parse(parentDocumentId) ?: return false
        val child = FsPath.parse(documentId) ?: return false
        return parent.isAncestorOf(child)
    }

    override fun getDocumentType(documentId: String): String {
        val path = pathOf(documentId)
        if (findRootProfile(documentId) != null) return Document.MIME_TYPE_DIR
        // 拿不到真实类型时按**文件名**猜：报 `application/octet-stream` 会让系统
        // 用"通用文件"打开，用户明明点的是图片却进了解析器，体感很差。
        val entry = runCatching { runBlocking { using(path) { it.stat(path) } } }.getOrNull()
            ?: return MimeTypes.of(path.displayName)
        return when {
            entry.isDirectory -> Document.MIME_TYPE_DIR
            // 不能写成 `entry.mimeType != null -> entry.mimeType`：`entry.mimeType` 是
            // **另一个模块**（`:core:model`）里的属性，Kotlin 不做跨模块的智能转换
            // （自定义 getter 理论上可以每次返回不同值），所以这里显式兜底。
            else -> entry.mimeType ?: MimeTypes.of(entry.name)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读写
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 打开文件。
     *
     * ## 为什么优先用 `openProxyFileDescriptor`（API 26+）
     * 另一种做法是 `createReliablePipe()` 加一个搬运线程，但它给出的 fd **不可 seek**：
     * 播放器拖不动进度条、PDF 阅读器无法直接跳到第 100 页。我们的后端本来就实现了
     * 随机读（[SeekableSource]），通过代理 fd 的回调可以把 `onRead(offset, size)`
     * **原样映射**到它上面，等于零成本拿到完整的 seek 能力。
     *
     * 代理 fd 是 API 26 才有的，所以 24/25 上退回管道（顺序读）——那两个版本上
     * 能打开文件就已经是可用状态了。
     */
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val path = pathOf(documentId)
        // 挂载点本身不是一个可以打开的文件。放行的话，某些客户端会把整个共享
        // 当文件读，然后在我们的后端里翻出一个莫名其妙的错误。
        if (findRootProfile(documentId) != null) {
            throw FileNotFoundException("$documentId 是连接挂载点，不是文件")
        }

        return when (mode) {
            MODE_READ -> openRead(path)
            // `rw`/`rwt` 语义上要同时随机读写，而后端只有"只写"或"只读"句柄。
            // 降级为写：系统要 `rw` 时绝大多数是在覆写整个文件。
            MODE_WRITE, MODE_WRITE_TRUNCATE, MODE_READ_WRITE, MODE_READ_WRITE_TRUNCATE ->
                openWrite(path, append = false)

            MODE_WRITE_APPEND -> openWrite(path, append = true)

            else -> openRead(path)
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = pathOf(parentDocumentId)
        val name = sanitizeName(displayName)
        val child = runCatching { parent.child(name) }
            .getOrElse { throw FileNotFoundException("非法文件名：$displayName") }

        // 真建一个空文件，而不是"记个名字等写入时再建"：SAF 的调用方会在
        // createDocument 之后立刻拿 documentId 去查元数据，那时文件必须已经存在。
        runCatching {
            runBlocking {
                using(child) { fs -> fs.openWrite(child, append = false, expectedSize = 0L).close() }
            }
        }.getOrElse { throw failure(child.toString(), it) }

        return child.toString()
    }

    override fun deleteDocument(documentId: String) {
        if (findRootProfile(documentId) != null) {
            throw FileNotFoundException("不能删除连接挂载点")
        }
        val path = pathOf(documentId)
        // recursive = true：SAF 的删除语义是"连同整个子树"，而系统只会调一次。
        runCatching { runBlocking { using(path) { it.delete(path, recursive = true) } } }
            .getOrElse { throw failure(documentId, it) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (findRootProfile(documentId) != null) {
            throw FileNotFoundException("不能重命名连接挂载点")
        }
        val path = pathOf(documentId)
        val name = sanitizeName(displayName)
        val renamed = runCatching { runBlocking { using(path) { it.rename(path, name) } } }
            .getOrElse { throw failure(documentId, it) }
        return renamed.toString()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 打开句柄的两种实现
    // ══════════════════════════════════════════════════════════════════════

    private fun openRead(path: FsPath): ParcelFileDescriptor {
        val source = runCatching { runBlocking { using(path) { it.openRead(path) } } }
            .getOrElse { throw failure(path.toString(), it) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            proxyFd(ParcelFileDescriptor.MODE_READ_ONLY, ReadCallback(source))
        } else {
            pipeRead(source)
        }
    }

    private fun openWrite(path: FsPath, append: Boolean): ParcelFileDescriptor {
        val sink = runCatching {
            runBlocking { using(path) { it.openWrite(path, append = append) } }
        }.getOrElse { throw failure(path.toString(), it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags = ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
            return proxyFd(flags, WriteCallback(sink))
        }
        return pipeWrite(sink)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun proxyFd(flags: Int, callback: ProxyFileDescriptorCallback): ParcelFileDescriptor {
        val manager = context?.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("拿不到 StorageManager")
        return runCatching { manager.openProxyFileDescriptor(flags, callback, callbackHandler()) }
            .getOrElse { throw failure("proxy fd", it) }
    }

    /**
     * API 26 以下的读路径：一次性管道。
     *
     * 数据在后台线程里推，调用方读到的是顺序流——**不能 seek**，
     * 所以老系统上从 SAF 打开远程视频无法拖动进度条。这一点没有更好的办法，
     * 只能如实退化（而不是假装支持：假装会让播放器拿到错误偏移的数据）。
     */
    private fun pipeRead(source: SeekableSource): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    source.stream(0L).use { input -> input.copyTo(out, COPY_BUFFER) }
                }
            } catch (t: Throwable) {
                runCatching { writeSide.closeWithError(t.message) }
                MimiLog.w(TAG, "pipe read aborted: ${t.message}")
            } finally {
                runCatching { source.close() }
            }
        }, "mimi-saf-read").apply { isDaemon = true }.start()
        return readSide
    }

    /** API 26 以下的写路径：一次性管道，收到的字节顺序写进 sink。 */
    private fun pipeWrite(sink: WriteSink): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) sink.write(buffer, 0, n)
                    }
                }
            } catch (t: Throwable) {
                MimiLog.w(TAG, "pipe write aborted: ${t.message}")
            } finally {
                // close() 才是"提交"——后端在这里发完最后一段、关掉会话。
                runCatching { sink.close() }
            }
        }, "mimi-saf-write").apply { isDaemon = true }.start()
        return writeSide
    }

    /**
     * 回调线程。
     *
     * 必须**不是**主线程：`onRead` 里是同步的网络往返，放在主线程上会直接 ANR。
     * 单线程也顺带保证了 `onRead` 与 `onRelease` 不会并发跑——否则释放句柄的
     * 那一刻可能正好有一次读在飞，把已关闭的连接用出 `FsIoException`。
     */
    private fun callbackHandler(): Handler {
        val existing = callbackThread
        if (existing != null) return Handler(existing.looper)
        return synchronized(this) {
            val thread = callbackThread ?: HandlerThread("mimi-saf-io").apply { start() }
                .also { callbackThread = it }
            Handler(thread.looper)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 游标构造
    // ══════════════════════════════════════════════════════════════════════

    private fun addDocumentRow(cursor: MatrixCursor, cols: Array<out String>, entry: FsEntry, isRoot: Boolean) {
        val caps = FileSystems.registry.capabilitiesOf(entry.path.scheme)
        row(
            cursor, cols,
            Document.COLUMN_DOCUMENT_ID to entry.path.toString(),
            Document.COLUMN_DISPLAY_NAME to entry.name,
            // 目录的大小报 null，不要报 0：报了 0 系统会显示"0 字节"，
            // 用户会以为目录是空的。
            Document.COLUMN_SIZE to if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
            Document.COLUMN_MIME_TYPE to if (entry.isDirectory) {
                Document.MIME_TYPE_DIR
            } else {
                entry.mimeType ?: MimeTypes.of(entry.name)
            },
            Document.COLUMN_LAST_MODIFIED to entry.lastModified.takeIf { it > 0 },
            Document.COLUMN_FLAGS to documentFlags(entry, caps, isRoot),
            Document.COLUMN_SUMMARY to null,
        )
    }

    private fun documentFlags(entry: FsEntry, caps: Int, isRoot: Boolean): Int {
        // 只读条目只放"读"类标志出去。
        // 早先的写法是先把写标志都加上、最后按位清掉 FLAG_SUPPORTS_WRITE——那漏掉了
        // 目录的 FLAG_DIR_SUPPORTS_CREATE，结果系统在只读目录上照样放出"新建文件夹"，
        // 点了必然失败。先分支、再决定加什么，不会有这种漏项。
        if (entry.isReadOnly) {
            return if (caps.has(FsCapability.METADATA)) Document.FLAG_SUPPORTS_METADATA else 0
        }

        var flags = 0
        if (entry.isDirectory) {
            if (caps.has(FsCapability.CREATE_DIRECTORY)) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (caps.has(FsCapability.WRITE)) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }

        // 挂载点不给改名/删除：那不是"一个文件夹"，而是"一条连接的入口"。
        // 让系统在那里放出"重命名"，用户改了之后我们既不能改数据库里的连接名、
        // 也不能改根路径，只会得到一个必然失败的按钮。
        if (!isRoot) {
            if (caps.has(FsCapability.DELETE)) flags = flags or Document.FLAG_SUPPORTS_DELETE
            if (caps.has(FsCapability.RENAME)) flags = flags or Document.FLAG_SUPPORTS_RENAME
        }
        if (caps.has(FsCapability.METADATA)) flags = flags or Document.FLAG_SUPPORTS_METADATA

        return flags
    }

    /** 按 projection 逐列填。少了这一步，`MatrixCursor.add` 会因列不存在而抛异常。 */
    private fun row(cursor: MatrixCursor, cols: Array<out String>, vararg values: Pair<String, Any?>) {
        val builder = cursor.newRow()
        for ((column, value) in values) {
            if (cols.contains(column)) builder.add(column, value)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 连接档案
    // ══════════════════════════════════════════════════════════════════════

    private fun profiles(): List<ConnectionProfile> {
        val ctx = context ?: return emptyList()
        return runCatching { runBlocking { Backends.connections(ctx).all() } }
            .onFailure { MimiLog.w(TAG, "读取连接列表失败：${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun rootDocumentId(profile: ConnectionProfile): String = profile.rootFsPath().toString()

    private fun findRootProfile(documentId: String): ConnectionProfile? =
        profiles().firstOrNull { rootDocumentId(it) == documentId }

    /** `SMB · 192.168.1.100/home`。与 `:feature:connection` 的列表文案保持同一种读法。 */
    private fun summaryOf(profile: ConnectionProfile): String {
        val label = when (profile.scheme) {
            SCHEME_SMB -> "SMB"
            SCHEME_WEBDAV -> "WebDAV"
            else -> profile.scheme.uppercase()
        }
        val path = profile.rootPath.trim().trim('/')
        val endpoint = profile.endpointLabel
        return if (path.isEmpty()) "$label · $endpoint" else "$label · $endpoint/$path"
    }

    // ══════════════════════════════════════════════════════════════════════
    // 小工具
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun <T> using(path: FsPath, block: suspend (FileSystem) -> T): T =
        block(FileSystems.registry.resolve(path))

    private fun pathOf(documentId: String): FsPath =
        FsPath.parse(documentId) ?: throw FileNotFoundException("无法解析的 documentId：$documentId")

    /**
     * SAF 只认 [FileNotFoundException]（以及少数 IO 异常），其余异常抛出会让
     * 调用方看到"未知错误"。但把原始原因吞掉又没法排障，所以塞进 message——
     * 它会出现在 logcat 与 `ContentResolver` 的失败信息里。
     */
    private fun failure(documentId: String, cause: Throwable): FileNotFoundException {
        MimiLog.w(TAG, "SAF 操作失败 $documentId：${cause.javaClass.simpleName}: ${cause.message}")
        return FileNotFoundException("$documentId — ${cause.message ?: cause.javaClass.simpleName}")
    }

    /** SAF 会直接把用户输入当文件名传进来，斜杠与 `.`/`..` 必须在落到路径层之前处理掉。 */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.trim().replace('/', '_').replace('\\', '_')
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            stringOf(R.string.provider_untitled)
        } else {
            cleaned
        }
    }

    private fun stringOf(resId: Int): String = context?.getString(resId) ?: "untitled"

    private companion object {
        const val TAG = "MimiSaf"

        const val MIME_ALL = "*/*"

        const val COPY_BUFFER = 64 * 1024

        const val SCHEME_SMB = "smb"
        const val SCHEME_WEBDAV = "webdav"

        const val MODE_READ = "r"
        const val MODE_WRITE = "w"
        const val MODE_WRITE_TRUNCATE = "wt"
        const val MODE_WRITE_APPEND = "wa"
        const val MODE_READ_WRITE = "rw"
        const val MODE_READ_WRITE_TRUNCATE = "rwt"
    }

    /**
     * 读句柄：把 `onRead(offset, size)` 原样映射到 [SeekableSource.read]。
     *
     * 这个映射之所以成立，是因为后端的读接口本来就是"给我第 offset 字节起的
     * size 个字节"——SAF 的代理 fd 要的正是同一件事。中间不需要任何缓存或对齐。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private class ReadCallback(private val source: SeekableSource) : ProxyFileDescriptorCallback() {

        override fun onGetSize(): Long = source.size.takeIf { it >= 0 } ?: 0L

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            val want = size.coerceAtMost(data.size)
            if (want <= 0) return 0
            val n = source.read(offset, data, 0, want)
            // SAF 的契约是"读到 0 表示 EOF"，而我们的后端用 -1 表示 EOF。
            return if (n <= 0) 0 else n
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
            // 只读句柄收到写请求说明调用方用错了 fd（或 mode 传错了）。
            // 抛异常比静默丢弃好：静默会让对方以为写成功了。
            throw FileNotFoundException("只读句柄")
        }

        override fun onRelease() {
            runCatching { source.close() }
                .onFailure { MimiLog.w(TAG, "关闭读句柄失败：${it.message}") }
        }
    }

    /**
     * 写句柄。
     *
     * `onWrite` 是**顺序追加**语义（offset 递增），所以直接转给 [WriteSink.write]。
     * 真正的提交在 `onRelease`：后端在 `close()` 里才发最后一段并落盘/断会话，
     * 提前 close 会写坏文件。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private class WriteCallback(private val sink: WriteSink) : ProxyFileDescriptorCallback() {

        // 写入过程中大小未知（后端要等 close 才知道最终长度）。
        override fun onGetSize(): Long = 0L

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = 0

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
            if (size > 0) sink.write(data, 0, size)
            // 契约要求返回实际写入的字节数；返回 0 会被系统当成"写不动"而放弃。
            return size
        }

        override fun onFsync() {
            runCatching { sink.flush() }
        }

        override fun onRelease() {
            runCatching { sink.flush() }
            runCatching { sink.close() }
                .onFailure { MimiLog.w(TAG, "提交写句柄失败：${it.message}") }
            MimiLog.d(TAG, "写句柄已提交，共 ${sink.bytesWritten} 字节")
        }
    }
}
