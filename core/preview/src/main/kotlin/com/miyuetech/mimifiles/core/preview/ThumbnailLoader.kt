package com.miyuetech.mimifiles.core.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream

/**
 * 列表缩略图加载器。
 *
 * ## 与 `ContentProbe` 的分工（重要）
 * 缩略图**只看扩展名**，不做魔数探测。原因很直接：探测一个文件要读 512 字节，
 * 而列表页一次要出几十个缩略图——如果每个都先探测再解码，在 SMB 上就是
 * 几十次额外的 ranged read，换来的只是"极少数改错扩展名的文件也能出图"。
 * 那个收益不值得让**每一次滚动**都变慢。类型校正留给"真正打开文件"的那一步。
 *
 * ## 缓存层级
 * L1（内存 LRU）→ L2（磁盘 LRU）→ 生成。命中 L2 时会把位图放回 L1，
 * 因为"刚看过的那一屏"是最可能被再次请求的。
 */
class ThumbnailLoader(
    context: Context,
    private val store: ThumbnailStore,
    private val registry: FileSystemRegistry = FileSystems.registry,
    private val maxEdgePx: Int = DEFAULT_MAX_EDGE,
    /**
     * `:provider` 那个 SAF `DocumentsProvider` 的 authority。
     *
     * 用来把网络路径变成 `content://` 交给平台的 `ImageDecoder`——它只认
     * `File` / `content://`，而 HEIC/AVIF 只有它能解（API 28+）。
     * 之所以是"注入字符串"而不是让 `:core:preview` 去 import `:provider`：
     * core 层不能依赖系统集成层，那会形成反向依赖。
     */
    private val documentAuthority: String? = null,
) {

    private val appContext = context.applicationContext

    /**
     * @param requestPx 目标显示边长。传 0 或负数表示"给我缓存里那份"。
     * @return `null` 表示"没有缩略图可用"，调用方应回落到类型图标——
     *         这**不是**错误，HEIC 在 API 28 以下、SVG、损坏文件都会走这条路。
     */
    suspend fun load(entry: FsEntry, requestPx: Int = maxEdgePx): Bitmap? {
        if (!entry.isFile) return null
        val kind = kindForExtension(entry)
        if (!kind.isMedia && kind != PreviewKind.IMAGE && kind != PreviewKind.ANIMATED_IMAGE) return null

        val key = ThumbnailKey.of(entry.path, entry.size, entry.lastModified)
        store.memoryGet(key)?.let { return it }
        store.readDisk(key, requestPx)?.let { cached ->
            store.memoryPut(key, cached)
            return cached
        }

        val produced = runCatching {
            when (kind) {
                PreviewKind.IMAGE, PreviewKind.ANIMATED_IMAGE -> imageThumbnail(entry)
                PreviewKind.VIDEO -> videoThumbnail(entry)
                else -> null
            }
        }.onFailure { MimiLog.d(TAG, "缩略图生成失败：${entry.name}", it) }.getOrNull() ?: return null

        val bounded = scaleToMaxEdge(produced, maxEdgePx)
        if (bounded !== produced) produced.recycle()
        store.memoryPut(key, bounded)
        store.writeDisk(key, bounded)
        return bounded
    }

    /**
     * 只按扩展名判定，且**不读文件**。
     *
     * 与 `:core:ui` 的 `FileTypeIcons.wantsThumbnail` 是同一套判据（都基于扩展名），
     * 这里用 `MimeTypes` 复算一遍而不是反向依赖 `:core:ui`——`core` 层不能依赖 UI 层。
     */
    private fun kindForExtension(entry: FsEntry): PreviewKind {
        val mime = MimeTypes.of(entry.name)
        return ContentProbe.declaredKindOf(mime)
    }

    // ── 图片 ─────────────────────────────────────────────────────

    private suspend fun imageThumbnail(entry: FsEntry): Bitmap? {
        // 已知大小的超大文件直接放弃：为了一张 96 px 的缩略图去读几十 MB，
        // 在网络上完全不划算，而用户滚过一个 1 GB 的 TIFF 时只会觉得"卡"。
        if (entry.hasKnownSize && entry.size > MAX_IMAGE_STREAM_BYTES) {
            MimiLog.d(TAG, "跳过超大图片缩略图（${entry.size / 1024 / 1024}MB）：${entry.name}")
            return null
        }
        val fileSystem = registry.resolveOrNull(entry.path) ?: return null
        val viaFactory = withContext(PreviewDispatchers.io) {
            runCatching {
                fileSystem.openRead(entry.path).use { source ->
                    // 第一遍：只解尺寸（`inJustDecodeBounds` 只读文件头，不解像素）
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BufferedInputStream(source.stream(0)).use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdgePx * 2)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    // 第二遍：真正解码。**必须重新开流**——第一个流已经读掉了文件头，
                    // 复用它会解出半张图（而且不报错，只是位置错乱）。
                    withContext(PreviewDispatchers.decode) {
                        BufferedInputStream(source.stream(0)).use {
                            BitmapFactory.decodeStream(it, null, options)
                        }
                    }
                }
            }.getOrNull()
        }
        if (viaFactory != null) return viaFactory

        // 平台解码器兜底：HEIC / HEIF / AVIF 在 API 28+ 只能走 ImageDecoder，
        // `BitmapFactory` 会直接返回 null（而不是抛异常，所以这里必须显式再试一次）。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            imageThumbnailViaDecoder(entry)
        } else {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun imageThumbnailViaDecoder(entry: FsEntry): Bitmap? =
        withContext(PreviewDispatchers.decode) {
            runCatching {
                val source = decoderSourceOf(entry.path)
                    ?: return@runCatching null
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // ⚠️ 所有 setter 都在 **ImageDecoder** 上，不在 ImageInfo 上；
                    // ImageInfo 只有 getter（`size` / `mimeType` / `isAnimated`）。
                    // 缩略图不需要可变位图（省掉一次整块拷贝）。
                    decoder.setMutableRequired(false)
                    val width = info.size.width
                    val height = info.size.height
                    if (maxOf(width, height) > maxEdgePx * 2) {
                        // 按目标边长设采样，避免为 HEIC 大图解出全尺寸位图。
                        runCatching {
                            decoder.setTargetSampleSize(sampleSizeFor(width, height, maxEdgePx * 2))
                        }
                    }
                }
            }.onFailure { MimiLog.d(TAG, "ImageDecoder 兜底失败：${entry.name}", it) }.getOrNull()
        }

    /** 本机路径走 `File`；网络路径走 `:provider` 的 `content://`。都拿不到就返回 `null`。 */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decoderSourceOf(path: FsPath): ImageDecoder.Source? =
        ImageSources.of(appContext, documentAuthority, path)

    // ── 视频 ─────────────────────────────────────────────────────

    /**
     * 抓一帧。
     *
     * 关键是 **`MediaMetadataRetriever.setDataSource(MediaDataSource)`**（API 23+）：
     * 它让 retriever 通过我们的 [SeekableSource] 随机读，于是抓帧只需要读文件头与
     * 目标关键帧附近的数据，**而不是把整个视频下载下来**。
     * 这也是"网络存储上滚动视频列表不卡"的唯一可行做法。
     *
     * 跑在 [PreviewDispatchers.io] 而不是解码池：retriever 的耗时主要花在
     * 等待远程字节上，不是 CPU。
     */
    private suspend fun videoThumbnail(entry: FsEntry): Bitmap? = withContext(PreviewDispatchers.io) {
        val fileSystem = registry.resolveOrNull(entry.path) ?: return@withContext null
        val source = runCatching { fileSystem.openRead(entry.path) }.getOrNull()
            ?: return@withContext null
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever().apply {
                setDataSource(SeekableMediaDataSource(source))
            }
            // `-1` = 任意一帧。先用 `CLOSEST_SYNC`（只解关键帧，最快），
            // 拿不到再退到"最近帧"——有些短片段只有一个关键帧在很靠后的位置。
            retriever.frameAtTime
                ?: retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (t: Throwable) {
            MimiLog.d(TAG, "视频抓帧失败：${entry.name}", t)
            null
        } finally {
            runCatching { retriever?.release() }
            // ⚠️ 顺序不能反：必须在 retriever 释放之后再关 source，
            // 否则可能出现"正在读的时候句柄被关掉"的原生崩溃（不是异常，是 SIGSEGV）。
            runCatching { source.close() }
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────

    /** 长边超过 [maxEdge] 就等比缩到 [maxEdge]；否则原样返回（可能返回同一对象）。 */
    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= maxEdge || edge <= 0) return bitmap
        val ratio = maxEdge.toFloat() / edge
        val target = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        return target
    }

    /**
     * 让长边落到 [target] **以内**所需的采样倍数。
     * 判据必须是"不超过"而不是"不小于"——理由见 `PreviewImageSource.sampleSizeFor`
     * 的注释（4200 px 的图在两种判据下分别是 70 MB 与 17 MB，而缩略图还要更极端）。
     */
    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (target <= 0 || width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > target) sample *= 2
        return sample
    }

    companion object {
        private const val TAG = "MimiThumb"

        /**
         * 存盘/内存里的缩略图长边。
         *
         * 384 的取值理由：列表行图标位 48 dp（xxhdpi = 144 px）、网格瓦片约 168 dp
         * （= 504 px，但那是有图片本身当背景，略糊可接受）。384 在"够清晰"与
         * "一张 384×384 的 RGB_565 位图只占 288 KB"之间取平衡。
         */
        const val DEFAULT_MAX_EDGE = 384

        /** 超过这个大小就不做流式解码缩略图（见 [imageThumbnail] 的注释）。 */
        private const val MAX_IMAGE_STREAM_BYTES = 24L * 1024 * 1024

        /** 与本机后端 `LocalStorageProvider.scheme` 一致。 */
        private const val LOCAL_SCHEME = "local"
    }
}

/**
 * `MediaDataSource` 到 [SeekableSource] 的适配器。
 *
 * `MediaMetadataRetriever` 与部分媒体组件只认这个（API 23+）的抽象；
 * 它的 [`readAt`][MediaDataSource.readAt] 返回 **-1 表示 EOF**，与
 * [SeekableSource.read] 的契约**恰好一致**——这也是 M1 把 `SeekableSource.read`
 * 的返回值定成"`-1` = 文件末尾"（而不是 0）的回报之一。
 */
class SeekableMediaDataSource(
    private val source: SeekableSource,
) : MediaDataSource() {

    override fun getSize(): Long = source.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        source.read(position, buffer, offset, size)

    /**
     * 刻意**不**在此关闭 [source]。
     *
     * retriever 在 `release()` 时会回调这里，而调用方紧接着还要用 source 做别的
     * （或反过来：source 由更外层的 `use` 管理）。让生命周期只有一个主人，
     * 是避免"双重关闭"这类难查问题的唯一办法。
     */
    override fun close() = Unit
}
