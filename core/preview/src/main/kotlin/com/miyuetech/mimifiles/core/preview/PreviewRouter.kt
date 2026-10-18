package com.miyuetech.mimifiles.core.preview

import com.miyuetech.mimifiles.core.common.MimeTypes

/**
 * 预览路由（纯函数，可在 JVM 单测里穷举）。
 *
 * 从 `ContentProbe` 的角色里拆出来是有意的：探测要 IO（慢、依赖后端），
 * 路由是**纯决策**（快、可测）。混在一起会让"路由对不对"这件事只能靠跑模拟器验证，
 * 而它的分支数是有限的、完全值得用单测钉死。
 */
object PreviewRouter {

    /** 由探测结论决定怎么打开。 */
    fun route(result: ProbeResult): PreviewRoute = route(result.kind, result.mime)

    fun route(kind: PreviewKind, mime: String?): PreviewRoute {
        if (kind.builtIn) return PreviewRoute.BuiltIn(kind)
        val safeMime = mime?.takeIf { it.isNotEmpty() && it != MimeTypes.OCTET_STREAM }
        return when (kind) {
            // 安装包 / Office：本应用不做内置渲染（见方案 §6.4 与 §3「明确不做」），
            // 但**知道该交给谁**，所以不是 Unsupported。
            // （压缩包已改为内置渲染，见 PreviewKind.ARCHIVE 的说明。）
            PreviewKind.PACKAGE, PreviewKind.OFFICE ->
                if (safeMime != null) {
                    PreviewRoute.External(safeMime, kind)
                } else {
                    PreviewRoute.Unsupported(kind)
                }

            // 未知类型：只有后端能报出一个像样的 MIME 时才值得外发。
            // 报 `application/octet-stream` 就外发，等于把"打开方式"列表甩给用户，
            // 而列表里通常一个能用的都没有。
            PreviewKind.UNKNOWN ->
                if (safeMime != null) {
                    PreviewRoute.External(safeMime, kind)
                } else {
                    PreviewRoute.Unsupported(kind)
                }

            else -> PreviewRoute.BuiltIn(kind)
        }
    }

    /** 打开时应该向系统声明的 MIME（外发用），拿不准就退回扩展名推断。 */
    fun mimeFor(result: ProbeResult, fileName: String): String =
        result.mime.takeIf { it.isNotEmpty() && it != MimeTypes.OCTET_STREAM }
            ?: MimeTypes.of(fileName)
}
