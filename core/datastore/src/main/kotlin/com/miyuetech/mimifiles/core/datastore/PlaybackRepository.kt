package com.miyuetech.mimifiles.core.datastore

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 播放进度（断点续播）。
 *
 * ## 为什么用 `FsPath.toString()` 当主键
 * 它是 M1 冻结的"统一持久化格式"（桌面快捷方式、SAF documentId 都复用它），
 * 天然带 scheme + authority + path，因此**同名的本机文件与 NAS 文件不会互相覆盖**。
 * 若改用文件名的 hash，就会出现"手机上的 `movie.mp4` 覆盖了 NAS 上同名的进度"。
 *
 * ## 为什么不存标题/时长以外的元数据
 * 只是为了让"继续播放"这个功能工作。存更多（缩略图、字幕轨选项）会让这张表变成
 * 第二个索引系统，而它的更新时机（播放中）恰好是最容易写坏数据的时刻。
 */
@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    /** `FsPath.toString()`，如 `smb://smb-abc/Video/movie.mp4`。 */
    @PrimaryKey val path: String,
    /** 上次离开时的位置（毫秒）。 */
    val positionMs: Long,
    /** 总时长（毫秒）；未知为 0。列表里的进度条要用它算比例。 */
    val durationMs: Long,
    /** 最后更新时间（epoch 毫秒），用于按时间淘汰旧记录。 */
    val updatedAt: Long,
) {
    /** 已看完的比例（0~1）。 */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** 是否"接近看完"——这种记录可以清掉，续播到最后一秒没有意义。 */
    val isNearlyFinished: Boolean
        get() = durationMs > 0L && positionMs >= durationMs - NEAR_END_MS

    companion object {
        const val NEAR_END_MS = 15_000L
    }
}

@Dao
interface PlaybackPositionDao {

    @Query("SELECT * FROM playback_positions WHERE path = :path LIMIT 1")
    suspend fun find(path: String): PlaybackPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackPositionEntity)

    @Query("DELETE FROM playback_positions WHERE path = :path")
    suspend fun delete(path: String)

    /** 淘汰旧记录：表不该无限增长（用户看过几千个视频，其中绝大多数不会再续播）。 */
    @Query("DELETE FROM playback_positions WHERE updatedAt < :before")
    suspend fun pruneBefore(before: Long)

    @Query("SELECT COUNT(*) FROM playback_positions")
    suspend fun count(): Int
}

/**
 * 播放进度仓库。
 *
 * ## 写入策略：只在"值得记"的时候写
 * 每 500 ms 写一次数据库会让闪存写入放大到无意义的程度。这里的规则是
 * **离开页面 / 暂停 / 切后台时写一次**，并且跳过"刚开头（< 5 s）"与
 * "快结束（< 15 s）"这两种没有续播价值的位置——前者等于从头开始，
 * 后者用户下次也不会想接着看最后几秒。
 */
class PlaybackRepository(private val database: MimiDatabase) {

    suspend fun positionOf(fsPath: String): Long =
        database.playbackPositions().find(fsPath)?.positionMs?.takeIf { it > 0L } ?: 0L

    suspend fun entryOf(fsPath: String): PlaybackPositionEntity? =
        database.playbackPositions().find(fsPath)

    /**
     * 记录进度。[positionMs] 为 0 或接近片尾时**删除**记录。
     *
     * @return 是否真的写了（调用方可据此打日志，冒烟脚本也靠它取证）。
     */
    suspend fun remember(
        fsPath: String,
        positionMs: Long,
        durationMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val dao = database.playbackPositions()
        val tooEarly = positionMs < MIN_WORTH_SAVING_MS
        val nearlyDone = durationMs > 0L && positionMs >= durationMs - PlaybackPositionEntity.NEAR_END_MS
        if (tooEarly || nearlyDone) {
            // 从头开始的进度与"已看完"的进度都没有保存价值，顺手清掉旧记录
            // ——否则用户"重看一遍"时会莫名其妙从上次那 1 秒处继续。
            dao.delete(fsPath)
            return false
        }
        dao.upsert(
            PlaybackPositionEntity(
                path = fsPath,
                positionMs = positionMs,
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = now,
            ),
        )
        return true
    }

    suspend fun forget(fsPath: String) = database.playbackPositions().delete(fsPath)

    /** 淘汰 [days] 天未更新的记录（在设置页"清理缓存"与启动时各调一次即可）。 */
    suspend fun prune(days: Int = KEEP_DAYS): Int {
        val before = System.currentTimeMillis() - days * 24L * 3600L * 1000L
        database.playbackPositions().pruneBefore(before)
        return database.playbackPositions().count()
    }

    companion object {
        /** 小于这个位置不记（等于从头开始）。 */
        const val MIN_WORTH_SAVING_MS = 5_000L

        /** 保留 90 天。 */
        const val KEEP_DAYS = 90
    }
}
