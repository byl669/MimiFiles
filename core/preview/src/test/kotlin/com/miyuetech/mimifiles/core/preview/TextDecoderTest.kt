package com.miyuetech.mimifiles.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 文本编码探测与解码的单测。
 *
 * 这里的每一条都对应一个真实场景：中文 Windows 记事本存的 GBK txt、
 * macOS 文本编辑存的无 BOM UTF-8、带 BOM 的 Excel 导出 CSV、
 * 以及"什么都不是"的二进制垃圾。
 */
class TextDecoderTest {

    private fun gbk(text: String): ByteArray = text.toByteArray(Charset.forName("GBK"))

    @Test
    fun `无 BOM 的 UTF-8 中文被正确识别`() {
        val bytes = "这是一段中文说明。\n第二行。".toByteArray(Charsets.UTF_8)
        assertEquals(TextDecoder.Encoding.UTF_8, TextDecoder.detect(bytes))
        assertEquals("这是一段中文说明。\n第二行。", TextDecoder.decode(bytes, TextDecoder.Encoding.UTF_8))
    }

    @Test
    fun `UTF-8 BOM 被识别并剥掉`() {
        val payload = "带 BOM 的文本".toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + payload
        assertEquals(TextDecoder.Encoding.UTF_8_BOM, TextDecoder.detect(withBom))
        // BOM 三个字节不能被当成正文显示出来（否则开头会出现一个不可见怪字符）
        assertEquals("带 BOM 的文本", TextDecoder.decode(withBom, TextDecoder.Encoding.UTF_8_BOM))
    }

    @Test
    fun `UTF-16 两种 BOM 都被识别`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "AB".toByteArray(Charsets.UTF_16LE)
        assertEquals(TextDecoder.Encoding.UTF_16_LE, TextDecoder.detect(le))

        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "AB".toByteArray(Charsets.UTF_16BE)
        assertEquals(TextDecoder.Encoding.UTF_16_BE, TextDecoder.detect(be))
    }

    @Test
    fun `GBK 中文不会有 BOM 且不通过 UTF-8 校验_应被判为 GBK`() {
        val bytes = gbk("中文测试：这是一段 GBK 文本。\n")
        // 前提校验：这段字节确实不是合法 UTF-8（否则这条用例没有意义）
        assertEquals(null, runCatching {
            Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
        }.getOrNull())

        assertEquals(TextDecoder.Encoding.GBK, TextDecoder.detect(bytes))
        assertEquals("中文测试：这是一段 GBK 文本。\n", TextDecoder.decode(bytes, TextDecoder.Encoding.GBK))
    }

    @Test
    fun `纯 ASCII 内容判为 UTF-8`() {
        val bytes = "hello world\nplain ascii\n".toByteArray(Charsets.US_ASCII)
        assertEquals(TextDecoder.Encoding.UTF_8, TextDecoder.detect(bytes))
    }

    @Test
    fun `既非 UTF-8 也不像 GBK 时退回 Latin-1 而不是抛异常`() {
        // 0x80~0x87 在 GBK 里是非法组合（0x80 不是合法引导字节）
        val bytes = byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x82.toByte(), 0x83.toByte())
        assertEquals(TextDecoder.Encoding.LATIN_1, TextDecoder.detect(bytes))
        // 关键：解码必须成功（Latin-1 能映射任何字节），不能把异常抛给 UI
        assertTrue(TextDecoder.decode(bytes, TextDecoder.Encoding.LATIN_1).isNotEmpty())
    }

    @Test
    fun `空样本不崩溃`() {
        assertEquals(TextDecoder.Encoding.UTF_8, TextDecoder.detect(ByteArray(0)))
    }

    @Test
    fun `被切断的多字节字符不会让解码失败`() {
        // 模拟"分页正好切在一个字符中间"：取 UTF-8 中文的前 5 个字节（切断了第 2 个字）
        val full = "中文测试".toByteArray(Charsets.UTF_8)
        val truncated = full.copyOfRange(0, 5)
        val text = TextDecoder.decode(truncated, TextDecoder.Encoding.UTF_8)
        // 会多出一个替换字符，但绝不能抛异常，也不能丢掉前面的"中"
        assertTrue(text.startsWith("中"))
    }

    @Test
    fun `编码名带可读标签`() {
        assertTrue(TextDecoder.Encoding.GBK.label.isNotEmpty())
        assertTrue(TextDecoder.Encoding.UTF_8_BOM.label.contains("BOM"))
    }
}
