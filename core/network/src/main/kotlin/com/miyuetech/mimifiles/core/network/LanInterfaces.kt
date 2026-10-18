package com.miyuetech.mimifiles.core.network

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 一块网卡上的一个 IPv4 地址。
 *
 * [interfaceName] 是 OS 给的网卡名（`wlan0` / `ap0` / `eth0` / `tun0`），
 * 它在无线分享里**不是装饰**：同一条手机上"WiFi 地址"和"热点地址"必须能同时列出来，
 * 否则 PC 开热点连手机时看到的是 WiFi 那个连不上的地址，用户只会得出"这功能没用"。
 */
data class LanAddress(
    val interfaceName: String,
    val host: String,
    /** 是否落在私网段（`10/8`、`172.16/12`、`192.168/16`、链路本地）。 */
    val isPrivate: Boolean,
) {
    /** 展示用标签；网卡名为空时回落到地址本身（个别 ROM 会返回 unnamed0）。 */
    val label: String get() = interfaceName.ifBlank { host }

    companion object {
        /** 越小越靠前。 */
        fun rank(address: LanAddress): Int = when {
            !address.isPrivate -> 4
            address.host.startsWith("192.168.") -> 0
            address.host.startsWith("10.") -> 1
            address.host.startsWith("172.") -> 2
            else -> 3
        }
    }
}

/**
 * 枚举本机可用于局域网的 IPv4 地址。
 *
 * ## 为什么不用 `WifiManager.connectionInfo.ipAddress`
 * 那个 API 只认 WiFi：手机开热点时它给的是"客户端连的那个网络的地址"（常常是空），
 * 以太网平板完全无效，VPN 也会漏。而无线分享最常见的形态恰恰是
 * **"手机开热点 → PC 连进来"**，正是它测不到的那种。
 *
 * `NetworkInterface` 枚举则天然覆盖全部形态，代价只是要自己过滤。
 *
 * ## 过滤规则
 * - 跳过 `!isUp`（网卡被禁用）与 `isLoopback`（`lo`）；
 * - 只要 IPv4（PC 端 FTP 客户端填 IPv4 最省事，且 Android 上不可靠地有 IPv6 公网）；
 * - **跳过 `169.254.*`**：那是 DHCP 拿不到地址时的自分配地址，对着它连必然超时，
 *   列出来只会让用户白试一次。它在 [PrivateNetworkProxy] 里算私网（"该直连"），
 *   但在这里算"不可用"（"连不上"）——**两个判断的语义相反而且都对**，
 *   这点很容易搞混，所以两处都写了注释。
 */
object LanInterfaces {

    fun ipv4(): List<LanAddress> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { runCatching { it.isUp }.getOrDefault(false) }
            ?.filter { runCatching { !it.isLoopback }.getOrDefault(false) }
            ?.flatMap { iface ->
                iface.inetAddresses.asSequence().mapNotNull { addr ->
                    if (addr !is Inet4Address) return@mapNotNull null
                    val host = addr.hostAddress ?: return@mapNotNull null
                    if (host.startsWith("169.254.")) return@mapNotNull null
                    LanAddress(
                        interfaceName = iface.name.orEmpty(),
                        host = host,
                        isPrivate = PrivateNetworkProxy.isPrivateHost(host),
                    )
                }
            }
            ?.distinctBy { it.host }
            ?.sortedBy(LanAddress::rank)
            ?.toList()
            .orEmpty()
    } catch (_: Exception) {
        // 网卡枚举在受限环境（无 NET_ADMIN 的容器、部分定制 ROM）会抛
        // SocketException / NullPointerException。分享面板要能显示"未获取到地址"，
        // 而不是崩在打开的一瞬间。
        emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** 只取局域网可达的地址（私网段）。地址卡的默认展示集合。 */
    fun lanOnly(): List<LanAddress> = ipv4().filter { it.isPrivate }
}
