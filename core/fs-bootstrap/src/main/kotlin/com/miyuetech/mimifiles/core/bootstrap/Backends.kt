package com.miyuetech.mimifiles.core.bootstrap

import android.content.Context
import com.miyuetech.mimifiles.core.common.Authorities
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.datastore.ConnectionRepository
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fslocal.LocalStorageProvider
import com.miyuetech.mimifiles.core.fssmb.SmbFileSystemResolver
import com.miyuetech.mimifiles.core.fswebdav.WebDavFileSystemResolver
import com.miyuetech.mimifiles.core.preview.PreviewEnvironment

/**
 * 后端装配入口 —— **全应用唯一的"谁把后端注册进注册表"的地方**。
 *
 * ## 为什么需要一个专门的模块
 * 注册这件事有两个硬约束，散在各处做必然出错：
 *
 *  1. **只能做一次**。注册是"按 scheme 覆盖"语义，重复注册会把上一次的
 *     [FileSystem] 实例换掉——而实例里缓存着 SMB 会话与 WebDAV 的 OkHttp 连接池。
 *     换个实例就等于把连接池扔掉重建，一个 NAS 会被连出好几条 TCP。
 *     [FileSystems.installOnce] 保证只有第一次真正执行。
 *
 *  2. **不保证谁先跑**。正常路径是浏览页先起来注册；但**系统可以单独拉起进程里的
 *     `DocumentsProvider`**（第三方 App 通过 SAF 选文件时，我们一个 Activity 都没跑）。
 *     那时若只有浏览页负责注册，provider 会面对一张空注册表，操作全部失败。
 *
 * 把两件事合起来看，答案只能是"一个所有入口都调用的、幂等的、注册**全部**后端的地方"。
 * 拆成"各模块注册自己那几个"就无法满足第 2 条——后到的模块会被 `installOnce` 挡在门外，
 * 它那几个 scheme 就永远装不上。
 */
object Backends {

    @Volatile
    private var storageInstance: LocalStorageProvider? = null

    /**
     * 注册全部后端。幂等；重复调用直接返回。
     *
     * **会在调用线程做少量 IO**（本机存储要读 SAF 授权表）。因此不要放在
     * `Application.onCreate` 的冷启动路径上——那正是它没有被放进 [com.miyuetech.mimifiles.FileApp] 的原因。
     * 浏览页在 IO 线程调用它，provider 在其 Binder 线程调用。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val first = FileSystems.installOnce {
            // 本机存储（local + saf）。它同时是"权限档位 / SAF 授权表"的状态中心，
            // 所以实例要留着给上层用——见 [storage]。
            val storage = LocalStorageProvider(app)
            storageInstance = storage
            storage.install(FileSystems.registry)

            // 网络后端。数据来源用函数注入，这两个模块本身不依赖数据库；
            // 密码在 resolve 的那一瞬间才解密，且只在闭包里流转。
            val connections = ConnectionRepository(app)
            FileSystems.registry.register(
                SmbFileSystemResolver(
                    profileProvider = { id -> connections.byId(id) },
                    passwordProvider = { id -> connections.passwordOf(id) },
                ),
            )
            FileSystems.registry.register(
                WebDavFileSystemResolver(
                    profileProvider = { id -> connections.byId(id) },
                    passwordProvider = { id -> connections.passwordOf(id) },
                ),
            )

            MimiLog.i(TAG, "backends installed: ${FileSystems.registry.schemes()}")
        }
        if (!first) MimiLog.d(TAG, "backends already installed, skipping")
    }

    /**
     * 本机存储的状态中心（权限档位、SAF 授权增删）。
     *
     * 必须返回**同一个**实例，不能让各调用方自己 new：它内部缓存着权限探测结果，
     * 而注册表里的 `local` 解析器读的也是这份缓存。两份实例会出现"设置页刷新了权限，
     * 但文件列表仍按旧档位工作"这种自相矛盾。
     */
    fun storage(context: Context): LocalStorageProvider {
        install(context)
        return storageInstance
            ?: error("Backends.install() 未能初始化本机存储（LocalStorageProvider 构造失败）")
    }

    /**
     * 连接仓库。
     *
     * 多建几个包装对象是安全的：底层 Room 数据库是进程级单例，
     * 所以几个实例看到的是同一份数据、同一个 `Flow`。
     */
    fun connections(context: Context): ConnectionRepository =
        ConnectionRepository(context.applicationContext)

    /**
     * 预览能力（类型探测 / 缩略图 / 落盘缓存），**进程级唯一实例**。
     *
     * 必须唯一：缩略图有内存 LRU 与磁盘 LRU 两层，两份实例就是两份缓存——
     * 用户在浏览页看过的缩略图，进预览页又会重新解一遍（因为缓存键在另一份实例的
     * 内存表里）。而磁盘层的浪费更隐蔽：同一个键会被写两次。
     *
     * @param documentAuthority `:provider` 的 authority，用于让远端文件也能走平台的
     *        `ImageDecoder`（HEIC/AVIF 只有它能解）。传 `null` 表示放弃这条路。
     */
    fun previews(
        context: Context,
        documentAuthority: String? = Authorities.DOCUMENTS,
    ): PreviewEnvironment = PreviewEnvironment.of(context.applicationContext, documentAuthority)

    private const val TAG = "MimiBackends"
}
