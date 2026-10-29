package com.miyuetech.mimifiles.feature.player

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.TextView

/**
 * 给播放控制条的白色图标加描边（M7.20）。
 *
 * ## 为什么需要它
 * M7.19 去掉了 Media3 控制条那层铺满全屏的 60% 黑遮罩（点屏幕时整幅画面会变暗，
 * 用户明确不要）。遮罩一去掉，白色图标就失去了衬托 —— 遇到雪景、白墙、
 * 文档录屏、亮色动画这类**高亮画面**，播放/暂停、±15/30 秒这些按钮几乎看不见。
 *
 * 解法不是把遮罩加回去，而是**只给图标本身描一圈黑边**：亮画面下图标靠描边
 * 立住，暗画面下描边融进背景、看不出多余装饰。这是 YouTube / VLC 的老办法。
 *
 * ## 为什么不用 LayerDrawable + setLayerInset
 * 第一版就是那么写的：四层黑色剪影各用 `setLayerInset(i, dx, dy, -dx, -dy)`
 * 平移 1dp。**实测完全不显示**（纯白画面上按钮区域仍是纯白 255）。
 * 用负 inset 让子层超出容器边界，在较新的 LayerDrawable 实现里不可靠 ——
 * 子层会被夹回容器范围，四层剪影于是完全叠在原图位置，等于什么也没画。
 *
 * 改为自己实现 [Drawable]：每层画之前 `canvas.translate()`，偏移由画布保证，
 * 与 LayerDrawable 的 inset 语义无关。
 *
 * ## 为什么不画真模糊阴影
 * `Paint.setShadowLayer` 只对文字和自己用 `canvas.drawPath/drawText` 画的图元
 * 生效，对 `Drawable.draw()` 无效（那层用的是 drawable 自己的 paint）。
 * 要做真投影得先把每张图标渲染成 Bitmap 再走 `BlurMaskFilter`，代价是状态一切换
 * （播放↔暂停）就得重新渲染并缓存。对 20~44dp 的单色图标，四方向偏移剪影
 * 看起来与描边无异，还零开销。
 */
internal object PlayerIconOutline {

    /** 描边色：半透明黑。纯黑在亮画面上过于生硬。 */
    private const val OUTLINE_COLOR = 0xCC000000.toInt()

    /** 偏移量（dp）。图标本体约 24dp，1dp 描边正好是一圈细边。 */
    private const val OFFSET_DP = 1f

    /**
     * 给 [view] 当前的图标包一层描边。已经是包装态则什么都不做。
     *
     * @return true = 这次真的包了一层（调用方可据此打日志）
     */
    fun outlineIcon(view: ImageView): Boolean {
        val current = view.drawable ?: return false
        // 幂等：tag 指向的就是当前这张图 → 已经在包装态，别再套一层
        // （套两层会得到 2dp 的粗边，视觉上立刻变糊）。
        if (view.tag === current) return false

        // ⚠️ `android:tint` 会把整层一起染色 —— 连黑色剪影也会被染白，描边于是
        // 凭空消失。先把 tint 落到原图上，再把 View 的 tint 清掉。
        // 我们自己的横竖屏/字幕按钮（从顶栏搬进控制条）正是带 tint 的那两个。
        val tintList = view.imageTintList
        val source = if (tintList != null) {
            current.mutate().apply { setTintList(tintList) }
        } else {
            current
        }
        if (tintList != null) view.imageTintList = null

        val state = source.constantState
        if (state == null) {
            // constantState 为 null 的 drawable（某些动态包装）无法复制出剪影，
            // 降级为不描边 —— 宁可少一圈边，也不要让图标消失。
            return false
        }

        val res = view.resources
        val offsetPx = (OFFSET_DP * res.displayMetrics.density).toInt().coerceAtLeast(1)
        val silhouettes = SILHOUETTE_OFFSETS.map { (dx, dy) ->
            val d = state.newDrawable(res).mutate()
            // 原图的 tint（若有）会被 newDrawable 继承，必须清掉，
            // 否则黑色剪影先被染白、再叠 colorFilter，得到"白色描边"。
            d.setTintList(null)
            d.colorFilter = PorterDuffColorFilter(OUTLINE_COLOR, PorterDuff.Mode.SRC_IN)
            d to (dx to dy)
        }

        val wrapped = OutlinedIconDrawable(source, silhouettes, offsetPx)
        view.tag = wrapped
        view.setImageDrawable(wrapped)
        return true
    }

    /**
     * 给 `TextView` 的 **background** 加描边。
     *
     * Media3 的「快退 15 / 快进 30」两张图标**既不是 ImageButton 的 src，也不是
     * compound drawable**，而是 `Button` 的 `android:background`
     * （见 `exo_player_control_ffwd_button.xml` 的 `ExoStyledControls.Button.Center.FfwdWithAmount`，
     * 它把 background 设成 `@drawable/exo_styled_controls_fastforward`，数字走 text）。
     * 这一层漏了，这两个按钮在纯白画面上就整整齐齐地消失。
     */
    fun outlineBackgroundIcon(view: TextView): Boolean {
        val bg = view.background ?: return false
        if (bg is OutlinedIconDrawable) return false
        val state = bg.constantState ?: return false

        val res = view.resources
        val offsetPx = (OFFSET_DP * res.displayMetrics.density).toInt().coerceAtLeast(1)
        val silhouettes = SILHOUETTE_OFFSETS.map { (dx, dy) ->
            val s = state.newDrawable(res).mutate()
            s.setTintList(null)
            s.colorFilter = PorterDuffColorFilter(OUTLINE_COLOR, PorterDuff.Mode.SRC_IN)
            s to (dx to dy)
        }
        view.background = OutlinedIconDrawable(bg, silhouettes, offsetPx)
        return true
    }

    /**
     * 给 `TextView` 的**复合图标**（`drawableTop` 等）加描边。
     *
     * Media3 的「快退 15 秒 / 快进 30 秒」不是 `ImageButton`，而是 `Button`
     * （图标 + 数字一起显示，见 `exo_player_control_rewind_button.xml`）。
     * 它们的图标在 compound drawable 上，只遍历 `ImageView` 会整整齐齐漏掉这两个 ——
     * 实测正是这两个按钮在纯白画面上完全消失。
     */
    fun outlineCompoundIcon(view: TextView): Boolean {
        val draws = view.compoundDrawablesRelative
        // 已经包过就不再套（判断任一张即可，四个方向同进同出）。
        if (draws.any { it is OutlinedIconDrawable }) return false
        if (draws.all { it == null }) return false

        // `Button` 的 `android:drawableTint` 会在绘制时给每张 compound drawable
        // 套一层 tint —— 和 ImageButton 的 `imageTintList` 是同一个坑：黑色剪影会被
        // 整片染白，描边等于没画。先把 tint 落到原图，再把整体 tint 清掉。
        val tintList = view.compoundDrawableTintList
        if (tintList != null) view.compoundDrawableTintList = null

        val res = view.resources
        val offsetPx = (OFFSET_DP * res.displayMetrics.density).toInt().coerceAtLeast(1)
        var wrapped = false
        val replaced = Array(4) { i ->
            val d = draws.getOrNull(i) ?: return@Array null
            val base = if (tintList != null) d.mutate().apply { setTintList(tintList) } else d
            val state = base.constantState ?: return@Array base
            val silhouettes = SILHOUETTE_OFFSETS.map { (dx, dy) ->
                val s = state.newDrawable(res).mutate()
                s.setTintList(null)
                s.colorFilter = PorterDuffColorFilter(OUTLINE_COLOR, PorterDuff.Mode.SRC_IN)
                s to (dx to dy)
            }
            wrapped = true
            OutlinedIconDrawable(base, silhouettes, offsetPx)
        }
        if (!wrapped) return false
        view.setCompoundDrawablesRelative(replaced[0], replaced[1], replaced[2], replaced[3])
        return true
    }

    /**
     * 时间文字加投影。
     *
     * `TextView` 的 `setShadowLayer` 是**框架直接支持**的（文字走 `Paint` 的文字
     * 绘制路径），不像图标那样需要自己拼。半径 3dp、只向下偏 1dp：足够在亮画面上
     * 勾出字，又不会糊成一团。
     */
    fun shadowText(view: TextView) {
        if (view.tag === SHADOW_TAG) return
        view.tag = SHADOW_TAG
        view.setShadowLayer(3f * view.resources.displayMetrics.density, 0f, 1f, OUTLINE_COLOR)
    }

    /** 四个对角方向，足够围出一圈闭合的边。 */
    private val SILHOUETTE_OFFSETS = listOf(1 to 1, -1 to 1, 1 to -1, -1 to -1)

    /** 时间文字的标记，避免重复设阴影（`setShadowLayer` 会触发重绘）。 */
    private val SHADOW_TAG = Any()

    /**
     * 描边图标：先按四方向偏移画黑色剪影，再把原图盖在正中。
     *
     * 原图比剪影**内缩** [offsetPx]：这样描边完整落在原图外侧，
     * 而不是一半压在图标身上（那会让图标显得变胖）。
     */
    private class OutlinedIconDrawable(
        private val base: Drawable,
        private val layers: List<Pair<Drawable, Pair<Int, Int>>>,
        private val offsetPx: Int,
    ) : Drawable(), Drawable.Callback {

        private val tmp = Rect()

        init {
            // 让内层拿到 invalidate 回调，动画/着色变化能及时刷新。
            base.callback = this
            layers.forEach { it.first.callback = this }
        }

        // ── Drawable.Callback：把内层的失效请求转给宿主 ────────────
        override fun invalidateDrawable(who: Drawable) = invalidateSelf()

        override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) =
            scheduleSelf(what, whenMillis)

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return

            for ((d, off) in layers) {
                d.setBounds(b)
                canvas.save()
                canvas.translate((off.first * offsetPx).toFloat(), (off.second * offsetPx).toFloat())
                d.draw(canvas)
                canvas.restore()
            }

            // 原图内缩一点，给描边让出完整的一圈。
            tmp.set(b.left + offsetPx, b.top + offsetPx, b.right - offsetPx, b.bottom - offsetPx)
            base.setBounds(tmp)
            base.draw(canvas)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            invalidateSelf()
        }

        override fun setAlpha(alpha: Int) {
            base.alpha = alpha
            layers.forEach { it.first.alpha = alpha }
            invalidateSelf()
        }

        /** 注意：这个会覆盖剪影自身的黑色 colorFilter，所以外层不该再用 setColorFilter。 */
        override fun setColorFilter(colorFilter: ColorFilter?) {
            base.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = base.intrinsicWidth

        override fun getIntrinsicHeight(): Int = base.intrinsicHeight

        override fun getConstantState(): ConstantState? = null
    }
}
