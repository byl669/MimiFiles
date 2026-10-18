package com.miyuetech.mimifiles.core.fs

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * 支持**随机读**的只读句柄。
 *
 * 这是「SMB 上的视频能拖进度条」的地基：播放器要 seek 到第 N 字节，
 * 我们直接向后端要那一段，而不是从 0 开始把整个文件下载完。
 *
 * ## 实现契约
 * 后端**只需**实现 [size]、`read(position, ...)`、`close()` 三件事；
 * [stream] 与 [readBytes] 由默认实现基于 `read` 构造，因此顺序型后端（如一次性的 HTTP 响应）
 * 也能安全地退化成可用状态——代价是每次 seek 都要重新 skip。
 */
interface SeekableSource : Closeable {

    /** 总字节数；未知时返回 [SIZE_UNKNOWN]。 */
    val size: Long

    /**
     * 从 [position] 起读最多 [length] 字节写入 `buffer[offset ..]`。
     *
     * @return 实际读到的字节数；`-1` 表示已到文件末尾（与 [InputStream] 契约一致）。
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * 从 [position] 起的顺序流视图。
     *
     * 注意：**关闭返回的流不会关闭本对象**——[stream] 只是一层视图，
     * 生命周期仍由持有者通过 [close] 管理。这样调用方可以放心对视图用 `use { }`。
     */
    fun stream(position: Long = 0L): InputStream = object : InputStream() {
        private var pos = position
        private val single = ByteArray(1)

        override fun read(): Int {
            val n = this@SeekableSource.read(pos, single, 0, 1)
            if (n <= 0) return -1
            pos += 1
            return single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = this@SeekableSource.read(pos, b, off, len)
            if (n > 0) pos += n
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0L) return 0L
            val end = if (size >= 0) (pos + n).coerceAtMost(size) else pos + n
            val skipped = end - pos
            pos = end
            return skipped
        }

        override fun available(): Int {
            if (size < 0) return 0
            val remaining = size - pos
            return if (remaining <= 0) 0 else remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        override fun close() {
            // 刻意不关闭底层：视图关闭不应影响持有者的生命周期
        }
    }

    /** 精确读取 [length] 字节；不足则返回实际长度（不补零）。 */
    fun readBytes(position: Long, length: Int): ByteArray {
        require(length >= 0) { "length must be >= 0" }
        if (length == 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = read(position + read, buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) buffer else buffer.copyOf(read)
    }

    companion object {
        const val SIZE_UNKNOWN = -1L
    }
}

/**
 * 只写句柄。
 *
 * [bytesWritten] 是给"上传进度"用的：SMB / WebDAV 上传要显示进度条，
 * 而调用方不该自己去数写入字节（容易和压缩/加密层对不上）。
 */
interface WriteSink : Closeable {

    val bytesWritten: Long

    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset)

    fun flush()

    /** 顺序写视图；与 [SeekableSource.stream] 一样，关闭视图不会关闭本对象。 */
    fun stream(): OutputStream = object : OutputStream() {
        private val single = ByteArray(1)

        override fun write(b: Int) {
            single[0] = b.toByte()
            this@WriteSink.write(single, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            this@WriteSink.write(b, off, len)
        }

        override fun flush() {
            this@WriteSink.flush()
        }

        override fun close() {
            // 视图关闭不关闭底层
        }
    }
}
