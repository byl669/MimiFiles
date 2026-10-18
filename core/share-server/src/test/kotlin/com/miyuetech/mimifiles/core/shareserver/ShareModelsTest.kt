package com.miyuetech.mimifiles.core.shareserver

import com.miyuetech.mimifiles.core.model.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRequestTest {

    private val dir = FsPath.of("local", "", "/sdcard/DCIM")

    @Test
    fun `没有选中项时分享当前目录且不过滤`() {
        val req = ShareRequest.ofSelection(dir, emptyList(), selectedIsDir = false)
        assertEquals(dir, req.root)
        assertNull(req.visibleNames)
    }

    @Test
    fun `只选中一个目录时根就是它本身`() {
        // 用户选了一个文件夹点分享，期望"进去就看到里面的东西"，
        // 而不是"先看到一个只含它自己的列表，再点一层"。
        val sub = dir.child("Camera")
        val req = ShareRequest.ofSelection(dir, listOf(sub), selectedIsDir = true)
        assertEquals(sub, req.root)
        assertNull(req.visibleNames)
    }

    @Test
    fun `只选中一个文件时取共同父目录并过滤`() {
        val file = dir.child("a.jpg")
        val req = ShareRequest.ofSelection(dir, listOf(file), selectedIsDir = false)
        // ⚠️ 单文件必须取**父目录**：`reduceOrNull` 在只有一个元素时返回元素本身，
        // 直接拿它当根会让共享根变成一个文件，而 FTP/HTTP 的根必须是目录。
        assertEquals(dir, req.root)
        assertEquals(setOf("a.jpg"), req.visibleNames)
    }

    @Test
    fun `选中两个子目录时根取它们的共同父目录`() {
        val a = dir.child("Camera")
        val b = dir.child("Screenshots")
        val req = ShareRequest.ofSelection(dir, listOf(a, b), selectedIsDir = true)
        assertEquals(dir, req.root)
        assertEquals(setOf("Camera", "Screenshots"), req.visibleNames)
    }

    @Test
    fun `多个同目录文件取父目录并列出全部选中的名字`() {
        val req = ShareRequest.ofSelection(
            dir,
            listOf(dir.child("a.jpg"), dir.child("b.jpg")),
            selectedIsDir = false,
        )
        assertEquals(dir, req.root)
        assertEquals(setOf("a.jpg", "b.jpg"), req.visibleNames)
    }

    @Test
    fun `跨不同子目录的文件取共同祖先`() {
        val a = dir.child("Camera", "a.jpg")
        val b = dir.child("Screenshots", "b.png")
        val req = ShareRequest.ofSelection(dir, listOf(a, b), selectedIsDir = false)
        assertEquals(dir, req.root)
        assertEquals(setOf("a.jpg", "b.png"), req.visibleNames)
    }

    @Test
    fun `多选时选中项的原始列出顺序被保留`() {
        // LinkedHashSet：面板上"已选 N 项"的顺序应与用户勾选顺序一致，
        // 否则 PC 端看到的列表顺序会莫名变化。
        val req = ShareRequest.ofSelection(
            dir,
            listOf(dir.child("z.txt"), dir.child("a.txt"), dir.child("m.txt")),
            selectedIsDir = false,
        )
        assertEquals(listOf("z.txt", "a.txt", "m.txt"), req.visibleNames!!.toList())
    }
}

class ServerConfigTest {

    @Test
    fun `默认配置是随机账密 非只读 5 分钟`() {
        val c = ServerConfig.defaultFor(ServerType.FTP)
        assertTrue(c.auth is AuthMode.Credentials)
        assertTrue(!c.readOnly)
        assertEquals(5 * 60_000L, c.sessionTtlMillis)
        assertEquals(2121, c.port)
        assertEquals(2121, c.resolvedPreferredPort(ServerType.FTP))
    }

    @Test
    fun `端口填 0 时回落到该类型的推荐端口`() {
        val c = ServerConfig(port = 0)
        assertEquals(8080, c.resolvedPreferredPort(ServerType.HTTP))
        assertEquals(2121, c.resolvedPreferredPort(ServerType.FTP))
    }

    @Test
    fun `用户显式给的端口优先`() {
        val c = ServerConfig(port = 9999)
        assertEquals(9999, c.resolvedPreferredPort(ServerType.HTTP))
        assertEquals(9999, c.resolvedPreferredPort(ServerType.FTP))
    }

    @Test
    fun `随机密码排除了易混字符且长度正确`() {
        val seen = HashSet<String>()
        repeat(200) {
            val p = ServerConfig.randomPassword()
            assertEquals(8, p.length)
            assertTrue(p.none { it in "ilo01" })
            seen += p
        }
        // 200 次生成几乎必然全不相同；用 >190 而不是 ==200 是为了不让
        // 一次极小概率的碰撞把构建弄红。
        assertTrue("随机性不足：${seen.size}", seen.size > 190)
    }

    @Test
    fun `认证模式的描述里不含密码`() {
        val credentials = AuthMode.Credentials("mimi", "sup3rsecret")
        assertEquals("mimi", credentials.describe)
        assertTrue(!credentials.describe.contains("sup3rsecret"))
        assertEquals("匿名", AuthMode.Anonymous.describe)
    }

    @Test
    fun `错误原因的可重试判定`() {
        assertTrue(ShareError.PortsExhausted(2121, 32).retryable)
        assertTrue(ShareError.BindFailed(2121, "x").retryable)
        assertTrue(ShareError.IoFailure("x").retryable)
        assertTrue(ShareError.RootUnavailable("x").retryable)
        // 没网可连这件事重试一百次也一样，该引导用户去连 WiFi。
        assertTrue(!ShareError.NoLanAddress.retryable)
        assertTrue(!ShareError.NotSupported("FTPS").retryable)
    }

    @Test
    fun `地址 URL 不带凭据 带凭据版本单独提供`() {
        val a = ShareAddress(ServerType.FTP, "192.168.2.5", 2121, "wlan0")
        assertEquals("ftp://192.168.2.5:2121/", a.url)
        assertEquals("192.168.2.5:2121", a.hostPort)
        // 密码里的 `@` 与 `:` 必须编码，否则客户端会在第一个 `@` 处断错主机。
        assertEquals(
            "ftp://mimi:p%40ss%3Aword@192.168.2.5:2121/",
            a.urlWithCredentials("mimi", "p@ss:word"),
        )
    }

    @Test
    fun `HTTP 地址同样能带凭据`() {
        val a = ShareAddress(ServerType.HTTP, "10.0.0.3", 8080)
        assertEquals("http://10.0.0.3:8080/", a.url)
        assertEquals("http://u:p@10.0.0.3:8080/", a.urlWithCredentials("u", "p"))
    }

    @Test
    fun `服务端类型从字符串还原时容错`() {
        assertEquals(ServerType.FTP, ServerType.fromName("ftp"))
        assertEquals(ServerType.HTTP, ServerType.fromName("HTTP"))
        assertEquals(ServerType.HTTP, ServerType.fromName(null))
        assertEquals(ServerType.HTTP, ServerType.fromName("nonsense"))
    }
}

class SharePortsTest {

    @Test
    fun `能找到一个空闲端口`() {
        // 用一个高位端口段，撞上别的服务的概率极低。
        val port = SharePorts.findFree(41_000, attempts = 64)
        assertNotNull(port)
        assertTrue(port!! >= 41_000)
    }

    @Test
    fun `已被占用的端口会被跳过`() {
        val socket = java.net.ServerSocket(0)
        try {
            val busy = socket.localPort
            // `ServerSocket(0)` 会在所有网卡上监听，所以 busy 必然"不空闲"。
            assertTrue(!SharePorts.isFreeOnAllInterfaces(busy))
            val found = SharePorts.findFree(busy, attempts = 8)
            assertNotNull(found)
            assertTrue("应当跳过被占用的 $busy，实际 $found", found != busy)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `系统能分配临时端口`() {
        val p = SharePorts.pickEphemeral()
        assertTrue(p in 1..65535)
    }
}
