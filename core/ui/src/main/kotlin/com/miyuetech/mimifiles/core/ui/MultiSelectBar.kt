package com.miyuetech.mimifiles.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.ui.databinding.ViewMultiSelectBarBinding

/**
 * 多选「顶栏」（M7.16 重设计）—— 只管**选择状态**：N/M 计数 + 全选 + 取消。
 * 操作按钮（复制/剪切/删除/重命名/更多…）在屏幕底部的
 * [MultiSelectBottomBar]，两者组成 ES 文件浏览器式的上/下双栏。
 *
 * ## 历史沿革
 * M7.2 单行瓦片 → M7.3 图标文字并排两行 → M7.4 三行。真机反馈
 * 整体不美观（一坨按钮压在列表上方），M7.16 参照 ES 拆成上/下双栏：
 * 顶栏轻（状态），底栏重（操作），列表上方不再堆按钮。
 *
 * ## 为什么动作推导还留在这里
 * [Action] 枚举与 [actionsFor] 推导是"多选能做什么"的**唯一事实源**，
 * 底栏只是它的渲染端之一。放本类 companion 让"加一个动作"仍然只改一处
 * （枚举 + actionsFor），顶栏底栏自动跟着走。
 */
class MultiSelectBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * 一个可执行动作。[labelRes]/[iconRes] 放在枚举里，是为了让"加一个动作"
     * 只需要改这一处 + [actionsFor] 的推导规则，不用去动布局。
     */
    enum class Action(
        @StringRes val labelRes: Int,
        @DrawableRes val iconRes: Int,
    ) {
        SELECT_ALL(R.string.action_select_all, R.drawable.ic_select_all),
        RENAME(R.string.action_rename, R.drawable.ic_edit),
        COPY(R.string.action_copy, R.drawable.ic_content_copy),
        CUT(R.string.action_cut, R.drawable.ic_content_cut),
        DELETE(R.string.action_delete, R.drawable.ic_delete),
        /** M7.25：Android 系统分享面板（ACTION_SEND / ACTION_SEND_MULTIPLE）。 */
        SHARE(R.string.action_share, R.drawable.ic_share),
        /** 原「无线分享」：把手机变成 HTTP/FTP 服务端，改名后仍是同一个功能。 */
        ADVANCED_SHARE(R.string.action_share_advanced, R.drawable.ic_share_advanced),
        ADD_TO_HOME(R.string.action_add_to_home, R.drawable.ic_add_to_home),
        INFO(R.string.action_info, R.drawable.ic_info),

        /**
         * 收藏 / 取消收藏（M7.12）：把目录加进侧栏「快速访问」。
         * **不放进 [actionsFor] 的静态推导**——显示哪个取决于"该目录当前是否
         * 已收藏"，那是宿主状态，宿主在拼动作列表时自己选 [FAVORITE] 或 [UNFAVORITE]。
         */
        FAVORITE(R.string.action_favorite, R.drawable.ic_star),
        UNFAVORITE(R.string.action_unfavorite, R.drawable.ic_star),
    }

    /** 点「取消」：退出多选并清空选择。 */
    var onClose: (() -> Unit)? = null

    /**
     * 点「全选/取消选择」。顶栏只发 [Action.SELECT_ALL] 一种事件，
     * 全选态的标签切换在 [submit] 里做（全选时显示「取消选择」）。
     */
    var onAction: ((Action) -> Unit)? = null

    // <merge> 生成的 inflate 只有两参数版：它恒把子视图挂到 parent（也就是 this）上
    private val binding = ViewMultiSelectBarBinding.inflate(LayoutInflater.from(context), this)

    private val rowHeight = resources.getDimensionPixelSize(R.dimen.select_top_height)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = rowHeight
        setBackgroundColor(ContextCompat.getColor(context, R.color.select_bar_bg))

        binding.selectAllTile.setOnClickListener { onAction?.invoke(Action.SELECT_ALL) }
        binding.closeTile.setOnClickListener { onClose?.invoke() }
    }

    // ── 对外 API ────────────────────────────────────────────────────────

    /**
     * 显示并刷新内容。
     *
     * @param count 已选数量
     * @param total 当前目录可见条目总数；计数显示「N/M」，并决定
     *              全选瓦片显示「全选」还是「取消选择」。
     */
    fun show(count: Int, total: Int) {
        visibility = View.VISIBLE
        submit(count, total)
    }

    /** 只刷新内容，不改可见性（选择变化时调用）。 */
    fun submit(count: Int, total: Int) {
        binding.selectCount.text = "$count/$total"
        binding.selectCount.contentDescription =
            context.getString(R.string.select_ratio_a11y, count, total)
        val allSelected = total > 0 && count >= total
        binding.selectAllLabel.text =
            context.getString(if (allSelected) R.string.select_clear else R.string.action_select_all)
    }

    fun hide() {
        visibility = View.GONE
    }

    companion object {

        /**
         * 按当前选中项推导可用动作（顺序即按钮顺序）。
         *
         * 规则全部来自产品要求，不在这里加"顺手"的功能：
         * - 重命名 / 属性：仅单选 —— 批量重命名需要命名模板，是另一期的事
         * - 添加到桌面：仅单个**目录** —— 单文件快捷方式已明确不做
         * - 删除 / 剪切 / 重命名：选中项里有只读项时整体不出现，避免"部分成功"
         *
         * M3 时 [Action.SHARE] 被刻意排除，理由是"分享要对外发文件 URI，需要 FileProvider
         * 与授权流程"。M4 绕开它的办法是自己起 HTTP/FTP 服务端，于是「分享」这个名字
         * 被占用了四年（版本意义上）——用户点「分享」看到的却是局域网地址。
         *
         * M7.25 把两者拆开：[Action.SHARE] 走系统分享面板，[Action.ADVANCED_SHARE]
         * 保留原来的 HTTP/FTP 面板。系统分享的 URI 来自我们自己的 `DocumentsProvider`
         * （`ExternalOpen.documentUri`），不需要新增 FileProvider 或权限。
         *
         * - 系统分享**只在全是文件时出现**：目录没有可读的文件流，Provider 的
         *   `openDocument` 对目录会失败；也不静默跳过，免得用户以为漏发。
         * - 高级分享**始终可用**：目录恰恰是它最常见的共享根。
         * - 两者都不受 `writable` 约束（只读 = 不能改，不是不能读）。
         */
        fun actionsFor(selected: List<FsEntry>): List<Action> {
            val out = ArrayList<Action>(9)
            out += Action.SELECT_ALL
            if (selected.isEmpty()) return out

            val writable = selected.none { it.isReadOnly }
            out += Action.COPY
            if (selected.all { it.isFile }) out += Action.SHARE
            out += Action.ADVANCED_SHARE
            if (writable) {
                out += Action.CUT
                if (selected.size == 1) out += Action.RENAME
            }
            if (selected.size == 1) {
                if (selected[0].isDirectory) out += Action.ADD_TO_HOME
                out += Action.INFO
            }
            if (writable) out += Action.DELETE
            return out
        }
    }
}
