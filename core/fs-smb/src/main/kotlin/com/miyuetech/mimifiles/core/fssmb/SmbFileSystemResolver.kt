package com.miyuetech.mimifiles.core.fssmb

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.FileSystemResolver
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.network.NetworkDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `smb` scheme 的解析器。
 *
 * ## 为什么用"函数注入"而不是直接依赖 `:core:datastore`
 * 解析器需要两样运行期数据：连接档案与解密后的密码。最直接的写法是让本模块依赖
 * `:core:datastore`，但那会让**存储后端依赖数据库层**——一个纯粹的协议实现
 * 从此知道了 Room、知道了连接表的结构。将来 M5 要加书签表时，这个依赖会变得更黏。
 *
 * 改成注入两个挂起函数之后：
 *  - 本模块只依赖 `:core:fs` 与 `:core:model`（保持"纯协议实现"）；
 *  - 谁装配谁决定数据从哪来（`:feature:connection` 用连接库，测试里可以直接喂内存 map）。
 *
 * ## 缓存的是 `FileSystem` 而不是会话
 * 会话（SMB 连接）由 [SmbFileSystem] 内部按共享懒建。解析器这层缓存实例的意义是
 * **让同一个连接 id 每次拿到同一个对象**——浏览页、粘贴、无线分享、SAF Provider
 * 都会调 `resolve()`，如果每次 new 一个 `SmbFileSystem`，会话缓存就形同虚设，
 * 一个 NAS 会被连出四五条 TCP。
 */
class SmbFileSystemResolver(
    private val profileProvider: suspend (id: String) -> ConnectionProfile?,
    private val passwordProvider: suspend (id: String) -> String?,
    private val io: CoroutineDispatcher = NetworkDispatchers.io,
) : FileSystemResolver {

    override val scheme: String = SmbFileSystem.SCHEME

    override val displayName: String = "SMB"

    /**
     * 静态能力：**未连接时**也要能回答。
     *
     * 与 [SmbFileSystem.capabilities] 保持同一个值——SMB 的能力不随连接变化，
     * 只随账号权限变化，而账号权限要连上才知道。UI 因此不会提前灰置写操作，
     * 而是等真的被拒绝时给出 [com.miyuetech.mimifiles.core.fs.FsAccessDeniedException]
     * 对应的明确提示。
     */
    override val capabilities: Int = SmbFileSystem.CAPABILITIES

    private val lock = Mutex()

    private val cache = HashMap<String, SmbFileSystem>()

    override suspend fun resolve(authority: String): FileSystem {
        cacheSnapshot(authority)?.let { return it }
        return lock.withLock {
            cacheSnapshot(authority) ?: run {
                val profile = withContext(io) { profileProvider(authority) }
                    ?: throw FsNotFoundException(FsPath.of(scheme, authority))
                val password = withContext(io) { passwordProvider(authority) }
                SmbFileSystem(profile, password, io).also { fresh ->
                    cache[authority] = fresh
                    MimiLog.i(TAG, "resolved smb connection '${profile.displayName}' ($authority)")
                }
            }
        }
    }

    override suspend fun release(authority: String) {
        val removed = lock.withLock { cache.remove(authority) }
        // close() 会做 socket 关闭，属于阻塞 IO，不能占着锁跑——否则一个慢服务端
        // 会把其他连接的解析一起堵住。
        removed?.let { withContext(io) { it.close() } }
        if (removed != null) MimiLog.i(TAG, "released smb connection $authority")
    }

    private fun cacheSnapshot(authority: String): SmbFileSystem? = synchronized(cache) { cache[authority] }

    companion object {
        private const val TAG = "MimiSmb"
    }
}
