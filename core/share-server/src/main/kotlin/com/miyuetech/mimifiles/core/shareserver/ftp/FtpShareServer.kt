package com.miyuetech.mimifiles.core.shareserver.ftp

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.network.LanAddress
import com.miyuetech.mimifiles.core.shareserver.AuthMode
import com.miyuetech.mimifiles.core.shareserver.ServerConfig
import com.miyuetech.mimifiles.core.shareserver.ServerType
import com.miyuetech.mimifiles.core.shareserver.ShareAddress
import com.miyuetech.mimifiles.core.shareserver.ShareScope
import com.miyuetech.mimifiles.core.shareserver.ShareServer
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.Listener
import org.apache.ftpserver.listener.ListenerFactory

/**
 * FTP 服务端（方案 S-2）。
 *
 * ## 关于中文文件名：这一版**刻意没有**做编码开关
 * 方案 §10.1 要求"同款匿名/账密/端口/编码配置 + 中文文件名乱码排障提示"。
 * 落地前把 Apache FtpServer 1.2.1 的字符集路径整条查了一遍，结论是那个开关
 * **在本库上做不出来，而且不做也不会乱码**：
 *
 * | 环节 | 1.2.1 的实现 |
 * |---|---|
 * | 控制通道（`USER`/`CWD`/`RETR` 里的路径名） | `TextLineDecoder(StandardCharsets.UTF_8)`，硬编码 |
 * | 应答编码 | `FtpResponseEncoder` 的 `ThreadLocal<CharsetEncoder>` = `UTF_8`，硬编码 |
 * | `LIST` / `MLSD` 的响应体 | `IODataConnection.transferToClient(…, String)` 用 `OutputStreamWriter(out, UTF_8)`，硬编码 |
 *
 * 三者都由 `NioListener.start()` 里的 `new FtpServerProtocolCodecFactory()` 现场实例化；
 * 要换字符集就得把 `NioListener.start()` 整个重写（它同时装配 MINA 的
 * `ExecutorFilter`、`MdcInjectionFilter`、`MimaSessionFilter`、`FtpHandlerAdapter`，
 * 其中若干个是包私有类型）。为了一个**实际不会发生**的问题复刻 60 行 MINA 装配
 * 是负收益。而"中文乱码"在 Windows 资源管理器（Win7+）上本来就不会出现：
 * 它会先发 `OPTS UTF8 ON` 再按 UTF-8 读目录，正好对上本库的行为。
 *
 * 所以 `ServerConfig` 上**没有** `encoding` 字段。理由：一个拨了没反应的开关
 * 比没有这个开关更糟——用户会以为功能坏了。这条偏离已回写到方案档。
 *
 * ## 关于"不能重启"
 * `DefaultFtpServer.stop()` 会把内部的 `serverContext` 置 `null`，之后再 `start()`
 * 直接抛 `IllegalStateException("FtpServer has been stopped. Restart is not supported")`。
 * 所以 [start] **每次都重建一整套** `FtpServerFactory` → `createServer()`，
 * 而不是复用实例。这条不写下来一定会有人"顺手优化"成字段复用，然后遇到
 * "第二次启动必失败"。
 *
 * ## 多个地址 = 多个 Listener
 * 与 HTTP 侧同理（见 [com.miyuetech.mimifiles.core.shareserver.http.HttpShareServer]）：
 * `ListenerFactory.setServerAddress(host)` 把每个监听绑到自己那张网卡上，
 * 而不是 `0.0.0.0`。区别是 FTP 的 `PASV` 应答里带的地址取自会话的服务端地址，
 * 所以多监听还顺带解决了"PC 连的是热点地址、PASV 却回了 WiFi 地址"这类连不上的问题。
 */
class FtpShareServer : ShareServer {

    override val type: ServerType = ServerType.FTP

    private var server: FtpServer? = null

    override val isRunning: Boolean get() = server?.isStopped == false

    override suspend fun start(
        config: ServerConfig,
        scope: ShareScope,
        port: Int,
        hosts: List<LanAddress>,
    ): List<ShareAddress> {
        if (isRunning) stop()
        require(hosts.isNotEmpty()) { "FTP share requires at least one bind address" }

        val factory = FtpServerFactory()

        // ⚠️ 必须用 `setListeners` **整体替换**，不能用 `addListener` 追加。
        //
        // `DefaultFtpServerContext` 的构造函数里已经塞了一个名为 "default" 的监听器
        // （端口 21、通配地址），而 `FtpServerFactory.addListener` 是往那张表里**加**——
        // 于是服务端启动时会先去 bind `[::]:21`，在 Android 上因为 1024 以下需要
        // `CAP_NET_BIND_SERVICE` 而立刻失败，整台服务端跟着起不来。
        // 这条是 M4 真机/模拟器联调时抓到的：错误信息是
        // `Failed to bind to address ::/[::]:21`，而我们明明设的是 2121。
        val listeners = LinkedHashMap<String, Listener>(hosts.size)
        hosts.forEachIndexed { index, host ->
            val listenerFactory = ListenerFactory().apply {
                setServerAddress(host.host)
                setPort(port)
                setIdleTimeout(IDLE_TIMEOUT_SECONDS)
                setDataConnectionConfiguration(
                    DataConnectionConfigurationFactory().apply {
                        setIdleTime(DATA_IDLE_TIMEOUT_SECONDS)
                        // 主动模式（PORT）保持开启：个别老式客户端只会用主动模式。
                        // 局域网里它没有 NAT 穿透问题，而关掉它会让那些客户端完全不可用。
                        setActiveEnabled(true)
                        // ── 固定被动端口段 ──────────────────────────────────
                        // 不设的话，PASV 会用内核随机给的临时端口，带来两个实际后果：
                        //  ① 任何位于手机与 PC 之间的防火墙/路由器策略都无法预先放行；
                        //  ② **PASV 应答里带的地址是本机 IP**，而客户端若在 NAT 之后
                        //     就够不到它（`--ftp-skip-pasv-ip` 这类开关正是为此存在）。
                        // 固定成一小段之后，排障时"该放行哪些端口"是个确定答案，
                        // 而 10 条并发数据连接对"一台 PC 的资源管理器"绰绰有余。
                        setPassivePorts(PASSIVE_PORTS)
                    }.createDataConnectionConfiguration(),
                )
            }
            // 监听名只是内部键，但会进日志；用网卡名更好排查（"哪个地址没起来"一眼可见）。
            listeners["lan-$index-${host.interfaceName.ifBlank { "if" }}"] = listenerFactory.createListener()
        }
        factory.setListeners(listeners)

        factory.setUserManager(FtpUserManager(config))
        factory.setFileSystem(ShareFileSystemFactory(scope))
        factory.setConnectionConfig(
            ConnectionConfigFactory().apply {
                // 8 条并发会话：一台 PC 的资源管理器会开 2~4 条（控制 + 若干数据），
                // 两个人同时连也够用；再高只会让 SMB 后端更吃紧（见 FileSystemBridge 的闸门）。
                setMaxLogins(MAX_LOGINS)
                setMaxAnonymousLogins(MAX_LOGINS)
                setAnonymousLoginEnabled(config.auth is AuthMode.Anonymous)
                // 失败 3 次延迟 1 秒：局域网里暴力猜密码本就意义不大，
                // 但"零延迟无限试"是明确不该提供的。
                setMaxLoginFailures(MAX_LOGIN_FAILURES)
                setLoginFailureDelay(LOGIN_FAILURE_DELAY_SECONDS)
                setMaxThreads(MAX_LOGINS + 2)
            }.createConnectionConfig(),
        )

        val created = factory.createServer()
        try {
            created.start()
        } catch (e: Exception) {
            // `DefaultFtpServer.start()` 自己会回收**已经起来的**监听器再抛出，
            // 但 `serverContext` 还挂着；显式 stop 一次把它 dispose 掉，
            // 否则每次失败的启动都会漏下一个线程池与若干 MINA 资源。
            runCatching { created.stop() }
            MimiLog.w(TAG, "FTP 监听启动失败：${e.message}")
            throw e
        }
        server = created

        val addresses = hosts.map { host ->
            ShareAddress(ServerType.FTP, host.host, port, host.label)
        }
        addresses.forEach { MimiLog.i(TAG, "FTP 监听已启动：${it.interfaceLabel} ${it.host}:$port") }
        return addresses
    }

    override suspend fun stop() {
        val current = server ?: return
        server = null
        runCatching { current.stop() }
            .onFailure { MimiLog.w(TAG, "停止 FTP 监听失败：${it.message}") }
        MimiLog.i(TAG, "FTP 监听已停止")
    }

    companion object {
        private const val TAG = "MimiShareFtp"

        /** 控制连接空闲超时。5 分钟足够覆盖"用户在 PC 上翻目录、发呆一会儿"。 */
        private const val IDLE_TIMEOUT_SECONDS = 300

        /** 数据连接（PASV 后等待连接建立）超时。比控制连接短：卡住的数据连接没有等待价值。 */
        private const val DATA_IDLE_TIMEOUT_SECONDS = 60

        private const val MAX_LOGINS = 8
        private const val MAX_LOGIN_FAILURES = 3
        private const val LOGIN_FAILURE_DELAY_SECONDS = 1

        /**
         * 被动模式的端口段（10 条并发数据连接）。
         *
         * 取 40000 起而不是 49152 起的标准临时端口段：让"手机开的这几个端口"
         * 与"系统随手分配的临时端口"在 `netstat` 里一眼可分——排障时这一点很值。
         */
        const val PASSIVE_PORTS = "40000-40009"
    }
}
