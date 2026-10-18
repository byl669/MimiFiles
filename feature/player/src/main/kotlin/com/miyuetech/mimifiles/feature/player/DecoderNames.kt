package com.miyuetech.mimifiles.feature.player

import android.media.MediaCodecList

/**
 * 解码器名字与编码标签的静态知识（M7 视频信息面板的判据库）。
 *
 * ## 为什么这个对象里几乎全是"名字"而不是"逻辑"
 * 软解兜底（M6）只在硬解失败后启用，用户却不知道当前到底在用谁解——这正好是
 * 「要不要给包里加 FFmpeg」这类决策的唯一依据，而它**只能从解码器名字里读出来**：
 * Android 从不提供"这个解码器是不是软件实现"的 API，行业约定俗成的判据就是名字前缀。
 *
 * ## 分类规则（写在代码里而不是注释里，因为它要被单测钉死）
 * - `c2.android.*` —— AOSP 的 Codec2 软件解码器，所有设备都有，与厂商固件无关；
 * - `OMX.google.*` —— 老一点的 AOSP 软件解码器（OpenMAX 实现族）；
 * - 其余（`OMX.qcom.*` / `OMX.MTK.*` / `c2.qti.*` / `c2.exynos.*` …）—— 厂商硬解。
 *
 * 刻意**白名单软解、其余一律算硬解**：宁可把罕见的新软解前缀误判成硬解
 * （后果只是少显示一个角标），也不要把硬解误判成软解（用户会误以为性能出了问题）。
 * 模拟器上所有解码器都是 `c2.android.*`，因此模拟器上角标**必然常亮**——
 * 那是正确行为，不是 bug；真机上角标亮才说明真的降级到了软解。
 */
object DecoderNames {

    /**
     * 名字是否是系统软件解码器。判据是**前缀白名单**，理由见类注释。
     *
     * `ffmpeg` 是 M7.5 起内置的 FFmpeg 音频软解（NextLib 扩展）上报的解码器名
     * ——`FfmpegAudioRenderer` 的名字固定为 `FfmpegLibrary.name()` = "ffmpeg"，
     * 天然是软件实现，进白名单与 `c2.android.*` 同理。
     */
    fun isSoftware(name: String): Boolean =
        name.startsWith("c2.android.") ||
            name.startsWith("OMX.google.") ||
            name.startsWith("ffmpeg")

    /**
     * 人类可读的编码标签。
     *
     * [Format.sampleMimeType]（如 `video/hevc`）对用户没有信息量；[Format.codecs]
     * （如 `hvc1.1.6.L120`）才精确到 profile/level。展示时两个都要：标签给人看，
     * codecs 原文给排查用（把这一串发回来，我就能判断它是不是特殊 profile）。
     * 未知 mime 原样返回——宁可显示一行生僻 mime，也不要显示"未知"把信息抹掉。
     */
    fun labelOf(mime: String?): String = when (mime) {
        "video/avc" -> "H.264/AVC"
        "video/hevc" -> "HEVC (H.265)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/mp4v" -> "MPEG-4"
        "video/h263" -> "H.263"
        "video/mp2v" -> "MPEG-2"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/eac3-joc" -> "E-AC-3 JOC (Atmos)"
        "audio/true-hd" -> "TrueHD (Atmos)"
        "audio/dts" -> "DTS"
        "audio/dts-hd" -> "DTS-HD MA"
        "audio/dts-x" -> "DTS:X"
        "audio/dts-express" -> "DTS Express"
        "audio/ac4" -> "AC-4"
        "audio/x-ms-wma" -> "WMA"
        "audio/raw" -> "PCM"
        else -> mime ?: "unknown"
    }

    /** 一条可用解码器记录。 */
    data class Option(val name: String, val software: Boolean)

    /**
     * 本机上能解 [mime] 的全部解码器（硬解 + 软解，[MediaCodecList.ALL_CODECS]）。
     *
     * ## 这个列表是给谁看的
     * 「本机没有该编码的解码器」是"要不要自带 FFmpeg"的**决定性证据**——它为空时，
     * M6 的系统软解兜底也不可能成功（列表里根本没东西可排）。所以信息面板把它
     * 按硬解/软解分组全部列出，排障时一眼见分晓。
     *
     * `supportedTypes` 只是"声明支持该 mime"，不校验 profile/level——对排障足够了：
     * 列表**为空** ⇒ 必然播不了；列表**非空但播不了** ⇒ 精确到 profile 的能力问题，
     * 那要靠真实失败样本（错误码 + codecs 字符串）进一步判断，不是这个面板的职责。
     *
     * MediaCodecList 访问在个别 ROM 上会抛异常；此处失败返回空列表，
     * 信息面板照常展示其余内容（解码器一节显示"读取失败"由调用方区分）。
     */
    fun available(mime: String): List<Option> {
        if (mime.isEmpty()) return emptyList()
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                // MediaCodecInfo 没有 isDecoder——解码器就是"不是编码器"的那个。
                .filter { !it.isEncoder && it.supportedTypes.contains(mime) }
                .map { Option(it.name, isSoftware(it.name)) }
        }.getOrDefault(emptyList())
    }
}
