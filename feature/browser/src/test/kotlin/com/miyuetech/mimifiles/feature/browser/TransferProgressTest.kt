package com.miyuetech.mimifiles.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 圆形浮窗百分比的取值口径。
 *
 * 关键契约：**小文件批量复制时也必须给出数字**。1 KB 的文件几毫秒就复制完，
 * `onBytes` 可能一次都不回调，字节进度恒为未知；此时若照旧返回 UNKNOWN，
 * 圆形浮窗就只剩一个空转的圆环。
 */
class TransferProgressTest {

    private fun progress(
        totalItems: Int = 1,
        doneItems: Int = 0,
        currentBytes: Long = 0L,
        currentTotal: Long = TransferProgress.UNKNOWN_SIZE,
    ) = TransferProgress(
        kind = TransferKind.COPY,
        totalItems = totalItems,
        doneItems = doneItems,
        currentName = "a.bin",
        currentBytes = currentBytes,
        currentTotal = currentTotal,
        cancelRequested = false,
    )

    @Test
    fun 有字节进度时按字节算() {
        val p = progress(currentBytes = 700L, currentTotal = 1000L)

        assertEquals(70, p.currentPercent)
        assertEquals(70, p.bubblePercent)
    }

    @Test
    fun 小文件批量复制没有字节进度时按条目兜底() {
        val p = progress(totalItems = 4, doneItems = 1)

        assertEquals("字节未知", TransferProgress.UNKNOWN, p.currentPercent)
        assertEquals("按条目应给出 25%", 25, p.bubblePercent)
    }

    @Test
    fun 字节进度优先于条目进度() {
        // 第 1/10 项，但当前这个文件已传一半：圆环显示 50%，而不是 0% 或 10%。
        val p = progress(totalItems = 10, doneItems = 0, currentBytes = 50L, currentTotal = 100L)

        assertEquals(50, p.bubblePercent)
    }

    @Test
    fun 单条目且大小未知时保持不确定() {
        val p = progress(totalItems = 1, doneItems = 0)

        // 只有一个大文件、后端又不给大小时，按条目算恒为 0%，会被读成"卡住了"。
        assertEquals(TransferProgress.UNKNOWN, p.bubblePercent)
    }

    @Test
    fun 全部完成后是满格() {
        val p = progress(totalItems = 4, doneItems = 4)

        assertEquals(100, p.bubblePercent)
    }
}
