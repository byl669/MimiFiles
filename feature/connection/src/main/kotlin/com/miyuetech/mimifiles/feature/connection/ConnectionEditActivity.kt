package com.miyuetech.mimifiles.feature.connection

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.datastore.ConnectionRepository
import com.miyuetech.mimifiles.core.datastore.PasswordUpdate
import com.miyuetech.mimifiles.core.datastore.SaveOutcome
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fssmb.SmbAddress
import com.miyuetech.mimifiles.core.fssmb.SmbOptionKeys
import com.miyuetech.mimifiles.core.fssmb.SmbSettings
import com.miyuetech.mimifiles.core.fswebdav.WebDavOptionKeys
import com.miyuetech.mimifiles.core.fswebdav.WebDavSettings
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.ui.enableEdgeToEdge
import com.miyuetech.mimifiles.core.ui.padForSystemBars
import com.miyuetech.mimifiles.feature.connection.databinding.ActivityConnectionEditBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 新建 / 编辑一条 SMB 或 WebDAV 连接。
 *
 * ## 一个页面承载两种协议
 * SMB 与 WebDAV 的字段集**大部分重叠**（主机、端口、账号、密码），差异只有三处：
 * SMB 要共享名与域，WebDAV 要基准路径。为这点差异开两个页面，会让"我想改成别的协议"
 * 变成一个"先删再建"的动作，还会逼出一份重复的校验与测试逻辑。
 *
 * 所以做成一个页面 + 类型单选：切类型只切**可见性**，已填的字段全部保留。
 * 用户从 SMB 切到 WebDAV 看一眼又切回来，不会发现自己的输入被吃掉。
 *
 * ## 密码的三种意图
 * 密码框留空**不等于**清空密码，而是"不改动库里那份"（见 [PasswordUpdate]）。
 * 真正要删掉已保存的密码，走溢出菜单里的显式入口——同一个"清空输入框"的动作
 * 不可能同时表达这两件事。
 *
 * ## 为什么本页声明 `configChanges` 而浏览页不声明
 * 浏览页的形态（导航窗格宽度、紧凑行高、状态栏显隐）全靠资源限定符在**布局填充时**
 * 解析，必须重建才能换形态。本页两种朝向共用同一份布局、没有任何按朝向变化的资源，
 * 重建的唯一后果是丢掉用户填了一半的表单。所以这里横竖屏切换保留视图。
 */
class ConnectionEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionEditBinding

    private lateinit var repository: ConnectionRepository

    /** 编辑态从库里读出的原记录；新建态为 `null`。 */
    private var loaded: ConnectionProfile? = null

    /** 库里是否存着这条连接的密码。决定密码框下方的提示文案。 */
    private var hasStoredPassword = false

    /**
     * 表单是否已上屏并开始接受输入。
     *
     * [fillForm] 会往控件里写值，那些写入会触发文本监听器。用一个开关把它们与
     * "用户真的改了东西"区分开——否则一进编辑页就自动处于"有未保存修改"状态，
     * 用户什么都没碰，返回时却被问"要放弃修改吗"。
     */
    private var ready = false

    private var dirty = false

    /** 有异步操作在跑（载入 / 测试）。期间禁用保存，避免把半空表单写进库。 */
    private var busy = false

    private val editingId: String?
        get() = intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdge()
        // 顶栏吃掉状态栏的高度、底栏吃掉手势条的高度，中间的滚动区不额外加 padding
        // （它已经夹在两者之间，加了两头就多出一段空白）。
        binding.toolbar.padForSystemBars(top = true, bottom = false)
        binding.bottomBar.padForSystemBars(top = false, bottom = true)
        padRootForIme()

        repository = ConnectionRepository(this)

        val isNew = editingId == null
        binding.toolbar.title =
            getString(if (isNew) R.string.connection_new_title else R.string.connection_edit_title)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.inflateMenu(R.menu.connection_edit)
        binding.toolbar.menu.findItem(R.id.action_clear_password)?.isVisible = !isNew
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_password) {
                clearStoredPassword()
                true
            } else {
                false
            }
        }

        binding.btnAdvanced.setOnClickListener { toggleAdvanced() }
        binding.btnTest.setOnClickListener { runTest() }
        binding.btnSave.setOnClickListener { save() }

        installBackGuard()

        if (isNew) {
            // 新建：SMB 是局域网场景的绝大多数，默认选它，让最常见的一条路径少一次点击。
            binding.typeSmb.isChecked = true
            // 开关初值必须和 `SmbSettings.signing` 的默认值（true）**同源**。
            //
            // 之前这里没设，而 `MaterialSwitch` 默认是未勾选：保存时把 `signing=false`
            // 写进库，于是**每一条新建的 SMB 连接都不要求签名**——在 SMB3 服务端上
            // 直接连不上（smbj 不允许 SMB3 下关闭签名），而用户从头到尾没碰过这个开关。
            // 默认值分家是最难查的一类 bug：两边单看都对，合起来不对。
            binding.switchSigning.isChecked = true
            applyScheme(ConnectionFormats.SCHEME_SMB)
            binding.advancedPanel.isVisible = false
            updateAdvancedButton()
            updatePasswordHelper()
            startWatching()
        } else {
            loadExisting(editingId!!)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 载入
    // ══════════════════════════════════════════════════════════════════════

    private fun loadExisting(id: String) {
        // 载入期间先把按钮禁掉：用户完全可能在数据到位前就点保存，
        // 那会把一张半空表单写进库，覆盖掉真实配置——而且是静默的。
        setBusy(true)
        lifecycleScope.launch {
            // 整段包在 try/finally 里：中途任何一步抛异常，也要把按钮放回来
            // （理由见 `runTest`）。`setBusy(false)` 仍然放在**填完表单之后**，
            // 否则会出现"按钮已可点、表单还没填"的窗口，正好落进上面注释说的那个坑。
            try {
                val profile = repository.byId(id)
                if (profile == null) {
                    Toast.makeText(this@ConnectionEditActivity, R.string.connection_missing, Toast.LENGTH_SHORT)
                        .show()
                    finish()
                    return@launch
                }
                // 顺带探一次密码在不在。解密失败时 Repository 会就地清掉坏密文，
                // 所以下面这个 false 是有意义的"确实没有密码"，而不是"读失败了"。
                hasStoredPassword = repository.passwordOf(id) != null
                loaded = profile
                fillForm(profile)
                startWatching()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun fillForm(profile: ConnectionProfile) {
        when (profile.scheme) {
            ConnectionFormats.SCHEME_WEBDAV -> binding.typeWebdav.isChecked = true
            else -> binding.typeSmb.isChecked = true
        }
        applyScheme(profile.scheme)

        binding.inputLabel.setText(profile.displayName)
        binding.inputHost.setText(profile.host)
        binding.inputPort.setText(if (profile.port > 0) profile.port.toString() else "")
        binding.inputUsername.setText(profile.username.orEmpty())
        binding.inputDomain.setText(profile.domain.orEmpty())

        // 密码**不回填**。
        // 它本来就在加密库里躺着，需要时后端自己去取；把明文摆到屏幕上只是
        // 给截屏和旁人递机会，换不来任何便利——用户要改就重打一遍，要留就留空。
        binding.inputPassword.setText("")

        if (profile.scheme == ConnectionFormats.SCHEME_SMB) {
            val smb = SmbSettings.parse(profile.options)
            binding.inputShare.setText(profile.rootPath)
            binding.switchAnonymous.isChecked = smb.anonymous
            binding.switchSigning.isChecked = smb.signing
            binding.switchForceSmb1.isChecked = smb.forceSmb1
            // 默认编码留空表示"用默认"，不把 "UTF-8" 写进框里——框里有字会让人
            // 以为这里有个需要维护的设置项，而它其实是"没设置"。
            binding.inputEncoding.setText(
                if (smb.encoding == SmbOptionKeys.DEFAULT_ENCODING) "" else smb.encoding,
            )
        } else {
            val dav = WebDavSettings.parse(profile.options)
            binding.inputRootPath.setText(profile.rootPath)
            binding.switchAllowInsecure.isChecked = dav.allowInsecure
            // 指纹回填**原始值**而不是规范化后的值：用户可能按浏览器里那样带冒号，
            // 回显成我们归一化过的样子会让他以为自己填错了。
            binding.inputFingerprint.setText(profile.options[WebDavOptionKeys.TRUST_FINGERPRINT].orEmpty())
        }

        // 编辑已有连接时默认展开高级区。
        // 收起来的话，库里已经存着的签名开关、编码、证书指纹这些设置用户看不见，
        // 他会以为"这条连接没有任何高级配置"，出问题时也就不会想到来这儿看。
        binding.advancedPanel.isVisible = true
        updateAdvancedButton()
        updatePasswordHelper()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 协议切换 / 高级区
    // ══════════════════════════════════════════════════════════════════════

    private fun currentScheme(): String =
        if (binding.typeGroup.checkedRadioButtonId == R.id.type_webdav) ConnectionFormats.SCHEME_WEBDAV else ConnectionFormats.SCHEME_SMB

    /**
     * 切换协议：只改**可见性**，不动任何已填的值。
     *
     * 这与 [collectOptions] 的分支并不矛盾——那边会按最终协议清掉另一协议的专属键，
     * 因为往库里写一个属于另一种协议的参数才是真的错（切换回来发现"WebDAV 的
     * 明文开关"还在 SMB 连接的参数里，会被后端读出来）。界面上的值留在控件里
     * 只是为了让用户切回去时还在。
     */
    private fun applyScheme(scheme: String) {
        val smb = scheme == ConnectionFormats.SCHEME_SMB

        binding.boxShare.isVisible = smb
        binding.boxDomain.isVisible = smb
        binding.boxRootPath.isVisible = !smb

        binding.switchAnonymous.isVisible = smb
        binding.switchSigning.isVisible = smb
        binding.hintSigning.isVisible = smb
        binding.switchForceSmb1.isVisible = smb
        binding.boxEncoding.isVisible = smb

        binding.switchAllowInsecure.isVisible = !smb
        binding.hintAllowInsecure.isVisible = !smb
        binding.boxFingerprint.isVisible = !smb
    }

    private fun toggleAdvanced() {
        binding.advancedPanel.isVisible = !binding.advancedPanel.isVisible
        updateAdvancedButton()
    }

    private fun updateAdvancedButton() {
        binding.btnAdvanced.setText(
            if (binding.advancedPanel.isVisible) R.string.connection_advanced_collapse
            else R.string.connection_advanced,
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // 变更追踪与返回确认
    // ══════════════════════════════════════════════════════════════════════

    private fun startWatching() {
        FORM_TEXT_FIELDS.forEach { field ->
            field(binding).doAfterTextChanged { markDirty() }
        }
        listOf(
            binding.switchAnonymous,
            binding.switchSigning,
            binding.switchForceSmb1,
            binding.switchAllowInsecure,
        ).forEach { switch ->
            switch.setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        binding.typeGroup.setOnCheckedChangeListener { _, checkedId ->
            applyScheme(if (checkedId == R.id.type_webdav) ConnectionFormats.SCHEME_WEBDAV else ConnectionFormats.SCHEME_SMB)
            markDirty()
        }

        ready = true
        dirty = false
    }

    private fun markDirty() {
        if (ready) dirty = true
    }

    private fun installBackGuard() {
        onBackPressedDispatcher.addCallback(this) {
            if (!dirty) {
                finish()
                return@addCallback
            }
            AlertDialog.Builder(this@ConnectionEditActivity)
                .setTitle(R.string.connection_discard_title)
                .setMessage(R.string.connection_discard_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.connection_discard_confirm) { _, _ -> finish() }
                .show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 / 保存
    // ══════════════════════════════════════════════════════════════════════

    private fun runTest() {
        if (busy) return
        val profile = collectProfile() ?: return
        val typed = passwordText()
        setBusy(true)
        lifecycleScope.launch {
            // ⚠️ 这里的 `finally` 不是装饰。
            //
            // `setBusy(true)` 会把「测试 / 保存 / 高级选项」三个按钮**一起**置灰，
            // 而**只有** `setBusy(false)` 能把它们放回来。M2 实测踩到过一次
            // WebDAV 客户端的死锁（见 `WebDavFileSystem.sardine`）：协程永久挂起，
            // 这一行再也执行不到，页面从此失去全部交互能力 —— 而屏幕上一切正常，
            // 用户能看到的只有"按钮点了没反应、保存也不回列表"。
            // 所以解除禁用必须落在这里，而不是跟在正常路径后面。
            val outcome = try {
                // 密码框有内容就用新的（用户在验证"改过的密码能不能连"），空着就取库里那份。
                // 这是本页最有价值的一个动作：在离开表单之前就知道到底能不能连，
                // 而不是保存、返回、点进目录、失败、再回来。
                val password = if (typed.isNotEmpty()) typed else repository.passwordOf(loaded?.id.orEmpty())
                ConnectionTester.test(profile, password)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                // 后端理论上已把所有失败收成 TestOutcome，走到这里说明漏了一种。
                // 给一句可读的失败，总好过让三个按钮永远变灰。
                TestOutcome.Failure(t.message ?: t.javaClass.simpleName, retryable = true)
            } finally {
                setBusy(false)
            }
            val message = when (outcome) {
                is TestOutcome.Success -> getString(R.string.connection_test_success, outcome.detail)
                is TestOutcome.Failure -> getString(R.string.connection_test_failed, outcome.reason)
            }
            // 把结论同时记一条日志。理由有两个，都不是"为了好看"：
            //  1. **用户报"点了测试没反应"时能拿到现场**——Snackbar 只存在约 3.5 s，
            //     而 `uiautomator` 会把提示条判为不可见（它的宿主在界面快照里是一个
            //     倒置矩形，dump 直接跳过），所以自动化测试与截图都取不到这段文案。
            //  2. 让端到端冒烟可以断言"文案是**可读**的"（非空、且不是"未知错误"），
            //     而这条验收标准光靠"界面上有没有东西闪一下"证明不了。
            MimiLog.i(CONN_TAG, "connection test → $message")
            Snackbar.make(binding.bottomBar, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun save() {
        if (busy) return
        val profile = collectProfile() ?: return
        val password = collectPassword()
        setBusy(true)
        lifecycleScope.launch {
            // 同 runTest：解除禁用必须在 `finally` 里，否则一次异常就让本页永久失去交互。
            val outcome = try {
                repository.save(profile, password)
            } finally {
                setBusy(false)
            }
            if (outcome is SaveOutcome.Duplicate) {
                confirmOverwrite(profile, password, outcome.existing)
            } else {
                finishWith(outcome)
            }
        }
    }

    private fun confirmOverwrite(
        profile: ConnectionProfile,
        password: PasswordUpdate,
        existing: ConnectionProfile,
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.connection_duplicate_title)
            .setMessage(
                getString(
                    R.string.connection_duplicate_message,
                    ConnectionFormats.fallbackLabel(existing),
                    ConnectionFormats.endpoint(existing),
                ),
            )
            .setNegativeButton(R.string.connection_duplicate_back, null)
            .setPositiveButton(R.string.connection_duplicate_overwrite) { _, _ ->
                setBusy(true)
                lifecycleScope.launch {
                    val outcome = try {
                        repository.save(profile, password, overwriteExisting = true)
                    } finally {
                        setBusy(false)
                    }
                    finishWith(outcome)
                }
            }
            .show()
    }

    /**
     * 收尾：释放被顶掉的旧实例、提示、关闭本页。
     *
     * 是 `suspend` 的，因为 [FileSystems.registry] 的 `release` 要等待会话真正断开
     * （SMB 得发 LOGOFF、关 socket）。这一步不能丢在后台不管——句柄留着，
     * 那个 authority 会更久地占着连接。
     */
    private suspend fun finishWith(outcome: SaveOutcome) {
        // 覆盖保存顶掉的那条记录，它在注册表里可能还挂着一条活会话（连同旧凭据）。
        // 不释放的话，被顶掉的那个 authority 会继续存在到进程结束。
        outcome.replaced?.let { FileSystems.registry.release(FsPath.of(it.scheme, it.id)) }

        val message = when (outcome) {
            is SaveOutcome.Saved ->
                getString(R.string.connection_saved, ConnectionFormats.fallbackLabel(outcome.profile))

            is SaveOutcome.SavedWithoutPassword -> getString(R.string.connection_saved_no_password)

            // 已经处理过重复分支才会走到这里，留着是为了 sealed 穷尽时不漏项。
            is SaveOutcome.Duplicate -> return
        }
        // 用 Toast 而不是 Snackbar：本页马上 finish，Snackbar 会跟着窗口一起消失，
        // 用户根本看不到"保存成功了没有"。
        Toast.makeText(
            this,
            message,
            if (outcome is SaveOutcome.SavedWithoutPassword) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()

        dirty = false
        finish()
    }

    /**
     * 清除已保存的密码。
     *
     * 写回的是 [loaded] 而不是当前表单：[loaded] 是库里的原样，用它保存等价于
     * "把这一行原封不动再写一遍，只是密码列清空"。若改用表单内容，用户会看到
     * 一个自己没按过保存按钮的隐藏写入，而且写进去的还是半成品。
     */
    private fun clearStoredPassword() {
        val profile = loaded ?: return
        if (busy) return
        lifecycleScope.launch {
            repository.save(profile, PasswordUpdate.Clear)
            hasStoredPassword = false
            updatePasswordHelper()
            Snackbar.make(binding.bottomBar, R.string.connection_password_cleared, Snackbar.LENGTH_SHORT)
                .show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 表单 ⇄ 模型
    // ══════════════════════════════════════════════════════════════════════

    /** 校验并组装。[校验失败] 时把错误挂到对应的输入框上并返回 `null`。 */
    private fun collectProfile(): ConnectionProfile? {
        binding.boxHost.error = null
        binding.boxPort.error = null
        binding.boxShare.error = null

        val scheme = currentScheme()

        val hostRaw = text(binding.inputHost)
        if (hostRaw.isEmpty()) {
            binding.boxHost.error = getString(R.string.connection_error_host_required)
            binding.inputHost.requestFocus()
            return null
        }
        // ⚠️ 服务器地址要先清洗（SMB 才有意义；WebDAV 可能真的用 `host/path` 形态的 base）。
        // 用户从 Windows 地址栏整段粘过来是**最常见**的输入，而 `\\192.168.1.100\stock`
        // 被当成主机名去解析的结果是"连不上"，一个把原因完全藏起来的说法。
        // 顺带把地址里带的共享名接过去 —— 用户既然已经写了，就不该再让他抄一遍。
        val parsedHost = if (scheme == ConnectionFormats.SCHEME_SMB) SmbAddress.parse(hostRaw) else null
        val host = parsedHost?.host?.takeIf { it.isNotEmpty() } ?: hostRaw
        if (host.isEmpty()) {
            binding.boxHost.error = getString(R.string.connection_error_host_required)
            binding.inputHost.requestFocus()
            return null
        }

        val portRaw = text(binding.inputPort)
        val port = if (portRaw.isEmpty()) {
            // 0 表示"没指定"，交给后端按协议决定（SMB 445 / WebDAV 80·443）。
            // 这里不替用户猜一个数字填回去——猜错了会变成"连不上但看不出为什么"。
            // 例外：地址里**明确写了**端口（`192.168.1.100:4450`）时用它，
            // 那是用户自己写下的，比默认值可信。
            parsedHost?.port ?: ConnectionProfile.PORT_DEFAULT
        } else {
            val parsed = portRaw.toIntOrNull()
            if (parsed == null || parsed !in 1..65535) {
                binding.boxPort.error = getString(R.string.connection_error_port)
                binding.inputPort.requestFocus()
                return null
            }
            parsed
        }

        val rootPath = if (scheme == ConnectionFormats.SCHEME_SMB) {
            val typed = text(binding.inputShare)
            // 共享名空着时用地址里剥出来的那段（`\\192.168.1.100\stock` 的 `stock`）。
            val share = typed.ifEmpty { parsedHost?.share.orEmpty() }
            if (share.isEmpty()) {
                binding.boxShare.error = getString(R.string.connection_error_share_required)
                binding.inputShare.requestFocus()
                return null
            }
            // 允许 `share/sub/dir`：挂载到共享下的某个子目录是很常见的用法。
            share.trim('/')
        } else {
            text(binding.inputRootPath)
        }

        val skeleton = ConnectionProfile(
            id = loaded?.id.orEmpty(),
            scheme = scheme,
            displayName = "",
            host = host,
            port = port,
            username = text(binding.inputUsername).ifEmpty { null },
            // 域只对 SMB 有意义，切协议时清掉，别把 NTLM 域带到 WebDAV 记录里
            domain = if (scheme == ConnectionFormats.SCHEME_SMB) text(binding.inputDomain).ifEmpty { null } else null,
            rootPath = rootPath,
            options = collectOptions(scheme),
        )

        // 显示名留空时用端点兜底。强制用户起名没有意义——列表里显示
        // `192.168.1.100/home` 已经足够认出是谁，而多一个必填项只会挡住他。
        return skeleton.copy(
            displayName = text(binding.inputLabel).ifEmpty { ConnectionFormats.endpoint(skeleton) },
        )
    }

    /**
     * 组装非敏感参数。
     *
     * 以库里原有的参数为底，做两件事：
     *
     *  1. **去掉属于另一种协议的键**。用户把一条连接从 WebDAV 改成 SMB 之后，
     *     残留的 `allowInsecure` 会被 SMB 后端读到吗？不会——它只认自己的键。
     *     但它会一直留在库里，成为"这条 SMB 连接为什么是明文"这类误判的源头。
     *  2. **覆盖表单暴露的键**。没暴露且属于同一协议的（如 `timeoutMs`）原样带回去，
     *     否则用户每编辑一次连接，这些之前设过的值就被静默抹掉了。
     *
     * 注意两个协议的 `TIMEOUT_MS` 常量值恰好相同（都是 `"timeoutMs"`），值语义也相同
     * （连接超时毫秒），所以它是**共用键**，两边都不清——清了就等于把用户的超时设置
     * 在切换类型的瞬间丢掉。
     *
     * 写入时**等于默认值的键直接删掉**，不落进库：默认值该由代码的 `default` 决定，
     * 写进库里等于把默认值冻在数据上，以后改默认值对老记录不生效。
     */
    private fun collectOptions(scheme: String): Map<String, String> {
        val out = LinkedHashMap(loaded?.options.orEmpty())
        if (scheme == ConnectionFormats.SCHEME_SMB) {
            out.remove(WebDavOptionKeys.ALLOW_INSECURE)
            out.remove(WebDavOptionKeys.TRUST_FINGERPRINT)
            out.remove(WebDavOptionKeys.AUTH_METHOD)
            out.remove(WebDavOptionKeys.NO_DEPTH_ONE)

            putFlag(out, SmbOptionKeys.ANONYMOUS, binding.switchAnonymous.isChecked, default = false)
            putFlag(out, SmbOptionKeys.SIGNING, binding.switchSigning.isChecked, default = true)
            putFlag(out, SmbOptionKeys.FORCE_SMB1, binding.switchForceSmb1.isChecked, default = false)
            putOrRemove(
                out,
                SmbOptionKeys.ENCODING,
                text(binding.inputEncoding).takeIf { it.isNotEmpty() && it != SmbOptionKeys.DEFAULT_ENCODING },
            )
        } else {
            out.remove(SmbOptionKeys.ANONYMOUS)
            out.remove(SmbOptionKeys.SIGNING)
            out.remove(SmbOptionKeys.FORCE_SMB1)
            out.remove(SmbOptionKeys.ENCODING)
            out.remove(SmbOptionKeys.SHARE_SUBPATH)

            putFlag(out, WebDavOptionKeys.ALLOW_INSECURE, binding.switchAllowInsecure.isChecked, default = false)
            putOrRemove(
                out,
                WebDavOptionKeys.TRUST_FINGERPRINT,
                text(binding.inputFingerprint).takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private fun collectPassword(): PasswordUpdate {
        val typed = passwordText()
        return if (typed.isEmpty()) PasswordUpdate.Unchanged else PasswordUpdate.Set(typed)
    }

    private fun updatePasswordHelper() {
        binding.boxPassword.helperText = when {
            editingId == null -> null
            hasStoredPassword -> getString(R.string.connection_password_keep)
            else -> getString(R.string.connection_no_password)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 小工具
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 软键盘弹出时把整个内容顶上来。
     *
     * edge-to-edge 之后系统不再为键盘缩小窗口（`adjustResize` 在
     * `setDecorFitsSystemWindows(false)` 之下不生效），不自己处理的话，用户一点进
     * 「密码」框，下方的「保存」就被键盘盖住——而表单填完最后要看的正是这个按钮。
     *
     * 只吃 ime 的高度、不碰 systemBars：状态栏与手势条已经分别由顶栏、底栏消化过，
     * 这里再取一次就会把导航栏高度算两遍。键盘收起时 ime 不可见，padding 归零。
     */
    private fun padRootForIme() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val keyboard = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else {
                0
            }
            view.updatePadding(bottom = keyboard)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.testingProgress.isVisible = value
        binding.btnTest.isEnabled = !value
        binding.btnSave.isEnabled = !value
        binding.btnAdvanced.isEnabled = !value
    }

    private fun text(edit: TextInputEditText): String = edit.text?.toString()?.trim().orEmpty()

    /** 密码**不 trim**：首尾空格常常是密码的一部分，裁掉会让用户"明明填对了却连不上"。 */
    private fun passwordText(): String = binding.inputPassword.text?.toString().orEmpty()

    private fun putFlag(
        target: MutableMap<String, String>,
        key: String,
        value: Boolean,
        default: Boolean,
    ) {
        if (value == default) target.remove(key) else target[key] = value.toString()
    }

    private fun putOrRemove(target: MutableMap<String, String>, key: String, value: String?) {
        if (value == null) target.remove(key) else target[key] = value
    }
    companion object {

        private const val EXTRA_ID = "connection_id"

        /** logcat tag：`adb logcat -s MimiConn`。与 MimiSmb / MimiDav / MimiFs 并列。 */
        private const val CONN_TAG = "MimiConn"

        /** 需要追踪是否改过的文本框：用取值函数而不是 View 引用，避免长久持有视图。 */
        private val FORM_TEXT_FIELDS: List<(ActivityConnectionEditBinding) -> TextInputEditText> = listOf(
            { it.inputLabel }, { it.inputHost }, { it.inputPort }, { it.inputShare },
            { it.inputRootPath }, { it.inputUsername }, { it.inputPassword }, { it.inputDomain },
            { it.inputEncoding }, { it.inputFingerprint },
        )

        /** 打开编辑页；[id] 为空表示新建。 */
        fun intent(context: Context, id: String? = null): Intent =
            Intent()
                .setClassName(context, ConnectionEditActivity::class.java.name)
                .putExtra(EXTRA_ID, id)
    }
}
