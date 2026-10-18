package com.miyuetech.mimifiles.core.shareserver.http

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.network.LanAddress
import com.miyuetech.mimifiles.core.shareserver.AuthMode
import com.miyuetech.mimifiles.core.shareserver.ServerConfig
import com.miyuetech.mimifiles.core.shareserver.ServerType
import com.miyuetech.mimifiles.core.shareserver.ShareAddress
import com.miyuetech.mimifiles.core.shareserver.ShareScope
import com.miyuetech.mimifiles.core.shareserver.ShareServer
import fi.iki.elonen.NanoHTTPD
import java.net.BindException
import java.net.SocketException

/**
 * HTTP 网页分享的服务端（方案 S-1）。
 *
 * ## 为什么一个地址一个实例，而不是绑 `0.0.0.0`
 * `NanoHTTPD(hostname, port)` 会把监听套接字绑到**指定地址**，于是可以
 * "有几张网卡就起几个实例、都用同一个端口"。这比绑通配地址多写十几行，
 * 换来的是两件事：
 *
 *  1. **不越界暴露**：手机同时连着 WiFi 与蜂窝时，绑 `0.0.0.0` 会让运营商网络
 *     也能连进来；而我们的分享面板从头到尾只宣称"局域网"。**能连**与**宣称能连**
 *     必须一致，否则就是把用户没同意的东西暴露出去；
 *  2. **地址卡与实现一致**：面板上列几个地址，就真的只有那几个能连上。
 *     只绑 `0.0.0.0` 的话，列表与实际可达集合是脱节的（VPN 地址也列出来、
 *     还真能连上）。
 *
 * ## 停止：必须逐个停，且不能因为一个失败就漏掉其余的
 * 多实例意味着释放要遍历。哪怕中间某个 `stop()` 抛了，也必须继续停后面的——
 * 否则会留下一个**没人持有引用、却仍在监听端口**的线程与套接字，
 * 用户看到的是"面板显示已停止，但 PC 还能连上"，而这在安全语义上是明确的失败。
 */
class HttpShareServer : ShareServer {

    override val type: ServerType = ServerType.HTTP

    private val instances = ArrayList<NanoHTTPD>(2)

    /**
     * 本次会话的一次性令牌。每次 [start] 重新生成，随停止作废。
     *
     * 用 `SecureRandom` 而不是 `Random`：它守的是"谁能免密进这个网页"，
     * 而 `java.util.Random` 的输出在拿到两个连续值后可以被推出来。
     * 16 字节（128 位）足够抵御在线爆破，而它只会出现在二维码里，长度不是问题。
     */
    override var ticket: String? = null
        private set

    override val isRunning: Boolean get() = instances.isNotEmpty()

    override suspend fun start(
        config: ServerConfig,
        scope: ShareScope,
        port: Int,
        hosts: List<LanAddress>,
    ): List<ShareAddress> {
        if (isRunning) stop()
        require(hosts.isNotEmpty()) { "HTTP share requires at least one bind address" }

        // 匿名模式不需要令牌：没有密码要"免"。
        val session = if (config.auth is AuthMode.Credentials) newTicket() else null
        ticket = session
        val handler = HttpShareHandler(config, scope, config.deviceName, session)
        val bound = ArrayList<ShareAddress>(hosts.size)
        try {
            for (host in hosts) {
                val server = ShareNanoHttpd(host.host, port, handler)
                server.start(SOCKET_READ_TIMEOUT_MS, true)
                instances += server
                val actual = server.listeningPort.takeIf { it > 0 } ?: port
                bound += ShareAddress(ServerType.HTTP, host.host, actual, host.label)
                MimiLog.i(TAG, "HTTP 监听已启动：${host.label} ${host.host}:$actual")
            }
        } catch (e: Exception) {
            // 起了一半就失败：必须把已经起来的收回去。否则那几个端口一直被占着，
            // 用户点"重试"永远失败（因为他自己上一次的残留还占着端口）。
            MimiLog.w(TAG, "HTTP 监听启动失败，回收已起的实例：${e.message}")
            stop()
            throw e
        }
        return bound
    }

    override suspend fun stop() {
        for (server in instances) {
            // 逐个 try/catch 而不是整体一 try：见类注释"停止"一条。
            runCatching { server.stop() }
                .onFailure { MimiLog.w(TAG, "停止 HTTP 监听失败：${it.message}") }
        }
        instances.clear()
        // 令牌随停止作废：下一次启动会生成新的。留着旧的等于给一个已经关掉的服务
        // 留一把还能用的钥匙（虽然那时已经没人监听，但"不再有效的凭据仍被持有"
        // 本身就不该发生）。
        ticket = null
        MimiLog.i(TAG, "HTTP 监听已全部停止")
    }

    /**
     * 把 `serve()` 转给共享的 [handler]。
     *
     * handler 是无状态的（它只持 config 与 scope，都是不可变引用），
     * 所以多个实例共用一份即可——不需要每个网卡一份，也就不会出现
     * "两个地址看到的状态不一致"。
     */
    private class ShareNanoHttpd(
        host: String,
        port: Int,
        private val handler: HttpShareHandler,
    ) : NanoHTTPD(host, port) {

        override fun serve(session: IHTTPSession): Response = handler.handle(session)
    }

    companion object {

        private const val TAG = "MimiShareHttp"

        /**
         * Socket 读超时。
         *
         * NanoHTTPD 默认 5 秒。对**上传**来说太短：PC 通过 WiFi 往手机 NAS 上传一个
         * 大文件时，网络一次拥塞就可能超过 5 秒没有新数据，于是连接被判定超时中断，
         * 而用户的观感是"传了一半就失败"。30 秒是这个场景与"不要让空闲连接长期占线程"
         * 之间的折中（keep-alive 的空闲连接最多占 30 秒）。
         */
        private const val SOCKET_READ_TIMEOUT_MS = 30_000

        /** 令牌字节数。128 位随机量：在线爆破不可行，同时短到二维码不会因此变密。 */
        private const val TICKET_BYTES = 16

        /**
         * 生成令牌。
         *
         * 输出用 URL 安全字符（Base64 去 padding 后替换 `+/`）——它要出现在
         * 二维码链接的查询串里，标准 Base64 的 `+` 会被表单编码规则解成空格
         * （见 [HttpShareHandler] 顶部"关于 `+` 被当成空格"），到时候
         * "扫了码却提示需要登录"会极难排查。
         */
        private fun newTicket(): String {
            val bytes = ByteArray(TICKET_BYTES)
            java.security.SecureRandom().nextBytes(bytes)
            return android.util.Base64
                .encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        }
    }
}

/**
 * 把绑定失败翻译成 [BindException] 之外的语义。
 *
 * 单独留一个扩展点是因为：本机探测端口时用的是 `ServerSocket`（无 SO_REUSEADDR），
 * 而 NanoHTTPD 的 `start()` 会先 `setReuseAddress(true)` 再 bind。这个差异意味着
 * 理论上存在"探测说空闲、实际 bind 失败"的窗口（例如同一个端口被两个进程同时抢）。
 * 目前直接让 [java.io.IOException] 冒到 [com.miyuetech.mimifiles.core.shareserver.ShareEngine]
 * 去翻成 `ShareError.BindFailed`，这个函数留作将来需要更细分类时的落点。
 */
internal fun Throwable.asBindFailure(port: Int): Boolean =
    this is BindException || (this is SocketException && message?.contains("Cannot assign") == true)
