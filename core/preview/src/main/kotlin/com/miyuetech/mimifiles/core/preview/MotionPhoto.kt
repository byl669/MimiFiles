package com.miyuetech.mimifiles.core.preview

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.withContext

/**
 * 动态照片（Motion Photo）识别 —— 方案 §6.2 最后一行。
 *
 * ## 三种主流实现其实是一件事
 * 「静态图 + 一小段视频」有多个互不兼容的封装，但**都是同一张 JPEG/HEIC 后面接一段 MP4**：
 *
 * | 来源 | 声名 | 视频在哪 |
 * |---|---|---|
 * | Google / 小米 MVIMG | XMP `GCamera:MicroVideoOffset="N"` | 文件末尾 N 字节 |
 * | Google Motion Photo v2 | XMP `Container:Item Item:Mime="video/mp4" Item:Length="N"` | 文件末尾 N 字节 |
 * | 三星 / 华为荣耀等 | 同类 XMP，或干脆没有声名 | 文件末尾（靠 `ftyp` 反查） |
 * | Apple Live Photo | **不内嵌**：同目录同名的 `.MOV` | 另一个文件 |
 *
 * 所以判定策略是"先读声名，读不到就扫 `ftyp`"，最后单独处理 Apple 的配对文件。
 *
 * ## 只读尾部 128 KB
 * 声名（XMP）与追加视频的**头部**都在文件尾部附近。读 128 KB 足够覆盖 XMP 包
 * （Google 的 `Camera:MicroVideoOffset` 声明位于文件最末的 XMP 段里），
 * 而且这是一次 ranged read —— 不会为了"判断是不是动态照片"把整张照片读一遍。
 */
object MotionPhoto {

    enum class Kind {
        /** 不是动态照片。 */
        NONE,

        /** Google/小米的 MicroVideo（v1）。 */
        GOOGLE_MICRO_VIDEO,

        /** Google Motion Photo v2（`Container:Item` 形式）。 */
        GOOGLE_MOTION_PHOTO,

        /** 有声名但来自其它厂商（三星 / 华为 / 荣耀…）。 */
        VENDOR_XMP,

        /** 没有声名，但从文件结构上查到了追加的 `ftyp`（MP4/HEIC 混排）。 */
        APPENDED_VIDEO,

        /** Apple Live Photo：视频是同目录的另一个 `.MOV` 文件。 */
        APPLE_LIVE,
    }

    /**
     * @param videoOffset 内嵌视频在文件中的起始偏移；无内嵌视频时为 `-1`
     * @param videoLength 内嵌视频字节数；未知/无内嵌视频时为 `-1`
     * @param presentationUs 静态图对应的视频时间点（微秒），用于"长按从这个瞬间开始播"
     */
    data class Detected(
        val kind: Kind,
        val videoOffset: Long = -1L,
        val videoLength: Long = -1L,
        val presentationUs: Long? = null,
    ) {
        val isMotionPhoto: Boolean get() = kind != Kind.NONE

        /** 视频与静态图在同一个文件里（可以直接切片播放）。 */
        val hasEmbeddedVideo: Boolean get() = videoOffset >= 0 && videoLength > 0
    }

    /** 尾部读取窗口：128 KB。 */
    private const val TAIL_BYTES = 128 * 1024

    /**
     * 兜底全量扫描的上限。
     *
     * ## 为什么需要"全量"这一档（实测发现的缺口）
     * 尾部窗口只有 128 KB，而**追加的视频通常比它大得多**（1~5 秒的片段常见 200 KB~2 MB）。
     * 也就是说视频的 `ftyp` box 落在文件中间，尾部窗口根本看不到它 ——
     * 单靠尾部扫描，兜底路径对真实动态照片几乎永远不生效。
     *
     * 所以当"尾部没有声名、也没有在尾部找到 ftyp"且文件不大时，再整读一遍去找。
     * 上限 12 MB 的理由：动态照片（一张照片 + 几秒视频）几乎都在这个量级以内；
     * 而它同时把最坏代价钉死（一次 ≤12 MB 的读，而不是对一个 2 GB 的文件做全扫）。
     * 超过这个大小的文件若真是动态照片，它一定有 XMP 声名 —— 官方实现（Google MVIMG、
     * Samsung、Huawei）都会写；没有声名又巨大的，更可能是普通照片或视频。
     */
    private const val FULL_SCAN_LIMIT = 12L * 1024 * 1024

    suspend fun detect(
        path: FsPath,
        registry: FileSystemRegistry = FileSystems.registry,
    ): Detected = withContext(PreviewDispatchers.io) {
        val fileSystem = registry.resolveOrNull(path) ?: return@withContext Detected(Kind.NONE)
        val size = runCatching { fileSystem.stat(path).size }.getOrDefault(-1L)
        if (size <= 0L) return@withContext Detected(Kind.NONE)

        val tailStart = (size - TAIL_BYTES).coerceAtLeast(0L)
        val tail = runCatching {
            fileSystem.openRead(path).use { it.readBytes(tailStart, (size - tailStart).toInt()) }
        }.getOrNull()
        if (tail == null || tail.isEmpty()) return@withContext Detected(Kind.NONE)

        val tailText = String(tail, Charsets.ISO_8859_1)

        // ① XMP 声名（Google 家族与追随其规范的厂商）—— 这是最可靠的判据
        val declared = declaredLengthFrom(tailText, size)
        if (declared != null) return@withContext declared

        // ② 尾部里没有声名：先看尾部有没有 ftyp（小视频的情形）
        val tailHit = ftypVideoIn(tail, tailStart, size)
        if (tailHit != null) return@withContext tailHit

        // ③ 兜底：文件不大就整读一遍再找。这一档覆盖"视频比尾部窗口大"的常见情形。
        if (size <= FULL_SCAN_LIMIT) {
            val whole = runCatching {
                fileSystem.openRead(path).use { it.readBytes(0, size.toInt()) }
            }.getOrNull()
            if (whole != null && whole.size == size.toInt()) {
                declaredLengthFrom(String(whole, Charsets.ISO_8859_1), size)?.let { return@withContext it }
                // ftyp 从偏移 4 起找：给它前面的 box size 留位置；同时排除文件自身头部
                // 那些 HEIC/MP4 本来就有的 ftyp（它们的偏移是 4）。
                ftypVideoIn(whole, 0L, size, minimumFtypIndex = 16)?.let { return@withContext it }
            }
        }

        Detected(Kind.NONE)
    }

    /** 从 XMP 文本里解析"视频在文件末尾占多少字节"。返回 `null`=没有可用声名。 */
    private fun declaredLengthFrom(text: String, size: Long): Detected? {
        // XMP 包体很大且声名可能在任意位置，所以只看最后 8 KB 就够——
        // 各家的 XMP 都写在文件最末（JPEG 的 APP1 也可能在头部，所以尾部扫不到时
        // 由调用方在"整读"那一档里再试一次同样的解析）。
        val window = if (text.length > XMP_WINDOW) text.substring(text.length - XMP_WINDOW) else text
        val microVideoOffset = intAttr(window, MICRO_VIDEO_OFFSET)
        val itemLength = intAttr(window, ITEM_LENGTH)
        val length = microVideoOffset ?: itemLength ?: return null
        if (length <= 0L || length >= size) return null
        val kind = when {
            microVideoOffset != null -> Kind.GOOGLE_MICRO_VIDEO
            window.contains("Item:Mime=\"video/mp4\"") -> Kind.GOOGLE_MOTION_PHOTO
            else -> Kind.VENDOR_XMP
        }
        return Detected(
            kind = kind,
            videoOffset = size - length,
            videoLength = length,
            presentationUs = longAttr(window, PRESENTATION_US),
        )
    }

    /**
     * 在 [bytes] 里找追加的 `ftyp`。
     *
     * 三个必须的前置条件，否则会把**普通 HEIC / MP4** 误判成动态照片
     * （它们自己的文件头里就有一个 `ftyp`，偏移正好是 4）：
     *  1. `index >= minimumFtypIndex`：给 box 的 4 字节长度字段留位置，
     *     否则算出来的 `videoOffset` 会是负数；尾部扫描时下限是 4，整读时是 16；
     *  2. 算出的视频长度必须超过 [MIN_EMBEDDED_VIDEO_BYTES]；
     *  3. （由调用方保证）要么这次是尾部窗口、要么已经排除文件头部区域。
     */
    private fun ftypVideoIn(
        bytes: ByteArray,
        baseOffset: Long,
        size: Long,
        minimumFtypIndex: Int = 4,
    ): Detected? {
        val index = indexOfFtyp(bytes, from = minimumFtypIndex)
        if (index < 0 || index + 4 >= bytes.size) return null
        val videoOffset = baseOffset + index - 4
        if (videoOffset <= 0L) return null
        val videoLength = size - videoOffset
        if (videoLength <= MIN_EMBEDDED_VIDEO_BYTES) return null
        return Detected(
            kind = Kind.APPENDED_VIDEO,
            videoOffset = videoOffset,
            videoLength = videoLength,
            presentationUs = null,
        )
    }

    /**
     * 找同目录同名的 `.MOV` —— Apple Live Photo。
     *
     * 单独一个方法、而不是塞进 [detect]：它要**列一次目录**（网络后端上是一次真实请求），
     * 而绝大多数文件（PNG、普通 JPEG）根本不需要这一步。
     * 只在"用户长按了、确实想看动态效果"或者"文件是 HEIC"时才值得付这个代价。
     */
    suspend fun findLivePhotoCompanion(
        path: FsPath,
        registry: FileSystemRegistry = FileSystems.registry,
    ): FsPath? = withContext(PreviewDispatchers.io) {
        val parent = path.parent ?: return@withContext null
        val fileSystem = registry.resolveOrNull(path) ?: return@withContext null
        val base = path.displayName.substringBeforeLast('.').lowercase()
        if (base.isEmpty()) return@withContext null
        runCatching {
            fileSystem.list(parent)
                .asSequence()
                .filter { it.isFile }
                .firstOrNull { candidate ->
                    val name = candidate.name
                    val ext = name.substringAfterLast('.', "").lowercase()
                    ext in LIVE_PHOTO_EXTENSIONS &&
                        name.substringBeforeLast('.').lowercase() == base
                }
                ?.path
        }.getOrNull()
    }

    /**
     * 打开"内嵌视频"的切片句柄。
     *
     * 返回的 [SliceSeekableSource] 直接对着原文件读那一段，**不生成任何临时文件**。
     * 调用方负责关闭（可以放心给播放器用，因为[视图关闭不会关闭底层][SliceSeekableSource.close]）。
     */
    suspend fun openEmbeddedVideo(
        path: FsPath,
        detected: Detected,
        registry: FileSystemRegistry = FileSystems.registry,
    ): SeekableSource? = withContext(PreviewDispatchers.io) {
        if (!detected.hasEmbeddedVideo) return@withContext null
        runCatching {
            val fileSystem = registry.resolve(path)
            val source = fileSystem.openRead(path)
            SliceSeekableSource(source, detected.videoOffset, detected.videoLength)
        }.onFailure { MimiLog.w(TAG, "打开动态照片内嵌视频失败：$path", it) }.getOrNull()
    }

    // ── 解析辅助 ─────────────────────────────────────────────────

    private val MICRO_VIDEO_OFFSET = Regex(
        "(?:GCamera|Camera):MicroVideoOffset=\"(\\d+)\"",
    )
    private val ITEM_LENGTH = Regex("Item:Length=\"(\\d+)\"")
    private val PRESENTATION_US = Regex(
        "(?:Camera:MicroVideoPresentationTimestampUs|Camera:MotionPhotoPresentationTimestampUs)" +
            "=\"(-?\\d+)\"",
    )

    private fun intAttr(text: String, regex: Regex): Long? =
        regex.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun longAttr(text: String, regex: Regex): Long? =
        regex.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    /** 在字节里找 `ftyp`（ISO BMFF 的 box 类型），返回其下标；找不到返回 -1。 */
    private fun indexOfFtyp(bytes: ByteArray, from: Int): Int {
        val target = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        var i = from.coerceAtLeast(0)
        outer@ while (i <= bytes.size - target.size) {
            var j = 0
            while (j < target.size) {
                if (bytes[i + j] != target[j]) {
                    i++
                    continue@outer
                }
                j++
            }
            return i
        }
        return -1
    }

    /** XMP 声名只看末尾这么多字符：各家的 XMP 都写在文件最末。 */
    private const val XMP_WINDOW = 8 * 1024

    private const val TAG = "MimiMotionPhoto"

    /** 小于这个长度的"内嵌视频"当作误判（正常动态照片的视频都是几百 KB 起）。 */
    private const val MIN_EMBEDDED_VIDEO_BYTES = 64 * 1024L

    private val LIVE_PHOTO_EXTENSIONS = setOf("mov", "mp4")
}
