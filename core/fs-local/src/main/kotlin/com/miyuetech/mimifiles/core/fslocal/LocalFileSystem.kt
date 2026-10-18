package com.miyuetech.mimifiles.core.fslocal

import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.FileSystemResolver
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 本机存储后端（`java.io.File`）。
 *
 * ## 为什么不用 `Files.readAttributes` 一次拿全部属性
 * `java.nio.file` 要到 API 26 才有，而本项目 minSdk 是 24。为一个"少两次 syscall"
 * 的优化拆出两套实现，收益不抵复杂度——`File` 的实现在 Android 7 到 16 上行为一致，
 * 这份确定性更值钱。
 *
 * ## 权限的边界
 * 这个类**不判断权限**。能不能读 `/storage/emulated/0` 是 [StorageAccessManager] 的事，
 * 这里只如实反映内核给的答案：拿不到就抛 [com.miyuetech.mimifiles.core.fs.FsAccessDeniedException]。
 * 分工清楚，才不会出现"上层以为有权、下层默默返回空列表"这种最难查的 bug。
 */
class LocalFileSystem(
    private val rootsProvider: () -> List<FsEntry>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSystem {

    override val scheme: String = SCHEME_LOCAL

    override val displayName: String = "本机存储"

    override val capabilities: Int = FsCapability.FULL_LOCAL

    override suspend fun roots(): List<FsEntry> = withContext(io) {
        rootsProvider()
    }

    override suspend fun list(dir: FsPath): List<FsEntry> = withContext(io) {
        val file = dir.toFile()
        if (!file.exists()) throw notFound(dir)
        if (!file.isDirectory) throw FsIoException("not a directory: $dir")

        // listFiles() 返回 null 只有一种可能：目录存在但读不进去（权限）
        //
        // ## 为什么把这一段拆成"两笔账"记
        // 一次列举的耗时只可能来自两处，而且**优化手段完全不同**：
        //   ① `listFiles()`（readdir）—— 平台实现，我们改不动；
        //   ② 逐条 stat + 构造 FsEntry —— 我们的代码，能优化。
        // 只报一个总数时，1.6 s 到底是"平台慢"还是"我们慢"完全看不出来，
        // 而这两者的处置方式正好相反。所以分开记。
        //
        // 实测（5000 个文件的目录，模拟器 bench34）：
        //   release 包：readdir ≈ 1650 ms，own ≈ 41 ms
        //   debug 包  ：readdir ≈ 1400 ms，own ≈ 270 ms（debuggable 不 AOT，靠 JIT）
        // 结论：**自己的代码只占 2~15 %**，读目录的开销全在平台那一次 readdir 上。
        // 同一个 `File.listFiles()` 在一个普通 shell 进程里只要 ~110 ms——说明
        // 差距来自"应用进程的 FUSE 视图"这一上下文，而不是本项目的代码。
        // 这条拆分日志就是留着让这种判断在 M2/M3（接了 SMB/WebDAV 之后，own 会变成
        // 大头）一眼可辨，不用再重新埋点。
        val readdirStartedAt = android.os.SystemClock.elapsedRealtime()
        val children = file.listFiles() ?: throw accessDenied(dir)
        val readdirMs = android.os.SystemClock.elapsedRealtime() - readdirStartedAt

        val ownStartedAt = android.os.SystemClock.elapsedRealtime()
        val entries = ArrayList<FsEntry>(children.size)
        for (child in children) {
            // 遍历途中被删的条目直接跳过——toEntryOrNull 已经能正确处理，
            // 不需要预先 exists()。
            entries += child.toEntryOrNull() ?: continue
        }
        val ownMs = android.os.SystemClock.elapsedRealtime() - ownStartedAt

        android.util.Log.d(
            PERF_TAG,
            "list $dir -> ${entries.size} items | readdir=${readdirMs} own=${ownMs} ms",
        )
        entries
    }

    override suspend fun stat(path: FsPath): FsEntry = withContext(io) {
        val file = path.toFile()
        if (!file.exists()) throw notFound(path)
        file.toEntry()
    }

    override suspend fun openRead(path: FsPath): SeekableSource = withContext(io) {
        val file = path.toFile()
        if (!file.exists()) throw notFound(path)
        if (file.isDirectory) throw FsIoException("cannot read a directory: $path")
        try {
            RandomAccessFileSource(file)
        } catch (e: java.io.FileNotFoundException) {
            throw accessDenied(path, e)
        }
    }

    override suspend fun openWrite(path: FsPath, append: Boolean, expectedSize: Long): WriteSink =
        withContext(io) {
            val file = path.toFile()
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw FsIoException("cannot create parent directory: ${parent.absolutePath}")
                }
            }
            try {
                StreamWriteSink(FileOutputStream(file, append))
            } catch (e: java.io.FileNotFoundException) {
                throw accessDenied(path, e)
            }
        }

    override suspend fun mkdir(path: FsPath): Unit = withContext(io) {
        val file = path.toFile()
        if (file.exists()) throw com.miyuetech.mimifiles.core.fs.FsAlreadyExistsException(path)
        if (!file.mkdirs() && !file.isDirectory) {
            throw FsIoException("mkdir failed: $path")
        }
    }

    override suspend fun delete(path: FsPath, recursive: Boolean): Unit = withContext(io) {
        val file = path.toFile()
        if (!file.exists()) throw notFound(path)
        val ok = if (recursive) file.deleteRecursively() else file.delete()
        if (!ok && file.exists()) {
            throw FsIoException("delete failed: $path")
        }
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = withContext(io) {
        val source = path.toFile()
        if (!source.exists()) throw notFound(path)
        val parent = source.parentFile ?: throw FsIoException("cannot rename a root: $path")
        val target = File(parent, newName)
        if (target.exists()) throw com.miyuetech.mimifiles.core.fs.FsAlreadyExistsException(path)
        if (!source.renameTo(target)) throw FsIoException("rename failed: $path -> $newName")
        target.toLocalPath()
    }

    override suspend fun move(src: FsPath, destDir: FsPath): FsPath = withContext(io) {
        val source = src.toFile()
        if (!source.exists()) throw notFound(src)
        val dir = destDir.toFile()
        if (!dir.isDirectory) throw FsIoException("not a directory: $destDir")
        val target = File(dir, source.name)
        if (target.exists()) throw com.miyuetech.mimifiles.core.fs.FsAlreadyExistsException(target.toLocalPath())
        // 同一卷内 rename 是原子的、零拷贝；跨卷（内部存储 → SD 卡）会失败，交给上层走复制+删除
        if (source.renameTo(target)) target.toLocalPath() else
            throw com.miyuetech.mimifiles.core.fs.FsNotSupportedException(scheme, "move across volumes")
    }

    override suspend fun copy(src: FsPath, destDir: FsPath): FsPath = withContext(io) {
        val source = src.toFile()
        if (!source.exists()) throw notFound(src)
        val dir = destDir.toFile()
        if (!dir.isDirectory) throw FsIoException("not a directory: $destDir")
        val target = File(dir, source.name)
        val ok = source.copyRecursively(target, overwrite = true)
        if (!ok) throw FsIoException("copy failed: $src")
        target.toLocalPath()
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private companion object {
        /**
         * 性能日志 tag。与 `feature:browser` 里那条总耗时日志**共用同一个 tag**，
         * 这样 `adb logcat -s MimiPerf` 一次就能看到「总耗时 + readdir/own 拆分」，
         * 不用记两个 tag。
         */
        const val PERF_TAG = "MimiPerf"
    }

    private fun notFound(path: FsPath) =
        com.miyuetech.mimifiles.core.fs.FsNotFoundException(path)

    private fun accessDenied(path: FsPath, cause: Throwable? = null) =
        com.miyuetech.mimifiles.core.fs.FsAccessDeniedException(path, cause)

    /**
     * 基于 [RandomAccessFile] 的随机读。
     *
     * `RandomAccessFile` 的位置是**对象级共享状态**，多线程并发读会互相踩指针，
     * 所以每次读都在同一把锁里`seek`+`read`。视频播放器与缩略图线程确实会并发读同一文件，
     * 这不是理论风险——锁的代价远低于"画面错位"的排查成本。
     */
    private class RandomAccessFileSource(private val file: File) : SeekableSource {

        private val raf = RandomAccessFile(file, "r")
        private val lock = Any()

        override val size: Long get() = raf.length()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            synchronized(lock) {
                if (position < 0) throw FsIoException("negative position: $position")
                val total = raf.length()
                if (position >= total) return -1
                raf.seek(position)
                val n = raf.read(buffer, offset, length)
                return if (n < 0) -1 else n
            }

        override fun close() {
            runCatching { raf.close() }
        }
    }

    private class StreamWriteSink(private val out: FileOutputStream) : WriteSink {

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

/**
 * `local` 后端的解析器。
 *
 * 本机存储没有"连接"概念，所有 authority（恒为空串）都解析到同一个实例，
 * 因此这里就是个退化实现——但保留解析器这层，是为了让 `FileSystemRegistry`
 * 对"静态单例"和"按需建连"两类后端一视同仁。
 */
class LocalFileSystemResolver(
    private val accessProvider: () -> StorageAccess,
    private val rootsProvider: () -> List<FsEntry>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSystemResolver {

    override val scheme: String = SCHEME_LOCAL

    override val displayName: String = "本机存储"

    /**
     * 动态能力：降级到"仅应用私有目录"时，用户能做的操作实际上只剩读，
     * 但**不是**后端能力不足，而是范围受限——所以这里仍申报完整能力，
     * 靠 `roots()` 只返回私有目录来表达边界。真实的能力缺失（如 SAF 只读授权）
     * 才由对应后端自己降级申报。
     */
    override val capabilities: Int
        get() = FsCapability.FULL_LOCAL

    private val instance: LocalFileSystem by lazy { LocalFileSystem(rootsProvider, io) }

    override suspend fun resolve(authority: String): FileSystem = instance

    /** 供 UI 判断"要不要引导用户去授权全盘访问"。 */
    fun access(): StorageAccess = accessProvider()
}
