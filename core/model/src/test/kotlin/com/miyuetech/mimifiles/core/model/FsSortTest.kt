package com.miyuetech.mimifiles.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsSortTest {

    private fun dir(name: String) = FsEntry(
        path = FsPath.of("local", "", "/d/$name"),
        kind = FsKind.DIRECTORY,
    )

    private fun file(name: String, size: Long, time: Long) = FsEntry(
        path = FsPath.of("local", "", "/d/$name"),
        kind = FsKind.FILE,
        size = size,
        lastModified = time,
    )

    private val sample = listOf(
        file("b.txt", 300, 3_000),
        dir("zeta"),
        file("A.md", 100, 1_000),
        dir("alpha"),
        file("c.jpg", 200, 2_000),
    )

    @Test
    fun `默认按名称升序且目录在前`() {
        val names = sample.sortedBySpec(FsSort.DEFAULT).map { it.name }
        assertEquals(listOf("alpha", "zeta", "A.md", "b.txt", "c.jpg"), names)
    }

    @Test
    fun `关闭目录优先后严格按名称排`() {
        val names = sample.sortedBySpec(FsSort(directoriesFirst = false)).map { it.name }
        assertEquals(listOf("A.md", "alpha", "b.txt", "c.jpg", "zeta"), names)
    }

    @Test
    fun `按大小降序`() {
        val names = sample
            .sortedBySpec(FsSort(FsSortField.SIZE, ascending = false, directoriesFirst = false))
            .map { it.name }
        // 目录的大小是未知的，未知组永远垫底，组内按名称兜底 → alpha 在 zeta 前
        assertEquals(listOf("b.txt", "c.jpg", "A.md", "alpha", "zeta"), names)
    }

    @Test
    fun `大小未知的条目在升序降序下都排在最后`() {
        // SAF / SMB 经常拿不到 size，用 -1 表示未知。
        // 若直接比较 -1，它们在降序时会霸占"最大"的位置——用户看到的就是
        // 一堆没大小的东西挤在顶端，真实的大文件反而被压到下面。
        val list = listOf(
            file("known_small.txt", 10, 0),
            file("unknown.bin", FsEntry.SIZE_UNKNOWN, 0),
            file("known_big.txt", 1000, 0),
        )
        assertEquals(
            listOf("known_small.txt", "known_big.txt", "unknown.bin"),
            list.sortedBySpec(FsSort(FsSortField.SIZE, ascending = true)).map { it.name },
        )
        assertEquals(
            listOf("known_big.txt", "known_small.txt", "unknown.bin"),
            list.sortedBySpec(FsSort(FsSortField.SIZE, ascending = false)).map { it.name },
        )
    }

    @Test
    fun `按修改时间升序`() {
        val times = sample
            .sortedBySpec(FsSort(FsSortField.MODIFIED, directoriesFirst = false))
            .map { it.lastModified }
        assertEquals(listOf(0L, 0L, 1_000L, 2_000L, 3_000L), times)
    }

    @Test
    fun `按类型排序时同类型再按名称`() {
        val names = sample
            .sortedBySpec(FsSort(FsSortField.TYPE, directoriesFirst = false))
            .map { it.name }
        // 无扩展名的（两个目录）排最前，然后按扩展名 jpg → md → txt，各自内部按名称
        assertEquals(listOf("alpha", "zeta", "c.jpg", "A.md", "b.txt"), names)
    }

    @Test
    fun `toggled 同列翻转方向 异列切换并回到升序`() {
        val s = FsSort(FsSortField.NAME, ascending = true)
        assertEquals(FsSort(FsSortField.NAME, ascending = false), s.toggled(FsSortField.NAME))
        assertEquals(FsSort(FsSortField.SIZE, ascending = true), s.toggled(FsSortField.SIZE))
    }

    @Test
    fun `排序列头状态可推导`() {
        val s = FsSort(FsSortField.MODIFIED, ascending = false)
        assertTrue(s.field == FsSortField.MODIFIED)
        assertFalse(s.ascending)
    }
}
