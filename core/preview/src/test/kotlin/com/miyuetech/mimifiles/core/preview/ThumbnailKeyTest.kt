package com.miyuetech.mimifiles.core.preview

import com.miyuetech.mimifiles.core.model.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存键的单测。
 *
 * 重点不是"哈希对不对"，而是**键的组成是否足以区分内容**：
 * 路径变了、大小变了、修改时间变了，都必须产生不同的键。
 * 漏掉任何一个都会导致"改了文件却还显示旧缩略图"这类极难归因的问题。
 */
class ThumbnailKeyTest {

    private val path = FsPath.of("local", "", "/sdcard/DCIM/Camera/IMG_0001.jpg")
    private val otherPath = FsPath.of("local", "", "/sdcard/DCIM/Camera/IMG_0002.jpg")

    @Test
    fun `同样的输入一定产生同样的键`() {
        val a = ThumbnailKey.of(path, 1024L, 5000L)
        val b = ThumbnailKey.of(path, 1024L, 5000L)
        assertEquals(a, b)
    }

    @Test
    fun `路径不同则键不同`() {
        assertNotEquals(
            ThumbnailKey.of(path, 1024L, 5000L),
            ThumbnailKey.of(otherPath, 1024L, 5000L),
        )
    }

    @Test
    fun `大小变化即键变化_这是文件被覆盖的判据`() {
        assertNotEquals(
            ThumbnailKey.of(path, 1024L, 5000L),
            ThumbnailKey.of(path, 2048L, 5000L),
        )
    }

    @Test
    fun `修改时间变化即键变化`() {
        assertNotEquals(
            ThumbnailKey.of(path, 1024L, 5000L),
            ThumbnailKey.of(path, 1024L, 6000L),
        )
    }

    @Test
    fun `键长度固定且只含十六进制字符`() {
        val key = ThumbnailKey.of(path, 1L, 1L)
        assertEquals(32, key.length)
        assertTrue("键里出现了非十六进制字符：$key", key.all { it in "0123456789abcdef" })
    }

    @Test
    fun `键可以直接当文件名用_不含路径分隔符`() {
        val key = ThumbnailKey.of(path, 1L, 1L)
        assertTrue(!key.contains('/'))
        assertTrue(!key.contains('\\'))
        assertTrue(!key.contains(':'))
    }

    @Test
    fun `网络路径与本机路径同名也不会撞键`() {
        val remote = FsPath.of("smb", "nas-1", "/share/IMG_0001.jpg")
        assertNotEquals(
            ThumbnailKey.of(path, 1024L, 5000L),
            ThumbnailKey.of(remote, 1024L, 5000L),
        )
    }

    @Test
    fun `分片目录取键的前两位`() {
        val key = ThumbnailKey.of(path, 1L, 1L)
        assertEquals(key.substring(0, 2), ThumbnailKey.shardOf(key))
    }

    @Test
    fun `极短键的分片不会越界`() {
        assertEquals("00", ThumbnailKey.shardOf("a"))
        assertEquals("00", ThumbnailKey.shardOf(""))
    }

    @Test
    fun `大小与时间未知时也能生成键_不抛异常`() {
        // 网络后端经常拿不到这两个值，键仍然可用（只是失去了"内容变化"的辨识力）
        val key = ThumbnailKey.of(path, -1L, 0L)
        assertTrue(key.isNotEmpty())
    }
}
