package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FsMutations] 的语义测试。
 *
 * 这里真正要钉住的是「**消费即清除**」和「重复标记可合并」两条：
 * 前者失效 → 每次恢复浏览页都白刷一遍 NAS 目录；后者失效 → 连删三个片子刷三次。
 * 两条都不会崩、不会报错，只会让远端用户觉得"这破应用卡"，所以靠测试守。
 */
class FsMutationsTest {

    private fun dir(name: String) = FsPath.of("local", "", "/storage/emulated/0/Movies/$name")

    @Test
    fun `标记过的目录消费一次就没了`() {
        val d = dir("d1")
        FsMutations.markChanged(d)
        assertTrue(FsMutations.consumeChanged(d))
        // 第二次必须拿不到：否则浏览页每次 onResume 都会白刷一遍
        assertFalse(FsMutations.consumeChanged(d))
    }

    @Test
    fun `没标记过的目录不消费`() {
        assertFalse(FsMutations.consumeChanged(dir("d2")))
    }

    @Test
    fun `同一目录重复标记只算一次`() {
        val d = dir("d3")
        FsMutations.markChanged(d)
        FsMutations.markChanged(d)
        FsMutations.markChanged(d)
        assertTrue(FsMutations.consumeChanged(d))
        assertFalse(FsMutations.consumeChanged(d))
    }

    @Test
    fun `不同目录互不干扰`() {
        val a = dir("d4")
        val b = dir("d5")
        FsMutations.markChanged(a)
        assertFalse(FsMutations.consumeChanged(b))
        assertTrue(FsMutations.consumeChanged(a))
    }

    @Test
    fun `传 null 是空操作`() {
        // 没有父目录的路径（卷根）不该炸，也不该记下任何东西
        FsMutations.markChanged(null)
        assertFalse(FsMutations.consumeChanged(null))
    }

    @Test
    fun `超过上限时丢最老的而不是无限增长`() {
        val dirs = (1..20).map { dir("over$it") }
        dirs.forEach(FsMutations::markChanged)
        // 上限 16：最老的四个已经被挤掉
        assertFalse(FsMutations.consumeChanged(dirs[0]))
        assertFalse(FsMutations.consumeChanged(dirs[3]))
        // 后 16 个还在
        assertTrue(FsMutations.consumeChanged(dirs[4]))
        assertTrue(FsMutations.consumeChanged(dirs[19]))
    }
}
