package com.miyuetech.mimifiles.core.shareserver

/**
 * 启动失败的原因。
 *
 * 方案 §10.4「错误提示一致」要求四类错误各有固定文案与解决指引，这个 sealed 就是那四类
 * 的**唯一事实来源**。做成 sealed 而不是"一个 String message"：文案属于 `res/values`
 * （要翻译、要跟随深浅色主题的强调色），核心层不该替 UI 决定怎么说；
 * 而 UI 侧用 `when` 穷举时，少写一类**编译期就会报错**——这正是我们要的。
 */
sealed interface ShareError {

    /** 端口探测了一圈（连续 N 个候选）全部被占用。 */
    data class PortsExhausted(val preferred: Int, val tried: Int) : ShareError

    /** 绑定失败，且不是"端口占用"（如权限、网卡状态）。 */
    data class BindFailed(val port: Int, val detail: String) : ShareError

    /** 没有任何局域网可达地址：只连了蜂窝、或全部网卡都只有公网/VPN 地址。 */
    data object NoLanAddress : ShareError

    /** 当前形态不支持（如 FTP + TLS，M4 未实现）。 */
    data class NotSupported(val feature: String) : ShareError

    /** 共享根不存在或已被删除（分享期间目录被删是很常见的）。 */
    data class RootUnavailable(val detail: String) : ShareError

    /** 其它 IO 失败（磁盘满、系统禁止监听）。 */
    data class IoFailure(val detail: String) : ShareError

    /**
     * 是否值得给一个"重试"按钮。
     *
     * 端口被占、绑定失败、IO 失败都可能是一次性的（别的应用刚释放端口），
     * 重试有意义；而"没有局域网地址"重试一百次也一样，该引导用户去连 WiFi。
     */
    val retryable: Boolean
        get() = when (this) {
            is PortsExhausted, is BindFailed, is IoFailure, is RootUnavailable -> true
            NoLanAddress, is NotSupported -> false
        }
}
