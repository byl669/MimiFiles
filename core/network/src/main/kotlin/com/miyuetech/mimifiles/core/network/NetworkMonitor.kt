package com.miyuetech.mimifiles.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 连通性与本机地址探测。
 *
 * ## 为什么要自己包一层
 * 两件事都容易写错：
 *
 * 1. **"有网"的判断**：`NetworkInfo.isConnected` 从 API 23 起废弃、29 起对后台应用返回
 *    假值。现在的正确姿势是 `ConnectivityManager.activeNetwork` +
 *    `getNetworkCapabilities(net).hasCapability(NET_CAPABILITY_INTERNET)`，
 *    并且 **还要看 `NET_CAPABILITY_VALIDATED`** —— 连上了酒店 WiFi 但没通过强制门户时
 *    前者为真、后者为假。对 SMB 而言后者才是"能不能连到 NAS"的近似指标。
 *
 * 2. **本机 IP 的取法**：`WifiManager.connectionInfo.ipAddress` 拿的是 `int`，
 *    需要自己按小端拼字节，且 Android 12 起要新权限、且对以太网/热点/VPN 全部失效。
 *    直接枚举 `NetworkInterface` 才是普遍正确的做法——它同时在"手机开热点给 PC 用"
 *    这个场景下给出 `ap0`，而这恰好是无线分享（M4）最常用的形态。
 *
 * ## 刻意不在这里做"探测 NAS 是否在线"
 * 那属于各后端自己的连接测试（`:feature:connection` 的「测试」按钮），
 * 因为只有后端知道该连 445 还是 443、该不该带凭据。这里只管"手机自己有没有网"。
 */
class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val connectivityManager: ConnectivityManager?
        get() = appContext.getSystemService(ConnectivityManager::class.java)

    /** 当前默认网络（无网络时为 `null`）。 */
    fun activeNetwork(): Network? = connectivityManager?.activeNetwork

    fun capabilities(): NetworkCapabilities? {
        val cm = connectivityManager ?: return null
        return cm.getNetworkCapabilities(cm.activeNetwork ?: return null)
    }

    fun linkProperties(): LinkProperties? {
        val cm = connectivityManager ?: return null
        return cm.getLinkProperties(cm.activeNetwork ?: return null)
    }

    /** 是否有可用网络（含未通过强制门户的网络）。 */
    fun hasNetwork(): Boolean =
        capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    /**
     * 网络是否**确实可用**（能路由到公网/局域网对端）。
     *
     * 局域网的 NAS 在"未通过强制门户"的 WiFi 上通常仍可直连，所以这个方法判定为假时
     * **不能**直接禁止用户访问 SMB——只能用来给一句更准确的提示。
     */
    fun isOnline(): Boolean =
        capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    /** 是否走 WiFi（NAS 场景下这是"值得一试"的信号；走蜂窝时几乎必然连不上内网）。 */
    fun isOnWifi(): Boolean =
        capabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    /** 是否走以太网（部分平板/电视盒子的主用形态）。 */
    fun isOnEthernet(): Boolean =
        capabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

    /** 当前网络的展示名（SSID 在 API 27+ 需要定位权限才能读，这里退化为通用标签）。 */
    fun transportLabel(): String? {
        val caps = capabilities() ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> null
        }
    }

    /**
     * 本机 IPv4 地址（可能多个：WiFi + 热点 + VPN 会各有一个）。
     *
     * 按"最可能是局域网地址"排序：`192.168.*` → `10.*` → `172.16-31.*` → 其余。
     * 排序的意义在 M4：地址卡要挑一个展示给用户扫，选错的话扫码的人根本连不上。
     */
    fun localIpv4Addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { iface -> iface.inetAddresses.asSequence().map { iface.name to it } }
            ?.filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress }
            ?.map { (_, addr) -> addr.hostAddress }
            ?.filterNotNull()
            ?.distinct()
            ?.sortedBy(::privateAddressRank)
            ?.toList()
            .orEmpty()
    } catch (_: Exception) {
        // 网卡枚举在某些受限环境（无 NET_ADMIN 的容器、部分定制 ROM）会抛
        // SocketException / NullPointerException。地址探测失败不该让调用方崩，
        // 返回空列表，由 UI 显示"未能获取地址"。
        emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    companion object {

        /** 越小越优先展示。私网段优先，因为无线分享要的正是局域网可达地址。 */
        internal fun privateAddressRank(address: String): Int = when {
            address.startsWith("192.168.") -> 0
            address.startsWith("10.") -> 1
            address.startsWith("172.") -> 2
            else -> 3
        }
    }
}
