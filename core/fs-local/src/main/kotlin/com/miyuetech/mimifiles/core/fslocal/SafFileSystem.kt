package com.miyuetech.mimifiles.core.fslocal

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsAlreadyExistsException
import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.fs.FsNotSupportedException
import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.FileSystemResolver
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * SAF 授权树后端 —— 权限降级到 [StorageTier.SAF_TREE] 时的主力。
 *
 * ## 路径约定
 * `saf://<grantId>/子/路径`，[grantId][SafGrant.id] 由授权时的 treeUri 稳定推导。
 *
 * ## 为什么这是降级路径而不是默认路径
 * SAF 每一次目录列举都要跨进程查询 `DocumentsProvider`，比 `listFiles()` 慢一到两个数量级。
 * 它能兜住"用户就是不给全盘权限"的情况，但不能当日常主力——所以它在
 * [StorageAccessManager] 里排在 `MANAGE_EXTERNAL_STORAGE` 之后。
 *
 * ## 随机访问是可行的
 * 很多实现以为 SAF 只能顺序读（`openInputStream`）。其实
 * `openFileDescriptor(uri, "r")` 拿到的是**可 seek 的 fd**，
 * 转成 `FileInputStream` 后 `channel.position(n)` 就实现了随机读——
 * 这是 SAF 上视频能拖进度条的关键。
 */
class SafFileSystem(
    context: Context,
    private val grant: SafGrant,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSystem {

    private val appContext: Context = context.applicationContext

    override val scheme: String = SCHEME_SAF

    override val displayName: String = grant.displayName

    override val capabilities: Int =
        if (grant.canWrite) {
            FsCapability.LIST or FsCapability.READ or FsCapability.WRITE or
                FsCapability.CREATE_DIRECTORY or FsCapability.DELETE or
                FsCapability.RENAME or FsCapability.SEEK or FsCapability.METADATA
        } else {
            FsCapability.READ_ONLY
        }

    override suspend fun roots(): List<FsEntry> = listOf(grant.toRootEntry())

    override suspend fun list(dir: FsPath): List<FsEntry> = withContext(io) {
        val document = documentOf(dir)
        if (!document.exists()) throw FsNotFoundException(dir)
        if (!document.isDirectory) throw FsIoException("not a directory: $dir")
        document.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            child.toEntry(dir.child(name))
        }
    }

    override suspend fun stat(path: FsPath): FsEntry = withContext(io) {
        if (path.isRoot) return@withContext grant.toRootEntry()
        val document = documentOf(path)
        if (!document.exists()) throw FsNotFoundException(path)
        document.toEntry(path)
    }

    override suspend fun openRead(path: FsPath): SeekableSource = withContext(io) {
        val document = documentOf(path)
        if (!document.exists()) throw FsNotFoundException(path)
        if (document.isDirectory) throw FsIoException("cannot read a directory: $path")
        val descriptor = appContext.contentResolver.openFileDescriptor(document.uri, "r")
            ?: throw FsAccessDeniedException(path)
        PfdSource(descriptor)
    }

    override suspend fun openWrite(path: FsPath, append: Boolean, expectedSize: Long): WriteSink =
        withContext(io) {
            if (!grant.canWrite) throw FsAccessDeniedException(path)
            val document = existingOrCreated(path)
            // "wt" 是截断写，"wa" 是追加写；个别 provider 不认这两个模式，
            // 此时按语义回落到 "w" / "rw"，而不是直接失败。
            val stream = openOutputStream(document.uri, prefix = if (append) "wa" else "wt")
                ?: openOutputStream(document.uri, prefix = if (append) "rw" else "w")
                ?: throw FsAccessDeniedException(path)
            StreamSink(stream)
        }

    override suspend fun mkdir(path: FsPath): Unit = withContext(io) {
        if (!grant.canWrite) throw FsAccessDeniedException(path)
        val parentPath = path.parent ?: throw FsIoException("cannot mkdir a granted root: $path")
        val parent = documentOf(parentPath)
        if (!parent.isDirectory) throw FsIoException("not a directory: $parentPath")
        if (parent.findFile(path.displayName) != null) throw FsAlreadyExistsException(path)
        parent.createDirectory(path.displayName)
            ?: throw FsIoException("createDirectory failed: $path")
        Unit
    }

    override suspend fun delete(path: FsPath, recursive: Boolean): Unit = withContext(io) {
        if (!grant.canWrite) throw FsAccessDeniedException(path)
        if (path.isRoot) throw FsIoException("cannot delete a granted root: $path")
        val document = documentOf(path)
        if (!document.exists()) throw FsNotFoundException(path)
        if (document.isDirectory && !recursive && document.listFiles().isNotEmpty()) {
            throw FsIoException("directory not empty: $path")
        }
        // DocumentFile.delete() 自带递归语义（provider 负责删整棵子树）
        if (!document.delete()) throw FsIoException("delete failed: $path")
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = withContext(io) {
        if (!grant.canWrite) throw FsAccessDeniedException(path)
        val parentPath = path.parent ?: throw FsIoException("cannot rename a granted root: $path")
        val document = documentOf(path)
        if (!document.exists()) throw FsNotFoundException(path)
        if (!document.renameTo(newName)) throw FsIoException("rename failed: $path -> $newName")
        parentPath.child(newName)
    }

    // 移动与复制刻意不实现：SAF 的原生 copyTo 在跨 provider（本地 ↔ 云盘）时成功率很低，
    // 与其各自处理半成功状态，不如统一交给 FsOps 用「流式复制 + 删源」完成，
    // 至少失败时目标端不会留下一个内容不完整却看起来正常的文件。
    override suspend fun move(src: FsPath, destDir: FsPath): FsPath =
        throw FsNotSupportedException(scheme, "move")

    override suspend fun copy(src: FsPath, destDir: FsPath): FsPath =
        throw FsNotSupportedException(scheme, "copy")

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun rootDocument(): DocumentFile =
        DocumentFile.fromTreeUri(appContext, Uri.parse(grant.treeUri))
            ?: throw FsNotFoundException(grant.toFsPath())

    /**
     * 解析路径到 DocumentFile。
     *
     * 逐层 `findFile` 每层都是一次跨进程查询；授权树深度通常只有 1~3 层，
     * 加上 AIO 的 `DocumentFile` 自身有缓存，实际可接受。真成为瓶颈时，
     * 正确的优化是用 `DocumentsContract.buildChildDocumentsUriUsingTree` 一次性查子项，
     * 而不是在这里叠缓存——那会把"授权被撤销"的失效问题变得难查。
     */
    private fun documentOf(path: FsPath): DocumentFile {
        val root = rootDocument()
        if (path.isRoot) return root
        var current: DocumentFile? = root
        for (segment in path.segments) {
            current = current?.findFile(segment)
            if (current == null) throw FsNotFoundException(path)
        }
        return current ?: throw FsNotFoundException(path)
    }

    private fun existingOrCreated(path: FsPath): DocumentFile {
        val existing = runCatching { documentOf(path) }.getOrNull()
        if (existing != null && existing.exists()) return existing
        val parentPath = path.parent ?: throw FsIoException("cannot write to a granted root: $path")
        val parent = documentOf(parentPath)
        if (!parent.isDirectory) throw FsIoException("not a directory: $parentPath")
        return parent.createFile(MimeTypes.of(path.displayName), path.displayName)
            ?: throw FsIoException("createFile failed: $path")
    }

    private fun openOutputStream(uri: Uri, prefix: String): OutputStream? =
        runCatching { appContext.contentResolver.openOutputStream(uri, prefix) }.getOrNull()

    private fun DocumentFile.toEntry(selfPath: FsPath): FsEntry {
        val directory = isDirectory
        return FsEntry(
            path = selfPath,
            name = selfPath.displayName,
            kind = when {
                directory -> FsKind.DIRECTORY
                isFile -> FsKind.FILE
                else -> FsKind.OTHER
            },
            size = if (directory) FsEntry.SIZE_UNKNOWN else length(),
            lastModified = lastModified(),
            mimeType = type,
            isHidden = selfPath.displayName.startsWith('.'),
            // 授权是只读时如实标注，列表才能显示只读标记
            isReadOnly = !grant.canWrite,
        )
    }

    /** 基于可 seek 的 fd 的随机读。 */
    private class PfdSource(private val descriptor: ParcelFileDescriptor) : SeekableSource {

        private val stream = FileInputStream(descriptor.fileDescriptor)
        private val lock = Any()

        override val size: Long get() = descriptor.statSize.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            synchronized(lock) {
                if (position < 0) throw FsIoException("negative position: $position")
                val total = size
                if (total >= 0 && position >= total) return -1
                stream.channel.position(position)
                val n = stream.read(buffer, offset, length)
                if (n < 0) -1 else n
            }

        override fun close() {
            runCatching { stream.close() }
            runCatching { descriptor.close() }
        }
    }

    private class StreamSink(private val out: OutputStream) : WriteSink {

        override var bytesWritten: Long = 0L
            private set
        private var closed = false

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            out.close()
        }
    }
}

/** `saf` 后端的解析器：authority 就是授权 id。 */
class SafFileSystemResolver(
    private val context: Context,
    private val grantStore: SafGrantStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSystemResolver {

    override val scheme: String = SCHEME_SAF

    override val displayName: String = "已授权目录"

    /**
     * 保守申报只读：授权是否可写，只有解析到具体实例才知道。
     * 若这里乐观申报写能力，UI 会在只读授权上显示可点的"新建/删除"，
     * 用户点下去才收到错误——那是最差的交互。
     */
    override val capabilities: Int = FsCapability.READ_ONLY

    private val cache = ConcurrentHashMap<String, SafFileSystem>()

    override suspend fun resolve(authority: String): FileSystem {
        cache[authority]?.let { return it }
        val grant = grantStore.byId(authority)
            ?: throw FsNotSupportedException(scheme, "unknown grant: $authority")
        return cache.getOrPut(authority) { SafFileSystem(context, grant, io) }
    }

    override suspend fun release(authority: String) {
        cache.remove(authority)
    }

    /** 授权集合发生变化（新增/撤销）时清空缓存。 */
    fun invalidate() = cache.clear()
}
