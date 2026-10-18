package com.miyuetech.mimifiles.core.shareserver.ftp

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.shareserver.RelPath
import com.miyuetech.mimifiles.core.shareserver.ShareNode
import com.miyuetech.mimifiles.core.shareserver.ShareScope
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.ftplet.FtpFile

/**
 * 把 [ShareScope] 里的一条条目适配成 Apache FtpServer 的 [FtpFile]。
 *
 * ## 为什么每个方法都要 `runBlocking`
 * `ftplet` 的接口是**同步**的（`boolean delete()`、`InputStream createInputStream(long)`），
 * 而 [com.miyuetech.mimifiles.core.fs.FileSystem] 是 suspend 的。二者之间只能有一个
 * 阻塞适配层，位置选在这里而不是"给 FileSystem 加一套阻塞 API"：
 * 阻塞 API 一旦存在，就会有人从别的异步上下文里调用它。
 *
 * FtpServer 把每个会话放在 MINA 的线程池里跑（我们在 `ConnectionConfigFactory` 里
 * 限了 8 条），所以这里的阻塞只占用本会话自己的一条线程——与 HTTP 侧把
 * `runBlocking` 放在 NanoHTTPD 的每连接线程上是同一个道理。
 *
 * ## 关于 `rel == null`
 * 路径越狱或非法时**不抛异常**，而是构造一个"不存在"的文件对象（[rel] 为 `null`）。
 * 原因：FtpServer 会在真正操作之前调用 `doesExist()` / `isDirectory()` 去决定回哪条
 * 应答码，一个会抛异常的 `getFile()` 会让它把 550 变成 500。
 * 而"不存在"与"越狱"回同一个 550，也顺带不给探测者额外的信息。
 */
class ShareFtpFile(
    private val scope: ShareScope,
    private val rel: String?,
    private val userName: String,
) : FtpFile {

    private var cached: ShareNode? = null

    private fun node(): ShareNode? {
        val r = rel ?: return null
        cached?.let { return it }
        val value = runCatching { runBlocking { scope.stat(r) } }.getOrNull()
        cached = value
        return value
    }

    /** 让同一会话内的重复查询（FtpServer 会连着问 doesExist/isDirectory/isFile）只读一次元数据。 */
    fun invalidate() {
        cached = null
    }

    // ── 元数据 ──────────────────────────────────────────────────────────

    override fun getAbsolutePath(): String = "/" + (rel ?: INVALID)

    override fun getName(): String = RelPath.name(rel ?: INVALID)

    override fun isHidden(): Boolean = getName().startsWith(".")

    override fun isDirectory(): Boolean = node()?.isDirectory == true

    override fun isFile(): Boolean = node()?.let { !it.isDirectory } == true

    override fun doesExist(): Boolean = node() != null

    override fun isReadable(): Boolean = doesExist()

    /**
     * 可写判定。
     *
     * ## 为什么不能写成 "`doesExist() && scope.writable`"
     * 那是最自然的写法，但它让**上传新文件**永远失败：`STOR foo.txt` 时
     * `foo.txt` 还不存在，于是 `isWritable()` 为假，Apache 的 `STOR` 命令层直接回
     * `550 Permission denied`——而用户只是想把一个新文件传上去。
     *
     * 正确的判据分两种情形：
     *  - **已存在** → 看共享会话是否可写（覆盖已有文件）；
     *  - **不存在** → 看**父目录**在不在、是不是目录（"往这个位置放一个新文件"，权限
     *    由父目录决定——这与 POSIX 的语义一致：创建文件的权限看目录的写位）。
     *
     * 另外根目录本身不可写：`STOR /` 是"用一个文件替换掉整个共享根"，
     * 既无意义也该被挡住（同 [isRemovable] 的理由）。
     */
    override fun isWritable(): Boolean {
        if (!scope.writable) return false
        val r = rel ?: return false
        if (r.isEmpty()) return false
        if (node() != null) return true
        val parentRel = RelPath.parent(r) ?: return false
        if (parentRel.isEmpty()) return true
        val parent = runCatching { runBlocking { scope.stat(parentRel) } }.getOrNull() ?: return false
        return parent.isDirectory
    }

    /** 根目录不可删（与资源管理器一致：不能"删掉整个共享"）。 */
    override fun isRemovable(): Boolean = scope.writable && doesExist() && (rel?.isNotEmpty() == true)

    override fun getOwnerName(): String = userName

    override fun getGroupName(): String = GROUP

    override fun getLinkCount(): Int = if (isDirectory()) 2 else 1

    override fun getLastModified(): Long = node()?.lastModified ?: 0L

    /** 不支持改时间（后端差异太大，且 FTP 客户端几乎不用）。 */
    override fun setLastModified(time: Long): Boolean = false

    override fun getSize(): Long = node()?.size?.coerceAtLeast(0L) ?: 0L

    /**
     * 真实文件对象。
     *
     * 恒返回 `null`：FtpServer 只在"用默认 FileSystemView 实现"时才会拿它做
     * 本地优化（例如 `File.renameTo`），而我们走的是自己的 [ShareScope]。
     * 返回一个 `java.io.File` 反而危险——那等于把底层真实路径交给第三方库。
     */
    override fun getPhysicalFile(): Any? = null

    // ── 修改操作 ────────────────────────────────────────────────────────

    override fun mkdir(): Boolean {
        val r = rel ?: return false
        val parent = RelPath.parent(r) ?: return false
        val name = RelPath.name(r)
        if (name.isEmpty()) return false
        return try {
            runBlocking { scope.mkdir(parent, name) }
            invalidate()
            true
        } catch (e: Exception) {
            MimiLog.d(TAG, "FTP MKD 失败：/$r —— ${e.message}")
            false
        }
    }

    override fun delete(): Boolean {
        val r = rel ?: return false
        if (r.isEmpty()) return false
        return try {
            // `recursive = false`：FTP 的 RMD 对**非空目录必须失败**（这是协议语义，
            // Windows 资源管理器也依赖它来提示"目录不为空"）。
            // 递归删除属于"危险操作"，不该由一条 RMD 顺手完成。
            runBlocking { scope.delete(r, recursive = false) }
            invalidate()
            true
        } catch (e: Exception) {
            MimiLog.d(TAG, "FTP DELE/RMD 失败：/$r —— ${e.message}")
            false
        }
    }

    override fun move(destination: FtpFile): Boolean {
        val r = rel ?: return false
        if (r.isEmpty()) return false
        val destPath = destination.absolutePath ?: return false
        val destRel = RelPath.normalize(destPath.removePrefix("/")) ?: return false
        if (destRel.isEmpty()) return false
        val destParent = RelPath.parent(destRel) ?: RelPath.ROOT
        val destName = RelPath.name(destRel)
        if (destName.isEmpty()) return false
        return try {
            runBlocking {
                if (destParent == (RelPath.parent(r) ?: RelPath.ROOT)) {
                    scope.rename(r, destName)
                } else {
                    // 跨目录改名（RNFR/RNTO 可以带路径）。先移动再改名，
                    // 因为 FileSystem.rename 只接受"同目录下换个名字"。
                    val moved = scope.move(r, destParent)
                    if (RelPath.name(moved) != destName) scope.rename(moved, destName)
                }
            }
            invalidate()
            true
        } catch (e: Exception) {
            MimiLog.d(TAG, "FTP RNFR/RNTO 失败：/$r -> $destRel —— ${e.message}")
            false
        }
    }

    override fun listFiles(): MutableList<FtpFile> {
        val r = rel ?: return mutableListOf()
        val children = runCatching { runBlocking { scope.list(r) } }.getOrNull() ?: return mutableListOf()
        return children.mapTo(ArrayList(children.size)) { ShareFtpFile(scope, it.rel, userName) }
    }

    // ── 数据通道 ────────────────────────────────────────────────────────

    /**
     * 读。`offset` 由 FTP 的 `REST` 命令给出，是"断点续传/拖动播放"的基础。
     *
     * 返回的流在关闭时**一并关闭** [SeekableSource]：FtpServer 只关它拿到的
     * InputStream，如果不在这里回收，一次 RETR 就会漏一个后端句柄
     * （SMB 上是漏一条会话，几十次之后 NAS 就开始拒绝新连接）。
     */
    override fun createInputStream(offset: Long): InputStream {
        val r = rel ?: throw IOException("no such file")
        val source = try {
            runBlocking { scope.openRead(r) } ?: throw IOException("not a file")
        } catch (e: FsException) {
            throw IOException(e.message, e)
        }
        return SourceStream(source, offset.coerceAtLeast(0L))
    }

    /**
     * 写。
     *
     * `offset > 0` 表示客户端要做**续传**（`REST` + `STOR`）。我们的后端没有
     * 随机写（SMB 的 `openWrite` 只有覆盖与追加），所以只能退化成"追加"：
     * 写在前一次中断的位置之后，结果正确；但从头断点重来（offset 回退）会得到
     * 一个拼接错误的文件——那属于客户端行为异常，且任何只支持追加的 FTP 服务端
     * 都是这个表现。`offset == 0`（绝大多数上传）走覆盖，语义精确。
     */
    override fun createOutputStream(offset: Long): OutputStream {
        val r = rel ?: throw IOException("no such file")
        val sink = try {
            runBlocking { scope.openWrite(r, expectedSize = -1L, append = offset > 0L) }
        } catch (e: FsException) {
            throw IOException(e.message, e)
        }
        return SinkStream(sink)
    }

    private class SourceStream(private val source: SeekableSource, position: Long) : InputStream() {

        private val delegate = source.stream(position)

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun skip(n: Long): Long = delegate.skip(n)

        override fun available(): Int = delegate.available()

        override fun close() {
            runCatching { delegate.close() }
            runCatching { source.close() }
        }
    }

    private class SinkStream(private val sink: com.miyuetech.mimifiles.core.fs.WriteSink) : OutputStream() {

        private val delegate = sink.stream()

        override fun write(b: Int) = delegate.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun flush() = delegate.flush()

        override fun close() {
            runCatching { delegate.close() }
            runCatching { sink.close() }
        }
    }

    companion object {
        private const val TAG = "MimiShareFtp"
        private const val GROUP = "mimifiles"

        /**
         * 非法路径的占位名。
         *
         * 刻意**不用**含控制字符的串（那会污染 logcat 与 FTP 客户端的日志窗口，
         * 而且 ftpserver 会原样把它写进应答）。用这个不可能真实存在的名字即可：
         * `rel == null` 时 [node] 恒返回 `null`，`doesExist()` 稳定为假，
         * 无论是否存在一个恰好叫这个名字的真实文件。
         */
        private const val INVALID = "__mimi_invalid_path__"
    }
}
