package com.miyuetech.mimifiles.core.preview

import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 一次类型探测的结论。
 *
 * [declared] 与 [kind] 分开保留，是为了让 UI 能说人话：
 * 一个 `.bin` 文件被认出是 PNG 时，提示"按扩展名是未知类型，已按 PNG 打开"
 * 比默默打开更可信，也比"打开失败"有用得多。
 */
data class ProbeResult(
    /** 最终采信的类别。 */
    val kind: PreviewKind,
    /** 最终采信的 MIME（用于外发 Intent 与 SAF）。 */
    val mime: String,
    /** 命中的魔数名（`JPEG`/`ZIP`…）；`null` 表示没读到文件头或没命中任何魔数。 */
    val magic: String?,
    /** 仅按扩展名/后端声明得到的类别。 */
    val declared: PreviewKind,
    /** 魔数与扩展名结论不同、且我们采信了魔数。 */
    val corrected: Boolean,
) {
    /** 是否连"交给谁"都不知道。 */
    val isUnknown: Boolean get() = kind == PreviewKind.UNKNOWN
}

/**
 * 文件类型探测 —— **MIME + 扩展名 + 魔数三重判定**（方案 §6.1）。
 *
 * ## 代价控制（这是本类存在的唯一难点）
 * 探测**只读文件头 512 字节**。这条约束必须守住：如果为了判定类型去下载整个文件，
 * 那么"在 NAS 上点一个 1 GB 的 iso"会直接卡住界面十几分钟。512 字节在 SMB / WebDAV 上
 * 是一次很小的 ranged read。
 *
 * ## 采信顺序（有优先级，不是简单的"魔数优先"）
 * ```
 * 魔数命中 且 与扩展名同类别        → 用魔数（顺手拿到更准的 MIME）
 * 魔数命中 且 扩展名是"更具体"的类别 → 用扩展名，不算校正
 *     （ZIP 家族：.docx/.apk 的魔数都是 ZIP，扩展名才知道是 Word 还是安装包）
 * 魔数命中 且 扩展名是 UNKNOWN       → 用魔数（这是最有价值的场景：无扩展名也能打开）
 * 魔数命中 且 与扩展名冲突           → 用魔数，标记 [ProbeResult.corrected]
 * 魔数未命中                        → 用扩展名结论
 * ```
 */
class ContentProbe(
    private val registry: FileSystemRegistry = FileSystems.registry,
) {

    suspend fun probe(entry: com.miyuetech.mimifiles.core.model.FsEntry): ProbeResult =
        probe(path = entry.path, name = entry.name, isDirectory = entry.isDirectory)

    suspend fun probe(
        path: FsPath,
        name: String = path.displayName,
        isDirectory: Boolean = false,
    ): ProbeResult {
        val declaredMime = MimeTypes.of(name)
        val declared = declaredKindOf(declaredMime)
        // 目录不探测：多一次 IO 去确认"这是个目录"没有任何收益。
        if (isDirectory) return ProbeResult(PreviewKind.UNKNOWN, declaredMime, null, declared, false)

        val head = readHeader(path) ?: return ProbeResult(declared, declaredMime, null, declared, false)
        val hit = Magic.sniff(head) ?: return ProbeResult(declared, declaredMime, null, declared, false)

        // ZIP 家族的特例：魔数只能说到"这是个 ZIP 容器"，
        // 而 `.docx` / `.xlsx` / `.apk` 全都是 ZIP。扩展名在这种情况下**更可信**，
        // 不能因为魔数说了 ZIP 就把 Word 文档降级成压缩包（那会让"打开方式"选错应用）。
        if (hit.kind == PreviewKind.ARCHIVE &&
            (declared == PreviewKind.OFFICE || declared == PreviewKind.PACKAGE)
        ) {
            return ProbeResult(declared, declaredMime, hit.label, declared, false)
        }

        val corrected = hit.kind != declared && declared != PreviewKind.UNKNOWN
        return ProbeResult(hit.kind, hit.mime, hit.label, declared, corrected)
    }

    /**
     * 读文件头。**任何失败都返回 `null`**：探测失败不该让"打开文件"这件事失败，
     * 调用方拿不到魔数就按扩展名走。
     */
    private suspend fun readHeader(path: FsPath, limit: Int = HEADER_BYTES): ByteArray? {
        val fileSystem = registry.resolveOrNull(path) ?: return null
        return runCatching {
            fileSystem.openRead(path).use { source -> source.readBytes(0, limit) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    companion object {

        /**
         * 512 字节。取值理由：覆盖了所有本工程关心的魔数（最长的是 ISO BMFF 的
         * `ftyp` + brand，12 字节），同时留下足够样本让"像不像文本"的启发式有判断力
         * ——16 字节的 ASCII 不能说明是文本，512 字节可以。
         */
        const val HEADER_BYTES = 512

        /** MIME → [PreviewKind]。公共实现，`PreviewRouter` 也用它。 */
        fun declaredKindOf(mime: String?): PreviewKind {
            if (mime.isNullOrEmpty()) return PreviewKind.UNKNOWN
            return when {
                mime == "image/gif" -> PreviewKind.ANIMATED_IMAGE
                mime.startsWith("image/") -> PreviewKind.IMAGE
                mime.startsWith("video/") -> PreviewKind.VIDEO
                mime.startsWith("audio/") -> PreviewKind.AUDIO
                mime == "application/pdf" -> PreviewKind.PDF
                mime.startsWith("text/") -> PreviewKind.TEXT
                mime == "application/json" || mime == "application/xml" ||
                    mime == "application/rtf" -> PreviewKind.TEXT
                mime == "application/vnd.android.package-archive" -> PreviewKind.PACKAGE
                mime.contains("officedocument") || mime.contains("msword") ||
                    mime.contains("ms-excel") || mime.contains("ms-powerpoint") ||
                    mime.contains("opendocument") -> PreviewKind.OFFICE
                mime == "application/zip" ||
                    mime.contains("rar") || mime.contains("7z-compressed") ||
                    mime == "application/x-tar" || mime == "application/gzip" ||
                    mime.contains("bzip2") || mime.contains("x-xz") -> PreviewKind.ARCHIVE
                else -> PreviewKind.UNKNOWN
            }
        }
    }
}
