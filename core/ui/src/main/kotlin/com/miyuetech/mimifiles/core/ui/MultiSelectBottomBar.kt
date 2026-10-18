package com.miyuetech.mimifiles.core.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.forEach
import com.miyuetech.mimifiles.core.ui.MultiSelectBar.Action

/**
 * 多选「底栏」（M7.16，ES 文件浏览器式上/下双栏的下半）——
 * 图标在上、文字在下的操作瓦片**均分**整行宽度，固定在屏幕底部。
 *
 * ## 与旧顶栏动作行的关系
 * 动作集仍来自 [MultiSelectBar.actionsFor]（唯一事实源），本栏只负责
 * 「怎么摆」：最多 4 枚主操作平铺，其余收进「更多」弹出菜单。
 * 主操作的挑选按 [PRIORITY] 排序——复制/剪切/重命名/删除是文件管理器
 * 最高频的四件事（ES 同款），分享/属性/收藏/添加到桌面进溢出。
 *
 * ## 为什么瓦片用 weight 均分而不是 wrap_content
 * ES 的底栏按钮等距铺满全宽，视觉稳定、拇指落点可预期；wrap_content
 * 会随动作数量左右漂移（选 1 项和选 5 项按钮位置完全不同），反而难用。
 */
class MultiSelectBottomBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点某枚瓦片（含「更多」里的项）。宿主负责真正执行。 */
    var onAction: ((Action) -> Unit)? = null

    private val tileHeight = resources.getDimensionPixelSize(R.dimen.select_bottom_height)
    private val tileIcon = resources.getDimensionPixelSize(R.dimen.select_bottom_icon)
    private val tileTextSize = resources.getDimension(R.dimen.select_bottom_text)
    private val colorPrimaryText = ContextCompat.getColor(context, R.color.text_primary)
    private val colorDanger = ContextCompat.getColor(context, R.color.button_text_color_red)

    init {
        orientation = HORIZONTAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.select_bar_bg))
    }

    /** 显示并按 [actions] 渲染瓦片（SELECT_ALL 在顶栏，这里自动忽略）。 */
    fun show(actions: List<Action>) {
        visibility = View.VISIBLE
        removeAllViews()

        val usable = actions.filter { it != Action.SELECT_ALL }
        // 稳定排序：PRIORITY 里的按序靠前，不在表里的保持原顺序垫底
        val ordered = usable.sortedBy { action ->
            PRIORITY.indexOf(action).let { if (it >= 0) it else PRIORITY.size + usable.indexOf(action) }
        }
        val primary = ordered.take(MAX_PRIMARY)
        val overflow = ordered.drop(MAX_PRIMARY)

        primary.forEach { addView(buildTile(it)) }
        if (overflow.isNotEmpty()) addView(buildMoreTile(overflow))
    }

    fun hide() {
        visibility = View.GONE
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 一枚「图标上、文字下」的操作瓦片，weight=1 均分行宽。 */
    private fun buildTile(action: Action): View =
        buildTileCore(
            iconRes = action.iconRes,
            label = context.getString(action.labelRes),
            tint = if (action == Action.DELETE) colorDanger else colorPrimaryText,
        ).apply {
            contentDescription = context.getString(action.labelRes)
            setOnClickListener { onAction?.invoke(action) }
        }

    /** 「更多」瓦片：点开弹出溢出菜单，收纳剩余动作。 */
    private fun buildMoreTile(overflow: List<Action>): View =
        buildTileCore(
            iconRes = R.drawable.ic_more_vert,
            label = context.getString(R.string.action_more),
            tint = colorPrimaryText,
        ).apply {
            setOnClickListener { anchor -> showOverflowMenu(anchor, overflow) }
        }

    /**
     * 瓦片骨架：图标 + 文字竖排、矩形涟漪（borderless 会被相邻 weight 瓦片连片）。
     *
     * ## 为什么两个子 View 都必须显式给 LayoutParams（M7.16 真机错位 bug）
     * 竖直 LinearLayout 的 `generateDefaultLayoutParams` 给的默认宽度是
     * **match_parent**（不是直觉上的 wrap_content）。文字瓦片漏设了参数，
     * 于是 TextView 铺满整枚瓦片宽、文字在里头**齐左**排——真机上表现为
     * 「图标居中、文字贴瓦片左边缘」的错位。显式 wrap_content + 父级
     * gravity CENTER 才是想要的「图标上、文字下、双双居中」。
     */
    private fun buildTileCore(iconRes: Int, label: String, tint: Int): LinearLayout {
        val typed = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            typed,
            true,
        )
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, tileHeight, 1f)
            isClickable = true
            isFocusable = true
            setBackgroundResource(typed.resourceId)

            addView(
                ImageView(context).apply {
                    layoutParams = LayoutParams(
                        tileIcon,
                        tileIcon,
                    ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    imageTintList = android.content.res.ColorStateList.valueOf(tint)
                    setImageResource(iconRes)
                },
            )
            addView(
                TextView(context).apply {
                    layoutParams = LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                    includeFontPadding = false
                    maxLines = 1
                    textSize = pxToSp(tileTextSize)
                    setTextColor(tint)
                    text = label
                },
            )
        }
    }

    /**
     * 溢出菜单。带图标：`setOptionalIconsVisible` 是 MenuBuilder 的隐藏方法，
     * 反射打开（失败就纯文字，不影响功能——图标只是锦上添花）。
     */
    private fun showOverflowMenu(anchor: View, actions: List<Action>) {
        val popup = PopupMenu(context, anchor)
        actions.forEachIndexed { index, action ->
            popup.menu.add(0, action.ordinal, index, context.getString(action.labelRes))
                .setIcon(action.iconRes)
        }
        runCatching {
            popup.menu.javaClass
                .getDeclaredMethod("setOptionalIconsVisible", Boolean::class.javaPrimitiveType)
                .invoke(popup.menu, true)
            // 菜单里的图标不带主题色，统一染上（删除红已在主栏，这里全是常规色）
            popup.menu.forEach { item ->
                item.icon?.mutate()?.setTintList(
                    android.content.res.ColorStateList.valueOf(colorPrimaryText),
                )
            }
        }
        popup.setOnMenuItemClickListener { item ->
            Action.entries.firstOrNull { it.ordinal == item.itemId }?.let { onAction?.invoke(it) }
            true
        }
        popup.show()
    }

    private fun pxToSp(px: Float): Float =
        px / resources.displayMetrics.scaledDensity

    companion object {

        /** 底栏最多平铺的主操作数（4 枚主操作 + 1 枚「更多」= 5，均分不拥挤）。 */
        private const val MAX_PRIMARY = 4

        /** 主操作优先级（ES 同款：复制/剪切/删除/重命名最高频）。表外动作垫后。 */
        private val PRIORITY = listOf(
            Action.COPY,
            Action.CUT,
            Action.RENAME,
            Action.DELETE,
            Action.SHARE,
            Action.FAVORITE,
            Action.UNFAVORITE,
            Action.ADD_TO_HOME,
            Action.INFO,
        )
    }
}
