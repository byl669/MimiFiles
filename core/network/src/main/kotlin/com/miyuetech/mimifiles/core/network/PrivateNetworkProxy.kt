package com.miyuetech.mimifiles.core.network

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 对**私网地址绕过系统代理**的 `ProxySelector`。
 *
 * ## 为什么需要它
 *
 * 这个类不是"为了优雅"，是被实测逼出来的：在开发机上验证 WebDAV 时，所有
 * `http://127.0.0.1:8090` 的请求都被系统代理接走并回了 **502
 * upstream connect failed**——代理不认识这个地址，于是"服务端明明起着、健康检查
 * 也过、客户端却一个字节都拿不到"。
 *
 * 同一条链路在用户侧的原样复现是：**公司电脑开着系统代理，回家连 NAS 的
 * WebDAV / 群晖套件，请求先被送去公司代理，然后必然失败**。用户看到的只是
 * "连不上"，而连接配置每一项都是对的，无从下手。
 *
 * 所以：私网地址**一律直连**，其余地址继续跟随系统代理——企业环境里访问公网
 * WebDAV（坚果云、Nextcloud 云）时仍然走代理，这正是用户期望的。
 *
 * ## 为什么按**字面串**判断，而不做 DNS 解析
 *
 * 解析主机名会有两个代价：`select()` 是**同步调用**且在连接建立的关键路径上，
 * 多做一次 DNS 会直接拖慢建连；而且一旦解析失败，我们就得在两难里选——走代理
 * （错）还是直连（也可能是错）。字面判断只看用户填了什么，行为完全可预测。
 *
 * 覆盖范围（足矣）：`localhost` / `127.0.0.0/8` / `10.0.0.0/8` /
 * `172.16.0.0/12` / `192.168.0.0/16` / `169.254.0.0/16`（链路本地），
 * 以及 `.local` `.lan` `.home` `.internal` `.home.arpa` 这些家用后缀
 * （`.local` 是 mDNS 的标准后缀，NAS 默认主机名常是 `nas.local`）。
 *
 * 刻意**不做**的：不拦截公网 IP 的解析结果。把"用户填了域名、域名解析到私网"
 * 这种情况也绕过代理，需要 DNS 查询——见上一条理由。
 */
object PrivateNetworkProxy {

    /** 家用 / 内网常见域名后缀。`.local` 是 mDNS 标准后缀。 */
    private val HOME_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa")

    /**
     * 供 `OkHttpClient.Builder.proxySelector(...)` 使用。
     *
     * 永远返回一个非空选择器：系统没有配代理时 `ProxySelector.getDefault()` 可能
     * 是 `null`，直接交出去会让 OkHttp 退回内置行为（在个别 ROM 上表现为"仍然走代理"）。
     */
    fun selector(): ProxySelector = BypassingSelector(ProxySelector.getDefault())

    /** 该主机名是否属于"应当直连"的私网范围。公开给测试与排障用。 */
    fun isPrivateHost(host: String?): Boolean {
        val raw = host?.trim().orEmpty()
        if (raw.isEmpty()) return false
        // IPv6 字面量在 URL 里带方括号
        val h = raw.removePrefix("[").removeSuffix("]").lowercase()

        if (h == "localhost" || h == "::1" || h == "0.0.0.0") return true
        if (HOME_SUFFIXES.any { h.endsWith(it) }) return true

        val octets = h.split('.')
        if (octets.size != 4) return false
        val parts = octets.map { it.toIntOrNull() }
        if (parts.any { it == null || it < 0 || it > 255 }) return false

        val a = parts[0]!!
        val b = parts[1]!!
        return a == 10 ||                       // 10.0.0.0/8
            a == 127 ||                         // 回环
            (a == 172 && b in 16..31) ||        // 172.16.0.0/12
            (a == 192 && b == 168) ||           // 192.168.0.0/16
            (a == 169 && b == 254)              // 链路本地（169.254.x.x）
    }

    private class BypassingSelector(private val delegate: ProxySelector?) : ProxySelector() {

        override fun select(uri: URI): List<Proxy> {
            if (isPrivateHost(uri.host)) return listOf(Proxy.NO_PROXY)
            // delegate 为 null 时给 NO_PROXY 而不是空列表：空列表在 OkHttp 里
            // 会被理解成"没有可用代理"并直接抛错，等于把请求弄挂了。
            return delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // 直连失败不该回报给代理的失败回调（否则系统会把"直连失败"记成"代理失败"）
            val target = uri ?: return
            if (isPrivateHost(target.host)) return
            delegate?.connectFailed(target, sa, ioe)
        }
    }
}
