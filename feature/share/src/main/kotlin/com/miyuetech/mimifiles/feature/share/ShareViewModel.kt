package com.miyuetech.mimifiles.feature.share

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miyuetech.mimifiles.core.bootstrap.Backends
import com.miyuetech.mimifiles.core.datastore.SettingsRepository
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.shareserver.AuthMode
import com.miyuetech.mimifiles.core.shareserver.ServerConfig
import com.miyuetech.mimifiles.core.shareserver.ServerState
import com.miyuetech.mimifiles.core.shareserver.ServerType
import com.miyuetech.mimifiles.core.shareserver.ShareAddress
import com.miyuetech.mimifiles.core.shareserver.ShareEngine
import com.miyuetech.mimifiles.core.shareserver.ShareRequest
import com.miyuetech.mimifiles.core.shareserver.http.QrCodeUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * 面板的界面状态。
 *
 * 刻意**不做成**若干条独立的可观察字段，而是一个不可变快照：面板上
 * "状态点颜色 / 主按钮文字 / 地址卡显隐 / 提示卡内容"之间是有耦合的
 * （未启动时地址卡不该显示二维码、只读时不该显示匿名警告…）。
 * 分成多条流之后，这些耦合会以"某一帧看到 A 变了但 B 还没变"的形式泄漏到界面上，
 * 表现为一闪而过的错位。一个快照天然没有这个问题。
 */
data class ShareUiState(
    val type: ServerType = ServerType.HTTP,
    val server: ServerState = ServerState.Stopped,
    /** 共享根的文本形式，直接显示在"共享路径"一行。 */
    val rootText: String = "",
    /** 是否只分享了选中项（`visibleNames != null`）。 */
    val filteredCount: Int = 0,
    val readOnly: Boolean = false,
    val anonymous: Boolean = false,
    val keepScreenOn: Boolean = false,
    val credentials: AuthMode.Credentials? = null,
    /** 二维码（仅 HTTP、运行中、有地址时非空）。 */
    val qr: Bitmap? = null,
    val tipKinds: List<ShareTipKind> = emptyList(),
    /** 该协议上次用过的端口（0 表示用推荐值）。 */
    val port: Int = 0,
) {
    val running: Boolean get() = server.isRunning

    val starting: Boolean get() = server is ServerState.Starting

    val addresses: List<ShareAddress>
        get() = (server as? ServerState.Running)?.addresses.orEmpty()

    /** 主按钮是不是"停止"。启动中时也算，避免用户重复点。 */
    val primaryIsStop: Boolean get() = running || starting

    /** 实际生效的端口（面板上显示这个）。 */
    val effectivePort: Int
        get() = (server as? ServerState.Running)?.port ?: port.takeIf { it > 0 } ?: type.preferredPort
}

/** 排障提示的种类。文案在 Activity 里用 string 资源渲染（同 [ShareErrorText] 的理由）。 */
enum class ShareTipKind { AP_ISOLATION, WINDOWS_FTP, NOTIFICATION_DENIED }

/**
 * 分享面板的 ViewModel。
 *
 * ## 它**不持有**服务端的生命周期
 * 所有启停都转给 [ShareEngine]：那是一个进程级单例，面板被划掉、被系统回收、
 * 转屏重建都不会影响它。ViewModel 在这里的角色是"把用户的操作翻译成引擎调用，
 * 再把引擎的状态 + 本地开关合成一帧可渲染的快照"。
 *
 * 这么切之后有一个直接可验证的好处：**转屏不会重启服务端**。
 * 如果启停挂在 ViewModel（更别说 Activity）上，转屏就是一次 stop + start，
 * 而 PC 上正在下传的文件会断——那是最典型的"用着用着就断了"。
 *
 * ## 状态为什么用"手动重算"而不是 `combine` 五路
 * 参与快照的输入有九个（协议 / 引擎状态 / 请求 / 三个开关 / 凭据 / 二维码 /
 * 提示 / 端口）。`combine` 能表达的层数有限，硬凑成嵌套 Triple 之后，
 * "哪一路变化会触发重算"就变得不可读，而漏掉一路的表现是"某个开关点了界面没反应"。
 * 这里改成"任何输入变化时调用一次 [recompute]"，规则只有一条、一眼可查。
 */
class ShareViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)

    private val type = MutableStateFlow(ShareSessionHolder.lastType())

    /**
     * 当前待分享内容。取值顺序：**本视图的入参** → **会话记忆**。
     *
     * 第一项来自"用户这次是怎么进来的"（选中项 / 当前目录，由 [SharePanelActivity]
     * 在 `onCreate` 里写进 [ShareRequestHolder]）；第二项是"上次进来时想分享什么"
     * （[ShareSessionHolder]）。两者在"从通知点回面板"这条路径上会分叉 ——
     * 那条路径不带任何 extras，只有会话记忆能给出一个有意义的值。
     */
    private var request: ShareRequest? = ShareRequestHolder.current ?: ShareSessionHolder.pending()
    private var readOnly = false
    private var anonymous = false
    private var keepScreenOn = false
    private var credentials = AuthMode.Credentials(
        // 与 `ServerConfig.defaultFor` 同源地生成，避免"面板显示的密码"与
        // "实际写进配置的密码"是两份不同的随机值。
        user = DEFAULT_USER,
        password = ServerConfig.randomPassword(),
    )
    private var notificationAllowed = true
    private var qr: Bitmap? = null
    private var port = 0

    /**
     * 会话有效期覆盖值（毫秒）。`null` = 用 [ServerConfig.sessionTtlMillis] 的默认值。
     * 只有冒烟测试会设它，理由见 [ShareArgs.EXTRA_TTL_MS]。
     */
    private var ttlOverrideMs: Long? = null

    private val _ui = MutableStateFlow(ShareUiState())
    val ui: StateFlow<ShareUiState> = _ui.asStateFlow()

    /** 一次性消息（吐司）。值是资源 id 或错误串的键，由 Activity 翻成文案。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _pathErrors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pathErrors: SharedFlow<String> = _pathErrors.asSharedFlow()

    init {
        ShareEngine.install(app)
        // 后端装配是幂等的；编辑路径时要 stat 一次看它是否存在，所以必须先装好。
        runCatching { Backends.install(app) }

        viewModelScope.launch {
            ShareEngine.state.collect { state ->
                // 服务端一旦开始跑，"事实来源"就变成引擎里的请求：
                // 界面上显示的路径必须与 PC 上真正看到的目录一致。
                if (state.isRunning) ShareEngine.request()?.let { request = it }
                if (!state.isRunning) qr = null
                recompute()
            }
        }

        viewModelScope.launch {
            settings.shareKeepScreenOn.collect { keepScreenOn = it; recompute() }
        }

        viewModelScope.launch {
            type.flatMapLatest { t -> settings.sharePort(t.name) }.collect { port = it; recompute() }
        }

        recompute()
    }

    // ── 用户操作 ────────────────────────────────────────────────────────

    /**
     * 切换协议 Tab。
     *
     * 运行中切 Tab 会**自动重启**服务端（与参考应用一致）。不这么做的话，
     * 用户会看到一个"HTTP 已停止 / FTP 未启动"的中间态，还得再点一次启动——
     * 而他的意图明明只是"换成 FTP"。
     */
    fun selectType(next: ServerType) {
        if (type.value == next) return
        val wasRunning = ShareEngine.isRunning()
        type.value = next
        ShareSessionHolder.rememberType(next)
        recompute()
        if (wasRunning) {
            viewModelScope.launch {
                stopServer("switch_type")
                startServer()
            }
        }
    }

    /** 底部主按钮。 */
    fun togglePrimary() {
        if (_ui.value.primaryIsStop) {
            viewModelScope.launch { stopServer("panel") }
        } else {
            viewModelScope.launch { startServer() }
        }
    }

    fun setReadOnly(value: Boolean) {
        if (readOnly == value) return
        readOnly = value
        recompute()
        restartIfRunning()
    }

    fun setAnonymous(value: Boolean) {
        if (anonymous == value) return
        anonymous = value
        recompute()
        restartIfRunning()
    }

    fun setKeepScreenOn(value: Boolean) {
        keepScreenOn = value
        recompute()
        viewModelScope.launch { settings.setShareKeepScreenOn(value) }
    }

    /**
     * 换一个密码。
     *
     * 运行中换密码**必须重启**：凭据是在服务端启动时交给用户管理器的
     * （见 `FtpUserManager`，它读的就是构造时那份 config）。不重启的话，
     * 面板上显示新密码、PC 端还得用旧密码，用户会以为密码坏了。
     */
    fun randomizePassword() {
        credentials = AuthMode.Credentials(DEFAULT_USER, ServerConfig.randomPassword())
        recompute()
        restartIfRunning()
    }

    /** 覆写会话有效期（仅冒烟测试）。在 [startServer] 之前调用。 */
    fun setSessionTtl(ttlMillis: Long?) {
        ttlOverrideMs = ttlMillis?.takeIf { it > 0 }
    }

    fun setNotificationAllowed(allowed: Boolean) {
        notificationAllowed = allowed
        recompute()
    }

    /**
     * 修改共享路径。
     *
     * 接受两种写法（这是为了**非程序员用户**）：
     *  - 完整形式 `local:///sdcard/Movies`；
     *  - 手机里的普通路径 `/sdcard/Movies` —— 自动补成 `local://` 形式。
     *
     * 校验三件事：能解析、真实存在、是目录。任何一条不过就回一句人话，
     * 而不是让服务端启动时才失败（那时用户已经离开这个对话框了）。
     */
    fun setPath(raw: String) {
        viewModelScope.launch {
            val parsed = parseUserPath(raw)
            if (parsed == null) {
                _pathErrors.tryEmit(KEY_PATH_FORMAT)
                return@launch
            }
            val entry = runCatching { FileSystems.registry.resolve(parsed).stat(parsed) }.getOrNull()
            if (entry == null || !entry.isDirectory) {
                _pathErrors.tryEmit(KEY_PATH_MISSING)
                return@launch
            }
            request = ShareRequest(root = parsed, visibleNames = null)
            ShareRequestHolder.current = request
            recompute()
            if (ShareEngine.isRunning()) {
                // 路径变了但服务端还开着：旧的共享根必须一起换掉，
                // 否则界面显示新路径、PC 上仍是旧目录。
                stopServer("path_changed")
                startServer()
            }
        }
    }

    /** 面板可见 / 不可见 —— 驱动引擎里那个 5 分钟倒计时。 */
    fun onPanelVisible() = ShareEngine.onPanelOpened()

    fun onPanelHidden() = ShareEngine.onPanelClosed()

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun restartIfRunning() {
        if (!ShareEngine.isRunning()) return
        viewModelScope.launch {
            stopServer("config_changed")
            startServer()
        }
    }

    private suspend fun startServer() {
        val req = request
        if (req == null) {
            _messages.tryEmit(KEY_NO_REQUEST)
            return
        }
        val current = type.value
        val state = ShareEngine.start(current, buildConfig(current), req)
        if (state.isRunning) {
            // 服务端起来**之后**才拉前台服务：前台服务必须在应用处于前台时启动，
            // 而这一刻面板就在眼前；反过来先拉服务会撞上
            // "startForegroundService 后约 5 秒内必须 startForeground"的硬约束
            // （那时引擎还没开始跑，没有地址可显示）。
            ShareForegroundService.start(getApplication())
            _messages.tryEmit(KEY_STARTED)
        }
        recompute()
    }

    private suspend fun stopServer(reason: String) {
        // ⚠️ 这里**故意不调** `ShareForegroundService.stop()`，只用引擎的同步停止。
        //
        // 那个方法发的是一个 `Intent`（ACTION_STOP）——**异步投递**。而它在
        // `restartIfRunning()` 里的用法是"停一下，马上按新配置重启"。两者叠在
        // 一起就是一个真实的竞态：Intent 在 `startServer()` **之后**才被服务处理，
        // 于是它把刚刚起来的新服务端又关掉了。表现在用户与冒烟脚本眼里是
        // "改了只读/匿名之后，分享就再也连不上了"（端口已释放/连接被重置），
        // 而日志里只有一对看起来完全正常的"停止 → 启动"。
        //
        // 去掉之后，"停止"这件事只有一个同步入口（`ShareEngine.stop`），
        // 而前台服务完全跟随引擎状态：引擎变成 `Stopped` → 服务观察到
        // `everRunning && !running` → 自己撤通知并 `stopSelf()`。
        // 通知按钮上的 ACTION_STOP 仍然保留（那条路径是用户明确的"停"，
        // 不存在紧随其后的重启）。
        ShareEngine.stop(reason)
        qr = null
        recompute()
    }

    private fun buildConfig(current: ServerType): ServerConfig {
        val base = ShareEngine.defaultConfig(current)
        return base.copy(
            port = if (port > 0) port else current.preferredPort,
            auth = if (anonymous) AuthMode.Anonymous else credentials,
            readOnly = readOnly,
            deviceName = DEVICE_NAME,
            sessionTtlMillis = ttlOverrideMs ?: base.sessionTtlMillis,
        )
    }

    /**
     * 刷新二维码。
     *
     * 只在"HTTP + 运行中 + 有地址"时生成；其余情况清空。`ticket` 拼进查询串，
     * 扫码后服务端会下发会话 Cookie —— 这样用户不必在浏览器登录框里手输密码
     * （见 [com.miyuetech.mimifiles.core.shareserver.ServerState.Running.ticket]）。
     */
    private fun refreshQr() {
        val state = ShareEngine.state.value as? ServerState.Running
        if (state == null || type.value != ServerType.HTTP || state.addresses.isEmpty()) {
            qr = null
            return
        }
        val address = state.addresses.first()
        val content = state.ticket?.let { "${address.url}?t=$it" } ?: address.url
        qr = QrCodeUtil.encode(content)
    }

    private fun refreshTips(): List<ShareTipKind> {
        val list = ArrayList<ShareTipKind>(2)
        if (ShareEngine.isRunning()) {
            // 运行中才给"连不上"的排障提示：没启动时用户看到的应该是
            // "怎么启动"，而不是"连不上怎么办"。
            list += ShareTipKind.AP_ISOLATION
            if (type.value == ServerType.FTP) list += ShareTipKind.WINDOWS_FTP
        }
        if (!notificationAllowed) list += ShareTipKind.NOTIFICATION_DENIED
        return list
    }

    /** 把全部输入合成一帧。任何输入变化都必须调用它——这是本类唯一的规则。 */
    private fun recompute() {
        refreshQr()
        _ui.value = ShareUiState(
            type = type.value,
            server = ShareEngine.state.value,
            rootText = request?.root?.toString().orEmpty(),
            filteredCount = request?.visibleNames?.size ?: 0,
            readOnly = readOnly,
            anonymous = anonymous,
            keepScreenOn = keepScreenOn,
            credentials = if (anonymous) null else credentials,
            qr = qr,
            tipKinds = refreshTips(),
            port = port,
        )
    }

    private fun parseUserPath(raw: String): FsPath? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 已经带 scheme 的按原样解析
        FsPath.parse(text)?.let { return it }
        // 普通手机路径：补 scheme。
        if (text.startsWith("/")) return FsPath.parse("local://$text")
        return null
    }

    companion object {
        const val DEFAULT_USER = "mimi"

        /**
         * 设备名。
         *
         * 不带机型：这个串会出现在 PC 浏览器的标题与 FTP 欢迎语里，
         * 而 `Build.MODEL` 在部分 ROM 上是内部代号（"canoe"、"OP4F3F"），
         * 用户看到的是一串看不懂的东西。固定成产品名更清爽，
         * 面板上已经有地址卡负责"是哪台设备"的辨识。
         */
        const val DEVICE_NAME = "密密文件管理"

        // 消息键（由 Activity 翻成 string 资源）
        const val KEY_PATH_FORMAT = "path_format"
        const val KEY_PATH_MISSING = "path_missing"
        const val KEY_NO_REQUEST = "no_request"
        const val KEY_STARTED = "started"
    }
}

/**
 * 当前待分享请求的**进程级**暂存。
 *
 * 为什么 Intent extras 不够：面板可能被"通知点开"或被系统重建，
 * 那时 Intent 里根本没有 extras。放在进程级对象里，至少能保住
 * "从通知进来仍能看到刚才分享的是什么"。
 *
 * 与 [ShareSessionHolder] 的分工：后者管**用户意图**（下次进来想分享什么），
 * 这里管**当前视图的入参**。两者多数时候相同，但"运行中改了路径"这类情况下
 * 只有这里是最新的。
 */
internal object ShareRequestHolder {
    var current: ShareRequest? = null
}
