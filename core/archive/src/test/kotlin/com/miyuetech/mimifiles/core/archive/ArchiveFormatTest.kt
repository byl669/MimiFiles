package com.miyuetech.mimifiles.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 格式判定：扩展名 / 魔数 / MIME 三条路的优先级与边界。 */
class ArchiveFormatTest {

    @Test
    fun `扩展名判定覆盖常见格式且大小写不敏感`() {
        val cases = mapOf(
            "backup.zip" to ArchiveFormat.ZIP,
            "Photo.JPG.zip" to ArchiveFormat.ZIP,
            "app.apk" to ArchiveFormat.ZIP,
            "book.epub" to ArchiveFormat.ZIP,
            "data.7z" to ArchiveFormat.SEVEN_Z,
            "old.rar" to ArchiveFormat.RAR,
            "rootfs.tar" to ArchiveFormat.TAR,
            "logs.txt.gz" to ArchiveFormat.GZIP,
            "rootfs.tar.gz" to ArchiveFormat.GZIP,
            "rootfs.tgz" to ArchiveFormat.GZIP,
            "data.bz2" to ArchiveFormat.BZIP2,
            "data.tar.bz2" to ArchiveFormat.BZIP2,
            "data.xz" to ArchiveFormat.XZ,
            "data.zst" to ArchiveFormat.ZSTD,
            "photo.jpg" to null,
            "readme" to null,
        )
        for ((name, expected) in cases) {
            assertEquals("文件名 $name", expected, ArchiveFormat.byExtension(name))
        }
    }

    @Test
    fun `tar_gz 判定为 gz 而不是 tar（磁盘上确实是单层流）`() {
        // 这条是刻意设计：我们只做"一步解压"，所以 .tar.gz 解出来是一个 .tar 文件，
        // 用户再打开一次即可。若判成 TAR，解析器会拿 gzip 的头当 tar 头，直接失败。
        assertEquals(ArchiveFormat.GZIP, ArchiveFormat.byExtension("a.tar.gz"))
        assertEquals(ArchiveFormat.BZIP2, ArchiveFormat.byExtension("a.tar.bz2"))
    }

    @Test
    fun `魔数判定识别各类容器`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.byMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.byMagic(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertEquals(
            ArchiveFormat.SEVEN_Z,
            ArchiveFormat.byMagic(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte())),
        )
        assertEquals(ArchiveFormat.RAR, ArchiveFormat.byMagic("Rar!\u001A\u0007".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ArchiveFormat.GZIP, ArchiveFormat.byMagic(byteArrayOf(0x1F, 0x8B.toByte())))
        assertEquals(ArchiveFormat.BZIP2, ArchiveFormat.byMagic("BZh".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(
            ArchiveFormat.XZ,
            ArchiveFormat.byMagic(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58)),
        )
        assertEquals(
            ArchiveFormat.ZSTD,
            ArchiveFormat.byMagic(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())),
        )
    }

    @Test
    fun `tar 的魔数在偏移 257 处`() {
        val head = ByteArray(512)
        // 前 256 字节是文件名/权限等，随便填；257 起是 "ustar"
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(head, 257)
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.byMagic(head))
    }

    @Test
    fun `tar 判定不越界（短数据不崩）`() {
        // 262 字节才够读 ustar；给 10 字节必须安全返回 null 而不是抛异常。
        assertNull(ArchiveFormat.byMagic(ByteArray(10)))
        assertNull(ArchiveFormat.byMagic(ByteArray(0)))
    }

    @Test
    fun `扩展名优先于魔数`() {
        // 一个叫 .zip 但头是 7z 的文件：按扩展名选 zip 解析器会失败，
        // 这里如实按扩展名走，由调用方在解析失败后再用魔数纠正。
        // 明确写下这条优先级，是为了让"为什么这个逻辑不反过来"有据可查。
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect("x.zip", byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte())))
    }

    @Test
    fun `扩展名认不出时用魔数兜底`() {
        val head = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect("下载文件", head))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormat.detect("下载文件", null))
    }

    @Test
    fun `MIME 判定`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.byMime("application/zip"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.byMime("application/x-zip-compressed"))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.byMime("application/x-7z-compressed"))
        assertEquals(ArchiveFormat.RAR, ArchiveFormat.byMime("application/vnd.rar"))
        assertEquals(ArchiveFormat.GZIP, ArchiveFormat.byMime("application/gzip; charset=binary"))
        assertNull(ArchiveFormat.byMime("image/png"))
        assertNull(ArchiveFormat.byMime(null))
    }

    @Test
    fun `可读性与多条目标志`() {
        // ZSTD 明确不支持（它依赖原生库，已被排除）
        assertEquals(false, ArchiveFormat.ZSTD.readable)
        // gz 是单流：UI 要据此把"解压到文件夹"换成"解压到此处"
        assertEquals(false, ArchiveFormat.GZIP.multiEntry)
        assertEquals(true, ArchiveFormat.ZIP.multiEntry)
        assertEquals(true, ArchiveFormat.ZIP.readable)
        assertEquals(true, ArchiveFormat.RAR.readable)
    }
}
