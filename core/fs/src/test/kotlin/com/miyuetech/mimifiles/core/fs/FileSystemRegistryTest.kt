package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileSystemRegistryTest {

    private class FakeResolver(
        override val scheme: String,
        override val displayName: String = scheme.uppercase(),
        override val capabilities: Int = FsCapability.FULL_LOCAL,
        private val failure: FsException? = null,
    ) : FileSystemResolver {
        val fs = MemoryFileSystem(scheme)
        var releasedAuthorities = mutableListOf<String>()

        override suspend fun resolve(authority: String): FileSystem {
            failure?.let { throw it }
            return fs
        }

        override suspend fun release(authority: String) {
            releasedAuthorities += authority
        }
    }

    private fun path(scheme: String, authority: String = "") = FsPath.of(scheme, authority, "/x")

    @Test
    fun `注册后可解析到实例`() = runBlocking {
        val resolver = FakeResolver("local")
        val registry = FileSystemRegistry().apply { register(resolver) }
        assertSame(resolver.fs, registry.resolve(path("local")))
    }

    @Test
    fun `scheme 大小写不敏感`() = runBlocking {
        val registry = FileSystemRegistry().apply { register(FakeResolver("SMB")) }
        assertTrue(registry.isRegistered("smb"))
        assertTrue(registry.isRegistered("SMB"))
        assertSame(registry.resolve(path("smb")), registry.resolve(path("SMB")))
    }

    @Test
    fun `authority 被透传给解析器`() = runBlocking {
        val seen = mutableListOf<String>()
        val registry = FileSystemRegistry().apply {
            register(object : FileSystemResolver {
                override val scheme = "smb"
                override val displayName = "SMB"
                override val capabilities = FsCapability.FULL_LOCAL
                override suspend fun resolve(authority: String): FileSystem {
                    seen += authority
                    return MemoryFileSystem("smb")
                }
            })
        }
        registry.resolve(path("smb", "nas-01"))
        assertEquals(listOf("nas-01"), seen)
    }

    @Test
    fun `未注册的 scheme 解析抛不支持`() = runBlocking {
        val registry = FileSystemRegistry()
        try {
            registry.resolve(path("ftp"))
            fail("未注册的 scheme 应抛 FsNotSupportedException")
        } catch (e: FsNotSupportedException) {
            assertEquals("ftp", e.scheme)
            assertEquals("resolve", e.operation)
        }
    }

    @Test
    fun `未注册时 resolveOrNull 返回 null 而不是抛异常`() = runBlocking {
        assertNull(FileSystemRegistry().resolveOrNull(path("ftp")))
    }

    @Test
    fun `解析器报错时 resolveOrNull 返回 null`() = runBlocking {
        val registry = FileSystemRegistry().apply {
            register(FakeResolver("smb", failure = FsAuthException("nas-01")))
        }
        assertNull(registry.resolveOrNull(path("smb", "nas-01")))
    }

    @Test
    fun `解析器报错时 resolve 原样抛出语义化异常`() = runBlocking {
        val registry = FileSystemRegistry().apply {
            register(FakeResolver("smb", failure = FsAuthException("nas-01")))
        }
        try {
            registry.resolve(path("smb", "nas-01"))
            fail("应抛出 FsAuthException")
        } catch (e: FsAuthException) {
            assertEquals("nas-01", e.authority)
        }
    }

    @Test
    fun `同步查询能力与展示名`() {
        val registry = FileSystemRegistry().apply {
            register(FakeResolver("saf", displayName = "SAF 授权目录", capabilities = FsCapability.READ_ONLY))
        }
        assertEquals(FsCapability.READ_ONLY, registry.capabilitiesOf("saf"))
        assertEquals("SAF 授权目录", registry.displayNameOf("saf"))
        // 未注册降级为无能力与 scheme 本身，UI 不必写 null 分支
        assertEquals(FsCapability.NONE, registry.capabilitiesOf("nope"))
        assertEquals("nope", registry.displayNameOf("nope"))
    }

    @Test
    fun `重复注册同 scheme 覆盖旧的`() = runBlocking {
        val first = FakeResolver("local")
        val second = FakeResolver("local")
        val registry = FileSystemRegistry().apply {
            register(first)
            register(second)
        }
        assertSame(second.fs, registry.resolve(path("local")))
        assertEquals(1, registry.schemes().size)
    }

    @Test
    fun `unregister 与 clear`() {
        val registry = FileSystemRegistry().apply {
            register(FakeResolver("a"))
            register(FakeResolver("b"))
        }
        assertTrue(registry.unregister("a"))
        assertFalse(registry.unregister("a"))
        assertEquals(listOf("b"), registry.schemes())
        registry.clear()
        assertTrue(registry.schemes().isEmpty())
    }

    @Test
    fun `release 转发到对应解析器`() = runBlocking {
        val resolver = FakeResolver("smb")
        val registry = FileSystemRegistry().apply { register(resolver) }
        registry.release(path("smb", "nas-01"))
        assertEquals(listOf("nas-01"), resolver.releasedAuthorities)
    }

    @Test
    fun `release 对未注册 scheme 静默忽略`() = runBlocking {
        FileSystemRegistry().release(path("nope"))
    }

    @Test
    fun `注册顺序被保留 便于侧栏稳定展示`() {
        val registry = FileSystemRegistry().apply {
            register(FakeResolver("local"))
            register(FakeResolver("smb"))
            register(FakeResolver("webdav"))
        }
        assertEquals(listOf("local", "smb", "webdav"), registry.schemes())
    }
}
