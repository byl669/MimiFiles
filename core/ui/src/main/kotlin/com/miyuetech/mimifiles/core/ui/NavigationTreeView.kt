package com.miyuetech.mimifiles.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.ui.databinding.ItemTreeHeaderBinding
import com.miyuetech.mimifiles.core.ui.databinding.ItemTreeNodeBinding

/**
 * 左侧导航树（横屏 Windows 资源管理器形态的核心部件之一）。
 *
 * ## 职责边界（这一条决定了整个界面的复杂度）
 * 树**只负责展示与交互，不负责加载**。点击展开时通过 [onRequestChildren] 向宿主
 * 要子目录，宿主加载完用 [submitChildren] 送回来。理由：加载要走 `FileSystem`，
 * 那是 suspend 且可能连网络（SMB），而 View 不该持有协程作用域、也不该在被回收后
 * 还在写自己的状态。
 *
 * ## 为什么懒加载子目录
 * 一进应用就把内部存储的一级目录全读一遍，在 SD 卡上要几百毫秒；而用户通常
 * 只关心一个分支。"点击才读"把这段时间摊到了他真正需要的时候。
 *
 * ## 交互分工
 * - 点**箭头**：展开 / 收起
 * - 点**行**：在右侧列表跳转到该目录，并展开到该层的祖先
 *
 * 这两件事分开是有意的：资源管理器用户早就形成了"点名字是导航、点三角是展开"
 * 的肌肉记忆，合并成一个手势会让"想看一眼旁边的目录"变成一次误导航。
 */
class NavigationTreeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    /** 点击节点。宿主应据此在右侧列表跳转到该目录。 */
    var onNodeClick: ((FsPath) -> Unit)? = null

    /**
     * 长按节点（M7.12）：宿主用于"收藏/取消收藏"开关。
     * 侧栏树是**唯一能同时看到收藏与未收藏目录**的地方——长按即切换，
     * 比"收藏管理页"少一层界面；是否真的切换由宿主决定（含提示反馈）。
     */
    var onNodeLongClick: ((FsPath) -> Unit)? = null

    /** 首次展开某节点时请求其子目录；宿主用 [submitChildren] 回填。 */
    var onRequestChildren: ((FsPath) -> Unit)? = null

    private val treeAdapter = TreeAdapter()

    private var quickAccess: List<FsEntry> = emptyList()
    private var rootSections: List<RootSection> = emptyList()
    private val loadedChildren = LinkedHashMap<FsPath, List<FsEntry>>()
    private val expanded = LinkedHashSet<FsPath>()
    private val loading = HashSet<FsPath>()
    private var currentPath: FsPath? = null

    /**
     * 用户在当前路径上手动收起过的祖先节点。跟随机制不与人对着干：
     * 换了目标路径即清空，恢复"自动展开祖先"的默认行为。
     */
    private val manuallyCollapsed = HashSet<FsPath>()

    /**
     * 待滚动目标（[revealPath] 挂起的"滚到当前节点"意图）。
     * 祖先子目录异步加载到位、节点真正进列表后才消费并清空。
     */
    private var pendingReveal: FsPath? = null

    init {
        layoutManager = LinearLayoutManager(context)
        setHasFixedSize(true)
        // 关掉默认动画：树的展开/收起是离散的结构变化，逐项淡入看起来像卡顿
        itemAnimator = null
        adapter = treeAdapter
    }

    // ── 对外 API ────────────────────────────────────────────────────────

    fun setQuickAccess(entries: List<FsEntry>) {
        quickAccess = entries
        rebuild()
    }

    /** 存储根（内部存储 / SD 卡 / SAF 授权目录）。单组，不分组标题。 */
    fun setRoots(entries: List<FsEntry>) {
        setRootSections(listOf(RootSection(id = SECTION_STORAGE, entries = entries)))
    }

    /**
     * 存储根，**按组渲染**。
     *
     * 为什么要分组而不是把所有入口平铺：本机存储和网络位置是两类东西——
     * 前者永远在线、容量有限；后者要握手、可能连不上、容量未知。混在一个列表里，
     * 用户看到"客厅 NAS"和"内部存储"平级排着，很难判断哪个是插在手机里的。
     *
     * [RootSection.title] 由调用方给，`null` 时回落到「存储位置」：标题是文案，
     * 文案属于 `res/values` 与具体语言，`core:ui` 不该替业务模块决定怎么说。
     */
    fun setRootSections(sections: List<RootSection>) {
        rootSections = sections
        rebuild()
    }

    fun setCurrentPath(path: FsPath?) {
        if (currentPath == path) return
        currentPath = path
        rebuild()
    }

    /** 宿主加载完成后回填子目录。 */
    fun submitChildren(parent: FsPath, children: List<FsEntry>) {
        loadedChildren[parent] = children
        loading.remove(parent)
        // 祖先链是逐级异步喂进来的：每一次回填都可能让当前节点第一次
        // 出现在列表里——rebuild 的 commit 回调会顺带兑现挂起的滚动。
        rebuild()
    }

    fun setLoading(parent: FsPath) {
        if (loading.add(parent)) rebuild()
    }

    /**
     * 已加载的缓存失效（换存储、权限变化后调用），下次展开会重新读。
     *
     * 刻意**不叫** `invalidate()`：那个名字属于 `View`，重名会让所有调用点分不清
     * 到底是在刷新控件还是在刷新数据。数据刷新用有语义的名字更安全。
     */
    fun clearCache() {
        loadedChildren.clear()
        loading.clear()
        rebuild()
    }

    /** 收起全部展开项（换存储根时用，避免残留另一个后端的展开状态）。 */
    fun collapseAll() {
        expanded.clear()
        currentPath = null
        pendingReveal = null
        manuallyCollapsed.clear()
        rebuild()
    }

    /** 展开到指定路径的所有祖先并滚到可见（左侧树的"跟随"能力，M7.18 接线）。 */
    fun revealPath(path: FsPath) {
        // 宿主每次状态刷新都会走到这里；路径没变且祖先早已全部展开时，
        // 重复滚动会把用户正在翻的树硬拽回去——资源管理器的跟随只在该跟时跟。
        if (currentPath != path) manuallyCollapsed.clear()
        val ancestors = path.chainFromRoot().dropLast(1)
        if (currentPath == path && pendingReveal == null &&
            ancestors.none { it in manuallyCollapsed } && ancestors.all { it in expanded }
        ) {
            return
        }
        currentPath = path
        // 祖先的子目录是异步加载的，当前节点此刻多半还不在列表里；
        // 先挂起"滚过去"的意图，等 submitChildren 把节点喂进来后再补滚。
        pendingReveal = path
        ancestors.forEach { ancestor ->
            if (ancestor in manuallyCollapsed) return@forEach
            if (expanded.add(ancestor) && !loadedChildren.containsKey(ancestor)) {
                loading.add(ancestor)
                onRequestChildren?.invoke(ancestor)
            }
        }
        rebuild()
    }

    /**
     * 滚到 [pendingReveal] 指向的节点；节点尚未出现在列表里（祖先还在异步加载）
     * 就保留意图，等下一次 [submitChildren] 触发重试。
     */
    private fun scrollToRevealTarget() {
        val target = pendingReveal ?: return
        val index = treeAdapter.currentList.indexOfFirst { it.path == target }
        if (index >= 0) {
            pendingReveal = null
            post { layoutManager?.scrollToPosition(index) }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun rebuild() {
        val rows = ArrayList<TreeRow>()
        if (quickAccess.isNotEmpty()) {
            rows += TreeRow.header("quick", context.getString(R.string.nav_quick_access))
            quickAccess.forEach { appendBranch(it, 0, rows) }
        }
        for (section in rootSections) {
            // 空组连标题一起省掉：一个下面什么都没有的「网络位置」标题
            // 只会让人以为加载失败。
            if (section.entries.isEmpty()) continue
            rows += TreeRow.header(
                section.id,
                section.title ?: context.getString(R.string.nav_storage),
            )
            section.entries.forEach { appendBranch(it, 0, rows) }
        }
        treeAdapter.submitList(rows) {
            // ListAdapter 的 diff 异步提交：提交完成前 currentList 还是旧快照，
            // 滚动必须等 commit 回调（挂起的 reveal 意图在这里才可能兑现）。
            scrollToRevealTarget()
        }
    }

    private fun appendBranch(entry: FsEntry, depth: Int, rows: MutableList<TreeRow>) {
        rows += entry.toRow(depth)
        if (!expanded.contains(entry.path)) return
        val children = loadedChildren[entry.path] ?: return
        // 树里只下钻目录：文件在左侧导航里没有意义，只会把树撑爆
        children.filter { it.isDirectory }.forEach { child ->
            appendBranch(child, depth + 1, rows)
        }
    }

    private fun FsEntry.toRow(depth: Int) = TreeRow(
        key = path.toString(),
        path = path,
        label = name,
        depth = depth,
        isHeader = false,
        expandable = isDirectory,
        expanded = expanded.contains(path),
        isCurrent = path == currentPath,
    )

    private fun toggle(node: FsPath) {
        if (!expanded.remove(node)) {
            // 重新展开用户手动收起的节点：跟随约束随即解除
            manuallyCollapsed.remove(node)
            expanded.add(node)
            if (!loadedChildren.containsKey(node)) {
                loading.add(node)
                onRequestChildren?.invoke(node)
            }
        } else if (currentPath != null && currentPath!!.chainFromRoot().contains(node)) {
            // 用户收起的是当前路径的祖先：记录在案， revealPath 不再把它
            // 强行拽开（资源管理器同样尊重手动收起，直到换目标才恢复跟随）。
            manuallyCollapsed.add(node)
        }
        rebuild()
    }

    private data class TreeRow(
        val key: String,
        val path: FsPath?,
        val label: String,
        val depth: Int,
        val isHeader: Boolean,
        val expandable: Boolean,
        val expanded: Boolean,
        val isCurrent: Boolean,
    ) {
        companion object {
            fun header(key: String, label: String) = TreeRow(
                key = "header:$key",
                path = null,
                label = label,
                depth = 0,
                isHeader = true,
                expandable = false,
                expanded = false,
                isCurrent = false,
            )
        }
    }

    private inner class TreeAdapter : ListAdapter<TreeRow, RecyclerView.ViewHolder>(ROW_DIFF) {

        override fun getItemViewType(position: Int): Int =
            if (getItem(position).isHeader) TYPE_HEADER else TYPE_NODE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(ItemTreeHeaderBinding.inflate(inflater, parent, false))
            } else {
                NodeHolder(ItemTreeNodeBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = getItem(position)
            when (holder) {
                is HeaderHolder -> holder.bind(row)
                is NodeHolder -> holder.bind(row)
            }
        }
    }

    private inner class HeaderHolder(private val binding: ItemTreeHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: TreeRow) {
            binding.headerLabel.text = row.label
        }
    }

    private inner class NodeHolder(private val binding: ItemTreeNodeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val indentUnit = (14 * resources.displayMetrics.density).toInt()
        private val basePadding = (4 * resources.displayMetrics.density).toInt()

        init {
            binding.treeRow.setOnClickListener {
                val row = currentRow() ?: return@setOnClickListener
                row.path?.let { onNodeClick?.invoke(it) }
            }
            binding.treeArrow.setOnClickListener {
                val row = currentRow() ?: return@setOnClickListener
                row.path?.let { toggle(it) }
            }
            binding.treeRow.setOnLongClickListener {
                val row = currentRow() ?: return@setOnLongClickListener true
                row.path?.let { onNodeLongClick?.invoke(it) }
                true
            }
        }

        fun bind(row: TreeRow) {
            binding.root.updatePadding(left = basePadding + row.depth * indentUnit)
            binding.treeLabel.text = row.label
            binding.treeRow.isActivated = row.isCurrent
            binding.treeLabel.setTypeface(null, if (row.isCurrent) Typeface.BOLD else Typeface.NORMAL)

            // 箭头：可展开才显示；展开时旋转 90°（同一个图标，语义直观且省一张资源）
            binding.treeArrow.visibility = if (row.expandable) View.VISIBLE else View.INVISIBLE
            binding.treeArrow.rotation = if (row.expanded) 90f else 0f
            binding.treeArrow.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(holderContext, R.color.text_secondary),
            )

            binding.treeIcon.setImageResource(R.drawable.ic_file_folder)
            binding.treeIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(holderContext, R.color.icon_folder),
            )
        }

        private val holderContext: Context get() = binding.root.context

        /**
         * 取当前行数据。
         *
         * 不用 `adapter.getItem()`：`ListAdapter` 把它收成了 `protected`，跨类访问不到；
         * 而 `currentList` 是公开且已快照化的，越界返回 `null` 正好覆盖"点击时列表已刷新"
         * 这个真实存在的竞态。
         */
        private fun currentRow(): TreeRow? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return null
            return treeAdapter.currentList.getOrNull(position)
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_NODE = 1

        const val SECTION_STORAGE = "storage"

        val ROW_DIFF = object : DiffUtil.ItemCallback<TreeRow>() {
            override fun areItemsTheSame(oldItem: TreeRow, newItem: TreeRow): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: TreeRow, newItem: TreeRow): Boolean =
                oldItem == newItem
        }
    }
}

/**
 * 导航树里的一"组"存储入口。
 *
 * @param id DiffUtil 用的稳定标识。必须**跨次刷新保持一致**，否则每次重建
 *           系统都认为标题行是新的，整棵树会闪一下（`itemAnimator` 已被关掉，
 *           但 `submitList` 仍会重绑可见项）。
 * @param title 组标题；`null` 回落「存储位置」。
 */
data class RootSection(
    val id: String,
    val title: String? = null,
    val entries: List<FsEntry> = emptyList(),
)
