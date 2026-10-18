package com.miyuetech.mimifiles.core.common

/**
 * 按扩展名推断 MIME 类型。
 *
 * ## 定位
 * 这是一个**早于 `:core:preview` 的粗粒度查表**，只解决"给 SAF provider 建文件时要报个类型"
 * 和"列表页要选哪张默认图标"两件事。真正的类型判定（含魔数嗅探、`ContentResolver.getType`、
 * MediaStore 元数据）在 M3 的 `:core:preview` 里做——那里才知道文件的**内容**，
 * 这里只知道**名字**。
 *
 * 未识别的类型统一回落到 `application/octet-stream`：
 * SAF 的 provider（尤其是云盘类）对未知类型更保守，宁可让它当普通文件存。
 */
object MimeTypes {

    const val OCTET_STREAM = "application/octet-stream"

    private const val PREFIX_IMAGE = "image/"
    private const val PREFIX_VIDEO = "video/"
    private const val PREFIX_AUDIO = "audio/"
    private const val PREFIX_TEXT = "text/"

    private val TABLE: Map<String, String> = buildMap {
        // 图片
        put("jpg", "image/jpeg"); put("jpeg", "image/jpeg"); put("png", "image/png")
        put("gif", "image/gif"); put("bmp", "image/bmp"); put("webp", "image/webp")
        put("heic", "image/heic"); put("heif", "image/heif"); put("avif", "image/avif")
        put("svg", "image/svg+xml"); put("ico", "image/x-icon"); put("dng", "image/x-adobe-dng")
        put("raw", "image/x-dcraw"); put("tif", "image/tiff"); put("tiff", "image/tiff")

        // 视频
        put("mp4", "video/mp4"); put("m4v", "video/x-m4v"); put("mkv", "video/x-matroska")
        put("webm", "video/webm"); put("avi", "video/x-msvideo"); put("mov", "video/quicktime")
        put("wmv", "video/x-ms-wmv"); put("flv", "video/x-flv"); put("ts", "video/mp2t")
        put("m2ts", "video/mp2t"); put("mpg", "video/mpeg"); put("mpeg", "video/mpeg")
        put("3gp", "video/3gpp"); put("rmvb", "application/vnd.rn-realmedia-vbr")

        // 音频
        put("mp3", "audio/mpeg"); put("flac", "audio/flac"); put("wav", "audio/wav")
        put("aac", "audio/aac"); put("ogg", "audio/ogg"); put("oga", "audio/ogg")
        put("opus", "audio/opus"); put("m4a", "audio/mp4"); put("wma", "audio/x-ms-wma")
        put("ape", "audio/x-ape"); put("amr", "audio/amr")

        // 文本 / 文档
        put("txt", "text/plain"); put("log", "text/plain"); put("md", "text/markdown")
        put("json", "application/json"); put("xml", "text/xml"); put("csv", "text/csv")
        put("html", "text/html"); put("htm", "text/html"); put("css", "text/css")
        put("js", "text/javascript"); put("kt", "text/x-kotlin"); put("java", "text/x-java")
        put("py", "text/x-python"); put("sh", "text/x-shellscript"); put("ini", "text/plain")
        put("yaml", "text/yaml"); put("yml", "text/yaml"); put("properties", "text/plain")
        put("pdf", "application/pdf")
        put("epub", "application/epub+zip")

        // 压缩包
        put("zip", "application/zip"); put("rar", "application/vnd.rar")
        put("7z", "application/x-7z-compressed"); put("tar", "application/x-tar")
        put("gz", "application/gzip"); put("bz2", "application/x-bzip2")
        put("xz", "application/x-xz"); put("iso", "application/x-iso9660-image")

        // 安装包 / 其它
        put("apk", "application/vnd.android.package-archive")
        put("apks", "application/vnd.android.package-archive")
        put("xapk", "application/vnd.android.package-archive")
        put("bin", OCTET_STREAM)
    }

    /** 传入文件名或裸扩展名都可以。 */
    fun of(fileNameOrExtension: String): String {
        val ext = fileNameOrExtension.substringAfterLast('.', fileNameOrExtension).lowercase()
        if (ext.isEmpty()) return OCTET_STREAM
        return TABLE[ext] ?: OCTET_STREAM
    }

    fun isImage(mime: String?): Boolean = mime?.startsWith(PREFIX_IMAGE) == true

    fun isVideo(mime: String?): Boolean = mime?.startsWith(PREFIX_VIDEO) == true

    fun isAudio(mime: String?): Boolean = mime?.startsWith(PREFIX_AUDIO) == true

    fun isText(mime: String?): Boolean =
        mime?.startsWith(PREFIX_TEXT) == true ||
            mime == "application/json" || mime == "application/xml"

    /** 图片与视频共用一套"媒体"图标与缩略图策略。 */
    fun isMedia(mime: String?): Boolean = isImage(mime) || isVideo(mime)

    /** 可交给 Media3 播放。 */
    fun isPlayableMedia(mime: String?): Boolean = isVideo(mime) || isAudio(mime)
}
