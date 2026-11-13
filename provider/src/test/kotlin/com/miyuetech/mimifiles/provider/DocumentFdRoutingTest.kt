package com.miyuetech.mimifiles.provider

import com.miyuetech.mimifiles.core.model.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「哪些 documentId 直开真实 fd」的纯逻辑单测（M7.27）。
 *
 * 不碰 DocumentsProvider / StorageManager：这条分流规则的价值就在于它**能被 JVM 测**，
 * 不然一处 `scheme` 写错就会让 7 GB 的本机文件重新掉回 FUSE 慢路径，
 * 而那种回归在单测里看不出来、只在真机传大文件时才暴露。
 */
class DocumentFdRoutingTest {

    private fun local(absolutePath: String): FsPath = FsPath.of("local", "", absolutePath)

    private fun remote(scheme: String): FsPath = FsPath.of(scheme, "nas-01", "/share/big.iso")

    @Test
    fun 本机路径直开真实fd() {
        assertTrue(DocumentFdRouting.isLocal(local("/storage/emulated/0/a.mkv")))
    }

    @Test
    fun 远端三种后端继续走代理fd() {
        assertFalse(DocumentFdRouting.isLocal(remote("smb")))
        assertFalse(DocumentFdRouting.isLocal(remote("webdav")))
        assertFalse(DocumentFdRouting.isLocal(remote("saf")))
    }

    @Test
    fun 存在的本机文件可以直开() {
        val file = File.createTempFile("mimi-fd-", ".bin").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        assertEquals(file.absolutePath, DocumentFdRouting.directFile(local(file.absolutePath))?.absolutePath)
    }

    @Test
    fun 不存在的本机文件不能直开() {
        val dir = Files.createTempDirectory("mimi-fd-missing-").toFile().apply { deleteOnExit() }
        assertNull(DocumentFdRouting.directFile(local(File(dir, "nope.bin").absolutePath)))
    }

    @Test
    fun 目录不能当成文件直开() {
        val dir = Files.createTempDirectory("mimi-fd-dir-").toFile().apply { deleteOnExit() }
        assertNull(DocumentFdRouting.directFile(local(dir.absolutePath)))
    }

    @Test
    fun 远端路径永远拿不到直开文件() {
        assertNull(DocumentFdRouting.directFile(remote("smb")))
    }

    @Test
    fun 本机路径契约是空authority加绝对路径() {
        val path = local("/storage/emulated/0/电影/纪录片 4K.mkv")
        assertEquals("", path.authority)
        assertEquals("/storage/emulated/0/电影/纪录片 4K.mkv", path.path)
        assertTrue(DocumentFdRouting.isLocal(path))
    }
}
