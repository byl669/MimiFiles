package com.miyuetech.mimifiles.core.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import com.miyuetech.mimifiles.core.common.MimiLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 缩略图的两级缓存（L1 内存 LRU + L2 磁盘 LRU）。
 *
 * ## 为什么两级都要
 *  - **只有内存**：滚动出屏再滚回来就要重新解码。在 5000 张照片的相册里，
 *    每次快滚都是几十次解码，卡顿肉眼可见；
 *  - **只有磁盘**：每次进目录都要读盘 + 解码，冷启动迟迟不出图。
 *
 * 两级的分工是：L1 负责"刚才看过的那一屏"，L2 负责"上次看过的那个目录"。
 *
 * ## 容量怎么定
 * L1 取 `maxMemory / 8` 且**硬上限 16 MB**——Android 上 `Runtime.maxMemory()` 在
 * 低端机可能只有 96 MB，八分之一是 12 MB，此时按比例走；高端机有 512 MB，
 * 八分之一是 64 MB，但缩略图根本不需要那么多，硬上限把它压回 16 MB。
 *
 * L2 默认 128 MB，并**跟随可用空间自动收缩**（取 `usableSpace / 8`）。
 * 理由：手机剩 300 MB 的时候还去占 128 MB 缓存，是在制造"装不上别的应用"。
 *
 * ## 编码格式
 * 落盘统一 WebP（有损 q80）。同样一张 384 px 缩略图，WebP 比 JPEG 小 25~35%，
 * 而解码速度在 API 24+ 上已经与 JPEG 相当。
 */
class ThumbnailStore(
    context: Context,
    maxMemoryBytes: Int = defaultMemoryBytes(),
    private val maxDiskBytes: Long = DEFAULT_DISK_BYTES,
) {

    private val appContext = context.applicationContext

    private val dir = File(appContext.cacheDir, DIR_NAME)

    private val memory = object : LruCache<String, Bitmap>(maxMemoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 落盘/裁剪共用一把锁：两者都会操作同一批文件。 */
    private val diskLock = Mutex()

    // ── L1 ───────────────────────────────────────────────────────

    fun memoryGet(key: String): Bitmap? = memory.get(key)

    fun memoryPut(key: String, bitmap: Bitmap) {
        memory.put(key, bitmap)
    }

    fun clearMemory() = memory.evictAll()

    /** 仅测试与"清理缓存"设置项使用。 */
    fun clearDisk() {
        runCatching { dir.deleteRecursively() }
    }

    // ── L2 ───────────────────────────────────────────────────────

    /**
     * 读磁盘缓存。
     *
     * [requestPx] 是**目标显示边长**：磁盘上存的是 384 px 的缩略图，如果这次只要
     * 96 px（详情列表），用 `inSampleSize` 采样解码能省掉 15/16 的位图内存。
     * 反过来（要求 512 px）就直接解全图再交给调用方缩放——放大缓存图没有意义。
     *
     * @return 命中返回位图；未命中或解码失败返回 `null`（失败时顺手删掉坏文件，
     *         否则每次滚动都会重试同一个损坏条目）。
     */
    suspend fun readDisk(key: String, requestPx: Int): Bitmap? = withContext(PreviewDispatchers.io) {
        val file = fileFor(key)
        if (!file.isFile || file.length() <= 0L) return@withContext null
        val sample = sampleSizeFor(requestPx)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            BufferedInputStream(file.inputStream()).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull()
        if (bitmap == null) {
            MimiLog.w(TAG, "磁盘缩略图损坏，已丢弃：${file.name}")
            runCatching { file.delete() }
            return@withContext null
        }
        // 触碰一次，让 LRU 的"最近使用"语义成立（裁剪是按 lastModified 升序删）。
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        bitmap
    }

    /** 写入磁盘缓存。失败只记日志：缓存写不进去不该影响预览本身。 */
    suspend fun writeDisk(key: String, bitmap: Bitmap) = withContext(PreviewDispatchers.disk) {
        diskLock.withLock {
            val target = fileFor(key)
            if (target.isFile && target.length() > 0L) return@withLock
            runCatching {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.tmp")
                FileOutputStream(tmp).use { out ->
                    if (!bitmap.compress(compressFormat(), QUALITY, out)) {
                        error("compress 返回 false")
                    }
                }
                // 先写临时文件再改名：避免"写了一半被杀进程"留下一个能被 L2 命中的坏文件。
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                // 落盘成功打一条 INFO：这是"缩略图真的生成过"的**唯一外部可观测信号**
                // （界面上"有缩略图"没法从 UI dump 里读，而只查目录会漏掉"写了又删"）。
                MimiLog.i(TAG, "thumbnail cached: ${target.name} ${bitmap.width}x${bitmap.height}")
            }.onFailure { MimiLog.w(TAG, "缩略图落盘失败：$key", it) }
        }
    }

    /**
     * 按"最近最少使用"裁剪到容量以内。
     *
     * 用 `lastModified` 排序而不是维护一个访问序列表：文件系统的 mtime 已经够用，
     * 而维护额外索引意味着"索引与真实文件不一致"这一整类新 bug。
     */
    suspend fun trimDisk() = withContext(PreviewDispatchers.disk) {
        diskLock.withLock {
            val cap = effectiveCap()
            val files = runCatching {
                dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
            }.getOrNull() ?: return@withLock
            val total = files.sumOf { it.length() }
            if (total <= cap) return@withLock
            var remaining = total
            // 老的先删
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (remaining <= cap) return@forEach
                val len = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) remaining -= len
            }
            MimiLog.d(TAG, "缩略图缓存裁剪：${total / 1024}KB → ${remaining / 1024}KB（上限 ${cap / 1024}KB）")
        }
    }

    fun fileFor(key: String): File =
        File(File(dir, ThumbnailKey.shardOf(key)), "$key.$EXTENSION")

    /** 当前磁盘占用（字节），供设置页展示。 */
    fun diskUsageBytes(): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    private fun effectiveCap(): Long {
        val free = runCatching { appContext.cacheDir.usableSpace }.getOrDefault(maxDiskBytes)
        // 可用空间的一半再打个折：缓存可以牺牲，但不能把设备写满。
        val adaptive = free / 8
        return maxDiskBytes.coerceAtMost(adaptive.coerceAtLeast(MIN_DISK_BYTES))
    }

    /**
     * 目标边长 → `inSampleSize`（2 的幂）。
     *
     * 磁盘上存的是 [ThumbnailLoader.DEFAULT_MAX_EDGE] 边长，所以采样倍数
     * 只可能是 1/2/4/8；用"每次折半直到够小"的写法可以避免引入对具体存盘尺寸的依赖。
     */
    private fun sampleSizeFor(requestPx: Int): Int {
        if (requestPx <= 0) return 1
        var sample = 1
        var edge = STORED_EDGE
        while (edge / 2 >= requestPx && sample < MAX_SAMPLE) {
            sample *= 2
            edge /= 2
        }
        return sample
    }

    private fun compressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    companion object {
        private const val TAG = "MimiThumb"

        private const val DIR_NAME = "thumbnails"

        private const val EXTENSION = "webp"

        private const val QUALITY = 80

        /** 磁盘上限 128 MB。 */
        private const val DEFAULT_DISK_BYTES = 128L * 1024 * 1024

        /** 可用空间极度紧张时也不低于 16 MB，否则缓存等于没有。 */
        private const val MIN_DISK_BYTES = 16L * 1024 * 1024

        /** 存盘边长，必须与 [ThumbnailLoader.DEFAULT_MAX_EDGE] 保持一致。 */
        private const val STORED_EDGE = 384

        private const val MAX_SAMPLE = 8

        private fun defaultMemoryBytes(): Int {
            val eighth = (Runtime.getRuntime().maxMemory() / 8).toInt()
            return eighth.coerceIn(4 * 1024 * 1024, 16 * 1024 * 1024)
        }
    }
}
