package com.miyuetech.mimifiles.core.archive

import com.miyuetech.mimifiles.core.common.MimiLog
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 各种压缩包的读取器。
 *
 * ## 统一的输入是**本地文件**，不是我们的 `SeekableSource`
 * zip 与 7z 的目录表都在**文件尾部**，rar 也需要跨条目随机定位 ——
 * 给它们喂顺序流等于每次列目录都从头解压一遍。
 *
 * 我们当然可以写一个 `SeekableSource → SeekableByteChannel` 的适配器，
 * 但那只解决"能跑"，解决不了"跑得快"：网络后端上按 4 KB 取目录表会产生成千上万次往返。
 * 所以**远端压缩包先经 `PreviewCache` 落盘**（有容量上限，超限就如实告知并引导外发），
 * 之后所有读取都在本地完成。这与 PDF 的处理方式一致（同样是平台能力只吃本地 fd）。
 *
 * ## 为什么只引用具体实现类，而不用 `CompressorStreamFactory`
 * `CompressorStreamFactory` 会**静态引用** zstd / brotli / lz4 那几个编解码器，
 * 而 zstd 是原生库（`com.github.luben:zstd-jni`）。我们不支持 .zst，
 * 在 Gradle 里排除了它；只要不去碰工厂类，那些类就是不可达的、会被 R8 直接删掉。
 * 一旦用了工厂类，R8 就会把它们保留下来，然后因为找不到原生库而失败。
 */
/** 日志 tag（模块级常量，包内各读取器共用）。 */
private const val TAG = "MimiArchive"

object ArchiveReaders {

    /** 打开一个压缩包。格式不支持或无法解析时返回 `null`。 */
    suspend fun open(format: ArchiveFormat, file: File): ArchiveReader? = when (format) {
        ArchiveFormat.ZIP -> ZipArchiveReader(file)
        ArchiveFormat.SEVEN_Z -> SevenZArchiveReader(file)
        ArchiveFormat.RAR -> RarArchiveReader(file)
        ArchiveFormat.TAR -> TarArchiveReader(file)
        ArchiveFormat.GZIP -> SingleStreamArchiveReader(format, file, "gz")
        ArchiveFormat.BZIP2 -> SingleStreamArchiveReader(format, file, "bz2")
        ArchiveFormat.XZ -> SingleStreamArchiveReader(format, file, "xz")
        else -> null
    }
}

/**
 * 把流截断到 [limit] 字节。
 *
 * 为什么需要它：tar 是**流式**格式，一个条目的内容读完之后 `read()` 会继续返回
 * **下一个条目**的数据（不像 zip 那样天然有边界）。不加限制的话，
 * 解压出来的第一个文件会变成"从它开始到压缩包结束"的一坨。
 */
private class Bounded(
    input: InputStream,
    private var remaining: Long,
    /**
     * `close()` 是否关闭底层流。
     *
     * ⚠️ tar 必须传 `false`：它的底层是**整个包共享的顺序游标流**，
     * 调用方读完一个条目就 `use { }` 关掉，如果不拦着，下一个条目就会撞上
     * `IOException: Stream Closed`（本工程的往返单测正是这样抓到的）。
     * zip/7z/rar 则相反：每个条目是独立流，关掉它是正确且必要的
     * （zip 的 inflater 要靠 close 归还）。
     */
    private val propagateClose: Boolean = true,
) : FilterInputStream(input) {

    override fun close() {
        if (propagateClose) super.close()
        // 不传播时只把自己标记为关闭即可；底层由 ArchiveReader.close() 负责。
    }

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val allowed = minOf(len.toLong(), remaining).toInt()
        val read = super.read(b, off, allowed)
        if (read > 0) remaining -= read
        return read
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt().coerceAtLeast(0)
}

// ── ZIP ─────────────────────────────────────────────────────────────────────

/**
 * ZIP —— 也是最需要处理**文件名编码**的一种。
 *
 * ## 中文文件名的乱码是怎么来的
 * ZIP 规范里"是否 UTF-8"由条目的通用标志位 bit 11 表示。Windows 上的老压缩软件
 * （以及部分国产工具）**不设这个位**，直接按本地代码页（简体中文是 GBK）写入字节。
 * 于是按 UTF-8 解码就得到一堆乱码。
 *
 * ## 处置：先按 UTF-8 试，发现替换字符就改用 GBK
 * 判据是**解码结果里出现了 U+FFFD**：GBK 的双字节序列绝大多数不是合法 UTF-8，
 * 解码时会产生替换字符。这个判据简单、可解释，而且不会误伤真正的 UTF-8 文件名
 * （合法 UTF-8 解出来不会有替换字符）。
 * 反过来的情况（UTF-8 名字被当 GBK 解）在 commons-compress 里不会发生 ——
 * 它**总是**优先尊重 bit 11，只有未设该位时才用我们给的字符集。
 */
private class ZipArchiveReader(private val file: File) : ArchiveReader {

    override val format = ArchiveFormat.ZIP

    private var zip: ZipFile? = null

    /** 已定下来的兜底字符集（第一次列目录时决定）。 */
    private var charset: Charset = GBK

    private var cached: List<ArchiveEntry>? = null

    /** 名字 → 条目。zip 的目录表本来就是按名索引的，这里复用一份以免重复遍历。 */
    private val byName = HashMap<String, ZipArchiveEntry>()

    private suspend fun ensureOpen() {
        if (zip != null) return
        // ⚠️ 兜底字符集的优先级是 **GBK 优先、UTF-8 兜底**，不是反过来。
        //
        // 关键在于 commons-compress 的语义（`ZipFile.Builder.setCharset` 的文档）：
        // 这个字符集**只用于"条目自己没有声明编码"的情况** —— 也就是通用标志位
        // 的 bit 11 没打开时。而现代压缩工具**都会**打开这个位、并把名字写成 UTF-8：
        // 那种条目无论我们传什么，都会被正确解成 UTF-8。
        //
        // 反过来，一个**没开这个位**的包，其名字就是"按本地代码页写进去的字节"。
        // 对中文用户，那个代码页几乎总是 GBK（Windows 简体中文的默认）。
        // 于是"GBK 作为兜底"恰好只在需要它的时候生效，且不会影响任何现代包。
        //
        // M3 收口时的实测教训：一开始写的是"UTF-8 优先，发现替换字符再换 GBK"。
        // 但 GBK 字节偶尔能凑成**合法**的 UTF-8 序列（不产生替换字符），
        // 那种名字就被静默解成了乱码 —— 中文名 `中文文件名.txt` 实测解出
        // `?????ļ???`，而检测逻辑完全没察觉。**能确定的默认值比启发式修正可靠。**
        val gbk = openWith(GBK)
        // 兜底检查：若 GBK 解出的名字里出现了替换字符，说明这个包其实用的是别的
        // 单字节编码（西欧 Windows-1252、日文等）。UTF-8 是唯一还能再试一次的选择。
        val suspicious = gbk.second.any { it.name.contains('\uFFFD') }
        if (suspicious) {
            ZipFile.closeQuietly(gbk.first)
            val utf8 = openWith(Charsets.UTF_8)
            zip = utf8.first
            charset = Charsets.UTF_8
            cache(utf8.second)
            MimiLog.i(TAG, "zip 兜底字符集改为 UTF-8（GBK 解出替换字符）：${file.name}")
        } else {
            zip = gbk.first
            charset = GBK
            cache(gbk.second)
        }
    }

    private fun cache(entries: List<ArchiveEntry>) {
        if (cached != null) return
        cached = entries
        val handle = zip ?: return
        for (e in handle.entries) {
            byName.putIfAbsent(e.name, e)
        }
    }

    private fun openWith(charset: Charset): Pair<ZipFile, List<ArchiveEntry>> {
        val handle = ZipFile.builder()
            .setFile(file)
            .setCharset(charset)
            // 读取 Unicode 附加字段（Info-ZIP 的 `0x7075`）：有些工具虽然没设 bit 11，
            // 但把 UTF-8 名字额外写了一处，这个开关能直接把正确名字取回来。
            .setUseUnicodeExtraFields(true)
            .get()
        val entries = ArrayList<ArchiveEntry>()
        val it = handle.entries
        while (it.hasMoreElements() && entries.size < ArchiveLimits.MAX_ENTRIES) {
            entries += it.nextElement().toEntry()
        }
        return handle to entries
    }

    private fun ZipArchiveEntry.toEntry() = ArchiveEntry(
        name = name,
        isDirectory = isDirectory,
        size = size,
        compressedSize = compressedSize,
        modifiedAtMillis = time,
        // 通用标志位 bit 0 = 加密
        encrypted = generalPurposeBit?.usesEncryption() == true,
    )

    override suspend fun list(limit: Int): List<ArchiveEntry> {
        ensureOpen()
        return cached.orEmpty().take(limit)
    }

    override suspend fun openEntry(name: String, password: String?): InputStream? {
        ensureOpen()
        val handle = zip ?: return null
        val entry = byName[name] ?: return null
        if (entry.isDirectory) return null
        if (password != null && entry.generalPurposeBit?.usesEncryption() == true) {
            // 传统 ZipCrypto / AES：commons-compress 的 `ZipFile` 不支持带密码读取，
            // 如实报"需要密码"比抛一个看不懂的异常好。
            throw ArchiveException.PasswordRequired("加密的 ZIP 暂不支持在应用内解压，请用其它工具")
        }
        return handle.getInputStream(entry).let { Bounded(it, entry.size) }
    }

    override suspend fun sizeOf(name: String): Long {
        ensureOpen()
        return byName[name]?.size ?: -1L
    }

    override fun close() {
        cached = null
        byName.clear()
        ZipFile.closeQuietly(zip)
        zip = null
    }

    private companion object {
        /** GBK 在 Android 上恒可用（`Charset.isSupported` 对它是 true）。 */
        val GBK: Charset = Charset.forName("GBK")
    }
}

// ── 7z ──────────────────────────────────────────────────────────────────────

/**
 * 7z。
 *
 * ⚠️ `SevenZFile` 是**顺序**读取器（内部的 LZMA 解码是流式的），
 * 所以这里只支持"按 `list()` 的顺序逐个取"，随机按名取会退化成从头发扫。
 * 对"解压全部"这条主路径没有影响 —— 正好是顺序的。
 */
private class SevenZArchiveReader(private val file: File) : ArchiveReader {

    override val format = ArchiveFormat.SEVEN_Z

    private var sevenZ: SevenZFile? = null

    private var cached: List<ArchiveEntry>? = null

    private val byName = HashMap<String, SevenZArchiveEntry>()

    private suspend fun ensureOpen(password: String? = null) {
        if (sevenZ != null) return
        val handle = SevenZFile.builder()
            .setFile(file)
            // 默认 8 MB 的字典内存上限在大端包上会直接失败；128 MB 覆盖绝大多数正常 7z，
            // 同时不至于把低内存设备压垮。
            .setMaxMemoryLimitKb(128 * 1024)
            .apply { if (!password.isNullOrEmpty()) setPassword(password) }
            .get()
        sevenZ = handle
        val entries = ArrayList<ArchiveEntry>()
        var count = 0
        for (e in handle.entries) {
            if (count++ >= ArchiveLimits.MAX_ENTRIES) break
            entries += e.toEntry()
            byName.putIfAbsent(e.name, e)
        }
        cached = entries
    }

    private fun SevenZArchiveEntry.toEntry() = ArchiveEntry(
        name = name,
        isDirectory = isDirectory,
        size = size,
        // `SevenZArchiveEntry` 不暴露 compressedSize（它在文件夹级别的编码信息里，
        // 单个条目拿不到）。给 -1 表示未知，UI 只显示解压后大小。
        compressedSize = -1L,
        modifiedAtMillis = if (hasLastModifiedDate) lastModifiedDate.time else 0L,
        // 7z 的加密标记在"条目有流但没有 CRC"时不可靠，这里不据此判断：
        // 真遇到加密包时 `SevenZFile` 会抛 PasswordRequired，那时再如实报错。
        encrypted = false,
    )

    override suspend fun list(limit: Int): List<ArchiveEntry> {
        ensureOpen()
        return cached.orEmpty().take(limit)
    }

    override suspend fun openEntry(name: String, password: String?): InputStream? {
        ensureOpen(password)
        val handle = sevenZ ?: return null
        val entry = byName[name] ?: return null
        if (entry.isDirectory) return null
        val stream = runCatching { handle.getInputStream(entry) }.getOrElse { error ->
            if (isPasswordError(error)) {
                throw ArchiveException.PasswordRequired("这个 7z 需要密码（或密码不对）")
            }
            throw error
        }
        return Bounded(stream, entry.size)
    }

    override suspend fun sizeOf(name: String): Long {
        ensureOpen()
        return byName[name]?.size ?: -1L
    }

    private fun isPasswordError(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is org.apache.commons.compress.PasswordRequiredException) return true
            val name = cause.javaClass.name
            if (name.contains("PasswordRequired") || name.contains("Crypto")) return true
            cause = cause.cause?.takeIf { it !== cause }
        }
        return false
    }

    override fun close() {
        runCatching { sevenZ?.close() }
        sevenZ = null
        cached = null
        byName.clear()
    }
}

// ── RAR（只读）─────────────────────────────────────────────────────────────

/**
 * RAR —— **只读**。
 *
 * commons-compress 从 1.16 起移除了 RAR 支持（专利/许可证原因），所以这里用
 * `junrar`。它的定位很清楚：只解、不压，正好是本工程需要的全部。
 *
 * 加密的 RAR 需要密码才能列目录（RAR 的头部本身就加密了），
 * 此时如实报"需要密码"。
 */
private class RarArchiveReader(private val file: File) : ArchiveReader {

    override val format = ArchiveFormat.RAR

    private var rar: com.github.junrar.Archive? = null

    private var cached: List<ArchiveEntry>? = null

    private val byName = HashMap<String, com.github.junrar.rarfile.FileHeader>()

    private suspend fun ensureOpen() {
        if (rar != null) return
        val handle = com.github.junrar.Archive(file)
        rar = handle
        val entries = ArrayList<ArchiveEntry>()
        val headers = runCatching { handle.fileHeaders }.getOrElse { error ->
            if (error.javaClass.name.contains("Password")) {
                throw ArchiveException.PasswordRequired("这个 RAR 需要密码")
            }
            throw error
        }
        for (h in headers) {
            if (entries.size >= ArchiveLimits.MAX_ENTRIES) break
            val name = h.fileName.replace('\\', '/')
            entries += ArchiveEntry(
                name = if (h.isDirectory && !name.endsWith("/")) "$name/" else name,
                isDirectory = h.isDirectory,
                size = h.fullUnpackSize,
                compressedSize = -1L,
                modifiedAtMillis = runCatching { h.mTime.time }.getOrDefault(0L),
                encrypted = h.isEncrypted,
            )
            byName.putIfAbsent(entries.last().name, h)
        }
        cached = entries
    }

    override suspend fun list(limit: Int): List<ArchiveEntry> {
        ensureOpen()
        return cached.orEmpty().take(limit)
    }

    override suspend fun openEntry(name: String, password: String?): InputStream? {
        ensureOpen()
        val handle = rar ?: return null
        val header = byName[name] ?: return null
        if (header.isDirectory) return null
        return runCatching { handle.getInputStream(header) }.getOrElse { error ->
            if (error.javaClass.name.contains("Password")) {
                throw ArchiveException.PasswordRequired("这个 RAR 需要密码（或密码不对）")
            }
            throw error
        }
    }

    override fun close() {
        runCatching { rar?.close() }
        rar = null
        cached = null
        byName.clear()
    }
}

// ── TAR ─────────────────────────────────────────────────────────────────────

/**
 * TAR —— 纯顺序格式，这里用"正向游标"避免 O(n²)。
 *
 * ## 为什么不能用"每次从头扫"的朴素实现
 * tar 没有目录表：要读第 N 个条目，必须从包头开始一路跳过前 N-1 个。
 * 如果每个条目都从头扫，解一个 1000 文件的 tar 包就是
 * `1+2+…+1000 ≈ 50 万次`条目解压 —— 明明白白地把线性操作做成了平方。
 *
 * 所以这里维护一个**只向前**的游标：`openEntry` 时若目标在当前游标之后，
 * 就顺序推进（tar 的 `getNextEntry()` 会自动跳过当前条目的剩余数据）；
 * 只有在"要回头取"时才重开一次流。解压全部正好是严格顺序的，总代价 O(n)。
 */
private class TarArchiveReader(private val file: File) : ArchiveReader {

    override val format = ArchiveFormat.TAR

    private var stream: TarArchiveInputStream? = null

    private var cursor = -1

    private var cached: List<ArchiveEntry>? = null

    private var names: List<String> = emptyList()

    override suspend fun list(limit: Int): List<ArchiveEntry> {
        cached?.let { return it.take(limit) }
        val entries = ArrayList<ArchiveEntry>()
        file.inputStream().use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                while (entries.size < ArchiveLimits.MAX_ENTRIES) {
                    val e = tar.nextEntry ?: break
                    entries += ArchiveEntry(
                        name = e.name,
                        isDirectory = e.isDirectory,
                        size = e.size,
                        compressedSize = -1L,
                        modifiedAtMillis = e.modTime?.time ?: 0L,
                    )
                }
            }
        }
        cached = entries
        names = entries.map { it.name }
        return entries.take(limit)
    }

    override suspend fun openEntry(name: String, password: String?): InputStream? {
        val all = list(ArchiveLimits.MAX_ENTRIES)
        val index = names.indexOf(name)
        if (index < 0) return null
        val target = all[index]
        // 回头取 → 必须重开；否则顺序推进游标。
        if (stream == null || cursor >= index) {
            closeStream()
            stream = TarArchiveInputStream(file.inputStream())
            cursor = -1
        }
        val tar = stream ?: return null
        while (cursor < index) {
            val e = tar.nextEntry ?: return null
            cursor++
            if (e.name != names[cursor]) {
                // 两次遍历的条目顺序必须一致（同一个文件、同一个实现），
                // 不一致说明文件在两次读取之间被改了 —— 如实失败，不要用错位的名字。
                closeStream()
                throw ArchiveException.Unsupported("压缩包在读取过程中被修改")
            }
        }
        return Bounded(tar, target.size, propagateClose = false)
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
    }

    override fun close() {
        closeStream()
        cached = null
        names = emptyList()
    }
}

// ── gz / bz2 / xz（单文件流）────────────────────────────────────────────────

/**
 * 单文件压缩流（`.gz` / `.bz2` / `.xz`）。
 *
 * ## 它"只有一个条目"，而且名字是**推**出来的
 * 这类格式里没有目录结构，只有一个流。所以：
 *  · 条目名 = 去掉压缩后缀的文件名（`logs.txt.gz` → `logs.txt`，`backup.tar.gz` → `backup.tar`）；
 *  · 列表里就一项，UI 据此把"解压到文件夹"换成"解压到此处"。
 *
 * ⚠️ gzip 头里其实存了原始文件名，但它**不是**必须的、而且历史上有过把路径写进去的
 * 恶意构造（`../../etc/passwd`）。我们**不采用**头里的名字，只用文件名推导 ——
 * 少一个可被利用的输入。
 */
private class SingleStreamArchiveReader(
    override val format: ArchiveFormat,
    private val file: File,
    private val suffix: String,
) : ArchiveReader {

    /** 去掉压缩后缀之后的名字。 */
    private val innerName: String = file.name
        .removeSuffix(".$suffix")
        .ifEmpty { file.name }
        .let { if (it == file.name) "${file.name}.out" else it }

    override suspend fun list(limit: Int): List<ArchiveEntry> = listOf(
        ArchiveEntry(
            name = innerName,
            isDirectory = false,
            // 单流格式的头部虽然有 ISIZE（gz 尾部有解压后大小），但拿它要先解一遍，
            // 得不偿失。给 -1 表示未知，解压时按实际读到的字节走。
            size = -1L,
            modifiedAtMillis = file.lastModified(),
        ),
    )

    override suspend fun openEntry(name: String, password: String?): InputStream? {
        if (name != innerName) return null
        val raw = file.inputStream()
        return when (format) {
            ArchiveFormat.GZIP -> GzipCompressorInputStream(raw, true)
            ArchiveFormat.BZIP2 -> BZip2CompressorInputStream(raw, true)
            ArchiveFormat.XZ -> XZCompressorInputStream(raw)
            else -> null
        }
    }

    override fun close() = Unit
}
