package com.miyuetech.mimifiles.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FsPath] 是全部后端的公共地基，且其文本形式会被持久化（快捷方式 extras / 书签 /
 * DocumentsProvider 文档 id）。这里对规范化、解析、祖先关系做穷举式验证。
 */
class FsPathTest {

    // ── 规范化 ────────────────────────────────────────────────────────────

    @Test
    fun `根路径统一为空串`() {
        assertEquals("", FsPath.of("local", "", "/").path)
        assertEquals("", FsPath.of("local", "", "").path)
        assertEquals("", FsPath.of("local", "", "///").path)
        assertEquals("", FsPath.of("local", "", "/./").path)
        assertEquals("", FsPath.of("local", "", "/a/..").path)
    }

    @Test
    fun `折叠连续斜杠与当前目录段`() {
        assertEquals("/a/b/c", FsPath.of("local", "", "//a//b///c").path)
        assertEquals("/a/b", FsPath.of("local", "", "/a/./b/").path)
    }

    @Test
    fun `上溯段不得越过根`() {
        // 这是防路径逃逸的关键：用户在输入框里敲 ../../.. 不能跑出挂载根
        assertEquals("", FsPath.of("local", "", "/../../..").path)
        assertEquals("", FsPath.of("local", "", "/a/../../..").path)
        assertEquals("/b", FsPath.of("local", "", "/a/../b").path)
    }

    @Test
    fun `末尾不含斜杠`() {
        assertFalse(FsPath.of("local", "", "/a/b/").path.endsWith("/"))
        assertEquals("/a/b", FsPath.of("local", "", "/a/b/").path)
    }

    @Test
    fun `scheme 统一小写`() {
        assertEquals("smb", FsPath.of("SMB", "nas", "/x").scheme)
        assertEquals("webdav", FsPath.of("webDAV", "w", "/x").scheme)
    }

    // ── 文本形式与还原 ────────────────────────────────────────────────────

    @Test
    fun `toString 与 parse 往返无损`() {
        val samples = listOf(
            FsPath.of("local", "", "/storage/emulated/0/DCIM/Camera"),
            FsPath.of("smb", "nas-01", "/share/photo"),
            FsPath.of("saf", "grant-3", "/DCIM"),
            FsPath.of("local", "", ""),
            FsPath.of("webdav", "wd-2", ""),
            // 含空格、中日韩、括号等真实文件名
            FsPath.of("local", "", "/sdcard/我的 照片/2026年(上海)"),
        )
        for (p in samples) {
            assertEquals("往返失败: $p", p, FsPath.parse(p.toString()))
        }
    }

    @Test
    fun `文本形式固定为 scheme 双斜杠 authority 路径`() {
        assertEquals("local:///storage/emulated/0", FsPath.of("local", "", "/storage/emulated/0").toString())
        assertEquals("smb://nas-01/share", FsPath.of("smb", "nas-01", "/share").toString())
        assertEquals("local://", FsPath.of("local", "", "").toString())
        assertEquals("smb://nas-01", FsPath.of("smb", "nas-01", "").toString())
    }

    @Test
    fun `parse 对非法输入返回 null 而不抛异常`() {
        assertNull(FsPath.parse(null))
        assertNull(FsPath.parse(""))
        assertNull(FsPath.parse("   "))
        assertNull(FsPath.parse("/storage/emulated/0")) // 缺 scheme
        assertNull(FsPath.parse("://x/y"))
        assertNull(FsPath.parse("1local://x")) // scheme 不能以数字开头
        assertNull(FsPath.parse("lo cal://x")) // 非法字符
        assertNull(FsPath.parse("local:x")) // 缺 //
    }

    @Test
    fun `parseOrThrow 对非法输入抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { FsPath.parseOrThrow("nope") }
    }

    // ── 层级关系 ──────────────────────────────────────────────────────────

    @Test
    fun `parent 与 ancestor 正确收敛到根`() {
        val p = FsPath.of("local", "", "/a/b/c")
        assertEquals("/a/b", p.parent!!.path)
        assertEquals("/a", p.parent!!.parent!!.path)
        assertEquals("", p.parent!!.parent!!.parent!!.path)
        assertNull(p.parent!!.parent!!.parent!!.parent)

        assertEquals("/a", p.ancestor(2).path)
        assertEquals("/a/b/c", p.ancestor(0).path)
        // 越界停在根，而不是抛异常 —— 面包屑"上一级"按钮不该因边界而炸
        assertEquals("", p.ancestor(99).path)
    }

    @Test
    fun `segments depth displayName extension`() {
        val p = FsPath.of("local", "", "/sdcard/DCIM/IMG_0001.JPG")
        assertEquals(listOf("sdcard", "DCIM", "IMG_0001.JPG"), p.segments)
        assertEquals(3, p.depth)
        assertEquals("IMG_0001.JPG", p.displayName)
        assertEquals("jpg", p.extension)

        assertEquals(0, FsPath.of("local", "").depth)
        // 根路径没有末段，回退到 authority
        assertEquals("local", FsPath.of("local", "").displayName)
        assertEquals("nas-01", FsPath.of("smb", "nas-01").displayName)
    }

    @Test
    fun `隐藏文件不当作扩展名`() {
        assertEquals("", FsPath.of("local", "", "/sdcard/.gitignore").extension)
        assertTrue(FsPath.of("local", "", "/sdcard/.gitignore").isHidden)
        assertTrue(FsPath.of("local", "", "/sdcard/.config").isHidden)
        assertFalse(FsPath.of("local", "", "/sdcard/normal.txt").isHidden)
        // 以点结尾同样不算扩展名
        assertEquals("", FsPath.of("local", "", "/sdcard/weird.").extension)
    }

    @Test
    fun `isAncestorOf 要求同后端同 authority`() {
        val root = FsPath.of("local", "", "/a")
        val child = FsPath.of("local", "", "/a/b")
        val other = FsPath.of("smb", "nas", "/a/b")

        assertTrue(root.isAncestorOf(child))
        assertTrue(child.isDescendantOf(root))
        assertFalse(child.isAncestorOf(root))
        assertFalse(root.isAncestorOf(other))
        // 前缀相似但不是祖先：/ab 不该被 /a 认领
        assertFalse(root.isAncestorOf(FsPath.of("local", "", "/ab")))
        // 根是所有后代的祖先
        assertTrue(FsPath.of("local", "").isAncestorOf(child))
    }

    @Test
    fun `commonAncestor`() {
        val a = FsPath.of("local", "", "/x/y/z")
        val b = FsPath.of("local", "", "/x/q/r")
        assertEquals("/x", a.commonAncestor(b)!!.path)
        // 一方是另一方的祖先时取祖先
        assertEquals("/x/y", FsPath.of("local", "", "/x/y").commonAncestor(FsPath.of("local", "", "/x/y/z"))!!.path)
        // 不同后端没有公共祖先
        assertNull(a.commonAncestor(FsPath.of("smb", "n", "/x/y/z")))
    }

    @Test
    fun `chainFromRoot 从根到自身`() {
        val chain = FsPath.of("local", "", "/a/b").chainFromRoot()
        assertEquals(listOf("", "/a", "/a/b"), chain.map { it.path })
    }

    // ── 构造子路径 ────────────────────────────────────────────────────────

    @Test
    fun `child 与 resolve`() {
        val root = FsPath.of("local", "", "/sdcard")
        assertEquals("/sdcard/DCIM", root.child("DCIM").path)
        assertEquals("/sdcard/DCIM/Camera", root.child("DCIM", "Camera").path)
        assertEquals("/DCIM", FsPath.of("local", "").child("DCIM").path)

        // 相对解析
        assertEquals("/sdcard/a/b", root.resolve("a/b").path)
        // 上溯到挂载根（/sdcard 的父级），再往上就停住
        assertEquals("/a", root.resolve("../a").path)
        // 以 / 开头视为相对后端根
        assertEquals("/a/b", root.resolve("/a/b").path)
    }

    @Test
    fun `child 拒绝非法段`() {
        val root = FsPath.of("local", "")
        assertThrows(IllegalArgumentException::class.java) { root.child("") }
        assertThrows(IllegalArgumentException::class.java) { root.child(".") }
        assertThrows(IllegalArgumentException::class.java) { root.child("..") }
        // 带斜杠的名字会被 trim 成合法单段，而不是悄悄生成两层路径
        assertEquals("/a", root.child("/a/").path)
    }

    @Test
    fun `equals 与 hashCode 基于三元组`() {
        val a = FsPath.of("local", "", "/a/b")
        val b = FsPath.of("local", "", "/a//b")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == FsPath.of("local", "x", "/a/b"))
        assertFalse(a == FsPath.of("smb", "", "/a/b"))
    }

    /**
     * 规范化有一条"已经是规范形式就直接返回"的快路径（列举 5000 项目录时
     * 每个条目都要构造 FsPath，走通用 split+join 太贵）。这组用例专门盯住
     * 快慢两条路径**结果必须一致**：任何一条漏判，路径就会被静默改写，
     * 而路径错误会一路传到文件操作上。
     */
    @Test
    fun `规范化快路径与通用路径结果一致`() {
        // 应当走快路径（已是规范形式）
        listOf(
            "/a",
            "/a/b",
            "/storage/emulated/0/DCIM/Camera",
            "/.hidden/x",
            "/a.b/c.d",
            "/...",
            "/a/..b/c",
            "/a b/中文 目录",
        ).forEach { normalized ->
            assertEquals(
                "已是规范形式却改写了：$normalized",
                normalized,
                FsPath.of("local", "", normalized).path,
            )
        }

        // 应当回落到通用路径，且逐条断言结果
        val cases = mapOf(
            "" to "",
            "/" to "",
            "//" to "",
            "///" to "",
            "/a/" to "/a",
            "/a//b" to "/a/b",
            "/a/./b" to "/a/b",
            "/./a" to "/a",
            "/a/b/../c" to "/a/c",
            "/a/.." to "",
            "/.." to "",
            "/../../a" to "/a",
            "/a/b/../../.." to "",
            "a/b" to "/a/b",
        )
        cases.forEach { (raw, expected) ->
            assertEquals("规范化失败：$raw", expected, FsPath.of("local", "", raw).path)
        }
    }
}
