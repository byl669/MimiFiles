package com.miyuetech.mimifiles.core.fssmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器地址清洗的契约。
 *
 * ## 为什么要钉死
 * 这些用例全部来自**用户真实会粘进来的字符串**（Windows 地址栏、浏览器地址栏、
 * 同事发来的消息）。它们同时被「测试连接」与「保存」两条路径使用 ——
 * 一旦行为漂移，症状是"测试通过、保存后打不开"，而那是最难归因的一类问题。
 */
class SmbAddressTest {

    @Test
    fun `纯主机名与字面 IP 原样通过`() {
        assertEquals(SmbAddress.Parsed("192.168.1.100"), SmbAddress.parse("192.168.1.100"))
        assertEquals(SmbAddress.Parsed("nas.local"), SmbAddress.parse("nas.local"))
        assertEquals(SmbAddress.Parsed("my-nas"), SmbAddress.parse("  my-nas  "))
    }

    @Test
    fun `主机带端口`() {
        assertEquals(SmbAddress.Parsed("192.168.1.100", port = 4450), SmbAddress.parse("192.168.1.100:4450"))
        assertEquals(SmbAddress.Parsed("nas.local", port = 445), SmbAddress.parse("nas.local:445"))
    }

    @Test
    fun `Windows 地址栏那种 UNC 形态`() {
        assertEquals(
            SmbAddress.Parsed("192.168.1.100", share = "stock"),
            SmbAddress.parse("\\\\192.168.1.100\\stock"),
        )
        // 子目录要连在共享名后面一起交出去，否则 `2024` 会被丢掉
        assertEquals(
            SmbAddress.Parsed("192.168.1.100", share = "stock/2024/q1"),
            SmbAddress.parse("\\\\192.168.1.100\\stock\\2024\\q1"),
        )
    }

    @Test
    fun `URL 形态`() {
        assertEquals(
            SmbAddress.Parsed("192.168.1.100", share = "stock"),
            SmbAddress.parse("smb://192.168.1.100/stock"),
        )
        assertEquals(
            SmbAddress.Parsed("192.168.1.100", port = 4450, share = "stock"),
            SmbAddress.parse("smb://192.168.1.100:4450/stock"),
        )
        // 大小写不敏感的协议前缀
        assertEquals(
            SmbAddress.Parsed("192.168.1.100", share = "stock"),
            SmbAddress.parse("SMB://192.168.1.100/stock"),
        )
        // 正斜杠的 UNC
        assertEquals(
            SmbAddress.Parsed("192.168.1.100", share = "stock"),
            SmbAddress.parse("//192.168.1.100/stock"),
        )
    }

    @Test
    fun `只有共享名没有主机时不猜`() {
        // `\\stock` 里的 stock 是主机名（而不是"共享名"）—— 我们没有依据断定它写错了，
        // 解析器只做删减、不做替换。真正的失败会由连接阶段报"连不上 stock"。
        assertEquals(SmbAddress.Parsed("stock"), SmbAddress.parse("\\\\stock"))
    }

    @Test
    fun `尾斜杠与空白被吃掉`() {
        assertEquals(SmbAddress.Parsed("192.168.1.100"), SmbAddress.parse("\\\\192.168.1.100\\"))
        assertEquals(SmbAddress.Parsed("192.168.1.100", share = "stock"), SmbAddress.parse("  \\\\192.168.1.100\\stock  "))
        assertEquals(SmbAddress.Parsed("192.168.1.100"), SmbAddress.parse("smb://192.168.1.100/"))
    }

    @Test
    fun `空输入返回空主机而不是抛异常`() {
        assertEquals("", SmbAddress.parse("").host)
        assertEquals("", SmbAddress.parse("   ").host)
        assertEquals("", SmbAddress.parse("\\\\").host)
        assertEquals("", SmbAddress.parse("smb://").host)
    }

    @Test
    fun `端口非法时不当成端口`() {
        // `:abc` 不是端口。此时整串保持原样 —— 让连接阶段报"连不上 xxx:abc"，
        // 比悄悄把它删掉更好：用户能看出自己写错了什么。
        assertEquals("192.168.1.100:abc", SmbAddress.parse("192.168.1.100:abc").host)
        assertNull(SmbAddress.parse("192.168.1.100:abc").port)
        // 超范围同理
        assertNull(SmbAddress.parse("192.168.1.100:70000").port)
        assertEquals("192.168.1.100:70000", SmbAddress.parse("192.168.1.100:70000").host)
    }

    @Test
    fun `IPv6 字面量的冒号不会被当成端口`() {
        // 含多个冒号 ⇒ 是 IPv6，不做端口切分（我们不做完整 IPv6 解析，
        // 但**绝不能**把它误切成 host=`[` port=xxx）。
        val parsed = SmbAddress.parse("[fe80::1]")
        assertNull(parsed.port)
        assertTrue(parsed.host.contains("::"))
    }

    @Test
    fun `主机名与共享名的大小写都要保留`() {
        // 共享名在 SMB 服务端上**大小写敏感**：`SHARE` 不能变成 `share`。
        val parsed = SmbAddress.parse("\\\\NAS-01\\SHARE")
        assertEquals("NAS-01", parsed.host)
        assertEquals("SHARE", parsed.share)
    }

    @Test
    fun `isClean 只在无需清洗时为真`() {
        assertTrue(SmbAddress.parse("192.168.1.100").isClean)
        assertTrue(!SmbAddress.parse("192.168.1.100:445").isClean)
        assertTrue(!SmbAddress.parse("\\\\192.168.1.100\\stock").isClean)
    }
}
