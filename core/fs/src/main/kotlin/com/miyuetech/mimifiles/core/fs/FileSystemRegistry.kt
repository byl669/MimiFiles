package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 把一个 scheme 映射到具体的 [FileSystem] 实例。
 *
 * ## 为什么不让 `FileSystem` 自己注册到表里
 * SMB / WebDAV 的 [FileSystem] **依赖运行期数据**：要先用 `authority`（连接 id）去取
 * 凭据、建会话，才谈得上有个可用的实例。若注册表直接持有 `FileSystem` 单例，
 * 就得在应用启动时把所有连接都连一遍——这是纯粹的启动惩罚。
 *
 * 因此注册表持有的是**解析器**：按需建连、内部缓存、空闲释放。
 * 本机存储的解析器退化成"永远返回同一个单例"，代价只是一个函数调用。
 */
interface FileSystemResolver {

    val scheme: String

    val displayName: String

    /**
     * 静态能力位。
     *
     * 与 [FileSystem.capabilities] 的区别：这个值在**未连接**时也必须可得，
     * 好让连接列表页提前灰置不可用的操作（例：某后端是只读的）。
     */
    val capabilities: Int

    /** 解析（必要时建立）指定 authority 的实例。失败必须抛 [FsException] 子类。 */
    suspend fun resolve(authority: String): FileSystem

    /** 连接/授权被移除时释放缓存的实例（SMB 断连、SAF 撤权）。默认无缓存，无需处理。 */
    suspend fun release(authority: String) = Unit
}

/**
 * scheme → 解析器 的注册表。
 *
 * 上层只认 [FsPath]，拿实例一律走 [resolve]；因此**新增一种存储协议对 UI 是零改动的**——
 * 加一个模块、注册一个解析器即可（这也是参考应用能挂 20+ 后端而 UI 不爆炸的原因）。
 */
class FileSystemRegistry {

    private val lock = Any()
    private val resolvers = LinkedHashMap<String, FileSystemResolver>()

    /** 重复注册同一 scheme 会覆盖（便于测试里换 Fake 实现）。 */
    fun register(resolver: FileSystemResolver) {
        synchronized(lock) { resolvers[resolver.scheme.lowercase()] = resolver }
    }

    fun unregister(scheme: String): Boolean =
        synchronized(lock) { resolvers.remove(scheme.lowercase()) != null }

    fun clear() = synchronized(lock) { resolvers.clear() }

    fun schemes(): List<String> = synchronized(lock) { resolvers.keys.toList() }

    fun isRegistered(scheme: String): Boolean = synchronized(lock) { resolvers.containsKey(scheme.lowercase()) }

    fun resolverOf(scheme: String): FileSystemResolver? =
        synchronized(lock) { resolvers[scheme.lowercase()] }

    /** 同步可得的能力位（用于未连接时灰置菜单）。未注册的 scheme 返回 [FsCapability.NONE]。 */
    fun capabilitiesOf(scheme: String): Int = resolverOf(scheme)?.capabilities ?: FsCapability.NONE

    fun displayNameOf(scheme: String): String =
        resolverOf(scheme)?.displayName ?: scheme

    /** 解析失败返回 `null`，供 UI 做"这个位置暂时打不开"的降级展示。 */
    suspend fun resolveOrNull(path: FsPath): FileSystem? =
        resolverOf(path.scheme)?.let { resolver ->
            try {
                resolver.resolve(path.authority)
            } catch (_: FsException) {
                null
            }
        }

    /** 解析失败抛 [FsNotSupportedException]，供"必须成功"的路径（复制/粘贴/删除）使用。 */
    suspend fun resolve(path: FsPath): FileSystem =
        resolverOf(path.scheme)?.resolve(path.authority)
            ?: throw FsNotSupportedException(path.scheme, "resolve")

    /** 释放某 authority 的缓存实例（连接被删除、SAF 撤权时调用）。 */
    suspend fun release(path: FsPath) {
        resolverOf(path.scheme)?.release(path.authority)
    }
}
