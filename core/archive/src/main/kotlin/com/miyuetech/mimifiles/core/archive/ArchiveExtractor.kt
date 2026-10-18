package com.miyuetech.mimifiles.core.archive

import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/** 压缩包里的一个条目。 */
data class ArchiveEntry(
    /** 包内路径，恒用 `/` 分隔；目录条目以 `/` 结尾。 */
    val name: String,
    val isDirectory: Boolean,
    /** 解压后的大小（未知为 -1）。 */
    val size: Long,
    /** 压缩后占用（未知为 -1）。 */
    val compressedSize: Long = -1L,
    val modifiedAtMillis: Long = 0L,
    /** 条目被加密（解压需要密码）。 */
    val encrypted: Boolean = false,
) {
    /** 去掉尾部斜杠的展示名（末段）。 */
    val displayName: String
        get() = name.trimEnd('/').substringAfterLast('/').ifEmpty { name.trimEnd('/') }

    /** 展示用的所属目录（末段之前的路径，可能为空串表示根）。 */
    val parentPath: String
        get() = name.trimEnd('/').substringBeforeLast('/', "")
}

/** 解压进度。 */
data class ExtractProgress(
    val done: Int,
    val total: Int,
    val currentName: String,
    val bytesWritten: Long,
)

/** 解压结果汇总。 */
data class ExtractResult(
    val files: Int,
    val directories: Int,
    /** 因路径非法（`../`、绝对路径）而被跳过的条目。 */
    val skippedUnsafe: Int,
    val bytesWritten: Long,
    /** 目标根目录（调用方用来"打开解压出的目录"）。 */
    val targetDir: FsPath,
)

/**
 * 一个可读的压缩包。
 *
 * ## 为什么 [list] 与 [openEntry] 是分开的两次遍历
 * zip/7z 的目录表在文件尾部，列一遍很快；而**解压必须重新按需读**。
 * 一次性把条目的流都打开会同时占用几十个句柄（zip 是中央目录驱动，可行；
 * 但 tar/gz 是纯顺序流，开第二个流就要求重头解压一遍）。所以接口按
 * "先列、后按需取"设计，实现方各自用最合适的方式满足它。
 *
 * 实现方**不保证** [openEntry] 能被并发调用（tar/gz 做不到），
 * 调用方应当串行解压——[ArchiveExtractor] 就是这么做的。
 */
interface ArchiveReader : AutoCloseable {

    val format: ArchiveFormat

    /**
     * 列举全部条目。
     *
     * @param limit 条目数上限，防止畸形包把内存撑爆（默认 [ArchiveLimits.MAX_ENTRIES]）。
     */
    suspend fun list(limit: Int = ArchiveLimits.MAX_ENTRIES): List<ArchiveEntry>

    /**
     * 打开某个**文件**条目的内容流。
     *
     * @param password 加密包的密码；不加密时忽略。
     * @return 内容流（调用方负责关闭）；条目不存在或无法解码时返回 `null`。
     */
    suspend fun openEntry(name: String, password: String? = null): InputStream?

    /** 条目在包内的真实数据大小（用于预先声明 Content-Length，利于网络后端选策略）。 */
    suspend fun sizeOf(name: String): Long = -1L
}

/** 各类上限，集中放置以便审计。 */
object ArchiveLimits {

    /**
     * 最多列举多少个条目。
     *
     * 20 万是一个很宽的量级：常规发布包最多几千个条目，超过这个数的几乎只有
     * 恶意构造的"条目炸弹"（用极小的压缩体积堆出海量条目耗尽内存）。
     */
    const val MAX_ENTRIES = 200_000

    /**
     * 单次解压允许写出的总字节上限（默认 8 GiB）。
     *
     * ## 为什么必须有这一条
     * zip bomb 的典型构造是"42 KB 解出 4 PB"。没有总量上限，用户点一下"解压"
     * 就会把设备（或 NAS）写满，而这个过程是**不可逆**的。
     * 8 GiB 远大于任何正常压缩包，同时把最坏损失钉在一个可承受的量级。
     */
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    /** 单个条目的解压上限（4 GiB）：正常文件不会超过它，超过基本可判定是炸弹。 */
    const val MAX_ENTRY_BYTES = 4L * 1024 * 1024 * 1024
}

/** 解压失败的原因（UI 需要区分文案）。 */
sealed class ArchiveException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** 格式不认识 / 无法解析。 */
    class Unsupported(message: String) : ArchiveException(message)

    /** 需要密码，或密码错误。 */
    class PasswordRequired(message: String = "需要密码") : ArchiveException(message)

    /** 超出安全上限（条目数 / 总字节）。 */
    class TooLarge(message: String) : ArchiveException(message)

    /** 目标位置不可写。 */
    class TargetNotWritable(message: String, cause: Throwable? = null) :
        ArchiveException(message, cause)
}

/**
 * 把压缩包解压到一个目标目录。
 *
 * ## 三条必须做的事（缺一个就是真 bug）
 *
 * ### 1. 防路径穿越（zip slip）
 * 条目的名字完全由压缩包决定，可以是 `../../../../data/data/<pkg>/…`。
 * 直接按名字拼路径 = 让压缩包往任意位置写文件。所以每个条目的名字都要**逐段校验**：
 * 拒绝空段、`.`、`..`、盘符与绝对路径（见 [safeSegments]）。**绝不"规范化后放行"**——
 * 规范化会把 `../a` 变成 `a`，看似安全，实际是把恶意路径静默改写成另一个文件，
 * 用户看到的是"包里有个 a"却不知道它原本指向别处。
 *
 * ### 2. 用 [WriteSink] 而不是 `File`
 * 目标可能是 SMB/WebDAV（用户完全可能把压缩包解到 NAS 上）。走 `FileSystem` 抽象
 * 才能一份代码同时覆盖本机与网络，也不需要先把整包下载下来。
 *
 * ### 3. 可取消 + 有进度
 * 解压几个 GB 是分钟级操作。没有进度用户会以为卡死，没有取消就只能杀进程。
 */
class ArchiveExtractor(
    private val reader: ArchiveReader,
    private val target: FileSystem,
    private val bufferSize: Int = DEFAULT_BUFFER,
) {

    /**
     * 解压到 [targetDir]。
     *
     * @param entries 要解压的条目（`null` = 全部）
     * @param flatten 是否丢掉包内目录结构、全部平铺到 [targetDir]（"解压当前项"用得上）
     * @param password 加密包的密码
     * @param onProgress 进度回调（在调用协程所在的线程上调用，通常在 IO）
     */
    suspend fun extract(
        targetDir: FsPath,
        entries: List<ArchiveEntry>? = null,
        flatten: Boolean = false,
        password: String? = null,
        onProgress: (ExtractProgress) -> Unit = {},
    ): ExtractResult {
        val all = entries ?: reader.list()
        val files = all.filterNot { it.isDirectory }
        val dirs = all.filter { it.isDirectory }
        var filesDone = 0
        var dirsDone = 0
        var written = 0L
        var skipped = 0

        if (!target.exists(targetDir)) {
            runCatching { target.mkdir(targetDir) }
        }
        if (!target.exists(targetDir)) {
            throw ArchiveException.TargetNotWritable("无法创建目录：$targetDir")
        }

        // 目录先建一遍：有些包只列出文件，不列目录条目（tar 常见），
        // 靠"写文件时按需建父目录"也能成，但先建一遍能让空目录也被解出来。
        for (dir in dirs) {
            currentCoroutineContext().ensureActive()
            val segments = safeSegments(dir.name, flatten)
            if (segments == null) {
                skipped++
                continue
            }
            mkdirs(targetDir, segments)
            dirsDone++
        }

        val total = files.size
        for ((index, entry) in files.withIndex()) {
            currentCoroutineContext().ensureActive()

            val segments = safeSegments(entry.name, flatten)
            if (segments == null) {
                // 危险路径：跳过并计数。**不静默**——结果里会如实告诉用户跳过了几个。
                skipped++
                continue
            }
            if (entry.size > ArchiveLimits.MAX_ENTRY_BYTES) {
                throw ArchiveException.TooLarge("条目过大，已停止：${entry.name}")
            }

            val parent = mkdirs(targetDir, segments.dropLast(1))
            val filePath = parent.child(segments.last())

            val stream = reader.openEntry(entry.name, password)
                ?: throw ArchiveException.Unsupported("无法读取条目：${entry.name}")

            var entryBytes = 0L
            try {
                val sink: WriteSink = target.openWrite(filePath, expectedSize = entry.size)
                sink.use { out ->
                    val buffer = ByteArray(bufferSize)
                    stream.use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            entryBytes += read
                            written += read
                            if (entryBytes > ArchiveLimits.MAX_ENTRY_BYTES ||
                                written > ArchiveLimits.MAX_TOTAL_BYTES
                            ) {
                                throw ArchiveException.TooLarge(
                                    "解压体积超出上限（$entry.name），已停止",
                                )
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    out.flush()
                }
                // 目标后端不支持写入时会抛 FsNotSupportedException —— 归成"目标不可写"
                // 更好懂（"SMB 上空文件夹"和"权限不足"对用户的含义是一样的）。
            } catch (e: CancellationException) {
                throw e
            } catch (e: ArchiveException) {
                throw e
            } catch (e: Throwable) {
                throw ArchiveException.TargetNotWritable("写入失败：${entry.name}", e)
            }

            filesDone++
            onProgress(ExtractProgress(index + 1, total, entry.displayName, written))
        }

        return ExtractResult(
            files = filesDone,
            directories = dirsDone,
            skippedUnsafe = skipped,
            bytesWritten = written,
            targetDir = targetDir,
        )
    }

    /**
     * 逐段创建目录并返回最深一层的路径。
     *
     * 逐段而不是一次 `mkdir` 整条路径：本机后端能一次建多层，
     * 但 SMB/WebDAV 的 `mkdir` 通常**要求父目录已存在**，一次建多层会失败。
     */
    private suspend fun mkdirs(
        root: FsPath,
        segments: List<String>,
    ): FsPath {
        var current = root
        for (segment in segments) {
            current = current.child(segment)
            if (!target.exists(current)) {
                runCatching { target.mkdir(current) }
            }
        }
        return current
    }

    companion object {
        const val DEFAULT_BUFFER = 64 * 1024

        /**
         * 把包内路径拆成安全的分段；不安全则返回 `null`。
         *
         * 规则（**只接受**，不做修正）：
         *  · 忽略空段（`a//b`）与 `.`；
         *  · 出现 `..` → 拒绝；
         *  · 段内含 `\` 或 `:`（Windows 盘符 / 路径分隔）→ 拒绝；
         *  · 段以 `/` 开头或结果为空 → 拒绝。
         *
         * @param flatten 平铺模式：只取末段（全部解到 [root] 下）
         */
        fun safeSegments(name: String, flatten: Boolean = false): List<String>? {
            val normalized = name.replace('\\', '/')
            if (normalized.startsWith("/")) return null
            val raw = normalized.split('/').filter { it.isNotEmpty() && it != "." }
            if (raw.isEmpty()) return null
            if (raw.any { it == ".." }) return null
            // 冒号只在"盘符式"名字里出现（`C:foo`），而合法的 Linux/macOS/Android
            // 文件名里冒号虽然允许，但在压缩包里出现它几乎必然是恶意构造。
            if (raw.any { it.contains(':') }) return null
            val segments = if (flatten) listOf(raw.last()) else raw
            // 逐段去掉控制字符：它们会让日志与 UI 显示错乱，也会让部分文件系统报错。
            val cleaned = segments.map { it.filter { ch -> ch.code >= 0x20 } }
            if (cleaned.any { it.isEmpty() }) return null
            return cleaned
        }
    }
}
