package com.miyuetech.mimifiles.core.fssmb

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.BufferedSeekableSource
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.smb.SmbFile
import java.io.IOException
import java.io.InputStream

/** 与 `SmbConnector` / `SmbFileSystem` 同一个 tag，便于 `adb logcat -s MimiSmb` 一次看全。 */
private const val TAG = "MimiSmb"

/**
 * SMB1 的预读窗口取 1 MiB。
 *
 * 与 SMB2 路径取同一个值，理由不同：SMB1 的单次 READ 上限更小（jcifs 默认 64 KiB），
 * 所以窗口并不是"一次往返"，而是"把 16 次 64 KiB 的往返合并成一次**连续**的顺序读"
 * ——收益来自"底层流一路向前、不必反复 skip/重开"，而不是省往返次数。
 */
private const val PREFETCH_WINDOW = 1024 * 1024

/**
 * SMB1 传输实现（jcifs-ng），用于 SMB2/3 协商失败的老 NAS 与电视盒子。
 *
 * ## 与 smbj 实现的三个实质差异（都写在这里，不让上层去猜）
 *
 * **一、没有服务端复制。** SMB1 没有 COPYCHUNK，[copyServerSide] 永远返回 `false`，
 * 跨目录复制一律走流式。对参考应用验证过的老设备（群晖 DSM 5、老款巴法络）这是必然的。
 *
 * **二、没有真正的随机读。** jcifs-ng 的 `openInputStream` 三个 int 参数是
 * `(shareAccess, flags, access)`，**不是** `(offset, length)`——这点很容易记错
 * （旧版 jcifs 确实有 `getInputStream(int off, int len)`）。所以这里的
 * [JcifsSeekableSource] 用"顺序流 + skip"来近似：顺序读时几乎零代价，
 * 一旦使用者往回 seek，就得重开流再跳。SMB1 本身就是兼容档位，
 * 这个代价换来的是"视频仍能拖动，只是拖完要等一会儿"。
 *
 * **三、路径必须带尾斜杠才稳。** jcifs 对目录 URL 的尾斜杠比 smbj 敏感，
 * [dir] 与 [entry] 因此分成两个构造口，别合并。
 */
internal class JcifsTransport(
    private val ctx: CIFSContext,
    /** `smb://host[:port]/share[/sub]`，无尾斜杠。 */
    private val baseUrl: String,
) : SmbTransport {

    override val dialectLabel: String = "SMB1（已降级，性能较低）"

    /**
     * jcifs-ng 恒为"活着"：它的 `CIFSContext` 内部会按需重连（连接池自己管存活），
     * 没有对外暴露的存活判据，而硬造一个只会带来误判——把一个还能用的会话判死、
     * 白白多付一次 SMB1 握手（SMB1 的握手比 SMB2 慢得多）。
     */
    override val isAlive: Boolean get() = true

    override fun list(path: String): List<SmbDirEntry> {
        val children = dir(path).listFiles() ?: return emptyList()
        val out = ArrayList<SmbDirEntry>(children.size)
        for (child in children) {
            val name = child.name.trimEnd('/')
            // jcifs 在部分服务端上也会把 "." / ".." 带回来
            if (name.isEmpty() || name == "." || name == "..") continue
            val isDir = child.isDirectory
            val attrs = runCatching { child.attributes }.getOrDefault(0)
            out += SmbDirEntry(
                name = name,
                isDirectory = isDir,
                size = if (isDir) SmbDirEntry.UNKNOWN else runCatching { child.length() }.getOrDefault(SmbDirEntry.UNKNOWN),
                lastModified = runCatching { child.lastModified }.getOrDefault(0L),
                isHidden = attrs and SmbConstants.ATTR_HIDDEN != 0,
                isReadOnly = attrs and SmbConstants.ATTR_READONLY != 0,
            )
        }
        return out
    }

    override fun stat(path: String): SmbDirEntry {
        // 先按目录试：目录的 URL 需要尾斜杠，而文件的 URL 绝不能带——
        // 用一个 SmbFile 同时问两种形态在 jcifs 上会得到"not a directory"。
        val asDir = dir(path)
        if (runCatching { asDir.exists() && asDir.isDirectory }.getOrDefault(false)) {
            val attrs = runCatching { asDir.attributes }.getOrDefault(0)
            return SmbDirEntry(
                name = nameOf(path),
                isDirectory = true,
                size = SmbDirEntry.UNKNOWN,
                lastModified = runCatching { asDir.lastModified }.getOrDefault(0L),
                isHidden = attrs and SmbConstants.ATTR_HIDDEN != 0,
                isReadOnly = attrs and SmbConstants.ATTR_READONLY != 0,
            )
        }
        val asFile = entry(path)
        val attrs = runCatching { asFile.attributes }.getOrDefault(0)
        return SmbDirEntry(
            name = nameOf(path),
            isDirectory = false,
            size = runCatching { asFile.length() }.getOrDefault(SmbDirEntry.UNKNOWN),
            lastModified = runCatching { asFile.lastModified }.getOrDefault(0L),
            isHidden = attrs and SmbConstants.ATTR_HIDDEN != 0,
            isReadOnly = attrs and SmbConstants.ATTR_READONLY != 0,
        )
    }

    override fun exists(path: String): Boolean =
        runCatching { dir(path).exists() || entry(path).exists() }.getOrDefault(false)

    /**
     * 打开读句柄，并套一层与 SMB2 路径**同一个**顺序预读窗口。
     *
     * ## 为什么 SMB1 也要预读
     * jcifs-ng 的单次 SMB1 READ 上限由 `jcifs.smb.client.rcv_buf_size` 决定
     * （默认 64 KiB），比 SMB2 更小；而消费端（Media3 64 KiB、安装器 32~64 KiB）
     * 要的块和它一样大，于是同样是"一次消费 = 一次往返"。窗口把这一点拉平。
     *
     * ## 与 [JcifsSeekableSource] 的顺序流语义如何共存
     * 预读层在命中窗口时不碰底层；未命中时才要一整窗口。对顺序读这正好是
     * "底层流一路向前缓读"，与"顺序流 + skip"天然契合；对回退 seek，
     * 预读层要求一整窗口，底层便重开流再 skip——代价与没有预读层时相同。
     */
    override fun openRead(path: String): SeekableSource {
        val file = entry(path)
        val size = runCatching { file.length() }.getOrDefault(SeekableSource.SIZE_UNKNOWN)
        return BufferedSeekableSource(
            delegate = JcifsSeekableSource(file, size),
            windowSize = PREFETCH_WINDOW,
            onClose = { stats -> MimiLog.d(TAG, "close read (SMB1) $path | $stats") },
        )
    }

    override fun openWrite(path: String, append: Boolean, expectedSize: Long): WriteSink {
        val file = entry(path)
        val out = if (append) file.openOutputStream(true) else file.openOutputStream(false)
        return object : WriteSink {
            private var written = 0L
            override val bytesWritten: Long get() = written

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                out.write(buffer, offset, length)
                written += length
            }

            override fun flush() = out.flush()

            override fun close() = out.close()
        }
    }

    override fun mkdir(path: String) {
        dir(path).mkdir()
    }

    override fun delete(path: String, recursive: Boolean) {
        // 是目录就用目录 URL，是文件就用文件 URL——jcifs 对这两者的 URL 形态敏感，
        // 用错形态会得到 "not a directory" 这种与真实原因无关的报错。
        val target = if (runCatching { dir(path).isDirectory }.getOrDefault(false)) dir(path) else entry(path)
        if (recursive) recursiveDelete(target) else target.delete()
    }

    /**
     * 递归删除。
     *
     * 必须自己走：jcifs 的 `SmbFile.delete()` 只删**空目录**（SMB1 的 DELETE 语义如此），
     * 对非空目录会抛错。smbj 那边有 `rmdir(path, true)` 代劳，SMB1 没有等价能力。
     */
    private fun recursiveDelete(target: SmbFile) {
        val isDir = runCatching { target.isDirectory }.getOrDefault(false)
        if (isDir) {
            target.listFiles()?.forEach { recursiveDelete(it) }
        }
        target.delete()
    }

    override fun rename(path: String, newName: String) {
        val src = if (runCatching { dir(path).isDirectory }.getOrDefault(false)) dir(path) else entry(path)
        val parent = path.substringBeforeLast('/', "")
        val target = if (parent.isEmpty()) dir(newName) else dir("$parent/$newName")
        src.renameTo(target, true)
    }

    override fun move(src: String, destDir: String): Boolean = try {
        val source = if (runCatching { dir(src).isDirectory }.getOrDefault(false)) dir(src) else entry(src)
        val name = src.substringAfterLast('/')
        val target = if (destDir.isEmpty()) dir(name) else dir("$destDir/$name")
        source.renameTo(target, true)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * SMB1 没有服务端复制（无 COPYCHUNK）。
     *
     * 直接返回 `false`，让 [SmbFileSystem] 走流式——**不要**在这里用
     * `SmbFile.copyTo()`：jcifs 的 copyTo 对目录会做递归，而我们的调用方
     * （`FsOps`）自己已经处理递归与冲突策略，两边都做会导致重复复制。
     */
    override fun copyServerSide(src: String, destDir: String): Boolean = false

    override fun probe() {
        dir("").listFiles()
    }

    override fun close() {
        runCatching { ctx.close() }
    }

    // ── 路径构造 ────────────────────────────────────────────────────────

    private fun dir(path: String): SmbFile =
        SmbFile(if (path.isEmpty()) "$baseUrl/" else "$baseUrl/${path.trim('/')}/", ctx)

    private fun entry(path: String): SmbFile =
        SmbFile("$baseUrl/${path.trim('/')}", ctx)

    private fun nameOf(path: String): String = path.trim('/').substringAfterLast('/')

    // ── 读句柄：顺序流 + skip 的近似随机读 ──────────────────────────────

    private class JcifsSeekableSource(
        private val file: SmbFile,
        override val size: Long,
    ) : SeekableSource {

        private var stream: InputStream? = null

        /** 当前流已读到哪一字节。 */
        private var cursor = 0L

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            if (size >= 0 && position >= size) return -1
            try {
                val input = streamAt(position)
                var done = 0
                while (done < length) {
                    // 与 `SmbjTransport.SmbjSeekableSource.readFrom` 同一条规矩：
                    // 到 EOF 就停，不发"起点 = 文件末尾"的读。
                    //
                    // SMB1 本身对越界读只是回 0 字节（不像 SMB2 那边会让 smbj 解析
                    // 错误响应时炸掉整条连接，见那边的长注释），所以这一条在这里
                    // **不是**在修一个已知故障，而是抹掉同类形状：两处循环长得一样，
                    // 只改一处，下一个人照抄时抄到的是哪一处就是运气。
                    if (size >= 0 && position + done >= size) break
                    val n = input.read(buffer, offset + done, length - done)
                    if (n <= 0) break
                    done += n
                    cursor += n
                }
                return if (done == 0) -1 else done
            } catch (e: IOException) {
                throw e
            }
        }

        /** 取得位于 [position] 的流：能顺延就顺延，要回退就重开。 */
        private fun streamAt(position: Long): InputStream {
            val current = stream
            if (current != null && position >= cursor) {
                skipFully(current, position - cursor)
                cursor = position
                return current
            }
            current?.let { runCatching { it.close() } }
            val fresh = file.openInputStream()
            skipFully(fresh, position)
            stream = fresh
            cursor = position
            return fresh
        }

        /**
         * 把流推进 [delta] 字节。
         *
         * 必须循环：`InputStream.skip` 允许"少跳"（SMB 的实现受单次请求上限约束，
         * 一次 skip 1 MB 实际可能只跳 64 KB）。只调一次会让后续读取从错误的位置开始，
         * 表现是"视频拖动后花屏"——极难排查，所以这里一次到位。
         */
        private fun skipFully(input: InputStream, delta: Long) {
            var remaining = delta
            val sink = ByteArray(SKIP_BUFFER)
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val n = input.read(sink, 0, minOf(remaining, SKIP_BUFFER.toLong()).toInt())
                if (n <= 0) break
                remaining -= n
            }
        }

        override fun close() {
            runCatching { stream?.close() }
            stream = null
        }

        private companion object {
            const val SKIP_BUFFER = 64 * 1024
        }
    }
}
