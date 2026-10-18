package com.miyuetech.mimifiles.core.archive

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream

/**
 * 各格式读取器的**往返测试**：用 commons-compress 的写出端造包，再用我们的读取器读回。
 *
 * ## 为什么用往返而不是"预置几个压缩包文件"
 *  · 二进制固件一旦入库就没人敢动，而且看不出它是怎么来的；
 *  · 往返测试同时钉住了"列举"与"按名取内容"两条路径，出事时能直接指向哪一侧；
 *  · 7z / rar 这类格式**在本机没有命令行工具**可以造包（宿主没装 7z），
 *    靠写出端造是最实际的办法。
 *
 * ## 覆盖不到的：RAR
 * junrar **只能读**，没有任何 Java 写出端。所以 RAR 只能靠真机上的真实 .rar 文件验，
 * 这一点在交付报告里如实标注为"未自动验证"，而不是"测过了"。
 */
class ArchiveReadersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payloadA = "hello-mimi-文件A\n".toByteArray(Charsets.UTF_8)
    private val payloadB = ByteArray(4096) { (it % 251).toByte() }

    // ── ZIP ────────────────────────────────────────────────────

    @Test
    fun `zip 列举与取内容`(): Unit = runBlocking {
        val file = tmp.newFile("plain.zip")
        zipWrite(file) { out ->
            out.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("dir/"))
            out.closeArchiveEntry()
            out.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("dir/a.txt"))
            out.write(payloadA); out.closeArchiveEntry()
            out.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("b.bin"))
            out.write(payloadB); out.closeArchiveEntry()
        }

        ArchiveReaders.open(ArchiveFormat.ZIP, file)!!.use { reader ->
            val entries = reader.list()
            assertEquals(3, entries.size)
            assertEquals("dir/", entries[0].name)
            assertTrue(entries[0].isDirectory)
            assertEquals(payloadA.size.toLong(), entries[1].size)
            assertEquals("b.bin", entries[2].name)

            assertEquals(payloadA.toList(), reader.openEntry("dir/a.txt")!!.use { it.readBytes().toList() })
            assertEquals(payloadB.toList(), reader.openEntry("b.bin")!!.use { it.readBytes().toList() })
            // 目录条目没有内容流；不存在的条目也必须返回 null 而不是抛异常
            assertNull(reader.openEntry("dir/"))
            assertNull(reader.openEntry("nope.txt"))
        }
    }

    @Test
    fun `zip 的中文文件名按 GBK 解码`(): Unit = runBlocking {
        // 手工构造一个"不设 UTF-8 标志位、名字是 GBK 字节"的 zip ——
        // 这正是 Windows 老压缩软件产出的形态，也是中文用户最容易碰到的那种乱码。
        val gbkName = "中文文件名.txt".toByteArray(Charset.forName("GBK"))
        val file = tmp.newFile("gbk.zip")
        file.writeBytes(minimalZip(gbkName, payloadA))

        ArchiveReaders.open(ArchiveFormat.ZIP, file)!!.use { reader ->
            val entries = reader.list()
            assertEquals(1, entries.size)
            // 关键断言：名字被正确还原成中文，而不是一串替换字符
            assertEquals("中文文件名.txt", entries[0].name)
            assertEquals(payloadA.toList(), reader.openEntry(entries[0].name)!!.use { it.readBytes().toList() })
        }
    }

    @Test
    fun `zip 条目流被限制在条目长度内`(): Unit = runBlocking {
        // 这是本轮修复的一个真实隐患：zip 的 `getInputStream` 理论上会在条目末尾返回 -1，
        // 但一旦某个实现的边界语义不同，读到下一个条目的数据就会"解压出超大且内容错乱的文件"。
        // 用 Bounded 明确截断后，这条断言就能保证"读到的字节数严格等于条目大小"。
        val file = tmp.newFile("bounds.zip")
        zipWrite(file) { out ->
            out.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("small.txt"))
            out.write("12345".toByteArray()); out.closeArchiveEntry()
            out.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("big.txt"))
            out.write(ByteArray(9000) { 'x'.code.toByte() }); out.closeArchiveEntry()
        }
        ArchiveReaders.open(ArchiveFormat.ZIP, file)!!.use { reader ->
            val small = reader.openEntry("small.txt")!!.use { it.readBytes() }
            assertEquals(5, small.size)
            val big = reader.openEntry("big.txt")!!.use { it.readBytes() }
            assertEquals(9000, big.size)
        }
    }

    // ── 7z ─────────────────────────────────────────────────────

    @Test
    fun `7z 列举与取内容`(): Unit = runBlocking {
        val file = tmp.newFile("plain.7z")
        SevenZOutputFile(file).use { out ->
            // 显式标成目录：`createArchiveEntry(File("dir"), …)` 会去看
            // `File.isDirectory()`，而这里并不存在这个真实目录，
            // 不标的话它会被当成一个**文件**条目，断言 `isDirectory` 就会失败。
            out.putArchiveEntry(
                out.createArchiveEntry(File("dir"), "dir/").apply { isDirectory = true },
            )
            out.closeArchiveEntry()
            out.putArchiveEntry(out.createArchiveEntry(File("a.txt"), "dir/a.txt").apply { size = payloadA.size.toLong() })
            out.write(payloadA)
            out.closeArchiveEntry()
            out.putArchiveEntry(out.createArchiveEntry(File("b.bin"), "b.bin").apply { size = payloadB.size.toLong() })
            out.write(payloadB)
            out.closeArchiveEntry()
        }

        ArchiveReaders.open(ArchiveFormat.SEVEN_Z, file)!!.use { reader ->
            val entries = reader.list()
            assertEquals(3, entries.size)
            assertEquals("dir/", entries[0].name)
            assertTrue(entries[0].isDirectory)
            assertEquals(payloadA.toList(), reader.openEntry("dir/a.txt")!!.use { it.readBytes().toList() })
            assertEquals(payloadB.toList(), reader.openEntry("b.bin")!!.use { it.readBytes().toList() })
        }
    }

    // ── TAR ────────────────────────────────────────────────────

    @Test
    fun `tar 顺序解压（游标推进而不是每次重扫）`(): Unit = runBlocking {
        val names = (1..6).map { "f$it.txt" }
        val file = tmp.newFile("plain.tar")
        TarArchiveOutputStream(file.outputStream()).use { out ->
            for ((i, name) in names.withIndex()) {
                val data = "content-$i".toByteArray()
                val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name).apply {
                    size = data.size.toLong()
                }
                out.putArchiveEntry(entry)
                out.write(data)
                out.closeArchiveEntry()
            }
        }

        ArchiveReaders.open(ArchiveFormat.TAR, file)!!.use { reader ->
            val entries = reader.list()
            assertEquals(6, entries.size)
            // 严格按顺序取：这条路径走的是"游标只向前"，不应有任何失败
            for ((i, name) in names.withIndex()) {
                val text = reader.openEntry(name)!!.use { String(it.readBytes()) }
                assertEquals("content-$i", text)
            }
            // 再倒着取一次：走的是"回头 → 重开流"的分支，同样必须正确
            for (i in names.indices.reversed()) {
                val text = reader.openEntry(names[i])!!.use { String(it.readBytes()) }
                assertEquals("content-$i", text)
            }
        }
    }

    @Test
    fun `tar 的条目流不会串到下一个条目`(): Unit = runBlocking {
        // tar 是纯流式格式，读到条目末尾后 `read()` 会继续给出下一个条目的字节 ——
        // 这是"解压出来的第一个文件变大一坨"的直接原因。
        val file = tmp.newFile("two.tar")
        TarArchiveOutputStream(file.outputStream()).use { out ->
            for (name in listOf("one.txt", "two.txt")) {
                val data = ByteArray(1000) { 'a'.code.toByte() }
                val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name).apply {
                    size = data.size.toLong()
                }
                out.putArchiveEntry(entry); out.write(data); out.closeArchiveEntry()
            }
        }
        ArchiveReaders.open(ArchiveFormat.TAR, file)!!.use { reader ->
            val one = reader.openEntry("one.txt")!!.use { it.readBytes() }
            assertEquals(1000, one.size)
        }
    }

    // ── gz / bz2 / xz（单文件流）────────────────────────────────

    @Test
    fun `gz 的条目名由文件名推导（不采用头里的名字）`(): Unit = runBlocking {
        val file = tmp.newFile("logs.txt.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(payloadA) }

        ArchiveReaders.open(ArchiveFormat.GZIP, file)!!.use { reader ->
            val entries = reader.list()
            assertEquals(1, entries.size)
            assertEquals("logs.txt", entries[0].name)
            assertTrue(!entries[0].isDirectory)
            assertEquals(payloadA.toList(), reader.openEntry("logs.txt")!!.use { it.readBytes().toList() })
        }
    }

    @Test
    fun `tar_gz 被当作 gz 流解出 tar 文件`(): Unit = runBlocking {
        // 设计取舍：我们只做"一步解压"。所以 .tar.gz 解出来是一个 .tar，
        // 用户再打开一次即可。这条断言把这个行为固定下来（而不是让它随实现漂移）。
        val inner = tmp.newFile("inner.tar")
        TarArchiveOutputStream(inner.outputStream()).use { out ->
            val data = "x".toByteArray()
            val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry("a.txt").apply {
                size = 1L
            }
            out.putArchiveEntry(entry); out.write(data); out.closeArchiveEntry()
        }
        val file = tmp.newFile("bundle.tar.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(inner.readBytes()) }

        ArchiveReaders.open(ArchiveFormat.GZIP, file)!!.use { reader ->
            val entries = reader.list()
            assertEquals(1, entries.size)
            // 名字是"去掉 .gz"，因此是 .tar
            assertEquals("bundle.tar", entries[0].name)
            val restored = reader.openEntry("bundle.tar")!!.use { it.readBytes() }
            assertEquals(inner.readBytes().toList(), restored.toList())
        }
    }

    @Test
    fun `不支持的格式返回 null 而不是抛异常`(): Unit = runBlocking {
        val file = tmp.newFile("x.zst")
        file.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()))
        assertNull(ArchiveReaders.open(ArchiveFormat.ZSTD, file))
        assertNull(ArchiveReaders.open(ArchiveFormat.UNKNOWN, file))
    }

    @Test
    fun `按扩展名 + 魔数组合判定的实际用法`(): Unit = runBlocking {
        val file = tmp.newFile("mystery")
        zipWrite(file) {
            it.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("a.txt"))
            it.write(payloadA)
            it.closeArchiveEntry()
        }
        val head = file.inputStream().use { it.readNBytes(512) }
        // 没有扩展名 → 靠魔数
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect("mystery", head))
        // 有扩展名 → 以扩展名为准
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.detect("mystery.7z", head))
    }

    // ── 工具 ───────────────────────────────────────────────────

    private fun zipWrite(file: File, block: (ZipArchiveOutputStream) -> Unit) {
        ZipArchiveOutputStream(file).use(block)
    }

    /**
     * 造一个最小可用的 zip：单个 **stored**（不压缩）条目，名字用 [rawName] 的原始字节，
     * **不设** UTF-8 标志位（通用标志位 bit 11）。
     *
     * 为什么不用 `zipfile`：Python/Java 的 zip 写出端在遇到非 ASCII 名字时会
     * 自动改写成 UTF-8 并置上标志位 —— 那正好绕开了我们要测的"GBK 无标志"场景。
     */
    private fun minimalZip(rawName: ByteArray, content: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32().apply { update(content) }.value
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
        fun u32(v: Long) = out.write(
            byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            ),
        )

        // ── 本地文件头 ──
        u32(0x04034b50)
        u16(20)            // version needed
        u16(0)             // flags：**不设** UTF-8 位
        u16(0)             // method = stored
        u16(0); u16(0)     // time / date
        u32(crc)
        u32(content.size.toLong())
        u32(content.size.toLong())
        u16(rawName.size)
        u16(0)             // extra len
        out.write(rawName)
        out.write(content)

        val localSize = out.size()
        val cdOffset = localSize

        // ── 中央目录 ──
        u32(0x02014b50)
        u16(20); u16(20)
        u16(0); u16(0)
        u16(0); u16(0)
        u32(crc)
        u32(content.size.toLong()); u32(content.size.toLong())
        u16(rawName.size)
        u16(0); u16(0); u16(0); u16(0)
        u32(0)
        u32(0)
        out.write(rawName)

        val cdSize = out.size() - cdOffset

        // ── 目录结束记录 ──
        u32(0x06054b50)
        u16(0); u16(0)
        u16(1); u16(1)
        u32(cdSize.toLong())
        u32(cdOffset.toLong())
        u16(0)
        return out.toByteArray()
    }

    @Test
    fun `读取器 close 后可重复打开`(): Unit = runBlocking {
        val file = tmp.newFile("reopen.zip")
        zipWrite(file) {
            it.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("a.txt"))
            it.write(payloadA)
            it.closeArchiveEntry()
        }
        repeat(3) {
            val reader = ArchiveReaders.open(ArchiveFormat.ZIP, file)
            assertNotNull(reader)
            reader!!.use { r ->
                assertEquals(1, r.list().size)
                assertEquals(payloadA.size, r.openEntry("a.txt")!!.use { s -> s.readBytes().size })
            }
        }
    }
}
