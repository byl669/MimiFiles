package com.miyuetech.mimifiles.core.fssmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SmbShareNames] 的契约：jcifs 服务器根列表返回的名字要能**直接**喂给
 * `FsPath.child(name)`，否则主目录里点开共享就会抛「child name must not be blank」
 * 或拼出两段路径。
 *
 * 真实服务端的返回形态在 JVM 单测里造不出来，但这些净化规则是纯字符串逻辑，
 * 值得用快测钉死。
 */
class SmbShareNamesTest {

    @Test
    fun `去掉尾斜杠与反斜杠`() {
        // 返回值同时是排序后的，所以顺序是 home < photo < public。
        assertEquals(
            listOf("home", "photo", "public"),
            SmbShareNames.sanitize(listOf("home/", "public/", "photo\\")),
        )
    }

    @Test
    fun `空名与点段被丢掉`() {
        assertEquals(
            listOf("home"),
            SmbShareNames.sanitize(listOf("", "   ", "/", ".", "..", "home/")),
        )
    }

    @Test
    fun `管理共享与非磁盘共享不显示`() {
        // IPC$ / ADMIN$ / C$ / print$ 都是"点进去必然报不支持的共享类型"的系统对象。
        assertEquals(
            listOf("home", "video"),
            SmbShareNames.sanitize(listOf("IPC$/", "ADMIN$/", "C$/", "print$/", "home/", "video/")),
        )
    }

    @Test
    fun `大小写不敏感去重且保留首次出现的写法`() {
        assertEquals(
            listOf("Home", "video"),
            SmbShareNames.sanitize(listOf("Home/", "home/", "HOME/", "video/")),
        )
    }

    @Test
    fun `结果按名字排序`() {
        val got = SmbShareNames.sanitize(listOf("video/", "Album/", "home/"))
        assertEquals(listOf("Album", "home", "video"), got)
    }

    @Test
    fun `空列表返回空列表`() {
        assertTrue(SmbShareNames.sanitize(emptyList()).isEmpty())
    }

    @Test
    fun `首尾空白被清理`() {
        assertEquals(listOf("home"), SmbShareNames.sanitize(listOf("  home/  ")))
    }

    @Test
    fun `中文共享名保持原样`() {
        // 排序按字符码点：影(U+5F71) 在 照(U+7167) 之前，与输入顺序无关。
        assertEquals(
            listOf("影音", "照片"),
            SmbShareNames.sanitize(listOf("照片\\", "影音/")),
        )
    }
}
