package com.miyuetech.mimifiles.core.fslocal

import android.content.Context
import android.net.Uri
import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.model.FsEntry

/**
 * 本机存储的**装配入口与状态中心**。
 *
 * 上层（`FileApp` / `DocumentsActivity` / 设置页）只需要拿一个实例，做三件事：
 *  - [install]：把 `local` 与 `saf` 两个后端注册进 [FileSystemRegistry]；
 *  - [access]：读当前权限档位，决定要不要显示"去授权"提示条；
 *  - [registerSafGrant] / [removeSafGrant]：处理目录授权的增删。
 *
 * ## 缓存策略
 * [access] 的结果被缓存，因为探测本身会做 `isExternalStorageManager()`、
 * 遍历 `getExternalFilesDirs()`、读 SharedPreferences——在 `onResume` 里无脑重算
 * 虽然不贵，但也没有意义。**从系统设置页返回时必须传 `refresh = true`**，
 * 这是唯一会让档位变化的外部事件。
 */
class LocalStorageProvider(context: Context) {

    private val appContext: Context = context.applicationContext

    private val accessManager = StorageAccessManager(appContext)

    private val grantStore = SafGrantStore(appContext)

    private val localResolver = LocalFileSystemResolver(
        accessProvider = { access() },
        rootsProvider = { localRoots() },
    )

    private val safResolver = SafFileSystemResolver(appContext, grantStore)

    @Volatile
    private var cachedAccess: StorageAccess? = null

    /** 注册全部本机相关后端。重复调用安全（注册表按 scheme 覆盖）。 */
    fun install(registry: FileSystemRegistry) {
        registry.register(localResolver)
        registry.register(safResolver)
    }

    /** 当前存储访问实况。 */
    fun access(refresh: Boolean = false): StorageAccess {
        val current = cachedAccess
        if (!refresh && current != null) return current
        val detected = accessManager.detect(grantStore.all())
        cachedAccess = detected
        return detected
    }

    /** 仅 `local` scheme 的根（侧栏"本机"分组用）。 */
    fun localRoots(): List<FsEntry> =
        access().roots.filter { it.path.scheme == SCHEME_LOCAL }

    /** 仅 `saf` scheme 的根。 */
    fun safRoots(): List<FsEntry> =
        access().roots.filter { it.path.scheme == SCHEME_SAF }

    /** 权限探测与授权列表发生变化后清缓存（从设置页返回、授权对话框返回时调用）。 */
    fun invalidate() {
        cachedAccess = null
    }

    /**
     * 登记一条 SAF 目录授权。
     *
     * `flags` 直接来自 `ACTION_OPEN_DOCUMENT_TREE` 的结果 Intent，
     * 里面是否带 `FLAG_GRANT_WRITE_URI_PERMISSION` 决定了这个目录能不能写。
     */
    fun registerSafGrant(treeUri: Uri, flags: Int): SafGrant {
        val grant = grantStore.add(treeUri, flags)
        safResolver.invalidate()
        invalidate()
        return grant
    }

    /** 撤销授权（同时释放系统侧的持久化权限，避免"权限还在但列表里没了"的悬空状态）。 */
    fun removeSafGrant(id: String) {
        grantStore.remove(id)
        safResolver.invalidate()
        invalidate()
    }

    fun grants(): List<SafGrant> = grantStore.all()

    fun manager(): StorageAccessManager = accessManager
}
