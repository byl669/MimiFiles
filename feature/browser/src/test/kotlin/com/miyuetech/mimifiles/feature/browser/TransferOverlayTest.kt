package com.miyuetech.mimifiles.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度浮窗形态的契约（M7.28）。
 *
 * 这一组测试钉住的是那个真实 bug 的边界：**"最小化"必须仍然可见**，
 * 而"搬运结束"才是唯一不再显示的理由。旧实现把两者混成同一个布尔量，
 * 结果一次系统关闭（切后台）就等于永久隐藏。
 */
class TransferOverlayTest {

    @Test
    fun 没有搬运时不画任何浮窗() {
        val overlay = TransferOverlay()

        assertEquals(TransferSurface.NONE, overlay.surface(hasProgress = false))
    }

    @Test
    fun 有搬运时默认是大浮窗() {
        val overlay = TransferOverlay()

        assertFalse(overlay.isMinimized)
        assertEquals(TransferSurface.DIALOG, overlay.surface(hasProgress = true))
    }

    @Test
    fun 最小化后变成圆形浮窗而不是消失() {
        val overlay = TransferOverlay()

        overlay.minimize()

        assertTrue(overlay.isMinimized)
        assertEquals(
            "最小化之后进度仍必须可见，否则用户只剩通知栏",
            TransferSurface.BUBBLE,
            overlay.surface(hasProgress = true),
        )
    }

    @Test
    fun 点圆形浮窗还原成大浮窗() {
        val overlay = TransferOverlay()
        overlay.minimize()

        overlay.restore()

        assertFalse(overlay.isMinimized)
        assertEquals(TransferSurface.DIALOG, overlay.surface(hasProgress = true))
    }

    @Test
    fun 搬运结束后下一次搬运重新从大浮窗开始() {
        val overlay = TransferOverlay()
        overlay.minimize()

        // 搬运结束：进度归空。
        overlay.clear()
        assertEquals(TransferSurface.NONE, overlay.surface(hasProgress = false))

        // 下一次搬运。
        assertEquals(TransferSurface.DIALOG, overlay.surface(hasProgress = true))
    }

    @Test
    fun 已最小化但搬运已结束时依然什么都不画() {
        val overlay = TransferOverlay()
        overlay.minimize()

        // 即使还带着最小化标记，没有进度时也不该留一个停在 0% 的空浮窗。
        assertEquals(TransferSurface.NONE, overlay.surface(hasProgress = false))
    }
}
