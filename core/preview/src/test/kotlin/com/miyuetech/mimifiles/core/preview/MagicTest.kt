package com.miyuetech.mimifiles.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 魔数嗅探的单元测试。
 *
 * 这一层**必须**有单测：它是一大堆"偏移 + 字节比较"的手写逻辑，而错误的表现
 * 恰恰是"某类文件打不开"——在真机上逐个试成本极高。这里用构造出的最小字节头
 * 把所有分支跑一遍。
 */
class MagicTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `JPEG 头被识别为图片`() {
        val hit = Magic.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10))
        assertNotNull(hit)
        assertEquals(PreviewKind.IMAGE, hit!!.kind)
        assertEquals("image/jpeg", hit.mime)
    }

    @Test
    fun `PNG 头被识别为图片`() {
        val hit = Magic.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00))
        assertEquals(PreviewKind.IMAGE, hit?.kind)
        assertEquals("image/png", hit?.mime)
    }

    @Test
    fun `GIF 被单独归类为动图`() {
        val hit = Magic.sniff(ascii("GIF89a") + ByteArray(4))
        assertEquals(PreviewKind.ANIMATED_IMAGE, hit?.kind)
        assertEquals("image/gif", hit?.mime)
    }

    @Test
    fun `RIFF 家族按 fourCC 分流`() {
        val webp = Magic.sniff(ascii("RIFF") + bytes(0x00, 0x00, 0x00, 0x00) + ascii("WEBP"))
        assertEquals(PreviewKind.IMAGE, webp?.kind)

        val wav = Magic.sniff(ascii("RIFF") + bytes(0x00, 0x00, 0x00, 0x00) + ascii("WAVE"))
        assertEquals(PreviewKind.AUDIO, wav?.kind)

        val avi = Magic.sniff(ascii("RIFF") + bytes(0x00, 0x00, 0x00, 0x00) + ascii("AVI "))
        assertEquals(PreviewKind.VIDEO, avi?.kind)
    }

    @Test
    fun `ISO BMFF 按 brand 区分 HEIC 与视频`() {
        // 结构：[4 字节 box size]['ftyp'][brand]
        val heic = Magic.sniff(bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii("heic") + ByteArray(4))
        assertEquals(PreviewKind.IMAGE, heic?.kind)
        assertEquals("image/heic", heic?.mime)

        val avif = Magic.sniff(bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii("avif") + ByteArray(4))
        assertEquals(PreviewKind.IMAGE, avif?.kind)

        val mp4 = Magic.sniff(bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii("isom") + ByteArray(4))
        assertEquals(PreviewKind.VIDEO, mp4?.kind)
        assertEquals("video/mp4", mp4?.mime)

        val mov = Magic.sniff(bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii("qt  ") + ByteArray(4))
        assertEquals(PreviewKind.VIDEO, mov?.kind)
        assertEquals("video/quicktime", mov?.mime)
    }

    @Test
    fun `音频容器被正确识别`() {
        assertEquals(PreviewKind.AUDIO, Magic.sniff(ascii("ID3\u0004"))?.kind)
        assertEquals(PreviewKind.AUDIO, Magic.sniff(ascii("fLaC") + ByteArray(4))?.kind)
        assertEquals(PreviewKind.AUDIO, Magic.sniff(ascii("OggS") + ByteArray(4))?.kind)
        assertEquals(PreviewKind.AUDIO, Magic.sniff(ascii("MThd") + ByteArray(4))?.kind)
    }

    @Test
    fun `Matroska 与 FLV 为视频`() {
        assertEquals(
            PreviewKind.VIDEO,
            Magic.sniff(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01, 0x02, 0x03, 0x04))?.kind,
        )
        assertEquals(PreviewKind.VIDEO, Magic.sniff(ascii("FLV\u0001") + ByteArray(4))?.kind)
    }

    @Test
    fun `PDF 与 RTF 分别归类`() {
        assertEquals(PreviewKind.PDF, Magic.sniff(ascii("%PDF-1.7\n") + ByteArray(4))?.kind)
        assertEquals(PreviewKind.TEXT, Magic.sniff(ascii("{\\rtf1\\ansi") + ByteArray(4))?.kind)
    }

    @Test
    fun `ZIP 家族按内部条目名称细分`() {
        // 普通压缩包
        val zip = Magic.sniff(bytes(0x50, 0x4B, 0x03, 0x04) + ascii("\u0000\u0000\u0000\u0000mimifiles/readme.txt"))
        assertEquals(PreviewKind.ARCHIVE, zip?.kind)

        // APK：ZIP 里含 AndroidManifest.xml
        val apk = Magic.sniff(
            bytes(0x50, 0x4B, 0x03, 0x04) + ascii("\u0000\u0000\u0000\u0000AndroidManifest.xmlXXXXXXXXXX"),
        )
        assertEquals(PreviewKind.PACKAGE, apk?.kind)
        assertEquals("application/vnd.android.package-archive", apk?.mime)

        // docx：ZIP 里含 word/
        val docx = Magic.sniff(bytes(0x50, 0x4B, 0x03, 0x04) + ascii("[Content_Types].xmlword/document.xml"))
        assertEquals(PreviewKind.OFFICE, docx?.kind)
    }

    @Test
    fun `其它压缩格式`() {
        assertEquals(PreviewKind.ARCHIVE, Magic.sniff(ascii("Rar!\u001A\u0007"))?.kind)
        assertEquals(PreviewKind.ARCHIVE, Magic.sniff(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))?.kind)
        assertEquals(PreviewKind.ARCHIVE, Magic.sniff(bytes(0x1F, 0x8B, 0x08, 0x00))?.kind)
        assertEquals(PreviewKind.ARCHIVE, Magic.sniff(ascii("BZh9") + ByteArray(4))?.kind)
    }

    @Test
    fun `UTF-8 BOM 的文本被识别`() {
        val hit = Magic.sniff(bytes(0xEF, 0xBB, 0xBF) + ascii("你好，世界"))
        assertEquals(PreviewKind.TEXT, hit?.kind)
    }

    @Test
    fun `纯中文 UTF-8 文本能被识别为文本`() {
        // 这条最容易被"只允许 ASCII"的实现判错：中文全是高位字节。
        val hit = Magic.sniff("这是一份纯文本说明，没有任何二进制内容。".toByteArray(Charsets.UTF_8))
        assertEquals(PreviewKind.TEXT, hit?.kind)
    }

    @Test
    fun `含 NUL 的字节流不会被误判为文本`() {
        assertNull(Magic.sniff(bytes(0x00, 0x01, 0x02, 0x41, 0x42, 0x43)))
    }

    @Test
    fun `太短的输入返回 null 而不是崩溃`() {
        assertNull(Magic.sniff(ByteArray(0)))
        assertNull(Magic.sniff(bytes(0xFF)))
        assertNull(Magic.sniff(bytes(0xFF, 0xD8)))
    }

    @Test
    fun `未知二进制返回 null`() {
        assertNull(Magic.sniff(bytes(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x11, 0x22, 0x33)))
    }

    @Test
    fun `每次命中都带一个给人看的 label`() {
        val hit = Magic.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE1))
        assertTrue(hit!!.label.isNotEmpty())
    }
}
