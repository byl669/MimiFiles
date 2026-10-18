package com.miyuetech.mimifiles.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码器名字分类的反向验证。
 *
 * ## 为什么这些断言值得存在
 * 角标判据（"软解时显示、硬解时隐藏"）在模拟器上**只能验证一半**——模拟器上
 * 所有解码器都是 `c2.android.*`（软解），永远走不到"隐藏"分支。而"硬解时隐藏"
 * 恰恰是真机上最不能出错的语义（把硬解标成软解，用户会误判性能出了问题）。
 * 所以"厂商硬解名字判为非软解"这一组用例就是**旧实现会红的那条反向证据**：
 * 任何把规则改坏（比如把白名单改成"名字里含 google 就算软解"——
 * `c2.qti.google` 之类乱入，或者把 startsWith 写成 contains）的改动，
 * 会先在这里红掉，而不是等真机。
 */
class DecoderNamesTest {

    @Test
    fun `厂商硬解名字判为非软解`() {
        // 这些是真实设备上的常见硬解前缀，覆盖高通 / MTK / Exynos / Amlogic 四大家。
        for (name in listOf(
            "OMX.qcom.video.decoder.avc",
            "OMX.MTK.VIDEO.DECODER.HEVC",
            "c2.qti.avc.decoder",
            "c2.exynos.hevc.decoder",
            "OMX.amlogic.avc.decoder.awesome",
            "OMX.hisi.video.decoder.avc",
        )) {
            assertFalse("期望硬解，实际判成了软解：$name", DecoderNames.isSoftware(name))
        }
    }

    @Test
    fun `系统软解名字判为软解`() {
        for (name in listOf(
            "c2.android.avc.decoder",
            "c2.android.hevc.decoder",
            "c2.android.av1.decoder",
            "OMX.google.h264.decoder",
            "OMX.google.hevc.decoder",
        )) {
            assertTrue("期望软解，实际判成了硬解：$name", DecoderNames.isSoftware(name))
        }
    }

    @Test
    fun `空串与未知名字保守地判为非软解`() {
        // 白名单策略的另一半：宁可漏标（少显示角标），不可错标（把硬解当软解）。
        for (name in listOf("", "unknown.decoder", "c2.androidx.fake")) {
            assertFalse(DecoderNames.isSoftware(name))
        }
    }

    @Test
    fun `startsToWith 不是 contains——乱入的子串不生效`() {
        // 把 startsWith 写成 contains 是这条规则最可能的坏法（"OMX.googleXX" 或
        // "fake.OMX.google.h264" 都会被误判）。钉住前缀语义。
        assertFalse(DecoderNames.isSoftware("vendor.OMX.google.h264.decoder"))
        assertFalse(DecoderNames.isSoftware("c2.vendor.android.avc.decoder"))
    }

    @Test
    fun `mime 标签映射正确且未知 mime 原样保留`() {
        assertEquals("H.264/AVC", DecoderNames.labelOf("video/avc"))
        assertEquals("HEVC (H.265)", DecoderNames.labelOf("video/hevc"))
        assertEquals("AAC", DecoderNames.labelOf("audio/mp4a-latm"))
        // 未知编码必须原样显示：把信息抹成"未知"会让排障失去唯一线索。
        assertEquals("video/x-odd-codec", DecoderNames.labelOf("video/x-odd-codec"))
        assertEquals("unknown", DecoderNames.labelOf(null))
    }

    @Test
    fun `内置 FFmpeg 软解名字判为软解`() {
        // M7.5 起 FfmpegAudioRenderer 上报的解码器名固定是 "ffmpeg"。
        // 不进白名单的话，音频走 FFmpeg 软解时软解角标不亮——错标（漏标）方向。
        assertTrue(DecoderNames.isSoftware("ffmpeg"))
    }

    @Test
    fun `杜比 DTS 系编码标签可读`() {
        // 这批编码是"无声片"问题的当事编码：标签错了信息面板就成了谜语。
        assertEquals("AC-3", DecoderNames.labelOf("audio/ac3"))
        assertEquals("E-AC-3", DecoderNames.labelOf("audio/eac3"))
        assertEquals("E-AC-3 JOC (Atmos)", DecoderNames.labelOf("audio/eac3-joc"))
        assertEquals("TrueHD (Atmos)", DecoderNames.labelOf("audio/true-hd"))
        assertEquals("DTS", DecoderNames.labelOf("audio/dts"))
        assertEquals("DTS-HD MA", DecoderNames.labelOf("audio/dts-hd"))
        assertEquals("DTS:X", DecoderNames.labelOf("audio/dts-x"))
    }
}
