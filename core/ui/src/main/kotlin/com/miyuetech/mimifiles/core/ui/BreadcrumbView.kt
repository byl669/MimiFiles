package com.miyuetech.mimifiles.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.ui.databinding.ItemBreadcrumbSegmentBinding

/**
 * 地址栏面包屑（横屏 Windows 资源管理器形态的核心部件之一）。
 *
 * ## 行为
 * - 从根到当前目录逐段展开，`内部存储 › DCIM › Camera`
 * - 点任意**祖先**段直接跳过去（末段禁用——点"当前目录"没有意义）
 * - 内容超宽时横向滚动，并**自动滚到末端**，让用户始终看到自己所在的层级
 *
 * ## 为什么不用 `HorizontalScrollView` 里套 `Breadcrumb` 的库
 * 需求里"每段可点 + 支持超长中文文件名 + 深色模式 + 自动滚到末尾"这四条，
 * 一个三十行的自定义 View 就够了；引第三方库反而要额外处理主题属性适配。
 */
class BreadcrumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.horizontalScrollViewStyle,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /** 点击某个祖先段。末段（当前目录）不会触发。 */
    var onSegmentClick: ((FsPath) -> Unit)? = null

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private var currentPath: FsPath? = null

    private var currentBase: FsPath? = null

    init {
        isHorizontalScrollBarEnabled = false
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    val path: FsPath? get() = currentPath

    /**
     * 重建面包屑。
     *
     * @param path  当前目录
     * @param base  起始段。传 `null` 时从**路径根**画起，这会把后端路径结构暴露给用户
     *              （本机存储会变成 `local › storage › emulated › 内部存储`——对用户而言
     *              `storage` 与 `emulated` 是无意义的中间层，而且 `local` 是我们的内部术语）。
     *              正确做法是传"当前所在的存储卷"，面包屑于是从 `内部存储 › DCIM › Camera`
     *              开始，长度也更适合手机屏幕。
     * @param labelOf 每段的显示名。根段的 `displayName` 会退化成 scheme（如 "local"），
     *                所以调用方通常需要把存储卷映射成"内部存储"这类人类可读的名字。
     */
    fun setPath(
        path: FsPath?,
        base: FsPath? = null,
        labelOf: (FsPath) -> String = { it.displayName },
    ) {
        if (path == currentPath && base == currentBase && container.childCount > 0) return
        currentPath = path
        currentBase = base
        container.removeAllViews()
        if (path == null) return

        val full = path.chainFromRoot()
        val startIndex = base
            ?.let { anchor -> full.indexOfFirst { it == anchor } }
            ?.takeIf { it >= 0 }
            ?: 0
        val chain = full.subList(startIndex, full.size)
        val inflater = LayoutInflater.from(context)

        chain.forEachIndexed { index, segment ->
            if (index > 0) {
                container.addView(buildChevron())
            }
            val binding = ItemBreadcrumbSegmentBinding.inflate(inflater, container, false)
            val isCurrent = index == chain.lastIndex
            binding.segmentLabel.text = labelOf(segment)
            binding.segmentLabel.isEnabled = !isCurrent
            // 祖先段稍暗、当前段全亮：一眼能看出"我在哪儿"
            binding.segmentLabel.alpha = if (isCurrent) 1f else 0.7f
            if (!isCurrent) {
                binding.segmentLabel.setOnClickListener { onSegmentClick?.invoke(segment) }
            }
            container.addView(binding.root)
        }

        // 自动滚到末端，保证当前层级可见
        post { fullScroll(FOCUS_RIGHT) }
    }

    private fun buildChevron(): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron_right)
        imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.text_disabled),
        )
        layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
