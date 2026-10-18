package com.miyuetech.mimifiles.core.shareserver

import com.miyuetech.mimifiles.core.model.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RelPath] 的穷举测试。
 *
 * 这是整个无线分享里**最值得用单测钉死**的一块：它的每一个返回值都直接决定
 * "PC 能不能读到共享根以外的文件"。放在 JVM 上跑而不是在设备上试，是因为
 * 边界的数量（`..`、NUL、反斜杠、超长、多重斜杠、`%2e`）远超过手工点击能覆盖的量。
 */
class RelPathTest {

    @Test
    fun `根路径的多种写法都归一到空串`() {
        // `null` 输入是"没有路径参数"的语义，归到根。
        assertEquals("", RelPath.normalize(null))
        assertEquals("", RelPath.normalize(""))
        assertEquals("", RelPath.normalize("/"))
        assertEquals("", RelPath.normalize("///"))
        assertEquals("", RelPath.normalize("."))
        assertEquals("", RelPath.normalize("./"))
        assertEquals("", RelPath.normalize("/./."))
    }

    @Test
    fun `普通路径被保留并去掉首尾斜杠`() {
        assertEquals("a", RelPath.normalize("/a"))
        assertEquals("a/b", RelPath.normalize("a/b"))
        assertEquals("a/b", RelPath.normalize("/a/b/"))
        assertEquals("a/b", RelPath.normalize("a//b"))
        assertEquals("a/b", RelPath.normalize("a/./b"))
        assertEquals("a/b", RelPath.normalize("./a/b"))
    }

    @Test
    fun `中间的点点点被正确消解`() {
        assertEquals("b", RelPath.normalize("a/../b"))
        assertEquals("", RelPath.normalize("a/.."))
        assertEquals("c", RelPath.normalize("a/b/../../c"))
        assertEquals("a/c", RelPath.normalize("a/b/../c"))
    }

    @Test
    fun `任何形式的越狱都返回 null`() {
        // 这是本节最重要的一组：**不是"钳在根"，而是判非法**。
        // 钳在根（`..` 到根就停）看起来更宽容，但那样 `/f/../../x` 会静默变成
        // `/f/x`，用户与日志都看不出发生过越狱尝试。
        assertNull(RelPath.normalize(".."))
        assertNull(RelPath.normalize("../"))
        assertNull(RelPath.normalize("../etc/passwd"))
        assertNull(RelPath.normalize("a/../../b"))
        assertNull(RelPath.normalize("/../.."))
        assertNull(RelPath.normalize("a/b/../../../c"))
    }

    @Test
    fun `反斜杠被当分隔符从而挡下 Windows 风格越狱`() {
        // `..\..\windows\system32` —— 如果 `\` 被当成普通字符，这里会变成
        // 一个"文件名"，能否越狱就取决于最终落到哪个后端（SMB 落到 Windows
        // 服务端时行为不可控）。按分隔符处理则必然判非法。
        assertEquals("a/b", RelPath.normalize("a\\b"))
        assertEquals("b", RelPath.normalize("a\\..\\b"))
        assertNull(RelPath.normalize("..\\..\\windows"))
        assertNull(RelPath.normalize("a\\..\\..\\b"))
    }

    @Test
    fun `NUL 字节被拒绝`() {
        assertNull(RelPath.normalize("a\u0000b"))
        assertNull(RelPath.normalize("a/b\u0000"))
        assertNull(RelPath.normalize("\u0000"))
    }

    @Test
    fun `超长被拒绝`() {
        val deep = (1..(RelPath.MAX_DEPTH + 5)).joinToString("/") { "d$it" }
        assertNull(RelPath.normalize(deep))

        val longSegment = "x".repeat(RelPath.MAX_SEGMENT_LENGTH + 1)
        assertNull(RelPath.normalize(longSegment))

        // 边界值本身合法
        val atLimit = "x".repeat(RelPath.MAX_SEGMENT_LENGTH)
        assertEquals(atLimit, RelPath.normalize(atLimit))
    }

    @Test
    fun `百分号编码在规范化之前已被解码的情形按普通段处理`() {
        // 调用方（HTTP 层）必须先解码。解码后 `%2e%2e%2f` 变成 `../`，
        // 于是被这里正常挡住——这条测试记的就是那个顺序契约的结果。
        assertNull(RelPath.normalize("../secret"))
        // 未解码的原始形态只是一个普通文件名，不该被判越狱（它也确实不是）。
        assertEquals("%2e%2e%2fsecret", RelPath.normalize("%2e%2e%2fsecret"))
    }

    @Test
    fun `中文与空格原样保留`() {
        assertEquals("照片/2026 假期.jpg", RelPath.normalize("/照片/2026 假期.jpg"))
        assertEquals("a+b/c", RelPath.normalize("a+b/c"))
    }

    @Test
    fun `parent 与 name`() {
        assertNull(RelPath.parent(""))
        assertEquals("", RelPath.parent("a"))
        assertEquals("a", RelPath.parent("a/b"))
        assertEquals("a/b", RelPath.parent("a/b/c"))

        assertEquals("", RelPath.name(""))
        assertEquals("a", RelPath.name("a"))
        assertEquals("b", RelPath.name("a/b"))
    }

    @Test
    fun `child 拒绝非法段名`() {
        assertNull(RelPath.child("a", ""))
        assertNull(RelPath.child("a", "."))
        assertNull(RelPath.child("a", ".."))
        assertNull(RelPath.child("a", "x/y"))
        assertNull(RelPath.child("a", "x\\y"))
        assertNull(RelPath.child("a", "x\u0000y"))
        assertEquals("a/b", RelPath.child("a", "b"))
        assertEquals("b", RelPath.child("", "b"))
    }

    @Test
    fun `resolve 落到 FsPath 上并做第二层校验`() {
        val root = FsPath.of("local", "", "/storage/emulated/0/DCIM")

        assertEquals(root, RelPath.resolve(root, ""))
        assertEquals(root, RelPath.resolve(root, "/"))
        assertEquals(root.child("a.jpg"), RelPath.resolve(root, "a.jpg"))
        assertEquals(root.child("sub", "b.jpg"), RelPath.resolve(root, "/sub/b.jpg"))

        assertNull(RelPath.resolve(root, ".."))
        assertNull(RelPath.resolve(root, "../secret"))
        assertNull(RelPath.resolve(root, "a/../../secret"))
    }

    @Test
    fun `resolve 对根为 scheme 根的情形同样成立`() {
        val root = FsPath.of("smb", "conn-1", "")
        assertEquals(root.child("share", "x"), RelPath.resolve(root, "share/x"))
        assertNull(RelPath.resolve(root, "../../x"))
    }
}
