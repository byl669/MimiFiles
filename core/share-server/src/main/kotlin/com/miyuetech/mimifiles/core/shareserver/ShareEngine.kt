package com.miyuetech.mimifiles.core.shareserver

import android.content.Context
import android.os.Build
import com.miyuetech.mimifiles.core.common.AppScope
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.network.LanAddress
import com.miyuetech.mimifiles.core.network.LanInterfaces
import com.miyuetech.mimifiles.core.shareserver.ftp.FtpShareServer
import com.miyuetech.mimifiles.core.shareserver.http.HttpShareServer
import java.net.BindException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 无线分享的**唯一状态持有者与唯一入口**。
 *
 * ## 为什么是一个进程级单例
 * 分享服务端的生命周期与任何单个界面都不同步：面板可以关掉、Activity 可以重建、
 * 前台服务可以活着也可以被系统回收——而"端口上有没有一个监听"是**进程级**的事实。
 * 把它挂在 Activity/ViewModel 上，就会出现"面板关掉后服务还在跑、但没人能停它"
 * 或者"Activity 重建后又起了一个监听、旧端口被占住"这类问题。
 * 参考应用也是这么做的（它的服务端与界面完全解耦）。
 *
 * ## 职责边界
 * [ShareEngine] 负责：挑地址 → 校验共享根 → 挑端口 → 创建并启动 [ShareServer] →
 * 持有唯一的 [ServerState] → 管理会话有效期。
 * [ShareServer] 实现负责：照着给定端口与地址起监听、停监听。
 *
 * 这样切之后，"FTP 与 HTTP 的状态是否一致"这个问题从根上不存在了——只有一份状态。
 *
 * ## 与前台服务的关系
 * Engine **不启动通知**，也不知道通知的存在。原因是 Android 要求前台服务必须在
 * 应用处于前台时被启动，而"是谁在什么时候拉起服务"是界面层的决策
 * （面板点「启动」按钮时）。Engine 只暴露 [state]，由
 * `feature:share` 的 `ShareForegroundService` 去观察并渲染成通知。
 */
object ShareEngine {

    private const val TAG = "MimiShare"

    private var appContext: Context? = null

    private val bridge = FileSystemBridge(FileSystems.registry)

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)

    /** 当前状态。面板、通知、QS Tile 全部观察这一个流。 */
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var server: ShareServer? = null

    private var activeConfig: ServerConfig? = null

    private var activeScope: ShareScope? = null

    private var activeRequest: ShareRequest? = null

    /** 面板可见计数。用计数而不是布尔：面板可能有一瞬间同时有两个实例（转屏重建）。 */
    private var panelRefs = 0

    private var expiryJob: Job? = null

    /** 幂等安装。各处（App 启动、面板、服务）都可以放心调用。 */
    fun install(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            MimiLog.i(TAG, "ShareEngine 已安装（device=${defaultDeviceName()}）")
        }
    }

    /** 当前生效的配置（未启动时为 `null`）。 */
    fun config(): ServerConfig? = activeConfig

    /** 当前的文件视图（供面板显示共享根、只读态）。 */
    fun scope(): ShareScope? = activeScope

    /** 当前待分享的请求（面板重开时用它回填"路径"一行）。 */
    fun request(): ShareRequest? = activeRequest

    /** 正在跑的服务端类型。 */
    fun runningType(): ServerType? = server?.type

    fun isRunning(): Boolean = _state.value.isRunning

    /**
     * 启动分享。
     *
     * 失败**不抛异常**，而是把失败原因写进 [state]（[ServerState.Error]）。
     * 理由：调用方是界面，"启动失败"是它必须渲染的一种正常结果，而不是异常路径；
     * 用异常表达会让每个调用点都写一遍 try/catch，且很容易漏掉某个分支让面板卡在
     * "启动中"。
     */
    suspend fun start(type: ServerType, config: ServerConfig, request: ShareRequest): ServerState {
        if (_state.value is ServerState.Starting) return _state.value
        // 换类型/换配置一律先停旧的：同时只跑一个服务端（与参考应用一致），
        // 否则会出现"FTP 还开着、面板已经切到 HTTP"这种用户无法理解的暴露面。
        stopInternal(reason = "restart")

        _state.value = ServerState.Starting
        activeConfig = config
        activeRequest = request

        val hosts = resolveHosts(config)
        if (hosts.isEmpty()) {
            MimiLog.w(TAG, "启动失败：没有局域网地址")
            return fail(ShareError.NoLanAddress)
        }

        // 共享根必须先存在。分享期间目录被删是很常见的（用户自己在手机上把它删了），
        // 提前问一次能让错误在面板上以"路径不存在"出现，而不是在 PC 端表现为
        // 空目录或 550。
        val rootEntry = try {
            bridge.stat(request.root)
        } catch (e: FsException) {
            MimiLog.w(TAG, "启动失败：共享根不可用 ${request.root} —— ${e.message}")
            return fail(ShareError.RootUnavailable(e.message ?: request.root.toString()))
        }
        if (!rootEntry.isDirectory && request.visibleNames == null) {
            // 单文件分享（visibleNames == null 且根不是目录）不在本期范围：
            // FTP 暴露一个文件当根在协议上是别扭的（PWD 该显示什么？）。
            // 面板本身也只从目录进入。
            MimiLog.w(TAG, "启动失败：共享根不是目录 ${request.root}")
            return fail(ShareError.RootUnavailable("共享路径必须是文件夹"))
        }

        // 共享根是网络存储时把并发压到 2（方案 §10.5「共享 SMB 目录为根」）。
        bridge.setParallelism(FileSystemBridge.parallelismFor(request.root))

        val scope = try {
            ShareScope.create(bridge, request, config.readOnly)
        } catch (e: FsException) {
            return fail(ShareError.RootUnavailable(e.message ?: "无法读取共享路径"))
        }
        activeScope = scope

        val port = pickPort(config, type)
            ?: return fail(ShareError.PortsExhausted(config.resolvedPreferredPort(type), SharePorts.DEFAULT_ATTEMPTS))

        val created: ShareServer = when (type) {
            ServerType.HTTP -> HttpShareServer()
            ServerType.FTP -> FtpShareServer()
        }

        val addresses = try {
            created.start(config, scope, port, hosts)
        } catch (e: BindException) {
            MimiLog.w(TAG, "绑定失败（端口 $port 可能被抢占）：${e.message}")
            return fail(ShareError.BindFailed(port, e.message ?: "端口被占用"))
        } catch (e: Exception) {
            MimiLog.e(TAG, "启动 $type 分享失败", e)
            return fail(ShareError.BindFailed(port, e.message ?: e.javaClass.simpleName))
        }

        server = created
        _state.value = ServerState.Running(
            addresses = addresses,
            since = System.currentTimeMillis(),
            port = addresses.firstOrNull()?.port ?: port,
            // 面板这会儿是开着的（用户刚点的启动），所以暂时不排失效时间。
            expiresAt = null,
            // 只有 HTTP 会给令牌（见 ShareServer.ticket）。面板用它拼二维码地址，
            // 让"扫码即用"不必先过一道 HTTP Basic 登录框。
            ticket = created.ticket,
        )
        MimiLog.i(TAG, "$type 分享已启动：${addresses.joinToString { it.hostPort }}（根=${request.root}，只读=${config.readOnly}）")
        // 扫码地址里的令牌是**给二维码用的**，会展示给整间屋子的人，因此它不是密钥；
        // 但仍然只在 DEBUG 级记录：`MimiLog.mirrorToLogcat` 在 release 上是关的，
        // 所以这一行不会出现在正式包的 logcat 里。冒烟脚本靠它验证"扫码即用"。
        created.ticket?.let { token ->
            addresses.firstOrNull()?.let { MimiLog.d(TAG, "扫码地址 ${it.url}?t=$token") }
        }
        return _state.value
    }

    /** 停止。幂等：没在跑时什么都不做。 */
    suspend fun stop(reason: String = "user") {
        stopInternal(reason)
        _state.value = ServerState.Stopped
    }

    private suspend fun stopInternal(reason: String) {
        expiryJob?.cancel()
        expiryJob = null
        val current = server
        server = null
        activeScope = null
        if (current != null) {
            MimiLog.i(TAG, "停止分享（原因：$reason）")
            runCatching { current.stop() }
                .onFailure { MimiLog.w(TAG, "停止失败：${it.message}") }
        }
    }

    // ── 会话有效期（方案 S-7）───────────────────────────────────────────

    /**
     * 面板变为可见。
     *
     * 面板开着时**不会**自动停服：用户正盯着二维码或地址卡，服务突然消失是最糟的
     * 体验，而且他会以为功能不稳定。
     */
    fun onPanelOpened() {
        panelRefs++
        expiryJob?.cancel()
        expiryJob = null
        val running = _state.value as? ServerState.Running ?: return
        if (running.expiresAt != null) _state.value = running.copy(expiresAt = null)
    }

    /**
     * 面板不再可见 —— **从这一刻开始计时**（方案 S-7 的语义）。
     *
     * 前台服务会继续把服务端保活到超时，所以"关掉面板再打开浏览器"这个最常见的
     * 动作用法不会被打断：PC 那边还在下载时，5 分钟远大于一次传输。
     */
    fun onPanelClosed() {
        panelRefs = (panelRefs - 1).coerceAtLeast(0)
        if (panelRefs > 0) return
        val config = activeConfig ?: return
        val running = _state.value as? ServerState.Running ?: return
        val ttl = config.sessionTtlMillis
        if (ttl <= 0L) return
        val deadline = System.currentTimeMillis() + ttl
        _state.value = running.copy(expiresAt = deadline)
        expiryJob?.cancel()
        expiryJob = AppScope.scope.launch {
            delay(ttl)
            if (_state.value is ServerState.Running) {
                MimiLog.i(TAG, "分享会话已过期（${ttl / 1000}s），自动停止")
                stop(reason = "expired")
            }
        }
    }

    /**
     * 网络变化时刷新地址。
     *
     * 只刷新 [ServerState.Running.addresses] 的**展示**，不动监听：已经起来的监听
     * 绑定的是具体地址，切换到另一个 WiFi 后旧地址不再可达，此时"地址卡片显示过期
     * 地址"比"悄无声息地重启服务"安全得多——后者会让正在传输的连接全部断开。
     * 面板会用新的地址集合覆盖显示，用户点一次重启即可。
     */
    fun refreshAddresses() {
        val running = _state.value as? ServerState.Running ?: return
        val config = activeConfig ?: return
        val hosts = resolveHosts(config)
        if (hosts.isEmpty()) return
        val live = running.addresses.mapNotNull { old ->
            hosts.firstOrNull { it.host == old.host }?.let { host ->
                old.copy(interfaceLabel = host.label)
            }
        }
        val added = hosts.filter { host -> running.addresses.none { it.host == host.host } }
            .map { ShareAddress(running.addresses.firstOrNull()?.protocol ?: ServerType.HTTP, it.host, running.port, it.label) }
        val next = live + added
        if (next != running.addresses) {
            MimiLog.i(TAG, "网络变化，地址列表刷新：${next.joinToString { it.hostPort }}")
            _state.value = running.copy(addresses = next)
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun resolveHosts(config: ServerConfig): List<LanAddress> {
        val all = LanInterfaces.ipv4()
        // `bindLanOnly` 关掉时把 VPN/公网地址也一并监听。默认是关的，
        // 因为把服务暴露到运营商网络上不该是"顺手就发生"的事（方案 §10.4「权限一致」）。
        return if (config.bindLanOnly) all.filter { it.isPrivate } else all
    }

    private fun pickPort(config: ServerConfig, type: ServerType): Int? {
        val preferred = config.resolvedPreferredPort(type)
        // 用户显式给了端口就尊重它（端口被占时向上找）；填 0 表示"随便"。
        SharePorts.findFree(preferred)?.let { return it }
        MimiLog.w(TAG, "端口 $preferred 起连续 ${SharePorts.DEFAULT_ATTEMPTS} 个候选都被占用，改用系统分配")
        return runCatching { SharePorts.pickEphemeral() }.getOrNull()
    }

    private fun fail(reason: ShareError): ServerState {
        val state = ServerState.Error(reason)
        _state.value = state
        return state
    }

    private fun defaultDeviceName(): String {
        val model = runCatching { Build.MODEL }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Android"
        return "密密文件管理 · $model"
    }

    /** 供面板构造默认配置。 */
    fun defaultConfig(type: ServerType): ServerConfig =
        ServerConfig.defaultFor(type).copy(deviceName = defaultDeviceName())

    /** 仅测试用：清空状态，避免用例之间互相污染。 */
    internal suspend fun resetForTest() {
        stopInternal("test")
        _state.value = ServerState.Stopped
        activeConfig = null
        activeRequest = null
        panelRefs = 0
    }
}
