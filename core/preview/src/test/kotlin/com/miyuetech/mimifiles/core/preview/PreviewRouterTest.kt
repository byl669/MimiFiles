package com.miyuetech.mimifiles.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由决策的单测。
 *
 * 路由是"点开文件之后到底发生什么"的唯一决策点，分支有限、纯函数，
 * 完全值得钉死——它的每一个错误都表现为用户可感知的"点了没反应"或"跳错应用"。
 */
class PreviewRouterTest {

    private fun result(kind: PreviewKind, mime: String, declared: PreviewKind = kind) =
        ProbeResult(kind = kind, mime = mime, magic = null, declared = declared, corrected = false)

    @Test
    fun `内置渲染的六类都走 BuiltIn`() {
        val cases = listOf(
            PreviewKind.IMAGE to "image/jpeg",
            PreviewKind.ANIMATED_IMAGE to "image/gif",
            PreviewKind.VIDEO to "video/mp4",
            PreviewKind.AUDIO to "audio/mpeg",
            PreviewKind.TEXT to "text/plain",
            PreviewKind.PDF to "application/pdf",
        )
        for ((kind, mime) in cases) {
            val route = PreviewRouter.route(result(kind, mime))
            assertTrue("$kind 应为 BuiltIn，实际 $route", route is PreviewRoute.BuiltIn)
            assertEquals(kind, (route as PreviewRoute.BuiltIn).kind)
        }
    }

    @Test
    fun `压缩包改为内置渲染`() {
        // 2026-10-14 变更：压缩包从"一律外发"改为"内置列举 + 解压"。
        // 这条断言的作用是**把这次决策固化下来** —— 它曾经是 External，
        // 若哪天有人改回去，必须先把这里的理由推翻。
        val zip = PreviewRouter.route(result(PreviewKind.ARCHIVE, "application/zip"))
        assertTrue(zip is PreviewRoute.BuiltIn)
        assertEquals(PreviewKind.ARCHIVE, (zip as PreviewRoute.BuiltIn).kind)
    }

    @Test
    fun `安装包交外部应用但有明确 MIME`() {

        val apk = PreviewRouter.route(
            result(PreviewKind.PACKAGE, "application/vnd.android.package-archive"),
        )
        assertTrue(apk is PreviewRoute.External)
    }

    @Test
    fun `Office 文档交外部应用`() {
        val docx = PreviewRouter.route(
            result(PreviewKind.OFFICE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
        assertTrue(docx is PreviewRoute.External)
    }

    @Test
    fun `未知类型但后端报出了具体 MIME 时仍然外发`() {
        val route = PreviewRouter.route(result(PreviewKind.UNKNOWN, "application/x-foo"))
        assertTrue(route is PreviewRoute.External)
    }

    @Test
    fun `未知类型且 MIME 是 octet-stream 时判为不支持`() {
        // 这条是"点了没反应"的防呆：把 octet-stream 外发等于把一堆用不了的应用
        // 甩到"打开方式"列表里，不如明确说一句不支持。
        val route = PreviewRouter.route(result(PreviewKind.UNKNOWN, "application/octet-stream"))
        assertTrue(route is PreviewRoute.Unsupported)
    }

    @Test
    fun `MIME 为空同样判为不支持`() {
        val route = PreviewRouter.route(PreviewKind.UNKNOWN, "")
        assertTrue(route is PreviewRoute.Unsupported)
    }

    @Test
    fun `外发 MIME 拿不准时回退到扩展名推断`() {
        val probe = ProbeResult(
            kind = PreviewKind.UNKNOWN,
            mime = "application/octet-stream",
            magic = null,
            declared = PreviewKind.UNKNOWN,
            corrected = false,
        )
        assertEquals("application/pdf", PreviewRouter.mimeFor(probe, "report.pdf"))
    }

    // ── ContentProbe 的纯映射部分 ────────────────────────────────

    @Test
    fun `MIME 到类别的映射覆盖所有分支`() {
        assertEquals(PreviewKind.IMAGE, ContentProbe.declaredKindOf("image/png"))
        assertEquals(PreviewKind.ANIMATED_IMAGE, ContentProbe.declaredKindOf("image/gif"))
        assertEquals(PreviewKind.VIDEO, ContentProbe.declaredKindOf("video/x-matroska"))
        assertEquals(PreviewKind.AUDIO, ContentProbe.declaredKindOf("audio/flac"))
        assertEquals(PreviewKind.PDF, ContentProbe.declaredKindOf("application/pdf"))
        assertEquals(PreviewKind.TEXT, ContentProbe.declaredKindOf("text/markdown"))
        // JSON / XML / RTF 没有 text/ 前缀，但同样是可读文本
        assertEquals(PreviewKind.TEXT, ContentProbe.declaredKindOf("application/json"))
        assertEquals(PreviewKind.TEXT, ContentProbe.declaredKindOf("application/xml"))
        assertEquals(PreviewKind.TEXT, ContentProbe.declaredKindOf("application/rtf"))
        assertEquals(
            PreviewKind.PACKAGE,
            ContentProbe.declaredKindOf("application/vnd.android.package-archive"),
        )
        assertEquals(
            PreviewKind.OFFICE,
            ContentProbe.declaredKindOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
        assertEquals(PreviewKind.OFFICE, ContentProbe.declaredKindOf("application/msword"))
        assertEquals(PreviewKind.ARCHIVE, ContentProbe.declaredKindOf("application/zip"))
        assertEquals(PreviewKind.ARCHIVE, ContentProbe.declaredKindOf("application/x-7z-compressed"))
        assertEquals(PreviewKind.UNKNOWN, ContentProbe.declaredKindOf("application/octet-stream"))
        assertEquals(PreviewKind.UNKNOWN, ContentProbe.declaredKindOf(null))
        assertEquals(PreviewKind.UNKNOWN, ContentProbe.declaredKindOf(""))
    }

    @Test
    fun `每种内置类别都声明自己是内置`() {
        for (kind in PreviewKind.entries) {
            if (kind.builtIn) {
                assertEquals(
                    "内置类别 $kind 必须被路由成 BuiltIn",
                    kind,
                    (PreviewRouter.route(kind, "x/y") as PreviewRoute.BuiltIn).kind,
                )
            } else {
                assertTrue(
                    "非内置类别 $kind 不应被路由成 BuiltIn",
                    PreviewRouter.route(kind, "x/y") !is PreviewRoute.BuiltIn,
                )
            }
        }
    }
}
