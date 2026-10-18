package com.miyuetech.mimifiles.core.datastore

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用数据库。
 *
 * ## 版本历史
 * | 版本 | 变更 |
 * |---|---|
 * | 1 | 连接表（M2） |
 * | 2 | 播放进度表（M3） |
 *
 * ## 为什么不配 `fallbackToDestructiveMigration`
 * 连接是用户手填的、丢了要重输密码，所以升级**必须**显式写迁移。
 * 这条约束从 M2 就立下了，M3 加表时照办（[MIGRATION_1_2]）。
 *
 * ## 仍然不导出 schema
 * `exportSchema = false`：本工程没有把 schema JSON 纳入版本控制（也就没有按版本比对的
 * 基础设施），导出只会让 KSP 报一条"未配置 room.schemaLocation"的警告。
 * 现在表变多了，**这笔账开始变得不划算**——M5 若再加表，建议同时把 schema 纳入
 * 版本控制，那样"迁移写漏了一个字段"就能在 CI 里被发现，而不是等用户升级后崩。
 */
@Database(
    entities = [ConnectionEntity::class, PlaybackPositionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MimiDatabase : RoomDatabase() {

    abstract fun connections(): ConnectionDao

    abstract fun playbackPositions(): PlaybackPositionDao

    companion object {

        private const val NAME = "mimi_files.db"

        @Volatile
        private var instance: MimiDatabase? = null

        fun get(context: Context): MimiDatabase = instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

        private fun build(context: Context): MimiDatabase =
            Room.databaseBuilder(context.applicationContext, MimiDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * v1 → v2：只新增一张表，**不动**既有表。
         *
         * 用 `CREATE TABLE IF NOT EXISTS` 且列定义与 [PlaybackPositionEntity] 的
         * 生成结果逐字一致（Room 会校验表结构，列类型/非空/主键差一点都会启动时抛
         * `IllegalStateException: Migration didn't properly handle…`）。
         * 这也是"加表"这种迁移最值得照抄 Room 生成 SQL 的原因——
         * 手写一个 `TEXT NOT NULL` 与 `TEXT` 的差别就是一个只有真机升级才暴露的崩溃。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_positions` " +
                        "(`path` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`path`))",
                )
            }
        }

        /** 仅测试用：释放单例，让下次 [get] 重新建库。 */
        fun resetForTest() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
