package com.miyuetech.mimifiles.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miyuetech.mimifiles.core.model.FileViewMode
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.model.FsSort
import com.miyuetech.mimifiles.core.model.FsSortField
import com.miyuetech.mimifiles.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** 全局设置存储。单文件、单实例，DataStore 内部已串行化写入。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mimi_settings",
)

/**
 * 用户设置读写。
 *
 * ## 为什么主题要有一个同步读取口
 * 主题必须在**任何 Activity 创建之前**定下来，否则第一帧会用错配色，用户看到的就是"闪白"。
 * `AppCompatDelegate.setDefaultNightMode` 只能在 `Application.onCreate` 里调，而那里是同步上下文。
 * 所以 [themeModeBlocking] 用一次 `runBlocking` 把 DataStore 的首个值取出来——
 * 这是整个应用**唯一**允许阻塞主线程的 IO，且只读一个 key，实测在 10 ms 量级。
 *
 * 其余所有设置都走 Flow，不参与启动路径。
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    // ── 外观 ────────────────────────────────────────────────────────────

    val themeMode: Flow<ThemeMode> = dataStore.safeData()
        .map { ThemeMode.fromId(it[KEY_THEME_MODE]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.id }
    }

    /**
     * 同步读取主题，仅供 `Application.onCreate` 使用。
     *
     * 异常一律吞掉并回落到默认值：**启动路径绝不能因为读设置失败而崩溃**，
     * 主题错了只是难看，崩了是打不开。
     */
    fun themeModeBlocking(): ThemeMode = runCatching {
        runBlocking { themeMode.first() }
    }.getOrDefault(ThemeMode.DEFAULT)

    // ── 浏览行为 ────────────────────────────────────────────────────────

    val viewMode: Flow<FileViewMode> = dataStore.safeData()
        .map { prefs ->
            FileViewMode.entries.firstOrNull { it.name == prefs[KEY_VIEW_MODE] }
                ?: FileViewMode.LIST
        }

    suspend fun setViewMode(mode: FileViewMode) {
        dataStore.edit { it[KEY_VIEW_MODE] = mode.name }
    }

    val sortSpec: Flow<FsSort> = dataStore.safeData().map { prefs ->
        val field = FsSortField.entries.firstOrNull { it.name == prefs[KEY_SORT_FIELD] }
            ?: FsSortField.NAME
        FsSort(
            field = field,
            ascending = prefs[KEY_SORT_ASCENDING] ?: true,
            directoriesFirst = prefs[KEY_SORT_DIRS_FIRST] ?: true,
        )
    }

    suspend fun setSortSpec(sort: FsSort) {
        dataStore.edit { prefs ->
            prefs[KEY_SORT_FIELD] = sort.field.name
            prefs[KEY_SORT_ASCENDING] = sort.ascending
            prefs[KEY_SORT_DIRS_FIRST] = sort.directoriesFirst
        }
    }

    val showHidden: Flow<Boolean> = dataStore.safeData()
        .map { it[KEY_SHOW_HIDDEN] ?: false }

    suspend fun setShowHidden(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_HIDDEN] = show }
    }

    // ── 播放（M7.6）────────────────────────────────────────────────────

    /**
     * 默认视频播放速度。可选值由设置页固定为 1x / 1.5x / 2x / 3x，
     * 存浮点原值 —— 将来加 0.5x / 4x 时不需要迁移任何数据。
     */
    val playerSpeed: Flow<Float> = dataStore.safeData()
        .map { it[KEY_PLAYER_SPEED] ?: 1f }

    suspend fun setPlayerSpeed(speed: Float) {
        dataStore.edit { it[KEY_PLAYER_SPEED] = speed }
    }

    // ── 收藏（M7.12）──────────────────────────────────────────────────

    /**
     * 收藏的文件夹（侧栏「快速访问」直达）。
     *
     * 存储格式：[FsPath.toString]（项目冻结的持久化格式，见其类注释——桌面快捷方式、
     * 书签、最近访问都用它）按**换行**拼接。用单 key 拼接而不是 `stringSetPreferencesKey`：
     * stringSet 是 `Set<String>`，**不保序**，而侧栏里"后收藏的排后面"是用户可感知的
     * 语义，顺序丢了会被当成 bug。
     */
    val favorites: Flow<List<FsPath>> = dataStore.safeData().map { prefs ->
        prefs[KEY_FAVORITE_DIRS]
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { FsPath.parse(it) }
            .orEmpty()
    }

    /** 加入收藏（已在收藏中时是幂等的空操作，不重复、不移动位置）。 */
    suspend fun addFavorite(path: FsPath) {
        dataStore.edit { prefs ->
            val current = currentFavorites(prefs)
            if (current.none { it == path }) {
                prefs[KEY_FAVORITE_DIRS] = (current.map { it.toString() } + path.toString())
                    .joinToString("\n")
            }
        }
    }

    /** 移出收藏（本来就不在时是幂等的空操作）。 */
    suspend fun removeFavorite(path: FsPath) {
        dataStore.edit { prefs ->
            val remaining = currentFavorites(prefs).filter { it != path }
            if (remaining.isEmpty()) {
                prefs.remove(KEY_FAVORITE_DIRS)
            } else {
                prefs[KEY_FAVORITE_DIRS] = remaining.joinToString("\n") { it.toString() }
            }
        }
    }

    private fun currentFavorites(prefs: Preferences): List<FsPath> =
        prefs[KEY_FAVORITE_DIRS]
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { FsPath.parse(it) }
            .orEmpty()

    // ── 无线分享（M4）──────────────────────────────────────────────────

    /**
     * 退出应用时自动停掉分享服务端。
     *
     * 默认**开**（方案 §10.4「与设置统一」）。理由：把手机上的目录通过 FTP
     * 暴露给整个局域网这件事，用户"以为已经结束"而实际还在跑，是明确的安全隐患。
     * 而忘记关的代价（要重新点一次启动）极小。默认值永远应该偏向安全的那一边。
     */
    val shareStopOnExit: Flow<Boolean> = dataStore.safeData()
        .map { it[KEY_SHARE_STOP_ON_EXIT] ?: true }

    suspend fun setShareStopOnExit(stop: Boolean) {
        dataStore.edit { it[KEY_SHARE_STOP_ON_EXIT] = stop }
    }

    /** 分享期间保持屏幕常亮。默认关：常亮是耗电大户，该由用户显式要。 */
    val shareKeepScreenOn: Flow<Boolean> = dataStore.safeData()
        .map { it[KEY_SHARE_KEEP_SCREEN_ON] ?: false }

    suspend fun setShareKeepScreenOn(keep: Boolean) {
        dataStore.edit { it[KEY_SHARE_KEEP_SCREEN_ON] = keep }
    }

    /** 上次用过的端口，按服务端类型分开记（`ShareServer.type`）。 */
    fun sharePort(type: String): Flow<Int> = dataStore.safeData()
        .map { it[intPreferencesKey(KEY_SHARE_PORT_PREFIX + type)] ?: 0 }

    suspend fun setSharePort(type: String, port: Int) {
        dataStore.edit { it[intPreferencesKey(KEY_SHARE_PORT_PREFIX + type)] = port }
    }

    /**
     * 读取失败时把整份设置当作空的。
     *
     * DataStore 在文件损坏时会抛 [IOException]；此时让 Flow 正常结束在"全默认值"上，
     * 用户体验是"设置被重置"，而不是"应用一打开就崩"。
     */
    private fun DataStore<Preferences>.safeData(): Flow<Preferences> =
        data.catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        val KEY_SORT_FIELD = stringPreferencesKey("sort_field")
        val KEY_SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
        val KEY_SORT_DIRS_FIRST = booleanPreferencesKey("sort_dirs_first")
        val KEY_SHOW_HIDDEN = booleanPreferencesKey("show_hidden")

        // 播放（M7.6）
        val KEY_PLAYER_SPEED = floatPreferencesKey("player_speed")

        // 收藏（M7.12）
        val KEY_FAVORITE_DIRS = stringPreferencesKey("favorite_dirs")

        // 无线分享（M4）
        val KEY_SHARE_STOP_ON_EXIT = booleanPreferencesKey("share_stop_on_exit")
        val KEY_SHARE_KEEP_SCREEN_ON = booleanPreferencesKey("share_keep_screen_on")

        /**
         * 端口键的前缀。用前缀而不是为 FTP/HTTP 各写一个常量：
         * 将来加 FTPS（M5）时不需要再改这里。
         */
        const val KEY_SHARE_PORT_PREFIX = "share_port_"
    }
}
