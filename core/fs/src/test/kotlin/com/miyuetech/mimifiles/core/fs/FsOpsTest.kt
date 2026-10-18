package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.model.has
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FsOpsTest {

    private val fs = MemoryFileSystem()
    private val registry = FileSystemRegistry().apply {
        register(object : FileSystemResolver {
            override val scheme = fs.scheme
            override val displayName = fs.displayName
            override val capabilities = fs.capabilities
            override suspend fun resolve(authority: String) = fs
        })
    }
    private val ops = FsOps(registry)

    private fun p(path: String) = FsPath.of(fs.scheme, "", path)

    private fun seedSample() {
        fs.seed(
            "/src" to null,
            "/src/a.txt" to "aaa",
            "/src/b.txt" to "bbbb",
            "/src/sub" to null,
            "/src/sub/c.txt" to "ccccc",
            "/dst" to null,
        )
    }

    // ── 纯函数：冲突命名 ────────────────────────────────────────────────

    @Test
    fun `uniqueName 不冲突时原样返回`() {
        assertEquals("报告.pdf", FsOps.uniqueName("报告.pdf", setOf("别的.pdf")))
    }

    @Test
    fun `uniqueName 保留扩展名并递增序号`() {
        assertEquals("报告 (1).pdf", FsOps.uniqueName("报告.pdf", setOf("报告.pdf")))
        assertEquals("报告 (2).pdf", FsOps.uniqueName("报告.pdf", setOf("报告.pdf", "报告 (1).pdf")))
    }

    @Test
    fun `uniqueName 对无扩展名的目录同样成立`() {
        assertEquals("相册 (1)", FsOps.uniqueName("相册", setOf("相册")))
    }

    @Test
    fun `uniqueName 把点开头的隐藏文件当无扩展名`() {
        // .gitignore 的 ".gitignore" 不该被拆成 "" + ".gitignore"
        assertEquals(".gitignore (1)", FsOps.uniqueName(".gitignore", setOf(".gitignore")))
    }

    @Test
    fun `uniqueName 对超过冲突上限的名字兜底`() {
        val existing = buildSet {
            add("x.txt")
            for (i in 1..9999) add("x ($i).txt")
        }
        val name = FsOps.uniqueName("x.txt", existing)
        assertNotEquals("x.txt", name)
        assertTrue(name.startsWith("x ("))
        assertTrue(name.endsWith(").txt"))
    }

    // ── 复制 ────────────────────────────────────────────────────────────

    @Test
    fun `复制单个文件`() = runBlocking {
        seedSample()
        val target = ops.copy(p("/src/a.txt"), p("/dst"))
        assertEquals("/dst/a.txt", target!!.path)
        assertEquals("aaa", fs.readText(p("/dst/a.txt")))
        // 源必须还在
        assertEquals("aaa", fs.readText(p("/src/a.txt")))
    }

    @Test
    fun `递归复制整个目录`() = runBlocking {
        seedSample()
        val target = ops.copy(p("/src"), p("/dst"))
        assertEquals("/dst/src", target!!.path)
        assertEquals("aaa", fs.readText(p("/dst/src/a.txt")))
        assertEquals("bbbb", fs.readText(p("/dst/src/b.txt")))
        assertEquals("ccccc", fs.readText(p("/dst/src/sub/c.txt")))
        assertTrue(fs.stat(p("/dst/src/sub")).isDirectory)
    }

    @Test
    fun `同名时默认保留两者`() = runBlocking {
        seedSample()
        ops.copy(p("/src/a.txt"), p("/dst"))
        val second = ops.copy(p("/src/a.txt"), p("/dst"))
        assertEquals("/dst/a (1).txt", second!!.path)
        assertEquals("aaa", fs.readText(p("/dst/a.txt")))
        assertEquals("aaa", fs.readText(p("/dst/a (1).txt")))
    }

    @Test
    fun `REPLACE 覆盖同名目标`() = runBlocking {
        seedSample()
        fs.putFile(p("/dst/a.txt"), "old")
        val target = ops.copy(p("/src/a.txt"), p("/dst"), ConflictPolicy.REPLACE)
        assertEquals("/dst/a.txt", target!!.path)
        assertEquals("aaa", fs.readText(p("/dst/a.txt")))
    }

    @Test
    fun `SKIP 时不产生任何写入`() = runBlocking {
        seedSample()
        fs.putFile(p("/dst/a.txt"), "old")
        val before = fs.writeCount
        val target = ops.copy(p("/src/a.txt"), p("/dst"), ConflictPolicy.SKIP)
        assertNull(target)
        assertEquals("old", fs.readText(p("/dst/a.txt")))
        assertEquals(before, fs.writeCount)
    }

    @Test
    fun `拒绝把目录复制进自己的子树`() = runBlocking {
        seedSample()
        try {
            ops.copy(p("/src"), p("/src/sub"))
            fail("应当拒绝自嵌套复制")
        } catch (e: FsIoException) {
            assertTrue(e.message!!.contains("own subtree"))
        }
    }

    @Test
    fun `进度回调单调递增且最终等于文件大小`() = runBlocking {
        seedSample()
        // 同后端且无冲突时会走后端原生 copy（零拷贝），那不产生流式进度；
        // 进度只在真正逐块搬运时才有意义，所以这里显式关掉原生通路。
        fs.supportsNativeCopy = false
        val progress = ArrayList<Long>()
        ops.copy(p("/src/b.txt"), p("/dst")) { copied, total ->
            assertEquals(4L, total) // 单文件复制时总大小是已知的
            progress += copied
        }
        assertEquals(listOf(4L), progress)
    }

    @Test
    fun `后端不支持原生 copy 时退回流式中转`() = runBlocking {
        seedSample()
        fs.supportsNativeCopy = false
        val target = ops.copy(p("/src"), p("/dst"))
        assertEquals("/dst/src", target!!.path)
        assertEquals("ccccc", fs.readText(p("/dst/src/sub/c.txt")))
    }

    // ── 移动 ────────────────────────────────────────────────────────────

    @Test
    fun `移动文件后源消失`() = runBlocking {
        seedSample()
        val target = ops.move(p("/src/a.txt"), p("/dst"))
        assertEquals("/dst/a.txt", target!!.path)
        assertEquals("aaa", fs.readText(p("/dst/a.txt")))
        assertFalse(fs.allPaths().contains(p("/src/a.txt")))
    }

    @Test
    fun `移动目录时整棵子树一起走`() = runBlocking {
        seedSample()
        ops.move(p("/src/sub"), p("/dst"))
        assertEquals("ccccc", fs.readText(p("/dst/sub/c.txt")))
        assertFalse(fs.allPaths().contains(p("/src/sub")))
        assertFalse(fs.allPaths().contains(p("/src/sub/c.txt")))
    }

    @Test
    fun `原生 move 不可用时退化为复制加删除`() = runBlocking {
        seedSample()
        fs.supportsNativeCopy = false
        val target = ops.move(p("/src/a.txt"), p("/dst"))
        assertEquals("/dst/a.txt", target!!.path)
        assertEquals("aaa", fs.readText(p("/dst/a.txt")))
        assertFalse(fs.allPaths().contains(p("/src/a.txt")))
    }

    // ── 删除 ────────────────────────────────────────────────────────────

    @Test
    fun `递归删除整棵子树`() = runBlocking {
        seedSample()
        ops.delete(p("/src"), recursive = true)
        assertFalse(fs.allPaths().contains(p("/src")))
        assertFalse(fs.allPaths().contains(p("/src/sub/c.txt")))
        assertFalse(fs.allPaths().contains(p("/src/a.txt")))
    }

    @Test
    fun `非递归删除非空目录会失败`() = runBlocking {
        seedSample()
        try {
            ops.delete(p("/src"), recursive = false)
            fail("非空目录不应被非递归删除")
        } catch (e: FsIoException) {
            assertTrue(e.message!!.contains("not empty"))
        }
    }

    @Test
    fun `批量删除单条失败不中断其余`() = runBlocking {
        seedSample()
        val failures = ops.deleteAll(listOf(p("/src/a.txt"), p("/nope.txt"), p("/src/b.txt")))
        assertEquals(1, failures.size)
        assertEquals("/nope.txt", failures.first().first.path)
        assertTrue(failures.first().second is FsNotFoundException)
        assertFalse(fs.allPaths().contains(p("/src/a.txt")))
        assertFalse(fs.allPaths().contains(p("/src/b.txt")))
    }

    // ── 新建与重命名 ────────────────────────────────────────────────────

    @Test
    fun `新建目录重名会抛已存在`() = runBlocking {
        seedSample()
        try {
            ops.createDirectory(p("/dst"), "src")
            ops.createDirectory(p("/dst"), "src")
            fail("重名应抛 FsAlreadyExistsException")
        } catch (e: FsAlreadyExistsException) {
            assertEquals("/dst/src", e.path.path)
        }
    }

    @Test
    fun `新建目录重名自动改名`() = runBlocking {
        seedSample()
        ops.createDirectoryUnique(p("/dst"), "新文件夹")
        val second = ops.createDirectoryUnique(p("/dst"), "新文件夹")
        assertEquals("/dst/新文件夹 (1)", second.path)
    }

    @Test
    fun `重命名成功后旧路径不存在`() = runBlocking {
        seedSample()
        val target = ops.rename(p("/src/a.txt"), "renamed.txt")
        assertEquals("/src/renamed.txt", target.path)
        assertEquals("aaa", fs.readText(p("/src/renamed.txt")))
        assertFalse(fs.allPaths().contains(p("/src/a.txt")))
    }

    @Test
    fun `重命名到已存在的名字会失败`() = runBlocking {
        seedSample()
        try {
            ops.rename(p("/src/a.txt"), "b.txt")
            fail("重名应抛 FsAlreadyExistsException")
        } catch (_: FsAlreadyExistsException) {
            // 期望
        }
    }

    @Test
    fun `根路径不能被重命名`() = runBlocking {
        try {
            ops.rename(p("/"), "x")
            fail("根路径重命名应失败")
        } catch (e: FsIoException) {
            assertTrue(e.message!!.contains("root"))
        }
    }

    // ── 打开与读取 ──────────────────────────────────────────────────────

    @Test
    fun `随机读句柄可跨位置读取`() = runBlocking {
        seedSample()
        fs.putFile(p("/dst/big.bin"), "0123456789")
        fs.openRead(p("/dst/big.bin")).use { source ->
            assertEquals(10L, source.size)
            assertEquals("345", String(source.readBytes(3, 3)))
            assertEquals("89", String(source.readBytes(8, 5))) // 越读越界只返回实际长度
            assertEquals("0123456789", source.stream().readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `流式读取 skip 后位置正确`() = runBlocking {
        seedSample()
        fs.putFile(p("/dst/big.bin"), "abcdefghij")
        fs.openRead(p("/dst/big.bin")).use { source ->
            val stream = source.stream()
            assertEquals(4L, stream.skip(4))
            assertEquals("efghij", stream.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `追加写入保留原有内容`() = runBlocking {
        seedSample()
        fs.openWrite(p("/dst/log.txt"), append = true, expectedSize = -1).use { sink ->
            val out = sink.stream()
            out.write("hello".toByteArray())
            out.write(" world".toByteArray())
            out.flush()
        }
        assertEquals("hello world", fs.readText(p("/dst/log.txt")))
    }

    // ── 契约完备性 ──────────────────────────────────────────────────────

    @Test
    fun `只读后端对写操作给出可判定的不支持异常`() = runBlocking {
        val readOnly = object : FileSystem {
            override val scheme = "ro"
            override val displayName = "ReadOnly"
            override val capabilities = FsCapability.READ_ONLY
            override suspend fun roots(): List<FsEntry> = emptyList()
            override suspend fun list(dir: FsPath): List<FsEntry> = emptyList()
            override suspend fun stat(path: FsPath): FsEntry = FsEntry(path, kind = FsKind.FILE)
            override suspend fun openRead(path: FsPath): SeekableSource = ByteArraySource0
        }
        try {
            readOnly.mkdir(p("/x"))
            fail("默认实现应抛 FsNotSupportedException")
        } catch (e: FsNotSupportedException) {
            assertEquals("ro", e.scheme)
            assertEquals("mkdir", e.operation)
        }
        assertFalse(readOnly.capabilities.has(FsCapability.WRITE))
    }

    @Test
    fun `exists 对不存在返回 false 而不抛异常`() = runBlocking {
        seedSample()
        assertTrue(fs.exists(p("/src/a.txt")))
        assertFalse(fs.exists(p("/src/missing.txt")))
    }

    @Test
    fun `不存在的路径抛出语义化异常`() = runBlocking {
        seedSample()
        try {
            fs.stat(p("/nope"))
            fail("应抛 FsNotFoundException")
        } catch (e: FsNotFoundException) {
            assertNotNull(e.path)
        }
    }

    private object ByteArraySource0 : SeekableSource {
        override val size: Long = 0
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun close() = Unit
    }
}
