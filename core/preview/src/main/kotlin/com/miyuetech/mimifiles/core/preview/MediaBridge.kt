package com.miyuetech.mimifiles.core.preview

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * 把 `FileSystem` 接到 Media3 —— 方案 §6.3 那张表里的「数据源」一行。
 *
 * ## 为什么必须是自定义 DataSource，而不是"先下载到临时文件再播"
 * 播放器的工作方式注定要随机访问：起播要读 `moov`/索引（可能在文件**末尾**）、
 * seek 要跳到任意关键帧、`MediaExtractor` 还会来回试探容器结构。
 * 如果先下载再播放，用户点开一个 2 GB 的片子要等好几分钟才有画面；
 * 而走 [SeekableSource]，起播只需要几次 ranged read（局域网内几十毫秒）。
 *
 * ## ⚠️ 阻塞接口与挂起 API 的接缝：`runBlocking`
 * Media3 的 [DataSource.open] 是**同步阻塞**方法，而本工程的文件系统抽象全是
 * `suspend`。这里用 `runBlocking` 把两者接起来，并且是**安全**的：
 * `open()` 由 ExoPlayer 的内部加载线程调用（`Loader` 的专用线程），
 * **绝不会是主线程**——因此不会阻塞 UI，也不会与主线程的协程抢调度。
 *
 * 反过来，"把 `open()` 改成异步"在此接口下不可能（签名就长这样）。
 * 这个接缝是**被迫**的，所以在此显式记下来：**任何在 Media3 回调里调 `open()`
 * 的代码路径都必须在非主线程上**。
 *
 * ## 关于 `length`
 * [DataSpec.length] 为 [C.LENGTH_UNSET] 表示"读到文件末尾"。读到超过有效长度的
 * 位置必须停：播放器依赖这个边界来判断文件结束，越界读会让它以为文件更长。
 */
class SeekableSourceDataSource(
    private val registry: FileSystemRegistry = FileSystems.registry,
    /**
     * "只播文件里的一段"：起始偏移与长度（动态照片的内嵌视频用）。
     *
     * 默认关闭（`-1`）。开启时打开的句柄会被 [SliceSeekableSource] 包一层，
     * 于是播放器看到的"文件"就是那段视频——**不需要把视频抠出来落盘**。
     */
    private val sliceOffset: Long = -1L,
    private val sliceLength: Long = -1L,
) : BaseDataSource(/* isNetwork = */ true) {

    private var source: SeekableSource? = null

    private var currentUri: Uri? = null

    /** 本次打开允许读取的剩余字节数；[Long.MAX_VALUE] 表示不限（直到 EOF）。 */
    private var bytesRemaining: Long = 0L

    private var readPosition: Long = 0L

    /** 是否处于"已 open 未 close"状态。用于保证 `transferStarted`/`transferEnded` 严格配对。 */
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = PreviewUri.pathOf(dataSpec.uri)
            ?: throw IOException("不是可供播放的地址：${dataSpec.uri}")

        val fileSystem: FileSystem = runBlocking { registry.resolve(path) }
        val opened = runBlocking { fileSystem.openRead(path) }
        // 切片：把"文件里的一段"当成整个文件交给播放器。
        val handle: SeekableSource = if (sliceOffset >= 0L) {
            val length = if (sliceLength > 0L) sliceLength else (opened.size - sliceOffset)
            if (length <= 0L) {
                runCatching { opened.close() }
                throw IOException("切片长度非法：offset=$sliceOffset length=$length")
            }
            SliceSeekableSource(opened, sliceOffset, length)
        } else {
            opened
        }

        val total = handle.size
        if (total >= 0 && dataSpec.position > total) {
            runCatching { handle.close() }
            // 用 Media3 的通用错误码而不是已废弃的 `DataSourceException.POSITION_OUT_OF_RANGE`：
            // 播放器会把这个 reason 翻成"读取位置越界"，进而在 UI 上给出可读提示。
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val available = if (total >= 0) total - dataSpec.position else Long.MAX_VALUE
        val length = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> available
        }

        source = handle
        currentUri = dataSpec.uri
        readPosition = dataSpec.position
        bytesRemaining = minOf(length, available)
        started = true

        // ⚠️ 这条日志是"随机访问真的发生了"的**唯一应用内部证据**，别删。
        //
        // 播放器对每个缓冲周期调用一次 `open()`，而 `dataSpec.position` 就是它**只**想要
        // 的那一段的起点。于是：
        //   · 顺序播放 → `pos` 单调递增，步长 ≈ 一次缓冲量；
        //   · **拖动进度条 → `pos` 出现跳跃**，且不会要求"从头重下"；
        //   · `moov` 在文件尾部的 MP4（本工程的录屏素材正是这种布局）→ 起播前就会出现
        //     一个接近文件末尾的 `pos`，这本身就是"必须先能随机读"的实证。
        // 没有它，"网络存储上的视频能拖动""不全量下载"这两条主张就只能靠观感，
        // 而观感无法反驳，也无法在回归时发现退化。
        MimiLog.i(
            TAG,
            "open pos=${dataSpec.position} want=$bytesRemaining total=$total uri=$currentUri",
        )

        transferStarted(dataSpec)
        // 返回"还能读多少"。已知总长时这是精确值，播放器据此显示进度条与总时长。
        return if (bytesRemaining == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val handle = source ?: throw IOException("DataSource 尚未 open()")
        val want = if (bytesRemaining == Long.MAX_VALUE) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = handle.read(readPosition, buffer, offset, want)
        if (read <= 0) return C.RESULT_END_OF_INPUT

        readPosition += read
        if (bytesRemaining != Long.MAX_VALUE) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val handle = source
        val wasStarted = started
        source = null
        currentUri = null
        started = false
        bytesRemaining = 0L
        if (handle != null) {
            runCatching { handle.close() }
                .onFailure { MimiLog.w(TAG, "关闭播放数据源失败", it) }
        }
        // 只有真的 open 过才通知"传输结束"：否则 ExoPlayer 会收到配对不上的回调。
        if (wasStarted) transferEnded()
    }

    /**
     * 每次 `createDataSource()` 都返回**新实例**。
     *
     * Media3 会在多个线程上并发使用多个 DataSource（例如一边播一边预读下一段），
     * 复用同一个实例会让"读位置"互相串台，症状是随机花屏或 audio glitch。
     */
    class Factory(
        private val registry: FileSystemRegistry = FileSystems.registry,
        private val sliceOffset: Long = -1L,
        private val sliceLength: Long = -1L,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SeekableSourceDataSource(registry, sliceOffset, sliceLength)
    }

    private companion object {
        const val TAG = "MimiPreviewSource"
    }
}

/**
 * 给"文件里的一段字节"套一个 [SeekableSource] 视图 —— 动态照片（MotionPhoto）用。
 *
 * 场景：Google/小米的动态照片是"一张 JPEG + 尾部追加一段 MP4"。
 * 播放那段 MP4 完全不必要把它抠出来落到磁盘：包一层偏移视图，
 * 播放器读到的第 0 字节就是我们映射过去的视频起始字节。
 *
 * 这样做的直接好处是**零临时文件**：用户在相册里长按看动态效果时，
 * 不会在缓存目录里留下几十个 `.mp4` 残片（参考应用就是这么干的，
 * 也是它缓存目录越来越大的原因之一）。
 */
class SliceSeekableSource(
    private val delegate: SeekableSource,
    private val offset: Long,
    override val size: Long,
) : SeekableSource {

    init {
        require(offset >= 0) { "offset 不能为负" }
        require(size >= 0) { "size 不能为负" }
    }

    override fun read(position: Long, buffer: ByteArray, offsetInBuffer: Int, length: Int): Int {
        if (position < 0 || position >= size) return -1
        val capped = minOf(length.toLong(), size - position).toInt()
        if (capped <= 0) return -1
        return delegate.read(this.offset + position, buffer, offsetInBuffer, capped)
    }

    /**
     * 只关视图，**不关**底层句柄。
     *
     * 与 [`SeekableMediaDataSource.close()`][SeekableMediaDataSource] 同样的理由：
     * 底层 source 的生命周期由它的创建者管理（通常是打开预览页的那段代码）。
     * 视图自作主张关掉它，会让"关掉动态播放紧接着还要显示静态图"这种正常流程直接崩掉。
     */
    override fun close() = Unit
}
