package com.miyuetech.mimifiles.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「删掉这一部之后接着播谁」的回归测试（M7.24）。
 *
 * 这几条分支的代价不对称：写错了就是**用户以为在播下一部、实际播的是上一部**，
 * 或者在审片时把顺序整乱、漏看片子。它们全是纯下标运算，适合用单测钉死。
 */
class DeleteAdvanceTest {

    @Test
    fun `删中间的视频_接着播原来下一个（左移一格）`() {
        val names = listOf("a.mp4", "b.mp4", "c.mp4")
        // 删掉 b（下标 1）→ 新列表是 [a, c]，c 落在下标 1
        assertEquals(1, DeleteAdvance.nextAfterDelete(names, 1))
    }

    @Test
    fun `删最后一个_后面没有了返回负一`() {
        val names = listOf("a.mp4", "b.mp4")
        assertEquals(-1, DeleteAdvance.nextAfterDelete(names, 1))
    }

    @Test
    fun `只有一个视频时删掉_返回负一`() {
        assertEquals(-1, DeleteAdvance.nextAfterDelete(listOf("only.mp4"), 0))
    }

    @Test
    fun `中间夹着图片_跳过图片继续找视频`() {
        val names = listOf("a.mp4", "shot.jpg", "b.mp4")
        // 删掉 a（下标 0）→ 新列表 [shot.jpg, b.mp4]，b 落在下标 1
        assertEquals(1, DeleteAdvance.nextAfterDelete(names, 0))
    }

    @Test
    fun `后面只有图片_返回负一（不能把图片当视频播）`() {
        val names = listOf("a.mp4", "x.jpg", "y.png")
        assertEquals(-1, DeleteAdvance.nextAfterDelete(names, 0))
    }

    @Test
    fun `不倒退_前面还有视频也不回去`() {
        val names = listOf("a.mp4", "b.mp4")
        // 删掉最后一部 b，前面还有 a —— 按产品要求是退出，不是回去播 a
        assertEquals(-1, DeleteAdvance.nextAfterDelete(names, 1))
    }

    @Test
    fun `下标越界_安全返回负一`() {
        assertEquals(-1, DeleteAdvance.nextAfterDelete(listOf("a.mp4"), 5))
        assertEquals(-1, DeleteAdvance.nextAfterDelete(emptyList(), 0))
    }

    @Test
    fun `音频也算可播`() {
        val names = listOf("a.mp4", "b.flac")
        // 删掉 a（下标 0）→ b 左移到下标 0
        assertEquals(0, DeleteAdvance.nextAfterDelete(names, 0))
    }
}
