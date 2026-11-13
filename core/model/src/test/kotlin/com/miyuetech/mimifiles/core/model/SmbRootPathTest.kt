package com.miyuetech.mimifiles.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「SMB 主目录」这条新路径的地基契约。
 *
 * 连接不填共享名时，挂载点是 [ConnectionProfile.rootFsPath] 给出的
 * `smb://<id>`（[FsPath.isRoot]）；点进去列出的每个共享是它的直接子节点。
 * 另外钉住 [ConnectionProfile.dedupeKey]：复制连接的防呆校验完全依赖它，
 * 如果这个键把 id 算进去，或者不比较 rootPath，"改个共享名就能新增"就不成立。
 */
class SmbRootPathTest {

    private val id = "smb-abcd1234"

    @Test
    fun `共享名留空时挂载点是主目录根`() {
        val profile = ConnectionProfile(id = id, scheme = "smb", displayName = "NAS", host = "192.168.2.10")
        val root = profile.rootFsPath()
        assertTrue(root.isRoot)
        assertEquals("", root.path)
        assertEquals("smb://$id", root.toString())
        assertEquals(id, root.authority)
    }

    @Test
    fun `主目录的子项是共享，共享的上一级回到主目录`() {
        val root = FsPath.of("smb", id)
        val share = root.child("home")
        assertEquals("smb://$id/home", share.toString())
        assertEquals("home", share.displayName)
        assertTrue("共享的 parent 是主目录根", share.parent?.isRoot == true)
        assertNull(share.parent?.parent)
    }

    @Test
    fun `填了共享名的挂载点不是根`() {
        val profile = ConnectionProfile(
            id = id, scheme = "smb", displayName = "NAS", host = "192.168.2.10", rootPath = "home/2024",
        )
        val mount = profile.rootFsPath()
        assertFalse(mount.isRoot)
        assertEquals("smb://$id/home/2024", mount.toString())
    }

    @Test
    fun `主目录与共享的文本形式可被 parse 无损还原`() {
        val root = FsPath.of("smb", id)
        assertEquals(root, FsPath.parse(root.toString()))
        val share = root.child("home")
        assertEquals(share, FsPath.parse(share.toString()))
    }

    @Test
    fun `dedupeKey 能识别「改了共享名」的副本`() {
        val source = ConnectionProfile(
            id = id, scheme = "smb", displayName = "NAS", host = "192.168.2.10", rootPath = "home",
        )
        val unchanged = source.copy(id = "new-id", displayName = "NAS（副本）")
        val changed = unchanged.copy(rootPath = "video")
        val toMain = unchanged.copy(rootPath = "")
        assertEquals("只改 id 与显示名仍指向同一位置", source.dedupeKey, unchanged.dedupeKey)
        assertNotEquals("改了共享名就是新位置", source.dedupeKey, changed.dedupeKey)
        assertNotEquals("改成主目录也是新位置", source.dedupeKey, toMain.dedupeKey)
    }

    @Test
    fun `主目录连接之间仍会互相判重`() {
        val a = ConnectionProfile(id = "a", scheme = "smb", displayName = "NAS", host = "192.168.2.10")
        val b = a.copy(id = "b")
        assertEquals(a.dedupeKey, b.dedupeKey)
    }
}
