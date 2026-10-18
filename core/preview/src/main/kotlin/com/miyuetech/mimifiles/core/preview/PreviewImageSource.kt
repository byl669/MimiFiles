package com.miyuetech.mimifiles.core.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File

/**
 * 图片元信息（尺寸 / 方向 / 体积）。
 *
 * [displayWidth]/[displayHeight] 与 [width]/[height] 分开：前者是**应用 EXIF 方向后**
 * 用户实际看到的尺寸。横竖判断、容器比例、缩放上限都必须用显示尺寸，
 * 否则"竖着拍的照片"在布局里会被当成横图。
 */
data class ImageMeta(
    val width: Int,
    val height: Int,
    val orientation: Int = 0,
    val byteSize: Long = -1L,
    val mime: String? = null,
) {
    val rotationDegrees: Int get() = exifRotationOf(orientation)

    /** 是否需要水平镜像（前置摄像头自拍常见）。 */
    val mirrored: Boolean get() = exifIsMirrored(orientation)

    val needsTransform: Boolean get() = rotationDegrees != 0 || mirrored

    val displayWidth: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    val displayHeight: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    val megapixels: Double get() = width.toLong() * height / 1_000_000.0

    /**
     * 是否算"大图"（需要分块解码）。
     *
     * 4096 这个门槛来自两个事实：① 绝大多数 GPU 的纹理上限能覆盖到这个尺寸，
     * 一次全解不至于超出渲染能力；② 4096×4096×4B ≈ 64 MB 已经是"勉强可接受"的
     * 单张位图内存，再大就必须分块。
     */
    val isLarge: Boolean get() = maxOf(width, height) > LARGE_EDGE

    val ratio: Float get() = if (displayHeight <= 0) 1f else displayWidth.toFloat() / displayHeight

    companion object {
        const val LARGE_EDGE = 4096
    }
}

/**
 * 一次"用于显示的解码"的结果。
 *
 * ## 为什么需要这个类型，而不是直接返回 Bitmap
 * 位图本身**不足以**描述"该怎么画"：还要知道它①覆盖原图的哪一块、②是否需要旋转/镜像、
 * ③相对原图降采样了多少倍。M3 收口时就是因为把这些信息散落在调用点（且其中一条
 * 被写错），导致长边超过 2560 px 的照片被画成半尺寸并贴在屏幕左边缘。
 * 把三个量绑在一个类型里返回，调用方就不可能只拿到其中两个。
 *
 * ⚠️ **[sourceRect] 与 [rotationDegrees] 必须同源**：`sourceRect` 是"施加旋转**之前**"
 * 的原图坐标区域，旋转由视图施加。若某一个解码路径自己转了（平台解码器就会），
 * 就必须把 [rotationDegrees] 报成 0 并同时把 [sourceRect] 换成转正后的尺寸。
 */
data class DecodedImage(
    val bitmap: Bitmap,
    /** 该位图覆盖的原图区域。整图解码时即整张原图。 */
    val sourceRect: RectF,
    /** 需要由视图施加的顺时针旋转角度。 */
    val rotationDegrees: Int,
    /** 需要由视图施加的水平镜像。 */
    val mirrored: Boolean,
    /** 相对原图的采样倍数（用于判断"再放大是否需要补一块更清晰的"）。 */
    val sample: Int,
    /**
     * 是否还能用同一坐标系做分块解码。
     * 平台解码器路线（HEIC/AVIF）为 `false`：它的坐标系已被转正，与分块器的不同源。
     */
    val regionDecodable: Boolean,
)

/**
 * 图片预览的数据源。
 *
 * ## 两种解码路线，各有明确边界
 * | 路线 | 何时用 | 内存 | 放大后清晰度 |
 * |---|---|---|---|
 * | **整图降采样** [decodeForDisplay] | 总是可用；图不大或无法分块时 | 恒 ≤ `4 × targetPx²` | 受 targetPx 限制 |
 * | **分块解码** [decodeRegion] | 图大、且拿得到可 seek 的本地文件 | 只解视口那块 | 接近原图 |
 *
 * 两条路的共存是"1 GB 文件预览不 OOM"与"放大看得清"能同时成立的原因：
 * 先给一张降采样图**立刻**显示出来，用户放大时再按视口补一块高清区域。
 * 如果只有分块路线，打开一张 1 GB 的图会先卡住等分块器就位；
 * 如果只有降采样，放大就只能看到马赛克。
 *
 * ## 分块为什么需要"本地文件"
 * `BitmapRegionDecoder.newInstance(InputStream, …)` 在 AOSP 实现里会把**整个流**
 * 读进内存——用它分块等于先整读一遍，与初衷相反。真正的按块读只有
 * `newInstance(path)` 这条（内部用随机读的 `SkStream`）。
 * 所以远端文件先经 [PreviewCache] 落盘（有容量上限，超限就不走分块路线）。
 */
class PreviewImageSource private constructor(
    val path: FsPath,
    private val registry: FileSystemRegistry,
    private val cache: PreviewCache?,
    private val context: Context?,
    private val documentAuthority: String?,
    val meta: ImageMeta,
) : Closeable {

    @Volatile
    private var regionDecoder: BitmapRegionDecoder? = null

    @Volatile
    private var regionDecoderTried = false

    /**
     * 解一张"用于显示"的图。
     *
     * 返回值带上"这块位图代表原图的哪一块 / 要不要旋转"，见 [DecodedImage]。
     */
    suspend fun decodeForDisplay(targetPx: Int): DecodedImage? =
        withContext(PreviewDispatchers.decode) {
            if (targetPx <= 0) return@withContext null
            val sample = sampleSizeFor(meta.width, meta.height, targetPx)
            val viaFactory = runCatching {
                registry.resolve(path).openRead(path).use { source ->
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BufferedInputStream(source.stream(0)).use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }
            }.getOrNull()
            if (viaFactory != null) {
                return@withContext DecodedImage(
                    bitmap = viaFactory,
                    sourceRect = RectF(0f, 0f, meta.width.toFloat(), meta.height.toFloat()),
                    // BitmapFactory **不会**应用 EXIF 方向，所以方向由视图施加。
                    rotationDegrees = meta.rotationDegrees,
                    mirrored = meta.mirrored,
                    sample = sample,
                    regionDecodable = true,
                )
            }
            // HEIC / AVIF：只有平台的 ImageDecoder 能解（API 28+）。
            //
            // ⚠️ 它**会自动应用 EXIF 方向**（API 28+ 默认 `setAutoTransform(true)`）。
            // 因此这里必须如实回报 rotation = 0，并把 `sourceRect` 换成**解码后**的
            // 实际尺寸 —— 否则视图会拿"转正后的位图"去填"未转正的框"，结果被拉伸，
            // 而且分块器（坐标系没转正）也不能再用，故 `regionDecodable = false`。
            val viaPlatform = decodeViaPlatformDecoder(targetPx) ?: return@withContext null
            DecodedImage(
                bitmap = viaPlatform,
                sourceRect = RectF(
                    0f,
                    0f,
                    viaPlatform.width.toFloat(),
                    viaPlatform.height.toFloat(),
                ),
                rotationDegrees = 0,
                mirrored = false,
                sample = sample,
                regionDecodable = false,
            )
        }

    /**
     * 解出 [region]（原图坐标系）的一块，采样倍数 [sample]。
     *
     * @return 解码成功返回结果；无法分块（拿不到本地文件 / 解码器不可用）返回 `null`，
     *         调用方应继续用降采样整图（不要弹错误——这是**能力降级**，不是失败）。
     */
    suspend fun decodeRegion(region: Rect, sample: Int): DecodedImage? =
        withContext(PreviewDispatchers.decode) {
            val decoder = prepareRegionDecoder() ?: return@withContext null
            val bounded = Rect(
                region.left.coerceIn(0, meta.width),
                region.top.coerceIn(0, meta.height),
                region.right.coerceIn(0, meta.width),
                region.bottom.coerceIn(0, meta.height),
            )
            if (bounded.width() <= 0 || bounded.height() <= 0) return@withContext null
            runCatching {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                decoder.decodeRegion(bounded, options)
            }.onSuccess { decoded ->
                // 这条日志是"分块解码真的发生了"的**唯一可取证信号**：
                // 界面上"变清晰了"无法通过 UI dump 观测，而截图对比又太脆。
                // 冒烟脚本 grep 它来证明放大时没有走"整图重解"，也就证明了内存有界。
                MimiLog.i(
                    TAG,
                    "region decoded ${bounded.width()}x${bounded.height()} sample=$sample " +
                        "-> ${decoded.width}x${decoded.height} bytes=${decoded.byteCount}",
                )
            }.onFailure { MimiLog.d(TAG, "分块解码失败：$path $bounded", it) }.getOrNull()
                ?.let { decoded ->
                    DecodedImage(
                        bitmap = decoded,
                        sourceRect = RectF(bounded),
                        rotationDegrees = meta.rotationDegrees,
                        mirrored = meta.mirrored,
                        sample = sample.coerceAtLeast(1),
                        regionDecodable = true,
                    )
                }
        }

    /**
     * 取分块解码器（懒加载，只试一次）。
     *
     * "只试一次"很重要：远端文件落盘可能要几秒，失败后重试会让每次放大都卡一次。
     * 失败即永久降级为整图路线，直到重新打开预览。
     */
    suspend fun prepareRegionDecoder(): BitmapRegionDecoder? {
        regionDecoder?.let { return it }
        if (regionDecoderTried) return null
        regionDecoderTried = true
        return withContext(PreviewDispatchers.io) {
            val local = localFileForRegion() ?: return@withContext null
            runCatching {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(local.absolutePath, false)
            }.onFailure { MimiLog.d(TAG, "无法创建分块解码器：$local", it) }.getOrNull()
                ?.also { regionDecoder = it }
        }
    }

    /** 本机文件直接用；远端走 [PreviewCache] 落盘（有上限，超限返回 `null`）。 */
    private suspend fun localFileForRegion(): File? {
        if (path.scheme == LOCAL_SCHEME) {
            return File(path.path).takeIf { it.isFile }
        }
        val previewCache = cache ?: return null
        return previewCache.materialize(path, meta.byteSize, registry)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun decodeViaPlatformDecoder(targetPx: Int): Bitmap? {
        val ctx = context ?: return null
        val source = runCatching { ImageSources.of(ctx, documentAuthority, path) }.getOrNull()
            ?: return null
        return runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setMutableRequired(false)
                val edge = maxOf(info.size.width, info.size.height)
                if (edge > targetPx * 2) {
                    runCatching {
                        decoder.setTargetSampleSize(
                            sampleSizeFor(info.size.width, info.size.height, targetPx * 2),
                        )
                    }
                }
            }
        }.onFailure { MimiLog.d(TAG, "平台解码器解码失败：$path", it) }.getOrNull()
    }

    override fun close() {
        runCatching { regionDecoder?.recycle() }
        regionDecoder = null
    }

    companion object {
        private const val TAG = "MimiImage"

        private const val LOCAL_SCHEME = "local"

        /**
         * 打开一个图片源。
         *
         * 只读文件头（`inJustDecodeBounds`）拿尺寸，**不解像素**，所以对任何大小的图
         * 都是几十毫秒级；并且顺带拿到 MIME 与"是否带 alpha"。
         */
        suspend fun open(
            context: Context?,
            path: FsPath,
            registry: FileSystemRegistry = FileSystems.registry,
            cache: PreviewCache? = null,
            documentAuthority: String? = null,
        ): PreviewImageSource? = withContext(PreviewDispatchers.io) {
            val fs = registry.resolveOrNull(path) ?: return@withContext null
            val size = runCatching { fs.stat(path).size }.getOrDefault(-1L)
            val bounds = runCatching {
                fs.openRead(path).use { source ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BufferedInputStream(source.stream(0)).use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    Pair(options.outWidth, options.outHeight) to options.outMimeType
                }
            }.getOrNull() ?: return@withContext null

            val (dimensions, mime) = bounds
            if (dimensions.first <= 0 || dimensions.second <= 0) {
                // BitmapFactory 认不出来（HEIC/AVIF 在 API 28 以下就是这样）。
                // 仍然返回一个"尺寸未知"的源：调用方可以走 ImageDecoder 或直接外发。
                return@withContext null
            }

            // 方向**总是**要读。
            //
            // ⚠️ 这里曾经是"只在 API < 28 时读"，理由是"API 28+ 的 `ImageDecoder`
            // 会自动应用 EXIF 方向，省一次文件头扫描"。但那句话的前提是
            // **真的走 ImageDecoder** —— 而 [decodeForDisplay] 优先走 `BitmapFactory`
            // （它不应用方向），ImageDecoder 只是解码失败时的兜底。
            // 于是 API 28+ 上所有带方向标记的照片（也就是手机拍的绝大多数）
            // 都按"存储方向"显示：竖拍的照片躺倒、横竖判断也跟着错。
            //
            // 现在方向统一由**视图**施加（见 `ZoomableImageView.setOrientation`），
            // 这里如实读出 EXIF；平台解码器那条路自己转正了，它会在
            // [decodeForDisplay] 里把角度报成 0，两边不冲突。
            // 代价：每次打开图片多一次文件头读取（上限 2 MB，JPEG 的 APP1 通常
            // 只有几 KB，`ExifInterface` 找到即停）。这是为"照片方向正确"付的合理价钱。
            val orientation = ExifReader.read(path, registry)?.orientation ?: 0

            PreviewImageSource(
                path = path,
                registry = registry,
                cache = cache,
                context = context,
                documentAuthority = documentAuthority,
                meta = ImageMeta(
                    width = dimensions.first,
                    height = dimensions.second,
                    orientation = orientation,
                    byteSize = size,
                    mime = mime,
                ),
            )
        }

        /**
         * 让长边落到 [target] **以内**所需的采样倍数（2 的幂）。
         *
         * ## ⚠️ 判据是"结果不超过目标"，不是"结果不小于目标"
         * 这两种写法在数学上都"接近 target"，但在内存上差别巨大：
         * 采样倍数只能是 2 的幂，所以 4200 px 的图对 target=2560 只有两个候选——
         * `1`（解出 4200² ≈ 70 MB）或 `2`（解出 2100² ≈ 17 MB）。
         * 用"不小于"的判据会选前者，于是"给基准位图设上限"这件事**完全失效**，
         * 而且看不出错：代码里明明写了上限，实际却按原尺寸解。
         * 这条是 M3 实测发现的——见交付报告 §3。
         */
        fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
            if (target <= 0 || width <= 0 || height <= 0) return 1
            var sample = 1
            while (maxOf(width, height) / sample > target) sample *= 2
            return sample
        }
    }
}

/**
 * 平台解码器（`ImageDecoder`）的数据源工厂。
 *
 * 由 [ThumbnailLoader] 与 [PreviewImageSource] 共用——之前两处各写了一遍，
 * 而它恰好是最容易写错的地方（`content://` 的 documentId 必须是 `FsPath.toString()`，
 * 少一层编码就会让 provider 解析失败）。
 */
internal object ImageSources {

    /**
     * @param documentAuthority `:provider` 的 authority；为 `null` 时远端文件无法用平台解码器。
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun of(context: Context, documentAuthority: String?, path: FsPath): ImageDecoder.Source? = when {
        path.scheme == "local" -> ImageDecoder.createSource(File(path.path))

        documentAuthority != null -> ImageDecoder.createSource(
            context.contentResolver,
            DocumentsContract.buildDocumentUri(documentAuthority, path.toString()),
        )

        else -> null
    }
}
