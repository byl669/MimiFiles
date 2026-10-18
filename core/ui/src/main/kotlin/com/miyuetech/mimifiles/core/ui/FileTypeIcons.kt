package com.miyuetech.mimifiles.core.ui

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind

/** 文件大类。图标、着色、类型标签都从它派生，保证三者永远一致。 */
enum class FileCategory { FOLDER, IMAGE, VIDEO, AUDIO, PDF, ARCHIVE, APK, CODE, TEXT, OTHER }

/**
 * 文件类型 → 大类 / 图标 / 着色。
 *
 * ## 为什么按扩展名而不是 MIME
 * 列表页拿到的 [FsEntry.mimeType] 在 SMB 上常常是 `null`（协议本身不返回），
 * 在 SAF 上可能是 provider 瞎报的 `application/octet-stream`。
 * 扩展名虽然不如魔数可靠，但**在浏览场景里它才是用户心里的"类型"**——
 * 一个被系统报成 octet-stream 的 `.txt`，用户依然期待看到文本图标。
 * 真正按内容判定留给需要打开文件的 M3。
 *
 * 所有图标都是单色 vector，[tintRes] 返回的语义色由调用方 setImageTintList。
 */
object FileTypeIcons {

    private val IMAGE = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "avif",
        "svg", "ico", "dng", "raw", "tif", "tiff", "jfif",
    )
    private val VIDEO = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts",
        "m2ts", "mpg", "mpeg", "3gp", "rmvb", "rm", "vob",
    )
    private val AUDIO = setOf(
        "mp3", "flac", "wav", "aac", "ogg", "oga", "opus", "m4a", "wma", "ape", "amr", "mid",
    )
    private val PDF = setOf("pdf")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "tgz", "lz", "zst")
    private val APK = setOf("apk", "apks", "xapk", "apkm")
    private val CODE = setOf(
        "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "cs", "go", "rs",
        "rb", "php", "swift", "sh", "bat", "ps1", "gradle", "sql", "lua", "dart",
    )
    private val TEXT = setOf(
        "txt", "log", "md", "markdown", "json", "xml", "csv", "ini", "cfg", "conf",
        "yaml", "yml", "properties", "html", "htm", "css",
    )

    fun categoryOf(entry: FsEntry): FileCategory = categoryOf(entry.kind, entry.extension)

    fun categoryOf(kind: FsKind, extension: String): FileCategory = when {
        kind == FsKind.DIRECTORY -> FileCategory.FOLDER
        extension in IMAGE -> FileCategory.IMAGE
        extension in VIDEO -> FileCategory.VIDEO
        extension in AUDIO -> FileCategory.AUDIO
        extension in PDF -> FileCategory.PDF
        extension in ARCHIVE -> FileCategory.ARCHIVE
        extension in APK -> FileCategory.APK
        extension in CODE -> FileCategory.CODE
        extension in TEXT -> FileCategory.TEXT
        else -> FileCategory.OTHER
    }

    @DrawableRes
    fun iconRes(entry: FsEntry): Int = iconRes(categoryOf(entry))

    @DrawableRes
    fun iconRes(category: FileCategory): Int = when (category) {
        FileCategory.FOLDER -> R.drawable.ic_file_folder
        FileCategory.IMAGE -> R.drawable.ic_file_image
        FileCategory.VIDEO -> R.drawable.ic_file_video
        FileCategory.AUDIO -> R.drawable.ic_file_audio
        FileCategory.PDF -> R.drawable.ic_file_pdf
        FileCategory.ARCHIVE -> R.drawable.ic_file_archive
        FileCategory.APK -> R.drawable.ic_file_apk
        FileCategory.CODE -> R.drawable.ic_file_code
        FileCategory.TEXT -> R.drawable.ic_file_text
        FileCategory.OTHER -> R.drawable.ic_file_generic
    }

    @ColorRes
    fun tintRes(entry: FsEntry): Int = tintRes(categoryOf(entry))

    @ColorRes
    fun tintRes(category: FileCategory): Int = when (category) {
        FileCategory.FOLDER -> R.color.icon_folder
        FileCategory.IMAGE -> R.color.icon_image
        FileCategory.VIDEO -> R.color.icon_video
        FileCategory.AUDIO -> R.color.icon_audio
        FileCategory.PDF -> R.color.icon_pdf
        FileCategory.ARCHIVE -> R.color.icon_archive
        FileCategory.APK -> R.color.icon_apk
        FileCategory.CODE -> R.color.icon_code
        FileCategory.TEXT -> R.color.icon_text
        FileCategory.OTHER -> R.color.icon_unknown
    }

    /** 该条目是否值得生成缩略图（M3 接入 Coil 后按此开关，避免给文本文件白跑一趟）。 */
    fun wantsThumbnail(entry: FsEntry): Boolean =
        entry.isFile && (entry.extension in IMAGE || entry.extension in VIDEO)
}
