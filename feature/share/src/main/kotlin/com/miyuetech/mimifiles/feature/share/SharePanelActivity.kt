package com.miyuetech.mimifiles.feature.share

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miyuetech.mimifiles.core.shareserver.ServerState
import com.miyuetech.mimifiles.core.ui.R as CoreR
import com.miyuetech.mimifiles.feature.share.databinding.ActivitySharePanelBinding
import com.miyuetech.mimifiles.feature.share.databinding.DialogSharePathBinding
import kotlinx.coroutines.launch

/**
 * 无线分享面板（方案 §10.4「入口一致 / 面板一致」的唯一落点）。
 *
 * ## 单向数据流
 * 与设置页同样的约定：控件**不自己保存状态**，一律从 [ShareViewModel.ui] 回填；
 * 用户操作只调 ViewModel。这样"界面上显示的"与"真正生效的"永远是同一份数据，
 * 不会出现"开关显示开着、服务端其实是只读"这类漂移。
 *
 * ## 面板可见性驱动会话有效期
 * `onStart` / `onStop` 里通知引擎（方案 S-7：面板关闭后 5 分钟失效）。
 * 用 `onStart/onStop` 而不是 `onResume/onPause`：转屏、切到浏览器查一次密码
 * 都会触发 `onPause`，但那些时刻用户**并没有离开分享这件事**，
 * 从那时开始倒计时会让"边看面板边在 PC 上操作"变得很紧张。
 * `onStop` 才是"真的不在这个界面了"。
 *
 * ## 两个主动作都在 ViewModel 里
 * 启停、换密码、改路径全部转调 [ShareViewModel]；Activity 只负责
 * "把状态画出来 + 把点击转出去"。这一条让"切 Tab 自动重启""改路径自动重启"
 * 这类联动逻辑只存在一处，不会因为两个入口各写一遍而分叉。
 */
class SharePanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySharePanelBinding

    private val viewModel: ShareViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 拒绝也继续：分享服务端照样能跑，只是没有通知栏入口（方案 §10.5 的降级路径）。
        viewModel.setNotificationAllowed(granted)
        if (granted) viewModel.togglePrimary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharePanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ShareSessionHolder.rememberType(ShareSessionHolder.lastType())

        // 入参优先级：Intent extras（本次进入的选中项）> 进程里暂存的请求
        // > 会话记忆。这样"从通知点进来"也能显示上一次分享的是什么。
        ShareArgs.readRequest(
            root = intent?.getStringExtra(ShareArgs.EXTRA_ROOT),
            selected = intent?.getStringArrayExtra(ShareArgs.EXTRA_SELECTED),
            selectedIsDir = intent?.getBooleanExtra(ShareArgs.EXTRA_SELECTED_IS_DIR, false) ?: false,
        )?.let { req ->
            ShareRequestHolder.current = req
            ShareSessionHolder.remember(req, ShareSessionHolder.lastType())
        }
        ShareArgs.readType(intent?.getStringExtra(ShareArgs.EXTRA_TYPE))?.let {
            ShareSessionHolder.rememberType(it)
        }
        // 仅供冒烟：把会话有效期压到秒级，好让"面板关闭 → 超时 → 自动停服 → 撤通知"
        // 这条端到端链路能在几秒内被验证（理由见 `ShareArgs.EXTRA_TTL_MS`）。
        intent?.let { i ->
            if (i.hasExtra(ShareArgs.EXTRA_TTL_MS)) {
                viewModel.setSessionTtl(i.getLongExtra(ShareArgs.EXTRA_TTL_MS, 0L))
            }
        }

        setupToolbar()
        setupTabs()
        setupSwitches()
        setupActions()
        setupAddressCard()
        observe()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onPanelVisible()
    }

    override fun onStop() {
        viewModel.onPanelHidden()
        super.onStop()
    }

    // ── 骨架 ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupTabs() {
        binding.tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val type = when (checkedId) {
                R.id.tab_ftp -> com.miyuetech.mimifiles.core.shareserver.ServerType.FTP
                else -> com.miyuetech.mimifiles.core.shareserver.ServerType.HTTP
            }
            viewModel.selectType(type)
        }
    }

    private fun setupSwitches() {
        // ⚠️ 回填时不要重新触发写入：见 `bindingState` 的说明。
        binding.switchReadonly.setOnCheckedChangeListener { _, checked ->
            if (bindingState) return@setOnCheckedChangeListener
            viewModel.setReadOnly(checked)
        }
        binding.switchAnonymous.setOnCheckedChangeListener { _, checked ->
            if (bindingState) return@setOnCheckedChangeListener
            viewModel.setAnonymous(checked)
        }
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            if (bindingState) return@setOnCheckedChangeListener
            viewModel.setKeepScreenOn(checked)
        }
    }

    /** 回填控件时置位，挡掉随之而来的监听回调（同 `SettingsActivity`）。 */
    private var bindingState = false

    private fun setupActions() {
        binding.actionPrimary.setOnClickListener {
            if (needsNotificationPermission()) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.togglePrimary()
            }
        }
        binding.actionNewPassword.setOnClickListener { viewModel.randomizePassword() }
        binding.infoPathEdit.setOnClickListener { showPathDialog() }
        binding.infoCredCopy.setOnClickListener { copyCredentials() }
        binding.tipAction.setOnClickListener { requestNotificationPermissionOrOpenSettings() }
    }

    private fun setupAddressCard() {
        // 地址卡内部自带复制与打开行为；这里只接一个回调，用于让冒烟脚本
        // 能从日志里确认"用户点了复制"（纯取证用途，无业务副作用）。
        binding.addressCard.onCopy = { address, text ->
            com.miyuetech.mimifiles.core.common.MimiLog.i(
                TAG,
                "复制地址 ${address.hostPort}（${text.length} 字符）",
            )
        }
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { state -> render(state) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { key -> renderMessage(key) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pathErrors.collect { key ->
                    // 路径错误用吐司而不是在"共享路径"那一行上做红色标注：那个字段
                    // 在对话框关掉之后是一个只读的显示行，把错误挂在它上面会让用户
                    // 找不到"改哪里"。
                    toast(
                        when (key) {
                            ShareViewModel.KEY_PATH_MISSING -> getString(R.string.share_dialog_path_missing)
                            else -> getString(R.string.share_dialog_path_invalid)
                        },
                    )
                }
            }
        }
    }

    private fun render(state: ShareUiState) {
        bindingState = true

        // Tab
        val tabId = if (state.type == com.miyuetech.mimifiles.core.shareserver.ServerType.FTP) {
            R.id.tab_ftp
        } else {
            R.id.tab_http
        }
        if (binding.tabs.checkedButtonId != tabId) binding.tabs.check(tabId)

        // 状态
        val (statusText, dotColorRes) = when (val server = state.server) {
            ServerState.Stopped -> getString(R.string.share_state_stopped) to CoreR.color.status_stopped
            ServerState.Starting -> getString(R.string.share_state_starting) to CoreR.color.status_stopped
            is ServerState.Running -> getString(R.string.share_state_running) to CoreR.color.status_running
            is ServerState.Error -> getString(R.string.share_state_error) to CoreR.color.status_error
        }
        binding.statusText.text = statusText
        binding.statusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, dotColorRes)

        binding.statusDetail.text = when (val server = state.server) {
            is ServerState.Running -> {
                val port = getString(R.string.share_info_port, server.port)
                server.expiresAt?.let { deadline ->
                    val minutes = ((deadline - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    "$port · " + getString(R.string.share_ttl_remaining, minutes)
                } ?: "$port · " + getString(
                    R.string.share_ttl_notice,
                    ShareEngineTtlMinutes,
                )
            }

            is ServerState.Error -> ShareErrorText.of(this, server.reason)
            else -> ""
        }

        // 分享信息
        binding.infoPath.text = state.rootText.ifEmpty { "—" }
        binding.infoFilter.isVisible = state.filteredCount > 0
        if (state.filteredCount > 0) {
            binding.infoFilter.text = getString(R.string.share_info_filtered, state.filteredCount)
        }
        val credentials = state.credentials
        binding.infoCredRow.isVisible = credentials != null
        binding.infoAnonymousWarning.isVisible = credentials == null
        if (credentials != null) {
            binding.infoCred.text = "${credentials.user} / ${credentials.password}"
        }
        binding.infoAccess.text = getString(
            if (state.readOnly) R.string.share_info_readonly else R.string.share_info_writable,
        )

        // 地址卡（FTP / HTTP 共用）
        binding.addressCard.bind(
            addresses = state.addresses,
            protocol = state.type,
            credentials = credentials,
            qr = state.qr,
        )

        // 开关
        binding.switchReadonly.isChecked = state.readOnly
        binding.switchAnonymous.isChecked = state.anonymous
        binding.switchKeepScreenOn.isChecked = state.keepScreenOn
        // 运行中改这些会触发重启，所以允许改；但"换密码"在匿名模式下没有意义。
        binding.actionNewPassword.isVisible = !state.anonymous

        // 主按钮
        binding.actionPrimary.text = getString(
            when {
                state.starting -> R.string.share_starting
                state.running -> R.string.share_stop
                else -> R.string.share_start
            },
        )
        binding.actionPrimary.setIconResource(
            if (state.primaryIsStop) R.drawable.ic_stop else R.drawable.ic_play_arrow,
        )

        // 提示
        renderTips(state)

        // 常亮（方案 S-8）
        if (state.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        bindingState = false
    }

    private fun renderTips(state: ShareUiState) {
        if (state.tipKinds.isEmpty()) {
            binding.tipsCard.isVisible = false
            return
        }
        binding.tipsCard.isVisible = true
        val text = state.tipKinds.joinToString("\n\n") { kind ->
            when (kind) {
                ShareTipKind.AP_ISOLATION -> getString(R.string.share_tip_ap_isolation)
                ShareTipKind.WINDOWS_FTP -> getString(R.string.share_tip_firewall)
                ShareTipKind.NOTIFICATION_DENIED -> getString(R.string.share_tip_notification)
            }
        }
        binding.tipText.text = text
        binding.tipAction.isVisible = ShareTipKind.NOTIFICATION_DENIED in state.tipKinds
    }

    private fun renderMessage(key: String) {
        val text = when (key) {
            ShareViewModel.KEY_STARTED -> getString(R.string.share_msg_started)
            ShareViewModel.KEY_NO_REQUEST -> getString(R.string.share_error_root_unavailable, "—")
            else -> key
        }
        toast(text)
    }

    // ── 对话框与权限 ────────────────────────────────────────────────────

    /**
     * 共享路径编辑。
     *
     * 刻意**直接给文本输入**而不是做一个目录选择器：这个字段的合法取值是
     * 任意 [com.miyuetech.mimifiles.core.model.FsPath]（本机 / SMB / WebDAV），
     * 而做一个覆盖三种后端的可视化选择器要复用整个浏览页。折中是
     * "接受普通手机路径并自动补 scheme"（见 `ShareViewModel.setPath`），
     * 让最常见的那类输入不需要懂 `local://` 这种写法。
     */
    private fun showPathDialog() {
        val dialogBinding = DialogSharePathBinding.inflate(layoutInflater)
        dialogBinding.pathInput.setText(viewModel.ui.value.rootText)
        // 打开对话框时就把光标放到末尾：用户多半是在现有路径上改一层目录，
        // 直接续写比先手动点到末尾省一步。
        dialogBinding.pathInput.setSelection(dialogBinding.pathInput.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_dialog_path_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.share_dialog_ok) { _, _ ->
                viewModel.setPath(dialogBinding.pathInput.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.share_dialog_cancel, null)
            .create()
        dialog.show()
    }

    private fun copyCredentials() {
        val credentials = viewModel.ui.value.credentials ?: return
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(
            // 只复制密码：用户名固定是 `mimi`（屏幕上就写着），而密码是那串
            // "得一个字一个字抄"的东西。复制整行会让用户还得自己删掉前半截。
            android.content.ClipData.newPlainText("password", credentials.password),
        )
        toast(getString(R.string.share_msg_password_copied))
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionOrOpenSettings() {
        if (needsNotificationPermission()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // 已经拒绝过一次：再弹系统框不会被显示，只能引导去设置页。
        toast(getString(R.string.share_tip_notification))
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MimiSharePanel"

        /** 面板上那句"面板关闭 N 分钟后自动停止"里的 N。与 `ServerConfig` 的默认值同源。 */
        const val ShareEngineTtlMinutes = 5
    }
}
