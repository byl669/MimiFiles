package com.miyuetech.mimifiles.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.miyuetech.mimifiles.core.model.FileViewMode
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsSortField
import com.miyuetech.mimifiles.core.ui.databinding.ItemFileDetailsBinding
import com.miyuetech.mimifiles.core.ui.databinding.ItemFileGridBinding
import com.miyuetech.mimifiles.core.ui.databinding.ItemFileRowBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 文件列表 Adapter —— 一套数据、三种视图。
 *
 * ## 为什么用 `ListAdapter` 而不是普通 Adapter
 * 勾选、重命名、剪切后刷新都会改变列表内容。`notifyDataSetChanged()` 在 5000 项的
 * 目录里会让整个可见区重绑，滚动位置也会跳。`ListAdapter` 用 DiffUtil 算出最小变更集，
 * 勾一个文件就只重绘那一行——这是"批量操作不卡"的关键，而不是可选的优化。
 *
 * ## 视图模式切换
 * 切模式时整列 `viewType` 都变了，DiffUtil 派不上用场，直接 `notifyDataSetChanged()`。
 * 这是低频操作（用户主动点），代价可以接受。
 */
class FileListAdapter : ListAdapter<FileRowItem, RecyclerView.ViewHolder>(DIFF) {

    var viewMode: FileViewMode = FileViewMode.LIST
        set(value) {
            if (field == value) return
            field = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    /**
     * 缩略图来源。为 `null` 时只显示类型图标 —— 这是"没有预览模块也能正常浏览"的保证。
     */
    var thumbnails: ThumbnailProvider? = null

    /**
     * 加载缩略图用的作用域（通常是宿主的 `lifecycleScope`）。
     *
     * 必须由宿主提供的原因：Adapter 没有被销毁的通知（`onDetachedFromRecyclerView`
     * 在配置变更时不一定会来），自建作用域就有泄漏风险；而宿主的作用域天然随页面结束而取消。
     */
    var thumbnailScope: CoroutineScope? = null

    /** 每个位置的加载任务，用于"视图被回收/重绑"时立刻取消，避免把位图贴到错的条目上。 */
    private val thumbnailJobs = HashMap<Int, Job>()

    /** 详情模式下用于高亮当前排序列。 */
    var sortField: FsSortField = FsSortField.NAME

    var onItemClick: ((FileRowItem, Int) -> Unit)? = null

    var onItemLongClick: ((FileRowItem, Int) -> Boolean)? = null

    /** 行尾「更多」按钮；`anchor` 是按钮本身，供 PopupMenu 定位。 */
    var onMoreClick: ((FileRowItem, View) -> Unit)? = null

    override fun getItemViewType(position: Int): Int = when (viewMode) {
        FileViewMode.LIST -> TYPE_LIST
        FileViewMode.GRID -> TYPE_GRID
        FileViewMode.DETAILS -> TYPE_DETAILS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GRID -> GridHolder(ItemFileGridBinding.inflate(inflater, parent, false))
            TYPE_DETAILS -> DetailsHolder(ItemFileDetailsBinding.inflate(inflater, parent, false))
            else -> RowHolder(ItemFileRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is RowHolder -> holder.bind(item)
            is GridHolder -> holder.bind(item)
            is DetailsHolder -> holder.bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        // 回收即取消。不取消的话，一个已经被复用的 ImageView 会在几百毫秒后被塞进
        // **别的**条目的缩略图——表现为"快速滚动时图片乱跳"。
        //
        // 位置可能已经是 `NO_POSITION`（回收恰好发生在刷新期间），那种情况下取消不到任何
        // 任务；但每次 `bind` 都会覆盖 ImageView 的 tag，回调回来时对不上就会自行放弃，
        // 所以不需要额外的兜底路径。
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            thumbnailJobs.remove(position)?.cancel()
        }
        super.onViewRecycled(holder)
    }

    // ── ViewHolder ──────────────────────────────────────────────────────

    inner class RowHolder(private val binding: ItemFileRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.rowRoot.setOnClickListener { emitClick() }
            binding.rowRoot.setOnLongClickListener { emitLongClick() }
            binding.rowMore.setOnClickListener { emitMore(binding.rowMore) }
        }

        fun bind(item: FileRowItem) {
            val entry = item.entry
            binding.rowTitle.text = entry.name
            val subtitle = FileLabels.subtitle(entry)
            binding.rowSubtitle.text = subtitle
            binding.rowSubtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
            bindIcon(binding.rowIcon, entry, bindingAdapterPosition)
            bindState(binding.rowRoot, binding.rowCheck, item)
        }

        private fun emitClick() {
            val position = safePosition() ?: return
            onItemClick?.invoke(getItem(position), position)
        }

        private fun emitLongClick(): Boolean {
            val position = safePosition() ?: return false
            return onItemLongClick?.invoke(getItem(position), position) ?: false
        }

        private fun emitMore(anchor: View) {
            val position = safePosition() ?: return
            onMoreClick?.invoke(getItem(position), anchor)
        }
    }

    inner class GridHolder(private val binding: ItemFileGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.gridRoot.setOnClickListener { emitClick() }
            binding.gridRoot.setOnLongClickListener { emitLongClick() }
        }

        fun bind(item: FileRowItem) {
            binding.gridTitle.text = item.entry.name
            bindIcon(binding.gridIcon, item.entry, bindingAdapterPosition)
            bindState(binding.gridRoot, binding.gridCheck, item)
        }

        private fun emitClick() {
            val position = safePosition() ?: return
            onItemClick?.invoke(getItem(position), position)
        }

        private fun emitLongClick(): Boolean {
            val position = safePosition() ?: return false
            return onItemLongClick?.invoke(getItem(position), position) ?: false
        }
    }

    inner class DetailsHolder(private val binding: ItemFileDetailsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.detailsRoot.setOnClickListener { emitClick() }
            binding.detailsRoot.setOnLongClickListener { emitLongClick() }
        }

        fun bind(item: FileRowItem) {
            val entry = item.entry
            binding.detailsName.text = entry.name
            binding.detailsSize.text = FileLabels.sizeColumn(entry)
            binding.detailsTime.text = FileLabels.timeColumn(entry)
            binding.detailsType.text = FileLabels.typeLabel(entry)
            bindIcon(binding.detailsIcon, entry, bindingAdapterPosition)
            // 详细信息行没有独立勾选框：32dp 行高塞不下一个可点中的 checkbox，
            // 硬塞会让误触率飙升。改用"整行高亮 + 名称加粗"表达选中。
            binding.detailsName.setTypeface(
                null,
                if (item.selected) Typeface.BOLD else Typeface.NORMAL,
            )
            bindState(binding.detailsRoot, null, item)
        }

        private fun emitClick() {
            val position = safePosition() ?: return
            onItemClick?.invoke(getItem(position), position)
        }

        private fun emitLongClick(): Boolean {
            val position = safePosition() ?: return false
            return onItemLongClick?.invoke(getItem(position), position) ?: false
        }
    }

    // ── 共用绑定 ────────────────────────────────────────────────────────

    /**
     * 位置安全化。
     *
     * 点击回调里最容易踩的坑：列表在动画/刷新过程中发出点击，此时
     * `bindingAdapterPosition` 已经是 `NO_POSITION`，直接拿去 `getItem()` 就崩。
     */
    private fun RecyclerView.ViewHolder.safePosition(): Int? {
        val position = bindingAdapterPosition
        return if (position == RecyclerView.NO_POSITION) null else position
    }

    private fun bindIcon(target: ImageView, entry: FsEntry, position: Int) {
        // 先无条件落到"类型图标"。这一步是**同步**的，所以任何时刻滚动都有一张正确的图，
        // 不会出现空白格；缩略图到了再替换。
        drawTypeIcon(target, entry)

        val provider = thumbnails ?: return
        if (!FileTypeIcons.wantsThumbnail(entry)) return
        val scope = thumbnailScope ?: return

        // 用 tag 记住"这个 ImageView 现在代表哪个路径"。异步回调回来时若对不上，
        // 说明视图已经被复用给了别的条目，直接丢弃结果。
        target.tag = entry.path
        thumbnailJobs.remove(position)?.cancel()
        thumbnailJobs[position] = scope.launch {
            val bitmap = runCatching { provider.load(entry, THUMBNAIL_EDGE_PX) }.getOrNull()
                ?: return@launch
            if (target.tag != entry.path) return@launch
            target.imageTintList = null
            target.scaleType = ImageView.ScaleType.CENTER_CROP
            target.setImageBitmap(bitmap)
        }
    }

    /** 恢复类型图标（含 tint 与缩放模式）。 */
    private fun drawTypeIcon(target: ImageView, entry: FsEntry) {
        target.setImageResource(FileTypeIcons.iconRes(entry))
        target.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(target.context, FileTypeIcons.tintRes(entry)),
        )
        target.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun bindState(root: View, check: ImageView?, item: FileRowItem) {
        // isActivated 驱动 foreground 里的选中高亮（见 bg_row_activated.xml）
        root.isActivated = item.selected
        // 剪切中的条目半透明：让用户直观知道"这些一会儿会消失"
        root.alpha = if (item.pendingCut) 0.45f else 1f
        check ?: return
        check.visibility = if (item.selectionMode) View.VISIBLE else View.GONE
        if (item.selectionMode) {
            val color = if (item.selected) R.color.primary else R.color.text_disabled
            check.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(check.context, color))
        }
    }

    companion object {
        private const val TYPE_LIST = 0
        private const val TYPE_GRID = 1
        private const val TYPE_DETAILS = 2

        /**
         * 向 [ThumbnailProvider] 申请的边长（像素）。
         *
         * 取 96 dp 的等效像素：列表行图标 24 dp、网格瓦片 48 dp、详细信息行 28 dp，
         * 而高分屏下 96 dp 已经足够覆盖它们的最大显示尺寸（还能覆盖 2 倍放大的情况）。
         * 请求得比显示尺寸大是**故意**的——缩略图在深色/浅色切换、缩放动画里都会被重绘，
         * 刚好等于显示尺寸时边缘会发虚。
         */
        private val THUMBNAIL_EDGE_PX: Int
            get() = 96 * android.content.res.Resources.getSystem().displayMetrics.density.toInt()

        private val DIFF = object : DiffUtil.ItemCallback<FileRowItem>() {
            override fun areItemsTheSame(oldItem: FileRowItem, newItem: FileRowItem): Boolean =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: FileRowItem, newItem: FileRowItem): Boolean =
                oldItem == newItem
        }
    }
}

/**
 * 网格列数计算。
 *
 * 按"可用宽度 ÷ 最小瓦片宽度"算，而不是写死列数：
 * 手机竖屏自然得到 3 列、平板横屏得到 8 列，一份代码覆盖两形态。
 */
object GridSpec {
    fun spanCount(context: Context, availableWidthPx: Int): Int {
        val minWidth = context.resources.getDimensionPixelSize(R.dimen.grid_min_width)
        if (minWidth <= 0 || availableWidthPx <= 0) return 2
        return (availableWidthPx / minWidth).coerceAtLeast(2)
    }
}
