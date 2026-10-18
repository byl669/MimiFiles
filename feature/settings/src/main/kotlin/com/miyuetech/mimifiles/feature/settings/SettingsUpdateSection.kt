package com.miyuetech.mimifiles.feature.settings

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miyuetech.mimifiles.core.update.UpdateEngine
import com.miyuetech.mimifiles.core.update.UpdateState
import com.miyuetech.mimifiles.core.update.isForced
import com.miyuetech.mimifiles.feature.settings.databinding.ActivitySettingsBinding
import com.miyuetech.mimifiles.feature.settings.databinding.DialogUpdateBinding
import kotlinx.coroutines.launch

/**
 * 设置页的「软件更新」区块。
 *
 * # 单独成文件的原因
 * 更新的界面逻辑有明确的三块（行内状态渲染、动作分派、四种详情的对话框），
 * 塞进 `SettingsActivity` 会让设置页从"一堆开关的回填"变成"一半是更新流程"。
 * 拆出来之后，`SettingsActivity` 里只剩一行 `SettingsUpdateSection(...).bind()`——
 * 想找更新相关的东西时不必在设置页的代码里翻。
 *
 * # 界面分工：行 = 详情，按钮 = 动作
 * 每一行状态都只有一个"可点的动作"，与按钮上的文字完全对应。行本身用来"看详情"。
 * 两者都触发同一个动作会让用户猜不准该点哪里；而"行也能做动作"则会出现
 * "点错了什么都没发生"的观感（尤其在下载中/检查中这种按钮被禁用的状态）。
 *
 * # 对话框什么时候自己弹
 * 只在**需要用户读或决定**的状态自动弹：发现新版、更新包就绪、需授权、失败。
 * 进度**不弹**（行内显示即可，弹窗会把用户从正在做的事里拽出来），
 * 已是最新也不弹（一次检查的正常结果，用行内文字告知足够）。
 *
 * 用户手动关掉过的那个状态会被记住（[suppressed]），不会被下一次渲染重新弹回来；
 * 但只要他做了动作（检查/下载/安装）或主动点了行，抑制就清零。
 */
internal class SettingsUpdateSection(
    private val activity: AppCompatActivity,
    private val binding: ActivitySettingsBinding,
) {

    private val engine: UpdateEngine? get() = UpdateEngine.current()

    private var dialog: AlertDialog? = null

    /**
     * 对话框当前呈现的是哪个状态。
     *
     * 只用于两处：`dismiss` 时记住"用户关过它"（避免下一次渲染弹回来），
     * 以及 showDialog 里重建前判断新旧。**不要**用它判断"要不要就地更新内容"——
     * 那正是上一版死代码的来源。
     */
    private var dialogState: UpdateState? = null

    /** 用户手动关掉过的状态。防止"关了又弹回来"。 */
    private var suppressed: UpdateState? = null

    fun bind() {
        val engine = engine
        if (engine == null) {
            // 引擎在 `FileApp.onCreate` 装配，正常不会走到这里。真走到了，
            // 藏掉整行 —— 显示一个点了没反应的按钮比不显示更糟。
            binding.updateRow.visibility = View.GONE
            return
        }

        // 进设置页先按当前状态渲染一次：用户可能是在下载途中离开又回来的，
        // 这一行必须立刻显示"正在下载 42%"，而不是空白或"已是最新"。
        render(engine.state.value)

        binding.updateRow.setOnClickListener { onRowClick(engine) }
        binding.updateAction.setOnClickListener { onActionClick(engine) }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect { render(it) }
            }
        }
    }

    // ── 渲染 ───────────────────────────────────────────────────────────────

    private fun render(state: UpdateState) {
        renderRow(state)
        if (state == suppressed) return
        if (!needsPrompt(state)) return
        showDialog(state)
    }

    private fun renderRow(state: UpdateState) {
        val engine = engine ?: return
        val installed = engine.installedVersion

        binding.updateSummary.text = when (state) {
            UpdateState.Idle ->
                activity.getString(R.string.settings_update_idle, installed.versionName)
            UpdateState.Checking ->
                activity.getString(R.string.settings_update_checking)
            is UpdateState.UpToDate ->
                activity.getString(R.string.settings_update_uptodate, state.remoteVersionName)
            is UpdateState.Available ->
                if (state.file.size > 0L) {
                    activity.getString(
                        R.string.settings_update_available,
                        state.manifest.versionName,
                        mb(state.file.size),
                    )
                } else {
                    activity.getString(R.string.settings_update_available_no_size, state.manifest.versionName)
                }
            is UpdateState.Downloading ->
                activity.getString(R.string.settings_update_downloading, state.percent)
            is UpdateState.Ready ->
                activity.getString(R.string.settings_update_ready, state.manifest.versionName)
            is UpdateState.NeedInstallPermission ->
                activity.getString(R.string.settings_update_grant_needed)
            is UpdateState.Failed ->
                activity.getString(R.string.settings_update_failed_row)
        }

        // 进度条只在下载时出现。用确定值（不是转圈）：用户看得见还剩多少，
        // 而下载失败/卡住时"停在 37%"本身就是有用的信息。
        binding.updateProgress.apply {
            if (state is UpdateState.Downloading) {
                visibility = View.VISIBLE
                isIndeterminate = false
                progress = state.percent
            } else {
                visibility = View.GONE
            }
        }

        val action = when (state) {
            UpdateState.Idle -> R.string.settings_update_check to true
            // 检查中/下载中把按钮禁用并改文案：这两个状态下没有"用户能做的事"，
            // 留着可点的「检查更新」只会让人重复点，而重复请求是被引擎忽略的
            // ——又是一次"点了没反应"。
            UpdateState.Checking -> R.string.settings_update_checking to false
            is UpdateState.UpToDate -> R.string.settings_update_recheck to true
            is UpdateState.Available -> R.string.settings_update_download to true
            is UpdateState.Downloading -> R.string.settings_update_downloading to false
            is UpdateState.Ready -> R.string.settings_update_install to true
            is UpdateState.NeedInstallPermission -> R.string.settings_update_grant to true
            is UpdateState.Failed -> R.string.settings_update_retry to true
        }
        binding.updateAction.text = if (state is UpdateState.Downloading) {
            // 下载中的按钮文案要带百分比，单独取一次（它需要格式化参数）。
            activity.getString(action.first, state.percent)
        } else {
            activity.getString(action.first)
        }
        binding.updateAction.isEnabled = action.second
    }

    // ── 点击 ───────────────────────────────────────────────────────────────

    /**
     * 点行：有详情就看详情，没详情就等同按钮。
     *
     * 主动点击会清掉 [suppressed] —— 用户既然又点进来了，就是明确要看。
     */
    private fun onRowClick(engine: UpdateEngine) {
        suppressed = null
        val state = engine.state.value
        if (needsPrompt(state)) {
            showDialog(state)
        } else {
            onActionClick(engine)
        }
    }

    private fun onActionClick(engine: UpdateEngine) {
        suppressed = null
        when (engine.state.value) {
            UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed -> engine.check()
            is UpdateState.Available -> engine.download()
            is UpdateState.Ready, is UpdateState.NeedInstallPermission -> engine.requestInstall()
            // 这两个状态下按钮本来就是禁用的；真被点到也什么都不做。
            UpdateState.Checking, is UpdateState.Downloading -> Unit
        }
    }

    // ── 对话框 ─────────────────────────────────────────────────────────────

    private fun needsPrompt(state: UpdateState): Boolean = when (state) {
        is UpdateState.Available,
        is UpdateState.Ready,
        is UpdateState.NeedInstallPermission,
        is UpdateState.Failed,
        -> true

        else -> false
    }

    private fun showDialog(state: UpdateState) {
        val content = DialogUpdateBinding.inflate(activity.layoutInflater)
        val engine = engine
        val forced = engine != null && state.isForced(engine.installedVersion.versionCode)

        content.dialogTitle.text = titleOf(state)
        content.dialogMessage.text = messageOf(state)
        content.dialogDetail.text = detailOf(state, forced)
        content.dialogDetail.visibility = if (content.dialogDetail.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val builder = MaterialAlertDialogBuilder(activity).setView(content.root)
        applyButtons(builder, state, engine, forced)

        val previous = dialog
        val created = builder.create()
        created.setOnDismissListener {
            // 记住"用户关过它"，否则下一次渲染（转屏、回到前台）会把它弹回来。
            suppressed = dialogState
            if (dialog === created) {
                dialog = null
                dialogState = null
            }
        }
        dialog = created
        dialogState = state
        // 先建后关：反过来会让屏幕空一瞬（旧对话框已消失、新的还没显示）。
        previous?.dismiss()
        created.show()
    }

    // 这里**没有**"下载中的对话框"。
    //
    // ## 为什么不弹
    // 下载中不需要用户做任何决定，而进度条已经在他刚点过的那个按钮**正下方一行**
    // （同一张卡片里）。为它弹一个模态框只有一个效果：把用户困住。
    //
    // ## 曾经写过一版，而且是**死代码**
    // 第一版实现过"点到下载后就地更新对话框里的进度"，结果那段分支永远走不到 ——
    // `MaterialAlertDialogBuilder.setPositiveButton` 在点击后**会自动关闭对话框**，
    // 而 `Downloading` 状态是在监听器返回之后才被 collect 到的，那时 `dialog` 已经是 null。
    // 于是它既不更新进度、也不会重新弹出，只留下一段"看起来在处理进度"的代码。
    // 冒烟里 U4 报的就是这个：等"正在下载"等到的是"更新包就绪"。
    // 与其把自动关闭挡掉去迁就它，不如承认"进度不该在模态框里"——
    // 需要用户读或决定的状态才配一个对话框（见 needsPrompt）。

    private fun applyButtons(
        builder: MaterialAlertDialogBuilder,
        state: UpdateState,
        engine: UpdateEngine?,
        forced: Boolean,
    ) {
        val engineRef = engine ?: return
        when (state) {
            is UpdateState.Available -> {
                builder.setPositiveButton(R.string.settings_update_download) { _, _ -> onActionClick(engineRef) }
                // 强制更新时不给取消键：这一版必须升上去（老版本与新服务端协议不兼容，
                // 或者有已修复的安全问题）。留着「取消」等于把这条约束变成一句提示。
                if (!forced) builder.setNegativeButton(R.string.update_dialog_cancel, null)
            }

            is UpdateState.Ready -> {
                builder.setPositiveButton(R.string.settings_update_install) { _, _ -> onActionClick(engineRef) }
                if (!forced) builder.setNegativeButton(R.string.update_dialog_later, null)
            }

            is UpdateState.NeedInstallPermission -> {
                builder.setPositiveButton(R.string.settings_update_grant) { _, _ -> onActionClick(engineRef) }
                builder.setNegativeButton(R.string.update_dialog_cancel, null)
            }

            is UpdateState.Failed -> {
                builder.setPositiveButton(R.string.settings_update_retry) { _, _ -> engineRef.retry() }
                builder.setNegativeButton(R.string.update_dialog_close, null)
            }

            else -> builder.setPositiveButton(R.string.update_dialog_close, null)
        }
    }

    private fun titleOf(state: UpdateState): CharSequence = when (state) {
        is UpdateState.Checking -> activity.getString(R.string.update_dialog_title_checking)
        is UpdateState.Available ->
            activity.getString(R.string.update_dialog_title_available, state.manifest.versionName)
        // 下载中不会走到这里（`needsPrompt` 把它排除在外），但 `when` 对密封接口
        // 要求穷尽 —— 补一个诚实的取值，而不是塞个 `else -> ""`。
        // 万一将来真走到这里，宁可标题显示"正在下载 42%"也不显示空白。
        is UpdateState.Downloading ->
            activity.getString(R.string.settings_update_downloading, state.percent)
        is UpdateState.Ready -> activity.getString(R.string.update_dialog_title_ready)
        is UpdateState.NeedInstallPermission -> activity.getString(R.string.update_dialog_title_permission)
        is UpdateState.Failed -> activity.getString(R.string.update_dialog_title_failed)
        is UpdateState.UpToDate -> activity.getString(R.string.settings_update_uptodate, state.remoteVersionName)
        UpdateState.Idle -> activity.getString(R.string.settings_update_title)
    }

    private fun messageOf(state: UpdateState): CharSequence = when (state) {
        is UpdateState.Checking -> activity.getString(R.string.update_dialog_msg_checking)
        is UpdateState.Available ->
            state.manifest.notes.takeIf { it.isNotBlank() }
                ?: activity.getString(R.string.update_dialog_notes_empty)
        is UpdateState.Ready -> activity.getString(R.string.update_dialog_msg_ready)
        is UpdateState.NeedInstallPermission -> activity.getString(R.string.update_dialog_msg_permission)
        is UpdateState.Failed -> state.message
        else -> ""
    }

    private fun detailOf(state: UpdateState, forced: Boolean): CharSequence {
        val base = when (state) {
            is UpdateState.Available ->
                if (state.file.size > 0L) {
                    activity.getString(R.string.update_dialog_detail_available, mb(state.file.size))
                } else {
                    activity.getString(R.string.update_dialog_detail_available_no_size)
                }

            is UpdateState.Failed -> activity.getString(R.string.update_dialog_detail_failed)
            else -> ""
        }
        return if (forced) {
            activity.getString(R.string.update_dialog_forced_hint) + "\n" + base
        } else {
            base
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    /** 字节 → MB（保留一位小数）。`%1$.1f` 由字符串资源负责格式化。 */
    private fun mb(bytes: Long): Float = bytes / 1024f / 1024f
}
