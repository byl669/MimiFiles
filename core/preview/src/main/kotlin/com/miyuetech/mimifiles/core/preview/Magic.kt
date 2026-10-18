package com.miyuetech.mimifiles.core.preview

/**
 * 文件头魔数嗅探。
 *
 * ## 为什么必须有这一层
 * 扩展名是可以撒谎的，而且在真实场景里经常撒谎：
 *  - 微信/QQ 传完把 `.jpg` 写成 `.bin`；
 *  - 相机导出的 `.JPG` 其实是 HEIC（改了名没改内容）；
 *  - 下载中断留下一个 0 字节的 `.mp4`；
 *  - 有人把 PDF 命名为 `.txt` 就为了绕过某个上传校验。
 *
 * 按魔数判定只需要 512 字节，代价可以忽略；而**判错的代价**是"打开失败"或者
 * 更糟——把一个 200 MB 的压缩包当文本读进内存。
 *
 * ## 只做"类别级"判定
 * 这里不追求精确到 MIME 子类型（`image/heic` 还是 `image/heif` 对用户没区别），
 * 只判定 [PreviewKind] + 一个人类可读的 `label` 用于日志与"类型校正"提示。
 * 需要精确 MIME 的地方（SAF、外发）走 `MimeTypes` + 平台探测。
 */
internal object Magic {

    /** 一次命中的结果。[label] 直接进日志，所以写成人话（"JPEG"、"ZIP"）。 */
    data class Hit(val kind: PreviewKind, val mime: String, val label: String)

    /**
     * @param head 文件**开头**的若干字节（建议 ≥ 64；本工程固定取 512）。
     */
    fun sniff(head: ByteArray): Hit? {
        if (head.size < 4) return null

        // ── 图片 ──────────────────────────────────────────────
        if (head.startsWith(0xFF, 0xD8, 0xFF)) return Hit(PreviewKind.IMAGE, "image/jpeg", "JPEG")
        if (head.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Hit(PreviewKind.IMAGE, "image/png", "PNG")
        }
        if (head.startsWithAscii("GIF87a") || head.startsWithAscii("GIF89a")) {
            return Hit(PreviewKind.ANIMATED_IMAGE, "image/gif", "GIF")
        }
        if (head.startsWithAscii("BM")) return Hit(PreviewKind.IMAGE, "image/bmp", "BMP")
        if (head.startsWith(0x49, 0x49, 0x2A, 0x00) || head.startsWith(0x4D, 0x4D, 0x00, 0x2A)) {
            return Hit(PreviewKind.IMAGE, "image/tiff", "TIFF")
        }
        if (head.startsWithAscii("RIFF")) {
            val fourCC = head.ascii(8, 4)
            when (fourCC) {
                "WEBP" -> return Hit(PreviewKind.IMAGE, "image/webp", "WEBP")
                "WAVE" -> return Hit(PreviewKind.AUDIO, "audio/wav", "WAVE")
                "AVI " -> return Hit(PreviewKind.VIDEO, "video/x-msvideo", "AVI")
            }
        }

        // ── ISO BMFF（MP4 / MOV / HEIC / AVIF 共用一个 container）──
        // 结构是 `[4 字节 box size][ 'ftyp' ][4 字节 major brand]`，
        // 所以品牌要读到偏移 8 才知道是视频还是 HEIC 照片。
        if (head.size >= 12 && head.ascii(4, 4) == "ftyp") {
            val brand = head.ascii(8, 4)
            return when (brand) {
                "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1" ->
                    Hit(PreviewKind.IMAGE, "image/heic", "HEIC")

                "avif", "avis" -> Hit(PreviewKind.IMAGE, "image/avif", "AVIF")

                "qt  " -> Hit(PreviewKind.VIDEO, "video/quicktime", "QuickTime")

                "3gp4", "3gp5", "3g2a", "3g2b" -> Hit(PreviewKind.VIDEO, "video/3gpp", "3GPP")

                else -> Hit(PreviewKind.VIDEO, "video/mp4", "MP4")
            }
        }

        // ── 音频 ──────────────────────────────────────────────
        if (head.startsWithAscii("ID3")) return Hit(PreviewKind.AUDIO, "audio/mpeg", "MP3(ID3)")
        if (head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) {
            return Hit(PreviewKind.AUDIO, "audio/mpeg", "MP3")
        }
        if (head.startsWithAscii("fLaC")) return Hit(PreviewKind.AUDIO, "audio/flac", "FLAC")
        if (head.startsWithAscii("OggS")) return Hit(PreviewKind.AUDIO, "audio/ogg", "OGG")
        if (head.startsWithAscii("MThd")) return Hit(PreviewKind.AUDIO, "audio/midi", "MIDI")

        // ── 视频（非 ISO BMFF 容器）────────────────────────────
        if (head.startsWith(0x1A, 0x45, 0xDF, 0xA3)) {
            return Hit(PreviewKind.VIDEO, "video/x-matroska", "Matroska")
        }
        if (head.startsWithAscii("FLV")) return Hit(PreviewKind.VIDEO, "video/x-flv", "FLV")
        if (head.size >= 12 && head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "AVI ") {
            return Hit(PreviewKind.VIDEO, "video/x-msvideo", "AVI")
        }

        // ── 文档 / 容器 ───────────────────────────────────────
        if (head.startsWithAscii("%PDF")) return Hit(PreviewKind.PDF, "application/pdf", "PDF")
        if (head.startsWithAscii("{\\rtf")) return Hit(PreviewKind.TEXT, "application/rtf", "RTF")
        if (head.startsWith(0x50, 0x4B, 0x03, 0x04) || head.startsWith(0x50, 0x4B, 0x05, 0x06)) {
            // ZIP 家族：docx/xlsx/pptx/apk 也在这里。子类型留给扩展名判断——
            // 精确区分要读中央目录（在文件**尾部**），为判定类型多跑一次尾读不划算。
            val kind = when {
                head.containsAscii("AndroidManifest.xml") -> PreviewKind.PACKAGE
                head.containsAscii("word/") || head.containsAscii("xl/") || head.containsAscii("ppt/") ->
                    PreviewKind.OFFICE

                else -> PreviewKind.ARCHIVE
            }
            val mime = when (kind) {
                PreviewKind.PACKAGE -> "application/vnd.android.package-archive"
                PreviewKind.OFFICE -> "application/vnd.openxmlformats-officedocument"
                else -> "application/zip"
            }
            return Hit(kind, mime, "ZIP")
        }
        if (head.startsWithAscii("Rar!")) return Hit(PreviewKind.ARCHIVE, "application/vnd.rar", "RAR")
        if (head.startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) {
            return Hit(PreviewKind.ARCHIVE, "application/x-7z-compressed", "7-Zip")
        }
        if (head.size >= 265 && head.ascii(257, 5) == "ustar") {
            return Hit(PreviewKind.ARCHIVE, "application/x-tar", "TAR")
        }
        if (head.startsWith(0x1F, 0x8B)) return Hit(PreviewKind.ARCHIVE, "application/gzip", "GZIP")
        if (head.startsWithAscii("BZh")) return Hit(PreviewKind.ARCHIVE, "application/x-bzip2", "BZIP2")

        // ── 文本（没有魔数，只能靠"长得像不像文本"）─────────────
        if (looksLikeText(head)) {
            return Hit(PreviewKind.TEXT, "text/plain", "TEXT")
        }
        return null
    }

    /** UTF-8 BOM。 */
    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * 文本启发式判定。
     *
     * 判据刻意保守：**允许** UTF-8/GBK 高位字节（中文文本必然是高位字节），
     * 但要求"控制字符"必须全部是合法的空白/控制（`\t \n \r \f \b \u001b`）。
     * 二进制文件几乎不可能在前 512 字节里一个非法控制字符都不出现——
     * 这也是 `file(1)` 判定 text 的经典做法。
     */
    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.size >= 3 &&
            head[0] == BOM_UTF8[0] && head[1] == BOM_UTF8[1] && head[2] == BOM_UTF8[2]
        ) {
            return true
        }
        // UTF-16 BOM：也算文本（解码交给 `TextDecoder`）。
        if (head.size >= 2 && ((head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) ||
                (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()))
        ) {
            return true
        }
        for (b in head) {
            val v = b.toInt() and 0xFF
            // 允许的 ASCII 控制字符（制表、换行、回车、换页、退格、ESC）
            if (v == 0x09 || v == 0x0A || v == 0x0D || v == 0x0C || v == 0x08 || v == 0x1B) continue
            // 0x00 / 0x01… 这些真·二进制控制符出现在前 512 字节里 → 一定是二进制
            if (v < 0x20) return false
            if (v == 0x7F) return false
            // 高位字节：中文文本必然是高位字节，交给 `TextDecoder` 判编码
            if (v >= 0x80) continue
            // 其余 ASCII 可打印字符一律接受
        }
        return true
    }

    // ── 字节工具 ──────────────────────────────────────────────

    private fun ByteArray.startsWith(vararg expected: Int): Boolean {
        if (size < expected.size) return false
        for (i in expected.indices) {
            if ((this[i].toInt() and 0xFF) != expected[i]) return false
        }
        return true
    }

    private fun ByteArray.startsWithAscii(text: String): Boolean {
        if (size < text.length) return false
        for (i in text.indices) {
            if (this[i].toInt() and 0xFF != text[i].code) return false
        }
        return true
    }

    private fun ByteArray.containsAscii(text: String): Boolean {
        if (text.isEmpty() || size < text.length) return false
        val first = text[0].code
        for (start in 0..size - text.length) {
            if ((this[start].toInt() and 0xFF) != first) continue
            var ok = true
            for (i in 1 until text.length) {
                if ((this[start + i].toInt() and 0xFF) != text[i].code) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    /** 越界返回空串（调用方据此自然判不中）。 */
    private fun ByteArray.ascii(from: Int, length: Int): String {
        if (from < 0 || from + length > size) return ""
        val sb = StringBuilder(length)
        for (i in from until from + length) {
            sb.append(((this[i].toInt() and 0xFF).toChar()))
        }
        return sb.toString()
    }
}
