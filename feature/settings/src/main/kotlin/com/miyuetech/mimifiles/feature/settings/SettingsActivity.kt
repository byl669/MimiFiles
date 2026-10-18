package com.miyuetech.mimifiles.feature.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miyuetech.mimifiles.core.datastore.SettingsRepository
import com.miyuetech.mimifiles.core.shareserver.ServerType
import com.miyuetech.mimifiles.core.update.UpdateEngine
import com.miyuetech.mimifiles.core.model.FileViewMode
import com.miyuetech.mimifiles.core.model.ThemeMode
import com.miyuetech.mimifiles.core.ui.applyThemeMode
import com.miyuetech.mimifiles.feature.settings.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * ## 数据流是单向的
 * 控件状态**不自己保存**，一律从 [SettingsRepository] 的 Flow 回填；用户操作只往
 * DataStore 写。这样"界面上显示的"与"真正生效的"永远是同一份数据，不会出现
 * 旋转屏幕后开关自己弹回去这类状态漂移。
 *
 * ## 主题为什么要单独处理
 * 主题改动的效果是 Activity 重建，而不是某个控件变色。所以写入完成之后必须再调
 * [applyThemeMode]。**顺序不能反**：先切昼夜模式会触发重建，而重建时读到的可能
 * 还是旧值，用户会看到"点深色但界面没变"。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository

    /**
     * 回填控件时置位，挡掉随之而来的监听回调。
     *
     * 虽然 `RadioGroup.check()` / `CompoundButton.setChecked()` 对"值未变"的情况
     * 本就不回调，但依赖框架的早退来实现正确性太隐晦；显式挡一层，
     * 将来换成自定义控件也不会悄悄退化成"打开设置页就把偏好重写一遍"。
     *
     * 置位与复位之间**没有挂起点**，所以多个 Flow 同时回填也不会互相踩。
     */
    private var bindingState = false

    private val themeIds by lazy {
        mapOf(
            ThemeMode.SYSTEM to R.id.theme_system,
            ThemeMode.LIGHT to R.id.theme_light,
            ThemeMode.DARK to R.id.theme_dark,
        )
    }

    private val viewModeIds by lazy {
        mapOf(
            FileViewMode.LIST to R.id.view_mode_list,
            FileViewMode.GRID to R.id.view_mode_grid,
            FileViewMode.DETAILS to R.id.view_mode_details,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.versionValue.text = appVersion()

        bindTheme()
        bindViewMode()
        bindShowHidden()
        bindPlayerSpeed()
        bindShare()
        // 软件更新（M5）：整块逻辑在 SettingsUpdateSection 里，这里只挂上去。
        SettingsUpdateSection(this, binding).bind()
        applyDebugUpdateOverrides()
    }

    /**
     * 冒烟用的调试入口：`--es update.url <清单地址>` 临时改掉更新清单地址。
     *
     * ## 为什么要留这个入口
     * debug 包的包名与签名都和生产包不同，所以它**没法**用生产清单里的 release 包
     * 走完"下载 → 三重校验 → 安装"（签名那一关必然拒绝，那是判据错了不是代码错了）。
     * 反过来，生产通道（真 HTTPS、真清单）又只有拿到生产地址才验得到。
     * 一个能临时指地址的入口，让**同一个 debug 包**把两条路都走一遍。
     *
     * 安全性由 API 自己保证：`overrideManifestUrlForTest` 内部检查
     * `FLAG_DEBUGGABLE`，正式包里这个调用是空操作（连额外分支都算不上）。
     */
    private fun applyDebugUpdateOverrides() {
        val url = intent?.getStringExtra(EXTRA_UPDATE_URL)?.takeIf { it.isNotBlank() } ?: return
        UpdateEngine.current()?.overrideManifestUrlForTest(url)
    }

    // ── 主题 ────────────────────────────────────────────────────────────

    private fun bindTheme() {
        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (bindingState) return@setOnCheckedChangeListener
            val mode = themeIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            lifecycleScope.launch {
                settings.setThemeMode(mode)
                // 落盘之后再切，保证重建时读到的是新值
                applyThemeMode(mode)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.themeMode.collect { mode ->
                    bindingState = true
                    binding.themeGroup.check(themeIds.getValue(mode))
                    bindingState = false
                }
            }
        }
    }

    // ── 列表视图 ────────────────────────────────────────────────────────

    private fun bindViewMode() {
        binding.viewModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (bindingState) return@setOnCheckedChangeListener
            val mode = viewModeIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            lifecycleScope.launch { settings.setViewMode(mode) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.viewMode.collect { mode ->
                    bindingState = true
                    binding.viewModeGroup.check(viewModeIds.getValue(mode))
                    bindingState = false
                }
            }
        }
    }

    // ── 显示隐藏文件 ────────────────────────────────────────────────────

    private fun bindShowHidden() {
        binding.switchShowHidden.setOnCheckedChangeListener { _, checked ->
            if (bindingState) return@setOnCheckedChangeListener
            lifecycleScope.launch { settings.setShowHidden(checked) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.showHidden.collect { show ->
                    bindingState = true
                    binding.switchShowHidden.isChecked = show
                    bindingState = false
                }
            }
        }
    }

    // ── 播放（M7.6）─────────────────────────────────────────────────────

    /** 「默认视频播放速度」的可选档位，与播放器读取的浮点值一一对应。 */
    private val speedOptions = listOf(1f, 1.5f, 2f, 3f)

    /** 当前速度的本地镜像：弹选择框时定位勾选项用（Flow 回填时更新）。 */
    private var currentSpeed = 1f

    /**
     * 「默认视频播放速度」：点整行弹单选框（1 / 1.5 / 2 / 3 倍速）。
     *
     * 与开关类设置不同，这行**没有内联控件** —— 档位只有 4 个且带小数，
     * RadioGroup 一排放不下；单选对话框是系统设置里"选一个值"的标准形态，
     * 用户零学习成本。
     */
    private fun bindPlayerSpeed() {
        binding.playerSpeedRow.setOnClickListener { showSpeedDialog() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.playerSpeed.collect { speed ->
                    currentSpeed = speed
                    binding.playerSpeedValue.text = speedLabel(speed)
                }
            }
        }
    }

    private fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()} 倍速" else "$speed 倍速"

    private fun showSpeedDialog() {
        val labels = speedOptions.map(::speedLabel).toTypedArray()
        var selected = currentSpeed
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_player_speed)
            .setSingleChoiceItems(labels, speedOptions.indexOf(currentSpeed)) { _, which ->
                selected = speedOptions[which]
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch { settings.setPlayerSpeed(selected) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── 无线分享（M4）──────────────────────────────────────────────────

    /**
     * 分享相关的三项设置。
     *
     * 端口做成"点一下弹输入框"而不是 `EditText`：它旁边没有"保存"按钮，
     * 常驻输入框会让用户不知道什么时候算改完了。对话框有明确的确定/取消，
     * 而且能在输入不合法时当场挡住（1024 以下在 Android 上根本 bind 不了）。
     */
    private fun bindShare() {
        binding.switchShareKeepScreen.setOnCheckedChangeListener { _, checked ->
            if (bindingState) return@setOnCheckedChangeListener
            lifecycleScope.launch { settings.setShareKeepScreenOn(checked) }
        }
        binding.switchShareStopOnExit.setOnCheckedChangeListener { _, checked ->
            if (bindingState) return@setOnCheckedChangeListener
            lifecycleScope.launch { settings.setShareStopOnExit(checked) }
        }
        binding.sharePortHttp.setOnClickListener { showPortDialog(ServerType.HTTP) }
        binding.sharePortFtp.setOnClickListener { showPortDialog(ServerType.FTP) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.shareKeepScreenOn.collect { keep ->
                    bindingState = true
                    binding.switchShareKeepScreen.isChecked = keep
                    bindingState = false
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.shareStopOnExit.collect { stop ->
                    bindingState = true
                    binding.switchShareStopOnExit.isChecked = stop
                    bindingState = false
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.sharePort(ServerType.HTTP.name).collect { port ->
                    binding.sharePortHttp.text = (port.takeIf { it > 0 } ?: ServerType.HTTP.preferredPort).toString()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.sharePort(ServerType.FTP.name).collect { port ->
                    binding.sharePortFtp.text = (port.takeIf { it > 0 } ?: ServerType.FTP.preferredPort).toString()
                }
            }
        }
    }

    private fun showPortDialog(type: ServerType) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.settings_share_port_hint)
            setText(bindingPortValue(type).toString())
            setSelection(text.length)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${getString(R.string.settings_share_port_title)} · ${type.label}")
            .setView(input)
            .setPositiveButton(R.string.settings_share_port_title) { _, _ ->
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value !in MIN_PORT..MAX_PORT) {
                    toast(getString(R.string.settings_share_port_invalid))
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    settings.setSharePort(type.name, value)
                    toast(getString(R.string.settings_share_port_saved))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindingPortValue(type: ServerType): Int = when (type) {
        ServerType.HTTP -> binding.sharePortHttp.text?.toString()?.toIntOrNull() ?: type.preferredPort
        ServerType.FTP -> binding.sharePortFtp.text?.toString()?.toIntOrNull() ?: type.preferredPort
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"

    private companion object {
        /**
         * 端口下限取 1024。
         *
         * 不是"约定俗成"而是**硬约束**：Linux 上 1024 以下需要 `CAP_NET_BIND_SERVICE`，
         * 而 Android 应用进程没有这个能力，填了也只会在启动时得到一个
         * "Permission denied"。在这里挡住，好过让用户在分享面板上看到一次失败。
         */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** 冒烟用：临时覆盖更新清单地址（只在 debug 构建生效，见 [applyDebugUpdateOverrides]）。 */
        const val EXTRA_UPDATE_URL = "update.url"
    }
}
