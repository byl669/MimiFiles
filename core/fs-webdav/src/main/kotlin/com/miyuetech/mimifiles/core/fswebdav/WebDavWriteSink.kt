package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.WriteSink
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 流式 `PUT`。
 *
 * ## 为什么不能直接用 `sardine.put`
 * sardine-android 0.9 的 `put` **只有两个重载**：`put(String, byte[])` 和
 * `put(String, File, String)`——**没有 `InputStream` 版本**（这点 javap 验过）。
 * 方案里写的"分片重传"在 0.9 上做不到，而"先落临时文件再 put"会让
 * "从 NAS 复制 5 GB 视频到 WebDAV" 吃掉手机 5 GB 存储。
 *
 * 所以这里绕开 sardine，直接用 OkHttp 发一个**真正的流式**请求体：
 * `RequestBody.contentLength()` 给出长度（有的话），`writeTo` 从管道里抽数据。
 *
 * ## 管道 + 线程：为什么必须这样
 * `WriteSink` 的 `write()` 是普通阻塞方法，而 OkHttp 的 `execute()` 也阻塞。
 * 两者必须并行——一个在"喂"，一个在"发"。用 [PipedInputStream]/[PipedOutputStream]
 * 把两者接起来是最小实现：管道自带跨线程的阻塞语义（缓冲满则 writer 阻塞、
 * 缓冲空则 reader 等待），不需要我们自己写有界队列与条件变量。
 *
 * 多一个线程的代价只在**传输期间**存在，传输结束线程即退出。
 *
 * ## 失败时要主动断开管道
 * 若服务端在传输中途返回 401/507，上传线程会带着错误退出。此时管道不再有人读，
 * 调用方下一次 `write()` 会永久阻塞。所以上传线程无论成功失败都 `close()` 读端，
 * 让写端立刻收到 broken pipe 并抛出——**卡死比报错严重得多**。
 */
internal class WebDavWriteSink(
    private val client: OkHttpClient,
    private val url: String,
    private val contentType: MediaType?,
    private val expectedSize: Long,
) : WriteSink {

    private val pipeOut = PipedOutputStream()
    private val pipeIn = PipedInputStream(pipeOut, PIPE_BUFFER)

    private val finished = CountDownLatch(1)

    private var written = 0L

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var closed = false

    private val uploader: Thread = Thread({ runUpload() }, "mimi-dav-put").apply {
        isDaemon = true
        start()
    }

    override val bytesWritten: Long get() = written

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        failure?.let { throw FsIoException("上传已失败：${it.message}", it) }
        try {
            pipeOut.write(buffer, offset, length)
            written += length
        } catch (e: IOException) {
            // 常见于"上传线程已因 4xx/5xx 退出"或"用户取消"。优先报真实原因。
            throw FsIoException(failure?.message ?: "上传中断：${e.message}", failure ?: e)
        }
    }

    override fun flush() {
        runCatching { pipeOut.flush() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { pipeOut.close() }
        // 给上传线程留足时间完成最后一次 flush 与服务端的响应处理。
        finished.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        failure?.let { throw FsIoException("上传失败：${it.message}", it) }
    }

    private fun runUpload() {
        try {
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = contentType

                override fun contentLength(): Long = expectedSize

                override fun writeTo(sink: BufferedSink) {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = try {
                            pipeIn.read(buffer)
                        } catch (_: IOException) {
                            break
                        }
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                    }
                    sink.flush()
                }
            }

            val request = Request.Builder().url(url).put(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    failure = FsIoException(
                        "PUT 失败：HTTP ${response.code} ${response.message}",
                    )
                }
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            runCatching { pipeIn.close() }
            finished.countDown()
        }
    }

    private companion object {
        /**
         * 管道缓冲 256 KB。
         *
         * 太小会频繁阻塞（喂一次等一次），太大等于把"流式"退化成内存缓冲。
         * 256 KB ≈ 4 个 64 KB 的复制块，配合 `FsOps` 的缓冲刚好不互相等待。
         */
        const val PIPE_BUFFER = 256 * 1024

        const val CLOSE_TIMEOUT_SECONDS = 120L
    }
}
