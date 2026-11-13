package com.miyuetech.mimifiles.core.preview

import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 系统分享的**纯逻辑**部分（M7.25）。
 *
 * 刻意不碰 Intent / Uri / Context：`android.jar` 在 JVM 单测里是 stub，
 * 构造 Intent 会抛 "Method ... not mocked"。于是把"能不能分享""发什么 MIME"
 * "用哪个 action""落地后路径长什么样"全做成不依赖 Android 运行时的函数，
 * 真正需要框架的部分交给模拟器验收。
 */
class SystemShareTest {

    private fun file(
        name: String,
        scheme: String = "local",
        mime: String? = null,
    ): FsEntry = FsEntry(
        path = FsPath.of(scheme, if (scheme == "local") "" else "nas-01", "/share/$name"),
        kind = FsKind.FILE,
        mimeType = mime,
    )

    private fun dir(name: String): FsEntry = FsEntry(
        path = FsPath.of("local", "", "/share/$name"),
        kind = FsKind.DIRECTORY,
    )

    // ── 能不能分享 ──────────────────────────────────────────────────

    @Test
    fun 全选文件可以分享() {
        assertTrue(SystemShare.canShare(listOf(file("a.jpg"), file("b.mp4"))))
    }

    @Test
    fun 空选择不能分享() {
        assertFalse(SystemShare.canShare(emptyList()))
    }

    @Test
    fun 含目录就不显示系统分享() {
        assertFalse(SystemShare.canShare(listOf(file("a.jpg"), dir("相册"))))
        assertFalse(SystemShare.canShare(listOf(dir("相册"))))
    }

    @Test
    fun 非文件条目也不能分享() {
        val other = FsEntry(
            path = FsPath.of("local", "", "/share/socket"),
            kind = FsKind.OTHER,
        )
        assertFalse(SystemShare.canShare(listOf(file("a.jpg"), other)))
    }

    // ── MIME 推导 ────────────────────────────────────────────────────

    @Test
    fun 单选按扩展名推断() {
        assertEquals("image/jpeg", SystemShare.mimeOf(listOf(file("a.jpg"))))
        assertEquals("video/mp4", SystemShare.mimeOf(listOf(file("a.mp4"))))
    }

    @Test
    fun 后端给的MIME优先于扩展名() {
        assertEquals(
            "image/webp",
            SystemShare.mimeOf(listOf(file("a.jpg", mime = "image/webp"))),
        )
    }

    @Test
    fun 后端说是octet_stream时退回按扩展名查表() {
        assertEquals(
            "application/pdf",
            SystemShare.mimeOf(listOf(file("a.pdf", mime = MimeTypes.OCTET_STREAM))),
        )
    }

    @Test
    fun 认不出的类型用通配而不是把目标过滤没() {
        assertEquals(SystemShare.ANY_TYPE, SystemShare.mimeOf(listOf(file("a.zzz"))))
    }

    @Test
    fun 同一大类用大类通配() {
        assertEquals(
            "image/*",
            SystemShare.mimeOf(listOf(file("a.jpg"), file("b.png"))),
        )
    }

    @Test
    fun 相同精确类型保留精确类型() {
        assertEquals(
            "image/jpeg",
            SystemShare.mimeOf(listOf(file("a.jpg"), file("b.jpg"))),
        )
    }

    @Test
    fun 混合类型用通配() {
        assertEquals(
            SystemShare.ANY_TYPE,
            SystemShare.mimeOf(listOf(file("a.jpg"), file("b.pdf"))),
        )
    }

    @Test
    fun 空列表用通配() {
        assertEquals(SystemShare.ANY_TYPE, SystemShare.mimeOf(emptyList()))
    }

    // ── 单选 / 多选 ──────────────────────────────────────────────────

    @Test
    fun 单选走ACTION_SEND_多选走ACTION_SEND_MULTIPLE() {
        assertEquals("android.intent.action.SEND", SystemShare.actionFor(1))
        assertEquals("android.intent.action.SEND_MULTIPLE", SystemShare.actionFor(2))
        assertEquals("android.intent.action.SEND_MULTIPLE", SystemShare.actionFor(7))
    }

    // ── 落地路径 ─────────────────────────────────────────────────────

    @Test
    fun 本机文件直接发_远端文件才需要落地() {
        assertFalse(SystemShare.needsStaging(file("a.jpg").path))
        assertTrue(SystemShare.needsStaging(file("a.jpg", scheme = "smb").path))
        assertTrue(SystemShare.needsStaging(file("a.jpg", scheme = "webdav").path))
        assertTrue(SystemShare.needsStaging(file("a.jpg", scheme = "saf").path))
    }

    @Test
    fun 落地文件转回本机路径且保留文件名() {
        val staged = SystemShare.stagedPath(File("/data/cache/外发/合同.pdf"))
        assertEquals("local", staged.scheme)
        assertEquals("/data/cache/外发/合同.pdf", staged.path)
        assertEquals("合同.pdf", staged.displayName)
    }

    // ── 超大远端文件：流式直发（M7.27）─────────────────────────────────

    private val twoGb = 2048L * 1024 * 1024

    @Test
    fun 七GB的远端文件超过暂存上限时改为流式直发() {
        val sevenGb = 7L * 1024 * 1024 * 1024
        assertTrue(SystemShare.shouldStreamDirect(sevenGb, twoGb))
    }

    @Test
    fun 超过4GB的大小不会被Int截断成负数() {
        val fiveGb = 5L * 1024 * 1024 * 1024
        assertTrue(fiveGb > Int.MAX_VALUE)
        assertTrue(SystemShare.shouldStreamDirect(fiveGb, twoGb))
    }

    @Test
    fun 恰好等于上限仍可落地() {
        assertFalse(SystemShare.shouldStreamDirect(twoGb, twoGb))
        assertFalse(SystemShare.shouldStreamDirect(twoGb - 1, twoGb))
    }

    @Test
    fun 大小未知时不凭猜测跳过落地() {
        assertFalse(SystemShare.shouldStreamDirect(-1L, twoGb))
        assertFalse(SystemShare.shouldStreamDirect(0L, twoGb))
    }

    @Test
    fun 上限不可信时保持原落盘路径() {
        val sevenGb = 7L * 1024 * 1024 * 1024
        assertFalse(SystemShare.shouldStreamDirect(sevenGb, 0L))
        assertFalse(SystemShare.shouldStreamDirect(sevenGb, -1L))
    }

    @Test
    fun 本机七GB文件既不落盘也不算流式() {
        val entry = FsEntry(
            path = FsPath.of("local", "", "/sdcard/纪录片 4K.iso"),
            kind = FsKind.FILE,
            size = 7L * 1024 * 1024 * 1024,
        )
        assertFalse(SystemShare.needsStaging(entry.path))
    }
}
