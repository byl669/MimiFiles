package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.SeekableSource
import java.io.IOException
import java.io.InputStream

/**
 * 一次 `Range` 请求的结果。
 *
 * `partial` 直接来自 HTTP 状态码（`206` vs `200`），**不是**靠比较长度猜出来的——
 * 猜法在"文件恰好比窗口短"时会误判，而误判的后果是播放器从错误的偏移解码
 * （现象是花屏或音画不同步，极难定位）。
 */
internal data class RangeResponse(
    val partial: Boolean,
    val stream: InputStream,
)

/**
 * 基于 HTTP `Range` 的随机读。
 *
 * ## 为什么要窗口缓冲
 * 最"正确"的实现是"每次 `read` 发一个 Range 请求"。播放器顺序读一个 2 GB 视频时，
 * 那意味着**几十万次 HTTP 往返**——即使局域网也会卡死。
 *
 * 这里用一个 [WINDOW_SIZE] 的滑动窗口：窗口内直接命中内存；越界才发一次请求，
 * 一次尽量多取（`max(窗口, 本次要读的长度)`）。顺序读因此退化成"每 512 KB 一次请求"。
 *
 * ## 服务端忽略 Range 时的处置
 * RFC 7233 允许服务端忽略 `Range` 并回 `200` + 全量内容。这时有两条路：
 *  - 文件不大（≤ [MAX_MIRROR_SIZE]）→ 整个读进内存当镜像，后续按偏移切片；
 *  - 文件很大 → **明确抛错**。绝不能"假装成功"地返回从头开始的数据，
 *    那会让视频静默地播错内容，比报错难查得多。
 *
 * 大文件 + 不支持 Range 的组合在真实服务端上很少见（那基本等于"不能拖进度条"），
 * 真遇到时用户可以改用 SMB——所以这里选择诚实失败。
 */
internal class WebDavSeekableSource(
    override val size: Long,
    private val openStream: (start: Long, endInclusive: Long) -> RangeResponse,
) : SeekableSource {

    private var windowStart = -1L
    private var window: ByteArray = EMPTY

    /** 退化模式：服务端忽略了 Range，整个文件已镜像在 [mirror] 里。 */
    private var mirror: ByteArray? = null

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (size >= 0 && position >= size) return -1

        mirror?.let { data ->
            if (position >= data.size) return -1
            val n = minOf(length.toLong(), (data.size - position).toLong()).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        var available = availableInWindow(position)
        if (available <= 0) {
            fetch(position, length)
            available = availableInWindow(position)
            if (available <= 0) {
                mirror?.let { data ->
                    if (position >= data.size) return -1
                    val n = minOf(length.toLong(), (data.size - position).toLong()).toInt()
                    System.arraycopy(data, position.toInt(), buffer, offset, n)
                    return n
                }
                return -1
            }
        }
        val n = minOf(length, available)
        System.arraycopy(window, (position - windowStart).toInt(), buffer, offset, n)
        return n
    }

    private fun availableInWindow(position: Long): Int {
        if (window.isEmpty() || position < windowStart) return 0
        val delta = position - windowStart
        if (delta >= window.size) return 0
        return window.size - delta.toInt()
    }

    private fun fetch(position: Long, length: Int) {
        val want = maxOf(length.toLong(), WINDOW_SIZE.toLong())
        val end = if (size > 0) minOf(position + want, size) - 1 else position + want - 1
        if (end < position) return

        try {
            val response = openStream(position, end)
            if (response.partial) {
                window = response.stream.use { readAtMost(it, want.toInt()) }
                windowStart = position
                return
            }
            // 服务端忽略了 Range：拿到了从 0 开始的完整响应。
            response.stream.use { stream ->
                if (size > MAX_MIRROR_SIZE) {
                    throw FsIoException(
                        "服务端不支持 Range 请求，且文件大小 ${size / 1024 / 1024} MB " +
                            "超过可镜像上限 ${MAX_MIRROR_SIZE / 1024 / 1024} MB；" +
                            "该文件无法拖动进度条（建议改用 SMB 访问同一位置）",
                    )
                }
                mirror = readAtMost(stream, MAX_MIRROR_SIZE.toInt())
            }
        } catch (e: FsIoException) {
            throw e
        } catch (e: IOException) {
            throw FsIoException("range read failed at $position: ${e.message}", e)
        }
    }

    /**
     * 最多读 [limit] 字节。
     *
     * 不用 `readBytes()`：那会一直读到流结束，对"服务端坚持返回全量"的服务端
     * 就是一次无上限的内存分配。这里读到 limit 就停，把内存风险关在常量里。
     */
    private fun readAtMost(stream: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 256 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (total < limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    override fun close() {
        window = EMPTY
        windowStart = -1L
        mirror = null
    }

    private companion object {
        /** 窗口 512 KB：够挡住"顺序小块读"，又不至于在手机上浪费内存。 */
        const val WINDOW_SIZE = 512 * 1024

        /** 退化模式下允许镜像的最大文件。64 MB 是"不影响其他应用"的保守值。 */
        const val MAX_MIRROR_SIZE = 64L * 1024 * 1024

        val EMPTY = ByteArray(0)
    }
}
