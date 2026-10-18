package com.miyuetech.mimifiles.core.datastore

import android.content.Context
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/** 保存密码时的三种意图。用 sealed 而不是 `String?`，因为"不改"和"清空"是两件事。 */
sealed interface PasswordUpdate {
    /** 编辑时用户没碰密码框：保留库里原有的密文。 */
    data object Unchanged : PasswordUpdate

    /** 用户清空了密码框（改成匿名/无密码）。 */
    data object Clear : PasswordUpdate

    data class Set(val value: String) : PasswordUpdate
}

/** 保存结果。 */
sealed interface SaveOutcome {

    /**
     * 覆盖保存时被顶掉的那条记录（没有则为 `null`）。
     *
     * 上层必须据此释放注册表里对应的实例：那条 authority 上的会话还握着**旧凭据**，
     * 留着它会出现"连接都删了/合并了，用旧密码还能读"这种极难归因的现象。
     */
    val replaced: ConnectionProfile?

    data class Saved(
        val profile: ConnectionProfile,
        override val replaced: ConnectionProfile? = null,
    ) : SaveOutcome

    /** 已存在等价连接（同 host/共享/账号）。上层应弹"是否覆盖那条"而不是静默新增。 */
    data class Duplicate(val existing: ConnectionProfile) : SaveOutcome {
        override val replaced: ConnectionProfile? get() = null
    }

    /** Keystore 不可用，密码没能加密——**连接本身已保存**，只是没存密码。 */
    data class SavedWithoutPassword(
        val profile: ConnectionProfile,
        override val replaced: ConnectionProfile? = null,
    ) : SaveOutcome
}

/**
 * 连接的增删改查。
 *
 * ## 职责边界（重要）
 * 这里**只管存取**，不做任何网络动作。"测试连通"属于各后端（`:core:fs-smb` 的
 * `testConnection`），因为只有后端知道该用哪种方言、该不该带域、超时设多少。
 * 把连通性测试塞进 Repository 会让它依赖所有后端模块，模块化就废了。
 *
 * ## 密码的流转
 * ```
 * 表单明文 ──encrypt(aad=connectionId)──> password_cipher 列
 *                                              │
 * 后端建会话 <──decrypt(aad=connectionId)───────┘   （只在 resolve 的一瞬间）
 * ```
 * [ConnectionProfile] 自始至终不含密码，因此它被塞进快捷方式 extras、日志、
 * 导航参数都不会泄漏——这是把密码与连接元数据分开建模的收益。
 */
class ConnectionRepository(
    context: Context,
    private val vault: CredentialVault = CredentialVault(),
) {

    private val dao = MimiDatabase.get(context).connections()

    /** 监听全部连接（侧栏、连接列表共用同一个 Flow，天然同步）。 */
    val profiles: Flow<List<ConnectionProfile>> =
        dao.observeAll().map { list -> list.map { it.toProfile() } }

    suspend fun all(): List<ConnectionProfile> = withContext(Dispatchers.IO) {
        dao.all().map { it.toProfile() }
    }

    suspend fun byId(id: String): ConnectionProfile? = withContext(Dispatchers.IO) {
        dao.byId(id)?.toProfile()
    }

    /** 保险箱是否可用。不可用时表单应提示"本机无法安全保存密码"。 */
    fun vaultAvailable(): Boolean = vault.isAvailable()

    /**
     * 取得解密后的密码。
     *
     * @return 无密码、密文损坏、Keystore 失效统一返回 `null`。
     *         损坏的条目会被**就地清除**，避免每次连接都要白等一次解密失败。
     */
    suspend fun passwordOf(id: String): String? = withContext(Dispatchers.IO) {
        val row = dao.byId(id) ?: return@withContext null
        val cipher = row.passwordCipher ?: return@withContext null
        val plain = vault.decrypt(cipher, associatedData = id)
        if (plain == null) {
            MimiLog.w(TAG, "credential for '$id' is undecryptable, clearing it")
            dao.upsert(row.copy(passwordCipher = null))
        }
        plain
    }

    /**
     * 新增或更新一条连接。
     *
     * @param overwriteExisting 用户已确认"覆盖那条重复项"时为 `true`；否则撞到重复会返回
     *                          [SaveOutcome.Duplicate]，由 UI 弹确认框。
     *
     * ## 覆盖到底覆盖了谁
     * 光"跳过重复检查、继续用新 id 插入"是**错的**——那样旧记录原封不动留着，
     * 用户点完"覆盖"会发现自己有两条一模一样的连接。所以这里要真的合并：
     *
     *  - **新建时撞车**（`profile.id` 为空）：改用那一条的 id 写进去。这样它已经存着的
     *    密码（用户没填新密码时走 `Unchanged`）和在桌面快捷方式里记着的 id 都还活着。
     *  - **编辑时撞车**（`profile.id` 非空）：保留自己的 id、删掉那一条。理由是
     *    "我正在编辑的这条"是用户此刻的上下文，它的 id 一旦变了，钉在桌面上的快捷方式
     *    就指向一个不存在的连接。两条里必须丢一条时，丢用户没在看的那条。
     */
    suspend fun save(
        profile: ConnectionProfile,
        password: PasswordUpdate = PasswordUpdate.Unchanged,
        overwriteExisting: Boolean = false,
    ): SaveOutcome = withContext(Dispatchers.IO) {
        // 1) 先定 id：它同时是密码的 AAD，必须在下一次加密之前就确定下来。
        var id = profile.id.ifBlank { newId(profile.scheme) }

        // 2) 去重（排除自己）。
        val duplicate = dao.findDuplicate(
            scheme = profile.scheme,
            host = profile.host,
            port = profile.port,
            rootPath = profile.rootPath,
            username = profile.username.orEmpty(),
            domain = profile.domain.orEmpty(),
        )
        var replaced: ConnectionProfile? = null
        if (duplicate != null && duplicate.id != id) {
            if (!overwriteExisting) return@withContext SaveOutcome.Duplicate(duplicate.toProfile())
            if (profile.id.isBlank()) {
                id = duplicate.id
            } else {
                dao.deleteById(duplicate.id)
                replaced = duplicate.toProfile()
                MimiLog.i(TAG, "overwrite removed duplicate connection ${duplicate.id}")
            }
        }

        val stable = profile.copy(id = id)

        // 3) 密码：三种意图分别处理。
        val existing = dao.byId(id)
        val cipher = when (password) {
            PasswordUpdate.Unchanged -> existing?.passwordCipher
            PasswordUpdate.Clear -> null
            is PasswordUpdate.Set -> vault.encrypt(password.value, associatedData = id)
        }
        val lostPassword = password is PasswordUpdate.Set && cipher == null

        val entity = ConnectionEntity.from(
            profile = stable,
            passwordCipher = cipher,
            sortOrder = existing?.sortOrder ?: nextSortOrder(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastUsedAt = existing?.lastUsedAt ?: 0L,
        )
        dao.upsert(entity)
        MimiLog.i(TAG, "saved connection ${stable.scheme}://$id (${stable.endpointLabel})")

        if (lostPassword) {
            SaveOutcome.SavedWithoutPassword(stable, replaced)
        } else {
            SaveOutcome.Saved(stable, replaced)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
        MimiLog.i(TAG, "deleted connection $id")
    }

    /** 记录最近使用时间；侧栏"最近"排序与连接列表的"上次使用"都靠它。 */
    suspend fun touch(id: String) = withContext(Dispatchers.IO) {
        dao.touch(id, System.currentTimeMillis())
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    private suspend fun nextSortOrder(): Int = (dao.all().maxOfOrNull { it.sortOrder } ?: -1) + 1

    private fun newId(scheme: String): String = "$scheme-${UUID.randomUUID().toString().take(8)}"

    private companion object {
        const val TAG = "MimiConn"
    }
}
