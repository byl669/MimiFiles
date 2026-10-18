package com.miyuetech.mimifiles.core.shareserver

/**
 * 一条可被 PC 访问的地址。
 *
 * FTP 与 HTTP **共用同一个类型**（方案 §10.4「地址展示一致」）：Tab 切换只是换了
 * [protocol]，地址卡本身一行代码都不用改。
 */
data class ShareAddress(
    val protocol: ServerType,
    val host: String,
    val port: Int,
    /** 网卡名（`wlan0` / `ap0`），用作地址卡上的小标签。 */
    val interfaceLabel: String = "",
) {
    /** 不带凭据的 URL。可以安全地进日志、进剪贴板、进二维码。 */
    val url: String get() = "${protocol.scheme}://$host:$port/"

    /** `192.168.2.5:2121` —— 参考应用里地址卡上那个大号粗体串。 */
    val hostPort: String get() = "$host:$port"

    /**
     * 带凭据的 URL，供用户直接粘到 FTP 客户端里一步到位。
     *
     * ## 为什么单独一个方法而不是让 [url] 自己带上
     * 带凭据的 URL 是**敏感串**：它会进剪贴板、会被用户截图、可能被别的应用读到。
     * 因此它必须是调用方**显式**索取的结果——日志与二维码永远用 [url]，
     * 只有"复制到剪贴板"那个按钮才调这里。把这两件事分开写，才不会有
     * 某天在日志里看到明文密码的意外。
     *
     * 密码里的 `@` `:` `/` 必须百分号编码，否则 `ftp://u:p@ss@h:p/` 会被
     * FTP 客户端在**第一个** `@` 断开，解析出错误的用户名与主机。
     */
    fun urlWithCredentials(user: String, password: String): String {
        val u = encodeUserInfo(user)
        val p = encodeUserInfo(password)
        return "${protocol.scheme}://$u:$p@$host:$port/"
    }

    private fun encodeUserInfo(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }
}

/**
 * 分享服务端的状态 —— **FTP 与 HTTP 共用**（方案 §10.4「状态模型一致」）。
 *
 * 共用带来一个直接好处：面板上的状态色点、通知文案、按钮可用性都只写一份，
 * 不会出现"HTTP 的停止按钮是灰的、FTP 的却能点"这种分支漂移。
 */
sealed interface ServerState {

    /** 未启动。面板上主按钮显示「启动」。 */
    data object Stopped : ServerState

    /** 正在起（探测端口、建监听）。这个状态通常只闪一下，但慢网卡上可能几百毫秒。 */
    data object Starting : ServerState

    /**
     * 已启动。
     *
     * [addresses] 为空是**合法**的：服务端确实起来并在监听，只是没探测到局域网地址
     * （比如手机插着 SIM 卡没连 WiFi）。此时面板要显示"服务已启动，但未获取到局域网
     * 地址"，而不是显示"启动失败"——两者对用户的下一步动作完全不同。
     */
    data class Running(
        val addresses: List<ShareAddress>,
        val since: Long,
        /** 实际端口。可能不等于用户设的那个（被占用时向上探测过）。 */
        val port: Int,
        /**
         * 会话失效时刻（epoch 毫秒）；`null` 表示**暂无失效计划**。
         *
         * 语义与参考应用一致（方案 S-7）：**面板关闭后**才开始计时。
         * 面板开着时它是 `null`（面板上不显示倒计时，也不会自动停服——
         * 用户正盯着地址卡片，突然停了是最糟的体验）。
         */
        val expiresAt: Long? = null,

        /**
         * 一次性的会话令牌（只有 HTTP 会给出）。用于让**扫码即用**成立。
         *
         * ## 为什么需要它
         * 默认不匿名，所以 PC 打开网页会撞上 HTTP Basic 的登录框 —— 而用户手上
         * 只有一个二维码，没有任何可以填进去的用户名密码。参考应用之所以"扫码即用"
         * 成立，是因为它默认匿名；我们不跟（方案 §10.5 明确要求默认不开匿名）。
         *
         * 于是补一条等价但更安全的路径：二维码里带 `?t=<token>`，
         * 服务端认出这个令牌后**下发一个 Cookie**，之后页面里的
         * `fetch('/api/list')` 等同源请求自动带上它。用户扫一下就进，
         * 而令牌字符串不会像 Basic 那样在**每一个**请求里重复出现。
         *
         * 令牌每次启动重新生成，随会话结束作废。
         */
        val ticket: String? = null,
    ) : ServerState {

        override val isRunning: Boolean get() = true
    }

    data class Error(val reason: ShareError) : ServerState

    val isRunning: Boolean get() = this is Running

    /** 状态色点 / 通知图标用的短标签。 */
    val label: String
        get() = when (this) {
            Stopped -> "已停止"
            Starting -> "启动中"
            is Running -> "运行中"
            is Error -> "启动失败"
        }
}
