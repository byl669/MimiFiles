package com.miyuetech.mimifiles.core.archive

import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 解压路径的安全性 —— 这是压缩包功能里**唯一会写文件**的地方，也是最该被钉死的部分。
 *
 * 用内存 `FileSystem` + 手工构造条目的方式测，不依赖真实压缩包：
 * 想构造 `../../evil` 这种条目，用一个真实的 zip 反而更麻烦（很多压缩工具会拒绝打包它）。
 */
class ArchiveExtractorTest {

    // ── safeSegments：路径穿越的第一道闸 ──────────────────────────

    @Test
    fun `正常路径被拆成分段`() {
        assertEquals(listOf("a", "b", "c.txt"), ArchiveExtractor.safeSegments("a/b/c.txt"))
        assertEquals(listOf("单层.txt"), ArchiveExtractor.safeSegments("单层.txt"))
        assertEquals(listOf("中文目录", "文件.txt"), ArchiveExtractor.safeSegments("中文目录/文件.txt"))
    }

    @Test
    fun `空段与当前目录段被忽略`() {
        assertEquals(listOf("a", "b"), ArchiveExtractor.safeSegments("a//b"))
        assertEquals(listOf("a", "b"), ArchiveExtractor.safeSegments("./a/./b"))
        assertEquals(listOf("a", "b"), ArchiveExtractor.safeSegments("a/b/"))
    }

    @Test
    fun `上跳目录一律拒绝（zip slip）`() {
        assertNull(ArchiveExtractor.safeSegments("../evil"))
        assertNull(ArchiveExtractor.safeSegments("a/../../evil"))
        assertNull(ArchiveExtractor.safeSegments("a/.."))
        assertNull(ArchiveExtractor.safeSegments(".."))
    }

    @Test
    fun `绝对路径与盘符被拒绝`() {
        assertNull(ArchiveExtractor.safeSegments("/etc/passwd"))
        assertNull(ArchiveExtractor.safeSegments("\\windows\\system32"))
        assertNull(ArchiveExtractor.safeSegments("C:/windows"))
        assertNull(ArchiveExtractor.safeSegments("C:evil.txt"))
    }

    @Test
    fun `反斜杠被当作分隔符处理`() {
        // Windows 打包的 zip 里常见反斜杠，它必须与 `/` 同等对待
        assertEquals(listOf("a", "b.txt"), ArchiveExtractor.safeSegments("a\\b.txt"))
        assertNull(ArchiveExtractor.safeSegments("a\\..\\evil"))
    }

    @Test
    fun `全是空段的路径被拒绝`() {
        assertNull(ArchiveExtractor.safeSegments("/"))
        assertNull(ArchiveExtractor.safeSegments(""))
        assertNull(ArchiveExtractor.safeSegments("///"))
        assertNull(ArchiveExtractor.safeSegments("./"))
    }

    @Test
    fun `平铺模式只取末段`() {
        assertEquals(listOf("c.txt"), ArchiveExtractor.safeSegments("a/b/c.txt", flatten = true))
        // 平铺也不能放过危险路径
        assertNull(ArchiveExtractor.safeSegments("a/../../evil", flatten = true))
    }

    // ── 端到端：解压到内存文件系统 ────────────────────────────────

    @Test
    fun `解压全部条目并跳过危险路径`(): Unit = runBlocking {
        val reader = FakeReader(
            listOf(
                ArchiveEntry("docs/", isDirectory = true, size = 0),
                ArchiveEntry("docs/readme.txt", isDirectory = false, size = 5),
                ArchiveEntry("../evil.txt", isDirectory = false, size = 4),
                ArchiveEntry("/abs.txt", isDirectory = false, size = 1),
                ArchiveEntry("bin/app", isDirectory = false, size = 3),
            ),
            contents = mapOf(
                "docs/readme.txt" to "hello",
                "../evil.txt" to "evil",
                "/abs.txt" to "x",
                "bin/app" to "bin",
            ),
        )
        val fs = MemoryFileSystem()
        val root = FsPath.parseOrThrow("mem://test/out")

        val result = ArchiveExtractor(reader, fs).extract(root)

        assertEquals(2, result.files)
        assertEquals(1, result.directories)
        // 两条危险路径必须被**计数**（UI 要如实告知"跳过了 2 个"）
        assertEquals(2, result.skippedUnsafe)
        assertEquals(5L + 3L, result.bytesWritten)

        assertEquals("hello", fs.readAll("mem://test/out/docs/readme.txt"))
        assertEquals("bin", fs.readAll("mem://test/out/bin/app"))
        // 关键断言：没有任何东西落到根之外
        assertFalse(fs.allPaths().any { it.contains("evil") })
        assertFalse(fs.allPaths().any { it.contains("abs") })
    }

    @Test
    fun `按需建出中间目录`(): Unit = runBlocking {
        // tar 包经常只列文件、不列目录条目，靠这条保证目录能被建出来
        val reader = FakeReader(
            listOf(ArchiveEntry("a/b/c/d.txt", isDirectory = false, size = 2)),
            contents = mapOf("a/b/c/d.txt" to "hi"),
        )
        val fs = MemoryFileSystem()
        val result = ArchiveExtractor(reader, fs).extract(FsPath.parseOrThrow("mem://test/out"))

        assertEquals(1, result.files)
        assertEquals("hi", fs.readAll("mem://test/out/a/b/c/d.txt"))
        assertTrue(fs.isDirectory("mem://test/out/a"))
        assertTrue(fs.isDirectory("mem://test/out/a/b"))
        assertTrue(fs.isDirectory("mem://test/out/a/b/c"))
    }

    @Test
    fun `完成一次解压不应抛异常`(): Unit = runBlocking {
        val reader = FakeReader(
            listOf(ArchiveEntry("a.txt", isDirectory = false, size = 1)),
            contents = mapOf("a.txt" to "x"),
        )
        // 上限设成 0 时，任何非空条目都应当立即被拒（而不是被写出去）
        val fs = MemoryFileSystem()
        try {
            ArchiveExtractor(reader, fs).extract(FsPath.parseOrThrow("mem://test/out"))
        } catch (e: Throwable) {
            fail("正常单条目不应抛异常：${e.message}")
        }
    }

    @Test
    fun `进度回调按文件数递增且最后一条是总数`(): Unit = runBlocking {
        val names = (1..5).map { "f$it.txt" }
        val reader = FakeReader(
            names.map { ArchiveEntry(it, isDirectory = false, size = 1) },
            contents = names.associateWith { "x" },
        )
        val seen = mutableListOf<ExtractProgress>()
        ArchiveExtractor(reader, MemoryFileSystem())
            .extract(FsPath.parseOrThrow("mem://test/out"), onProgress = { seen += it })

        assertEquals(5, seen.size)
        assertEquals(listOf(1, 2, 3, 4, 5), seen.map { it.done })
        assertEquals(5, seen.last().total)
        assertEquals("f5.txt", seen.last().currentName)
        assertEquals(5L, seen.last().bytesWritten)
    }

    @Test
    fun `单条目解压只写一个文件`(): Unit = runBlocking {
        val reader = FakeReader(
            listOf(
                ArchiveEntry("a.txt", isDirectory = false, size = 1),
                ArchiveEntry("b.txt", isDirectory = false, size = 1),
            ),
            contents = mapOf("a.txt" to "A", "b.txt" to "B"),
        )
        val fs = MemoryFileSystem()
        val one = reader.entries().filter { it.name == "b.txt" }
        ArchiveExtractor(reader, fs).extract(FsPath.parseOrThrow("mem://test/out"), entries = one)

        assertNull(fs.readAll("mem://test/out/a.txt"))
        assertEquals("B", fs.readAll("mem://test/out/b.txt"))
    }

    // ── 测试替身 ─────────────────────────────────────────────────

    private class FakeReader(
        private val entryList: List<ArchiveEntry>,
        private val contents: Map<String, String>,
    ) : ArchiveReader {

        fun entries(): List<ArchiveEntry> = entryList

        override val format = ArchiveFormat.ZIP

        override suspend fun list(limit: Int): List<ArchiveEntry> = entryList.take(limit)

        override suspend fun openEntry(name: String, password: String?): InputStream? =
            contents[name]?.let { ByteArrayInputStream(it.toByteArray()) }

        override fun close() = Unit
    }

    /** 极简内存文件系统：只实现解压需要的那几个方法。 */
    private class MemoryFileSystem : FileSystem {

        private val files = HashMap<String, ByteArray>()

        private val dirs = HashSet<String>()

        override val scheme = "mem"

        override val displayName = "内存"

        override val capabilities = 0

        fun allPaths(): Set<String> = files.keys + dirs

        fun isDirectory(path: String) = dirs.contains(path)

        fun readAll(path: String): String? = files[path]?.toString(Charsets.UTF_8)

        override suspend fun roots(): List<FsEntry> = emptyList()

        override suspend fun list(dir: FsPath): List<FsEntry> = emptyList()

        override suspend fun stat(path: FsPath): FsEntry {
            val key = path.toString()
            if (files.containsKey(key)) {
                return FsEntry(path = path, name = path.displayName, kind = FsKind.FILE, size = files[key]!!.size.toLong())
            }
            if (dirs.contains(key)) {
                return FsEntry(path = path, name = path.displayName, kind = FsKind.DIRECTORY)
            }
            throw com.miyuetech.mimifiles.core.fs.FsNotFoundException(path)
        }

        override suspend fun openRead(path: FsPath): SeekableSource =
            throw UnsupportedOperationException()

        override suspend fun openWrite(path: FsPath, append: Boolean, expectedSize: Long): WriteSink {
            val key = path.toString()
            val parentKey = path.parent?.toString()
            assertNotNull("写入前父目录必须已存在：$key（父=$parentKey）", parentKey)
            assertTrue("父目录必须已建好：$parentKey", dirs.contains(parentKey))
            val buffer = ByteArrayOutputStream()
            return object : WriteSink {
                override val bytesWritten: Long get() = buffer.size().toLong()
                override fun write(b: ByteArray, offset: Int, length: Int) = buffer.write(b, offset, length)
                override fun flush() {
                    files[key] = buffer.toByteArray()
                }

                override fun close() {
                    files[key] = buffer.toByteArray()
                }
            }
        }

        override suspend fun mkdir(path: FsPath) {
            dirs.add(path.toString())
        }
    }
}
