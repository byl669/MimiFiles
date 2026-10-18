package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import java.io.ByteArrayOutputStream

/**
 * 纯内存的文件系统，供单测使用。
 *
 * 有了它，[FsOps] 的递归复制、冲突改名、自嵌套防护、进度回调这些**最容易出事又最难手测**
 * 的逻辑，可以在 JVM 上毫秒级跑穿；等到接 SMB 时，同一批测试换个后端就是回归集。
 */
class MemoryFileSystem(
    override val scheme: String = "mem",
    override val displayName: String = "Memory",
    override val capabilities: Int = FsCapability.FULL_LOCAL,
    /** 置 false 可模拟"后端不支持原生 copy/move"，用于验证 [FsOps] 的流式回退分支。 */
    var supportsNativeCopy: Boolean = true,
) : FileSystem {

    private sealed interface Node {
        data object Dir : Node
        data class File(val bytes: ByteArray, val modified: Long = 0L) : Node
    }

    private val nodes = LinkedHashMap<FsPath, Node>()
    private val root: FsPath = FsPath.of(scheme, "", "")

    /** 记录 openWrite 的调用，便于断言"确实走了流式路径"。 */
    var writeCount: Int = 0
        private set

    init {
        nodes[root] = Node.Dir
    }

    // ── 测试辅助 ─────────────────────────────────────────────────────────

    fun putFile(path: FsPath, content: ByteArray) {
        require(!path.isRoot) { "cannot write to root" }
        nodes[path] = Node.File(content)
    }

    fun putFile(path: FsPath, content: String) = putFile(path, content.toByteArray())

    fun putDir(path: FsPath) {
        nodes[path] = Node.Dir
    }

    fun readText(path: FsPath): String =
        (nodes[path] as? Node.File)?.bytes?.toString(Charsets.UTF_8)
            ?: throw FsNotFoundException(path)

    fun sizeOf(path: FsPath): Int =
        (nodes[path] as? Node.File)?.bytes?.size ?: throw FsNotFoundException(path)

    fun allPaths(): Set<FsPath> = nodes.keys.toSet()

    // ── FileSystem ──────────────────────────────────────────────────────

    override suspend fun roots(): List<FsEntry> =
        listOf(FsEntry(path = root, name = displayName, kind = FsKind.DIRECTORY))

    override suspend fun list(dir: FsPath): List<FsEntry> {
        val node = nodes[dir] ?: throw FsNotFoundException(dir)
        if (node !is Node.Dir) throw FsIoException("not a directory: $dir")
        return nodes.entries
            .filter { it.key != dir && it.key.parent == dir }
            .map { (path, n) -> toEntry(path, n) }
    }

    override suspend fun stat(path: FsPath): FsEntry =
        toEntry(path, nodes[path] ?: throw FsNotFoundException(path))

    override suspend fun openRead(path: FsPath): SeekableSource {
        val node = nodes[path] ?: throw FsNotFoundException(path)
        if (node !is Node.File) throw FsIoException("not a file: $path")
        return ByteArraySource(node.bytes)
    }

    override suspend fun openWrite(path: FsPath, append: Boolean, expectedSize: Long): WriteSink {
        if (path.parent?.let { nodes[it] } !is Node.Dir) {
            throw FsNotFoundException(path.parent ?: path)
        }
        writeCount++
        val existing = (nodes[path] as? Node.File)?.bytes
        val buffer = ByteArrayOutputStream()
        if (append && existing != null) buffer.write(existing)
        return object : WriteSink {
            private var written = 0L
            private var closed = false

            override val bytesWritten: Long get() = written

            override fun write(buffer0: ByteArray, offset: Int, length: Int) {
                buffer.write(buffer0, offset, length)
                written += length
            }

            override fun flush() = Unit

            override fun close() {
                if (closed) return
                closed = true
                nodes[path] = Node.File(buffer.toByteArray())
            }
        }
    }

    override suspend fun mkdir(path: FsPath) {
        val parent = path.parent ?: throw FsIoException("cannot mkdir root")
        if (nodes[parent] !is Node.Dir) throw FsNotFoundException(parent)
        if (nodes.containsKey(path)) throw FsAlreadyExistsException(path)
        nodes[path] = Node.Dir
    }

    override suspend fun delete(path: FsPath, recursive: Boolean) {
        if (!nodes.containsKey(path)) throw FsNotFoundException(path)
        val descendants = nodes.keys.filter { it.isDescendantOf(path) }
        if (!recursive) {
            val hasChildren = descendants.any { it.parent == path }
            if (hasChildren) throw FsIoException("directory not empty: $path")
        }
        nodes.remove(path)
        descendants.forEach { nodes.remove(it) }
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        val parent = path.parent ?: throw FsIoException("cannot rename root")
        val target = parent.child(newName)
        if (nodes.containsKey(target)) throw FsAlreadyExistsException(target)
        return relocate(path, target)
    }

    override suspend fun move(src: FsPath, destDir: FsPath): FsPath {
        if (!supportsNativeCopy) throw FsNotSupportedException(scheme, "move")
        if (nodes[destDir] !is Node.Dir) throw FsNotFoundException(destDir)
        return relocate(src, destDir.child(src.displayName))
    }

    override suspend fun copy(src: FsPath, destDir: FsPath): FsPath {
        if (!supportsNativeCopy) throw FsNotSupportedException(scheme, "copy")
        if (nodes[destDir] !is Node.Dir) throw FsNotFoundException(destDir)
        val target = destDir.child(src.displayName)
        cloneTree(src, target)
        return target
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun toEntry(path: FsPath, node: Node): FsEntry = when (node) {
        is Node.Dir -> FsEntry(path = path, kind = FsKind.DIRECTORY, isHidden = path.isHidden)
        is Node.File -> FsEntry(
            path = path,
            kind = FsKind.FILE,
            size = node.bytes.size.toLong(),
            lastModified = node.modified,
            isHidden = path.isHidden,
        )
    }

    private fun relocate(src: FsPath, target: FsPath): FsPath {
        val moving = nodes.keys.filter { it == src || it.isDescendantOf(src) }
        require(moving.isNotEmpty()) { "source missing: $src" }
        for (old in moving) {
            val node = nodes.remove(old) ?: continue
            val tail = old.path.removePrefix(src.path)
            nodes[FsPath.of(scheme, "", target.path + tail)] = node
        }
        return target
    }

    private fun cloneTree(src: FsPath, target: FsPath) {
        when (val node = nodes[src] ?: throw FsNotFoundException(src)) {
            is Node.Dir -> {
                nodes[target] = Node.Dir
                nodes.keys.filter { it.parent == src }.forEach { child ->
                    cloneTree(child, target.child(child.displayName))
                }
            }
            is Node.File -> nodes[target] = Node.File(node.bytes.copyOf(), node.modified)
        }
    }

    private class ByteArraySource(private val data: ByteArray) : SeekableSource {
        override val size: Long get() = data.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < 0) throw FsIoException("negative position: $position")
            if (position >= data.size) return -1
            val n = minOf(length.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun close() = Unit
    }
}

/**
 * 便捷构造：一次性把字面量目录树塞进内存后端。
 * `content == null` 表示建目录（这样"空文件"和"目录"不会混淆）。
 */
fun MemoryFileSystem.seed(vararg spec: Pair<String, String?>) {
    for ((rawPath, content) in spec) {
        val path = FsPath.of(scheme, "", rawPath)
        if (content == null) putDir(path) else putFile(path, content)
    }
}
