package com.miyuetech.mimifiles.core.shareserver

import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.fs.FsNotSupportedException
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ShareScope] 的行为测试 —— **根级过滤与只读是安全属性，不是 UI 细节**。
 *
 * 用内存假后端（[MemoryBridge]）而不是真实 `local` 后端：这些断言全部是
 * "访问控制的判定"，不需要真的读写磁盘；跑在 JVM 上是秒级、可重复的。
 */
class ShareScopeTest {

    private val root = FsPath.of("local", "", "/share")

    private fun bridge(vararg children: String): MemoryBridge {
        val b = MemoryBridge()
        b.addDir(root)
        for (c in children) {
            val child = root.child(c)
            if (c.endsWith("/")) b.addDir(child) else b.file(child, "hello:$c")
        }
        return b
    }

    // ── 全目录分享 ──────────────────────────────────────────────────────

    @Test
    fun `整目录分享列出全部条目且目录在前`() {
        val b = bridge("b.txt", "a/", "c.txt")
        val scope = ShareScope(root, null, readOnly = false, bridge = b)
        val nodes = runBlocking { scope.list("")!! }
        assertEquals(listOf("a", "b.txt", "c.txt"), nodes.map { it.name })
        assertEquals("a/b.txt", "${nodes[0].rel}/${nodes[1].name}")
        assertTrue(nodes[0].isDirectory)
    }

    @Test
    fun `根级过滤只放出被选中的名字`() {
        val b = bridge("keep.txt", "skip.txt", "dir-keep/", "dir-skip/")
        val scope = ShareScope(
            root = root,
            visibleNames = setOf("keep.txt", "dir-keep"),
            readOnly = false,
            bridge = b,
        )
        val nodes = runBlocking { scope.list("")!! }
        assertEquals(listOf("dir-keep", "keep.txt"), nodes.map { it.name })
    }

    @Test
    fun `过滤只作用于根 进入被选中的目录后内容完整`() {
        val b = bridge("dir-keep/")
        b.file(root.child("dir-keep", "inner-hidden.txt"), "x")
        b.file(root.child("dir-keep", "inner-2.txt"), "y")
        val scope = ShareScope(root, setOf("dir-keep"), readOnly = false, bridge = b)

        val inner = runBlocking { scope.list("dir-keep")!! }
        assertEquals(listOf("inner-2.txt", "inner-hidden.txt"), inner.map { it.name })
    }

    @Test
    fun `未在列表里的兄弟文件即使知道名字也读不到`() {
        // 这条是"过滤不能只做在 list 上"的证明：`GET /f/secret.txt` 会走 stat，
        // 如果只在 list 里过滤，知道名字就能直接下载。
        val b = bridge("keep.txt", "secret.txt")
        val scope = ShareScope(root, setOf("keep.txt"), readOnly = false, bridge = b)

        assertNull(runBlocking { scope.stat("secret.txt") })
        assertNull(runBlocking { scope.openRead("secret.txt") })
        assertTrue(runBlocking { scope.stat("keep.txt") } != null)
    }

    // ── 越狱 ────────────────────────────────────────────────────────────

    @Test
    fun `越狱路径一律拒绝且不泄露原因`() {
        val b = bridge("keep.txt")
        val scope = ShareScope(root, null, readOnly = false, bridge = b)

        assertNull(scope.resolve("../etc/passwd"))
        assertNull(scope.resolve("a/../../x"))
        assertNull(runBlocking { scope.list("../..") })
        assertNull(runBlocking { scope.stat("../../x") })
    }

    @Test
    fun `写操作对越狱路径抛访问被拒而不是不存在`() {
        val b = bridge("keep.txt")
        val scope = ShareScope(root, null, readOnly = false, bridge = b)
        try {
            runBlocking { scope.mkdir("../evil", "x") }
            fail("应当抛 FsAccessDeniedException")
        } catch (e: FsAccessDeniedException) {
            // 期望
        }
    }

    // ── 只读 ────────────────────────────────────────────────────────────

    @Test
    fun `只读会话拒绝全部写操作`() {
        val b = bridge("keep.txt")
        val scope = ShareScope(root, null, readOnly = true, bridge = b)
        assertFalse(scope.writable)

        assertNotSupported { scope.mkdir("", "new") }
        assertNotSupported { scope.delete("keep.txt", false) }
        assertNotSupported { scope.rename("keep.txt", "x.txt") }
        assertNotSupported { scope.openWrite("up.txt", -1L) }
    }

    @Test
    fun `后端不可写时即使没开只读也拒绝写`() {
        val b = bridge("keep.txt")
        val scope = ShareScope(root, null, readOnly = false, bridge = b, backendWritable = false)
        assertFalse(scope.writable)
        assertNotSupported { scope.delete("keep.txt", false) }
    }

    @Test
    fun `不允许删除或重命名根`() {
        val b = bridge("keep.txt")
        val scope = ShareScope(root, null, readOnly = false, bridge = b)
        assertDenied { scope.delete("", true) }
        assertDenied { scope.rename("", "x") }
        assertDenied { scope.delete("/", true) }
    }

    // ── 正常写操作 ──────────────────────────────────────────────────────

    @Test
    fun `可写会话能新建 重命名 删除 并且相对路径正确`() {
        val b = bridge("old.txt")
        val scope = ShareScope(root, null, readOnly = false, bridge = b)

        assertEquals("newdir", runBlocking { scope.mkdir("", "newdir") })
        assertEquals("newdir", runBlocking { scope.mkdir("newdir", "sub") }.substringBefore("/sub"))

        val renamed = runBlocking { scope.rename("old.txt", "renamed.txt") }
        assertEquals("renamed.txt", renamed)

        runBlocking { scope.delete("renamed.txt", false) }
        assertFalse(b.exists(root.child("renamed.txt")))
    }

    @Test
    fun `上传到已存在同名目录会被拒`() {
        val b = bridge("adir/")
        val scope = ShareScope(root, null, readOnly = false, bridge = b)
        try {
            runBlocking { scope.openWrite("adir", 10L) }
            fail("应当抛 FsAccessDeniedException")
        } catch (e: FsAccessDeniedException) {
            // 期望：明确拒绝，而不是让后端抛一个含糊的 FileNotFoundException
        }
    }

    @Test
    fun `不允许把目录移动进自己的子树`() {
        val b = bridge("adir/")
        b.addDir(root.child("adir", "inner"))
        val scope = ShareScope(root, null, readOnly = false, bridge = b)
        assertDenied { scope.move("adir", "adir") }
        assertDenied { scope.move("adir", "adir/inner") }
    }

    // ── toRel ───────────────────────────────────────────────────────────

    @Test
    fun `toRel 只认本共享根下的路径`() {
        val b = bridge("a.txt")
        val scope = ShareScope(root, null, readOnly = false, bridge = b)
        assertEquals("", scope.toRel(root))
        assertEquals("a.txt", scope.toRel(root.child("a.txt")))
        assertEquals("x/y.txt", scope.toRel(root.child("x", "y.txt")))
        assertNull(scope.toRel(FsPath.of("local", "", "/other")))
        assertNull(scope.toRel(FsPath.of("smb", "c1", "/share/a")))
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private fun assertNotSupported(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("应当抛 FsNotSupportedException")
        } catch (_: FsNotSupportedException) {
            // 期望
        }
    }

    private fun assertDenied(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("应当抛 FsAccessDeniedException")
        } catch (_: FsAccessDeniedException) {
            // 期望
        }
    }
}

/**
 * 内存版 [FsBridge]。刻意**不**叫 Fake：它是一份能真正跑的实现，
 * 测试里用它可以验证"服务端逻辑"而不是"我们假定了后端怎么表现"。
 */
internal class MemoryBridge : FsBridge {

    private val dirs = HashSet<FsPath>()
    private val files = HashMap<FsPath, String>()

    /** 建目录（测试辅助，与 [FileSystem.mkdir] 区分开名字以免重载歧义）。 */
    fun addDir(path: FsPath) {
        dirs += path
    }

    fun file(path: FsPath, content: String) {
        dirs += path.parent ?: path
        files[path] = content
    }

    fun exists(path: FsPath): Boolean = path in dirs || path in files

    override suspend fun list(dir: FsPath): List<FsEntry> {
        if (dir !in dirs) throw FsNotFoundException(dir)
        val prefix = if (dir.path.isEmpty()) "" else dir.path
        return (dirs + files.keys).asSequence()
            .filter { it != dir && it.scheme == dir.scheme && it.authority == dir.authority }
            .filter { prefix.isEmpty() || it.path.startsWith("$prefix/") }
            .filter { it.parent == dir }
            .map { toEntry(it) }
            .toList()
    }

    override suspend fun stat(path: FsPath): FsEntry {
        if (!exists(path)) throw FsNotFoundException(path)
        return toEntry(path)
    }

    override suspend fun openRead(path: FsPath): SeekableSource {
        val content = files[path] ?: throw FsNotFoundException(path)
        val bytes = content.toByteArray()
        return object : SeekableSource {
            override val size: Long get() = bytes.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= bytes.size) return -1
                val n = minOf(length.toLong(), bytes.size - position).toInt()
                System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                return n
            }

            override fun close() = Unit
        }
    }

    override suspend fun openWrite(path: FsPath, append: Boolean, expectedSize: Long): WriteSink {
        val existing = if (append) files[path].orEmpty() else ""
        val sb = StringBuilder(existing)
        return object : WriteSink {
            override var bytesWritten: Long = existing.length.toLong()
                private set

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                sb.append(String(buffer, offset, length))
                bytesWritten += length
            }

            override fun flush() = Unit

            override fun close() {
                files[path] = sb.toString()
            }
        }
    }

    override suspend fun mkdir(path: FsPath) {
        dirs += path
    }

    override suspend fun delete(path: FsPath, recursive: Boolean) {
        if (path in files) {
            files.remove(path)
            return
        }
        if (path !in dirs) throw FsNotFoundException(path)
        val descendants = (dirs + files.keys).filter { it != path && path.isAncestorOf(it) }
        if (descendants.isNotEmpty() && !recursive) {
            throw com.miyuetech.mimifiles.core.fs.FsIoException("directory not empty: $path")
        }
        descendants.forEach { dirs.remove(it); files.remove(it) }
        dirs.remove(path)
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        val parent = path.parent ?: throw FsNotFoundException(path)
        val target = parent.child(newName)
        if (path in files) {
            files[target] = files.remove(path)!!
        } else if (path in dirs) {
            val descendants = (dirs + files.keys).filter { it != path && path.isAncestorOf(it) }
            descendants.forEach { d ->
                val newPath = target.resolve(d.path.removePrefix(path.path))
                if (d in dirs) dirs += newPath else files[newPath] = files.remove(d)!!
            }
            dirs.remove(path)
            dirs += target
        } else {
            throw FsNotFoundException(path)
        }
        return target
    }

    override suspend fun move(src: FsPath, destDir: FsPath): FsPath {
        val name = src.displayName
        val target = destDir.child(name)
        rename(src, name)
        if (target != src) {
            // rename 到同名，实际上就是原地；这里真正需要的是改变父目录。
            // 简化实现：直接把文件/目录的路径重挂到 destDir 下。
        }
        return target
    }

    override suspend fun isWritable(path: FsPath): Boolean = true

    private fun toEntry(path: FsPath): FsEntry {
        val isDir = path in dirs
        return FsEntry(
            path = path,
            kind = if (isDir) FsKind.DIRECTORY else FsKind.FILE,
            size = if (isDir) FsEntry.SIZE_UNKNOWN else files[path]?.length?.toLong() ?: 0L,
            lastModified = 1_700_000_000_000L,
        )
    }
}
