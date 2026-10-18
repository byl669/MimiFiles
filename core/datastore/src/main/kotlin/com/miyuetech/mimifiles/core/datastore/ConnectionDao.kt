package com.miyuetech.mimifiles.core.datastore

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {

    /** 按用户排序（sortOrder）+ 创建时间。侧栏与连接列表都用这个顺序，保证两处一致。 */
    @Query("SELECT * FROM connections ORDER BY sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections ORDER BY sort_order ASC, created_at ASC")
    suspend fun all(): List<ConnectionEntity>

    @Query("SELECT * FROM connections WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ConnectionEntity?

    /**
     * 去重查询：同 scheme + host + port + 共享 + 账号视为同一条。
     *
     * 特意**不含密码**：用户改了密码重新添加时，应该提示"已存在同名连接，是否更新"，
     * 而不是默默多出一条几乎一样的记录（参考应用在这点上会堆出重复项）。
     */
    @Query(
        """
        SELECT * FROM connections
        WHERE scheme = :scheme AND host = :host AND port = :port
          AND root_path = :rootPath
          AND IFNULL(username, '') = :username
          AND IFNULL(domain, '') = :domain
        LIMIT 1
        """,
    )
    suspend fun findDuplicate(
        scheme: String,
        host: String,
        port: Int,
        rootPath: String,
        username: String,
        domain: String,
    ): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConnectionEntity)

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE connections SET last_used_at = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("SELECT COUNT(*) FROM connections")
    suspend fun count(): Int
}
