package com.miyuetech.mimifiles.core.preview

import androidx.exifinterface.media.ExifInterface
import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 一张照片的 EXIF 摘要。
 *
 * 全部字段可空/带默认值，因为 EXIF **经常残缺**：微信压缩过的图、截图、HEIC 转存
 * 都可能只剩一个方向标记，甚至什么都没有。硬要求字段齐全会逼出"没 EXIF 就显示不了"
 * 这种荒唐结果。
 */
data class ExifSummary(
    val width: Int = 0,
    val height: Int = 0,
    /** 原始 EXIF 方向常量（`ExifInterface.ORIENTATION_*`）。 */
    val orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    val takenAtMillis: Long? = null,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val software: String? = null,
    /** 快门，如 `1/125 s`。 */
    val exposure: String? = null,
    /** 光圈，如 `f/1.8`。 */
    val aperture: String? = null,
    val iso: Int? = null,
    /** 焦距，如 `24 mm`。 */
    val focalLength: String? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val altitude: Double? = null,
) {
    /** 显示时需要顺时针旋转的角度。 */
    val rotationDegrees: Int get() = exifRotationOf(orientation)

    /** 是否需要水平镜像（前置摄像头自拍常见）。 */
    val mirrored: Boolean get() = exifIsMirrored(orientation)

    /** 是否需要做方向变换。 */
    val needsTransform: Boolean get() = rotationDegrees != 0 || mirrored

    val hasLocation: Boolean get() = latitude != null && longitude != null

    /** 一行参数摘要，如 `f/1.8 · 1/125 s · ISO 100 · 24 mm`。 */
    val cameraLine: String
        get() = listOfNotNull(aperture, exposure, iso?.let { "ISO $it" }, focalLength)
            .joinToString(" · ")
}

/** EXIF 方向 → 顺时针旋转角度。 */
fun exifRotationOf(orientation: Int): Int = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_TRANSPOSE,
    -> 90

    ExifInterface.ORIENTATION_ROTATE_180,
    ExifInterface.ORIENTATION_FLIP_VERTICAL,
    -> 180

    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSVERSE,
    -> 270

    else -> 0
}

/** 方向常量里含"镜像"的四种。 */
fun exifIsMirrored(orientation: Int): Boolean =
    orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
        orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE

/**
 * 读 EXIF。
 *
 * ## 为什么单独成一个"按需"操作
 * 读 EXIF 要把文件**从头扫一遍**（JPEG 的 EXIF 在 APP1 段，HEIC 在 `meta` box，
 * 都不在尾部）。对网络文件而言这是一次真实的数据传输，而"打开一张照片"这件事
 * 本身并不需要 EXIF——只在方向需要纠正、或用户点开"详细信息"时才需要。
 * 因此本类的调用点只有两处：**方向纠正**与**信息面板**。
 *
 * ## ⚠️ 方向纠正优先用平台能力，而不是这里
 * `BitmapFactory` 不会自动应用 EXIF 方向，但平台的 `ImageDecoder` 会
 * （API 28+ 默认 `setAutoTransform(true)`）。所以"照片显示倒了"这件事在
 * API 28+ 上不需要我们自己读 EXIF —— 少一次全文件扫描。低于 28 时才退回
 * [ExifReader]，这也是本类保留方向判定的原因。
 */
object ExifReader {

    /**
     * 读取上限。
     *
     * 有些相机把一整张缩略图塞进 APP1（可到几百 KB），而 EXIF 段本身极少数情况下
     * 会超过 1 MB。2 MB 足够覆盖所有正常情况，同时把"远端文件为了读 EXIF 而被
     * 完整拉一遍"的最坏代价钉死在 2 MB。
     */
    private const val LIMIT_BYTES = 2 * 1024 * 1024

    suspend fun read(
        path: FsPath,
        registry: FileSystemRegistry = FileSystems.registry,
    ): ExifSummary? = withContext(PreviewDispatchers.io) {
        val fileSystem = registry.resolveOrNull(path) ?: return@withContext null
        val source = runCatching { fileSystem.openRead(path) }.getOrNull() ?: return@withContext null
        try {
            val stream = LimitedInputStream(source.stream(0), LIMIT_BYTES)
            ExifInterface(stream).toSummary()
        } catch (_: Throwable) {
            // EXIF 缺失 / 格式损坏是最常见的情况，不是错误，也不该打日志刷屏。
            null
        } finally {
            runCatching { source.close() }
        }
    }

    // 注意：androidx 的 `intAttribute` / `latLong` / `text` 等**便捷扩展**住在
    // 独立的 `exifinterface-ktx` 工件里。本工程刻意不引它——多一个依赖只为少写几个
    // `getAttributeXxx`，与"减少供给链面"的取舍不符。所以下面全部用 Java 方法。

    private fun ExifInterface.toSummary(): ExifSummary {
        // 名字刻意不叫 `latLong`：那会与 ExifInterface 自己的属性同名，
        // 读起来分不清"这是原始数组还是我们要展示的经纬度"。
        val coords = runCatching { latLong }.getOrNull()
        val hasCoords = coords != null && (coords[0] != 0.0 || coords[1] != 0.0)
        return ExifSummary(
            width = runCatching { getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) }
                .getOrDefault(0),
            height = runCatching { getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) }
                .getOrDefault(0),
            orientation = runCatching {
                getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL),
            takenAtMillis = parseDate(),
            make = text(ExifInterface.TAG_MAKE),
            model = text(ExifInterface.TAG_MODEL),
            lens = text(ExifInterface.TAG_LENS_MODEL),
            software = text(ExifInterface.TAG_SOFTWARE),
            exposure = exposureText(),
            aperture = apertureText(),
            iso = runCatching {
                getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
            }.getOrDefault(0).takeIf { it > 0 },
            focalLength = rational(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${trimZero(it)} mm" },
            longitude = if (hasCoords) coords[1] else null,
            latitude = if (hasCoords) coords[0] else null,
            altitude = if (hasAttribute(ExifInterface.TAG_GPS_ALTITUDE)) {
                // `getAltitude` 返回米；0 视为"没记录"（海平面拍照也确实可能是 0，
                // 但显示"海拔 0 m"比不显示更容易让人以为应用算错了）。
                runCatching { getAltitude(0.0) }.getOrDefault(0.0).takeIf { it != 0.0 }
            } else {
                null
            },
        )
    }

    private fun ExifInterface.text(tag: String): String? =
        runCatching { getAttribute(tag) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 读 RATIONAL 型字段的数值。
     *
     * 不用 `getAttribute`：EXIF 里 RATIONAL 的原始文本是 `"18/10"` 这种分数字符串，
     * 直接展示给用户是"18/10 mm"而不是"1.8 mm"。`getAttributeDouble` 会替我们算好。
     */
    private fun ExifInterface.rational(tag: String): Double? =
        runCatching { getAttributeDouble(tag, 0.0) }.getOrNull()?.takeIf { it > 0.0 }

    private fun ExifInterface.exposureText(): String? {
        val seconds = rational(ExifInterface.TAG_EXPOSURE_TIME) ?: return null
        return if (seconds < 1.0) "1/${(1.0 / seconds).toInt()} s" else "${trimZero(seconds)} s"
    }

    private fun ExifInterface.apertureText(): String? =
        rational(ExifInterface.TAG_F_NUMBER)?.let { "f/${trimZero(it)}" }

    private fun ExifInterface.parseDate(): Long? {
        val raw = text(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: text(ExifInterface.TAG_DATETIME)
            ?: return null
        // EXIF 的日期格式固定为 `yyyy:MM:dd HH:mm:ss`（**不是** ISO 8601，日期用冒号分隔）。
        // 因此不能用 `java.time`，也不能让 `SimpleDateFormat` 用默认 Locale
        // （阿拉伯语等 Locale 下的数字形态会让解析失败）。
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        }.getOrNull()
    }

    private fun trimZero(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

/**
 * 把流截断到 [limit] 字节之后就当作 EOF。
 *
 * 用 [FilterInputStream] 而不是自己写一个读缓冲：`ExifInterface` 内部会做
 * mark/reset 与 skip，手写的流很容易在这两个方法上出错——skip 少读一个字节
 * 就会让 EXIF 解析整体错位，而且**不报错**，只是字段莫名变成默认值。
 */
private class LimitedInputStream(
    input: InputStream,
    private val limit: Int,
) : FilterInputStream(input) {

    private var consumed = 0

    override fun read(): Int {
        if (consumed >= limit) return -1
        val b = super.read()
        if (b >= 0) consumed++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (consumed >= limit) return -1
        val allowed = minOf(len, limit - consumed)
        val read = super.read(b, off, allowed)
        if (read > 0) consumed += read
        return read
    }

    override fun skip(n: Long): Long {
        val allowed = minOf(n, (limit - consumed).toLong()).coerceAtLeast(0L)
        val skipped = super.skip(allowed)
        if (skipped > 0) consumed += skipped.toInt()
        return skipped
    }

    override fun available(): Int = minOf(super.available(), limit - consumed).coerceAtLeast(0)

    /** mark/reset 的上限也要跟着截断，否则 reset 之后 [consumed] 会漂移。 */
    override fun mark(readlimit: Int) {
        super.mark(minOf(readlimit, limit - consumed).coerceAtLeast(0))
    }
}
