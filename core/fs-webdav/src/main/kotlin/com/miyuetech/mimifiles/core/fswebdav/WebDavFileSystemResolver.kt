package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.FileSystemResolver
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.network.NetworkDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `webdav` scheme 的解析器。
 *
 * 结构与 [com.miyuetech.mimifiles.core.fssmb.SmbFileSystemResolver] 一致：
 * 数据来源用函数注入（本模块不依赖 `:core:datastore`），实例按 authority 缓存，
 * 保证"浏览页 / 粘贴 / SAF Provider"拿到的是同一个 client（连接池、认证 nonce、
 * 证书校验结果都因此只建一次）。
 */
class WebDavFileSystemResolver(
    private val profileProvider: suspend (id: String) -> ConnectionProfile?,
    private val passwordProvider: suspend (id: String) -> String?,
    private val io: CoroutineDispatcher = NetworkDispatchers.io,
) : FileSystemResolver {

    override val scheme: String = WebDavFileSystem.SCHEME

    override val displayName: String = "WebDAV"

    override val capabilities: Int = WebDavFileSystem.CAPABILITIES

    private val lock = Mutex()

    private val cache = HashMap<String, WebDavFileSystem>()

    override suspend fun resolve(authority: String): FileSystem {
        cacheSnapshot(authority)?.let { return it }
        return lock.withLock {
            cacheSnapshot(authority) ?: run {
                val profile = withContext(io) { profileProvider(authority) }
                    ?: throw FsNotFoundException(FsPath.of(scheme, authority))
                val password = withContext(io) { passwordProvider(authority) }
                WebDavFileSystem(profile, password, io).also { fresh ->
                    cache[authority] = fresh
                    MimiLog.i(TAG, "resolved webdav connection '${profile.displayName}' ($authority)")
                }
            }
        }
    }

    override suspend fun release(authority: String) {
        val removed = lock.withLock { cache.remove(authority) }
        // 关 OkHttp 的 dispatcher 会短暂阻塞，放锁外做。
        removed?.let { withContext(io) { it.close() } }
        if (removed != null) MimiLog.i(TAG, "released webdav connection $authority")
    }

    private fun cacheSnapshot(authority: String): WebDavFileSystem? = synchronized(cache) { cache[authority] }

    private companion object {
        const val TAG = "MimiDav"
    }
}
