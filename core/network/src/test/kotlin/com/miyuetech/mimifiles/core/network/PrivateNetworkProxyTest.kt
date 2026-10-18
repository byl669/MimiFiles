package com.miyuetech.mimifiles.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PrivateNetworkProxy.isPrivateHost] 的穷举测试。
 *
 * ## 为什么这类判断必须写成测试
 *
 * 它的失败方式**不可见**：判错一个网段不会有任何报错，只会让"公司网络下连家里 NAS"
 * 这一条路径上多出一次注定失败的代理请求。用户看到的就是"连不上"，
 * 而连接配置每一项都对——没有任何线索指向代理。
 *
 * 所以这里把**边界**逐个钉死：`172.16`~`172.31` 是私网、`172.32` 不是；
 * `169.254` 是链路本地、`169.255` 不是。这些正是手写前缀判断最容易写错的地方。
 */
class PrivateNetworkProxyTest {

    // ── 应当直连 ────────────────────────────────────────────────────────────

    @Test
    fun `回环与本机名一律直连`() {
        assertTrue(PrivateNetworkProxy.isPrivateHost("localhost"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("LOCALHOST"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("127.0.0.1"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("127.255.255.254"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("::1"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("[::1]"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("0.0.0.0"))
    }

    @Test
    fun `RFC1918 三个网段的边界都要覆盖`() {
        // 10.0.0.0/8
        assertTrue(PrivateNetworkProxy.isPrivateHost("10.0.0.1"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("10.255.255.254"))
        // 172.16.0.0/12 —— 上界 172.31 是重点：写成 172.16~172.30 是常见错法
        assertTrue(PrivateNetworkProxy.isPrivateHost("172.16.0.1"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("172.31.255.254"))
        // 192.168.0.0/16
        assertTrue(PrivateNetworkProxy.isPrivateHost("192.168.0.1"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("192.168.1.100"))   // 家庭的 NAS
        assertTrue(PrivateNetworkProxy.isPrivateHost("192.168.255.254"))
    }

    @Test
    fun `链路本地地址也要直连`() {
        // 169.254.0.0/16：自动配置地址，家里直连网线时会出现
        assertTrue(PrivateNetworkProxy.isPrivateHost("169.254.0.1"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("169.254.255.254"))
    }

    @Test
    fun `家用域名后缀要直连`() {
        // .local 是 mDNS 标准后缀，群晖/QNAP 默认主机名就是 nas.local
        assertTrue(PrivateNetworkProxy.isPrivateHost("nas.local"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("NAS.LOCAL"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("diskstation.lan"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("box.home.arpa"))
        assertTrue(PrivateNetworkProxy.isPrivateHost("files.internal"))
    }

    // ── 不应直连（必须继续跟随系统代理）─────────────────────────────────────

    @Test
    fun `私网网段之外的一律走代理`() {
        assertFalse(PrivateNetworkProxy.isPrivateHost("8.8.8.8"))
        assertFalse(PrivateNetworkProxy.isPrivateHost("172.15.0.1"))    // 172.15 在 12 位前缀之外
        assertFalse(PrivateNetworkProxy.isPrivateHost("172.32.0.1"))    // 172.32 也在之外
        assertFalse(PrivateNetworkProxy.isPrivateHost("169.255.0.1"))   // 169.255 不是链路本地
        assertFalse(PrivateNetworkProxy.isPrivateHost("192.169.0.1"))
        assertFalse(PrivateNetworkProxy.isPrivateHost("11.0.0.1"))
    }

    @Test
    fun `公网域名与云盘地址继续走代理`() {
        // 企业里访问公网 WebDAV 应该照常走代理——这是"绕过私网"而不是"关掉代理"
        assertFalse(PrivateNetworkProxy.isPrivateHost("dav.jianguoyun.com"))
        assertFalse(PrivateNetworkProxy.isPrivateHost("cloud.example.com"))
        assertFalse(PrivateNetworkProxy.isPrivateHost("my.localcloud.com"))
    }

    @Test
    fun `畸形输入不能误判为私网`() {
        // 空与负例：宁可走代理（可能失败但符合系统设置），也不要凭空直连
        assertFalse(PrivateNetworkProxy.isPrivateHost(null))
        assertFalse(PrivateNetworkProxy.isPrivateHost(""))
        assertFalse(PrivateNetworkProxy.isPrivateHost("   "))
        // 段数不对 / 超范围 / 非数字，都不该崩，也不该判成私网
        assertFalse(PrivateNetworkProxy.isPrivateHost("10.0.0"))
        assertFalse(PrivateNetworkProxy.isPrivateHost("10.0.0.1.2"))
        assertFalse(PrivateNetworkProxy.isPrivateHost("10.0.0.999"))
        assertFalse(PrivateNetworkProxy.isPrivateHost("10.0.0.x"))
        assertFalse(PrivateNetworkProxy.isPrivateHost("10.0.-1.1"))
    }

    // ── 选择器整体行为 ──────────────────────────────────────────────────────

    @Test
    fun `选择器对私网返回直连、对公网不返回直连`() {
        val selector = PrivateNetworkProxy.selector()

        val privateProxies = selector.select(java.net.URI("http://192.168.1.100:5005/dav"))
        assertTrue(
            "私网地址必须解析为直连",
            privateProxies.any { it.type() == java.net.Proxy.Type.DIRECT },
        )

        // 公网地址的结果取决于当前环境有没有配代理，所以只断言"不是我们强行改直连"：
        // 有代理时应当给出非直连项，没有代理时给 DIRECT 也算对。
        val publicProxies = selector.select(java.net.URI("https://dav.jianguoyun.com/dav"))
        assertFalse("公网地址不该被无条件改成直连", publicProxies.isEmpty())
    }
}
