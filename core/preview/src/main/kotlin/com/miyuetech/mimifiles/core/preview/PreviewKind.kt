package com.miyuetech.mimifiles.core.preview

/**
 * 预览类别 —— 决定"点开一个文件之后走哪条路"。
 *
 * 与 `:core:ui` 的 `FileCategory` 是**两个不同的东西**，不要合并：
 *  - `FileCategory` 回答"列表里该画哪张图标"，所以它把 `CODE` 与 `TEXT` 分开、
 *    把 `APK` 单列——这些区分对图标有意义，对"怎么打开"没有意义；
 *  - `PreviewKind` 回答"用什么渲染器"，所以它把 `gif` 单列为 [ANIMATED_IMAGE]
 *    （要动图播放，不是静态解码），把 `docx/xlsx/pptx` 单列为 [OFFICE]（要外发）。
 */
enum class PreviewKind {

    /** 静态位图：jpg / png / webp / bmp / heic / avif / tiff… */
    IMAGE,

    /** 动图：gif（以及将来可能支持的动图 webp）——需要逐帧播放而不是单帧解码。 */
    ANIMATED_IMAGE,

    VIDEO,

    AUDIO,

    /** 纯文本：txt / log / md / json / 源码…… 可用等宽字体直接显示。 */
    TEXT,

    PDF,

    /**
     * 压缩包：**内置**列举与解压（zip / 7z / rar / tar / gz / bz2 / xz）。
     *
     * 支持范围见 `:core:archive` 的 `ArchiveFormat`；遇到不支持的格式（如 .zst）
     * 由 `ArchiveRenderer` 给出明确说明并引导外发 —— 而不是在这里一刀切地外发，
     * 那会让最常见的 zip 白白多绕一步（用户点开 zip 却跳去另一个应用）。
     */
    ARCHIVE,

    /** 安装包：只能外发安装，不可能"预览"。 */
    PACKAGE,

    /** Office 文档：不内置引擎（见方案 §6.4），一律外发。 */
    OFFICE,

    /** 类型未知：可能是任意二进制，也可能是扩展名缺失的文本。 */
    UNKNOWN,
    ;

    /** 是否能由本应用内置渲染。 */
    val builtIn: Boolean
        get() = this == IMAGE || this == ANIMATED_IMAGE || this == VIDEO ||
            this == AUDIO || this == TEXT || this == PDF || this == ARCHIVE

    /** 预览时是否需要"重量级"资源（播放器/解码器），用于决定要不要抢先申请后台播放。 */
    val isMedia: Boolean
        get() = this == VIDEO || this == AUDIO
}

/**
 * 路由结论。
 *
 * 三态刻意做成 `sealed` 而不是"返回可空 + 布尔标志"：调用方必须显式处理
 * "这个类型我打不开"这一支，否则就会出现 M1 那种"点了没反应"的体验。
 */
sealed interface PreviewRoute {

    /** 本应用内置渲染。 */
    data class BuiltIn(val kind: PreviewKind) : PreviewRoute

    /** 交给系统/第三方应用（Office、压缩包、安装包）。[mime] 是外发时声明的类型。 */
    data class External(val mime: String, val kind: PreviewKind) : PreviewRoute

    /** 既不能内置、也不知道该交给谁。 */
    data class Unsupported(val kind: PreviewKind) : PreviewRoute
}
