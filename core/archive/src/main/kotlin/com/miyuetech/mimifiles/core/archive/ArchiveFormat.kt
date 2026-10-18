package com.miyuetech.mimifiles.core.archive

/**
 * 支持的压缩格式。
 *
 * ## 判定为什么以"扩展名为主、魔数为辅"
 * 与图片不同：压缩包的扩展名可靠性很高（`zip`/`rar`/`7z`/`tar` 都是用户手动命名的），
 * 而"读魔数"在这里**必须先知道容器类型才能读对位置**——zip 的签名在文件头，
 * tar 的签名却在偏移 257 处，gz 又在文件头。所以先按扩展名选解析器，
 * 失败后再用魔数兜底（见 [detect]）。
 *
 * ## [readable] 与 [extractable]
 * 两者的区别是真实的：`gz`/`bz2`/`xz` 是**单文件压缩流**，里面没有目录结构，
 * 列出条目只能给一个名字，而"解压"就是把它写成同名的文件。
 * UI 需要据此换文案（不显示"解压到文件夹"）。
 */
enum class ArchiveFormat(
    val label: String,
    val extensions: List<String>,
    /** 能否内置列举与解压。 */
    val readable: Boolean,
    /** 是否有多条目（zip/tar/7z/rar）而非单流（gz/bz2/xz）。 */
    val multiEntry: Boolean,
) {
    ZIP("ZIP", listOf("zip", "jar", "apk", "xpi", "epub", "cbz"), readable = true, multiEntry = true),
    SEVEN_Z("7z", listOf("7z"), readable = true, multiEntry = true),
    RAR("RAR", listOf("rar"), readable = true, multiEntry = true),
    TAR("TAR", listOf("tar"), readable = true, multiEntry = true),
    GZIP("GZip", listOf("gz", "tgz"), readable = true, multiEntry = false),
    BZIP2("BZip2", listOf("bz2", "tbz", "tbz2"), readable = true, multiEntry = false),
    XZ("XZ", listOf("xz", "txz"), readable = true, multiEntry = false),
    ZSTD("Zstd", listOf("zst", "tzst"), readable = false, multiEntry = false),
    UNKNOWN("压缩包", emptyList(), readable = false, multiEntry = true),
    ;

    companion object {

        /**
         * 按文件名（先）与文件头（后）判定格式。
         *
         * @param name 文件名，用来看扩展名
         * @param head 文件开头若干字节（建议 ≥ 512）；给 `null` 表示只按扩展名判
         */
        fun detect(name: String, head: ByteArray? = null): ArchiveFormat {
            byExtension(name)?.let { return it }
            if (head != null) byMagic(head)?.let { return it }
            // 扩展名是 gz/tgz 之类但被上面的分支接住了；到这里说明两边都不认。
            return UNKNOWN
        }

        /**
         * 按扩展名判定。
         *
         * ⚠️ **两个复合扩展名要特殊处理**：
         * `tar.gz` 与 `tar.bz2` 既是"tar 包"又是"被压缩的流"。
         * 这里刻意判成 [GZIP]/[BZIP2]（而非 TAR）——因为它们在磁盘上**确实**是
         * 单层的 gz/bz2 流，"先解压再解包"是两步操作，而我们的解压器只做一步，
         * 所以先如实把它当 gz 流处理（解出来是一个 `.tar` 文件，用户再打开一次即可）。
         */
        fun byExtension(name: String): ArchiveFormat? {
            val lower = name.substringAfterLast('/').lowercase()
            val ext = lower.substringAfterLast('.', "")
            if (ext.isEmpty() || ext == lower) return null
            return entries.firstOrNull { format -> format.extensions.any { it == ext } }
        }

        /**
         * 按文件头魔数判定。
         *
         * tar 的签名（`ustar`）在偏移 257，所以 [head] 至少要 262 字节才判得出来 ——
         * 调用方给 512 字节即可覆盖全部情况。
         */
        fun byMagic(head: ByteArray): ArchiveFormat? {
            // ⚠️ 每个签名各自检查长度，**不能**统一要求 ≥4 字节：
            // gzip 的签名只有 2 字节（`1F 8B`），zip/7z/rar 需要 4 字节。
            // 统一要求 4 字节时，一个只有 2~3 字节开头的文件（截断的空包、
            // 极小的测试文件）会判不出来 —— 而"判不出来"会被上层当成"不是压缩包"。
            fun at(index: Int): Int? = if (index < head.size) head[index].toInt() and 0xFF else null
            fun matches(vararg bytes: Int): Boolean =
                bytes.indices.all { at(it) == bytes[it] }

            // PK\x03\x04（常规）/ PK\x05\x06（空包）/ PK\x07\x08（跨卷）
            if (matches(0x50, 0x4B, 0x03, 0x04) ||
                matches(0x50, 0x4B, 0x05, 0x06) ||
                matches(0x50, 0x4B, 0x07, 0x08)
            ) {
                return ZIP
            }
            if (matches(0x37, 0x7A, 0xBC, 0xAF)) return SEVEN_Z
            // RAR4 `Rar!\x1A\x07` 与 RAR5 `Rar!\x1A\x07\x01\x00` 共用前四字节
            if (matches(0x52, 0x61, 0x72, 0x21)) return RAR
            if (matches(0x1F, 0x8B)) return GZIP
            if (matches(0x42, 0x5A, 0x68)) return BZIP2
            if (matches(0xFD, 0x37, 0x7A, 0x58)) return XZ
            if (matches(0x28, 0xB5, 0x2F, 0xFD)) return ZSTD

            // tar：`ustar` 在偏移 257。必须做边界检查，否则短数据会越界。
            if (head.size >= 262) {
                val ustar = byteArrayOf(0x75, 0x73, 0x74, 0x61, 0x72)
                if (ustar.indices.all { head[257 + it] == ustar[it] }) return TAR
            }
            return null
        }

        /** 按 MIME 判定（部分后端能报出 `application/zip` 这类 MIME）。 */
        fun byMime(mime: String?): ArchiveFormat? {
            val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return null
            return when (m) {
                "application/zip", "application/x-zip-compressed" -> ZIP
                "application/x-7z-compressed" -> SEVEN_Z
                "application/vnd.rar", "application/x-rar-compressed" -> RAR
                "application/x-tar" -> TAR
                "application/gzip", "application/x-gzip" -> GZIP
                "application/x-bzip2" -> BZIP2
                "application/x-xz" -> XZ
                "application/zstd" -> ZSTD
                else -> null
            }
        }
    }
}
