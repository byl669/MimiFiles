package com.miyuetech.mimifiles.core.fslocal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条已持久化的 SAF 目录授权。
 *
 * [id] 会进入 [FsPath.authority]（`saf://<id>/子路径`），因此必须是
 * **由 treeUri 稳定推导**出来的——不能是自增序号，否则清一次数据、重新授权同一目录，
 * 之前存的快捷方式与书签就全部指向不存在的授权了。
 */
data class SafGrant(
    val id: String,
    val treeUri: String,
    val displayName: String,
    val canWrite: Boolean,
) {
    fun toFsPath(): FsPath = FsPath.of(SCHEME_SAF, id, "")

    fun toRootEntry(): FsEntry = FsEntry(
        path = toFsPath(),
        name = displayName,
        kind = FsKind.DIRECTORY,
    )
}

/**
 * SAF 授权树的持久化。
 *
 * 用 SharedPreferences + JSON 而不是 Room/DataStore：这份数据最多几条、
 * 只在授权/撤权时写、在启动探测时读一次。为它引入数据库或协程化的 DataStore，
 * 换来的只是"把同步读变成需要 runBlocking 的异步读"——反而更别扭。
 *
 * **权限本身仍由系统持有**（`takePersistableUriPermission`），这里存的只是索引；
 * 所以文件被用户清掉不会造成"假装有权限"的错位。
 */
class SafGrantStore(private val context: Context) {

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun all(): List<SafGrant> = read().values.toList()

    fun byId(id: String): SafGrant? = read()[id]

    /** 登记一条授权，并把读写权限持久化到系统。 */
    fun add(treeUri: Uri, flags: Int): SafGrant {
        val persistFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        // 个别 provider 不支持持久化；失败不该阻断当前会话（这次授权仍可临时使用）
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, persistFlags)
        }

        val id = stableId(treeUri)
        val grant = SafGrant(
            id = id,
            treeUri = treeUri.toString(),
            displayName = displayNameOf(treeUri),
            canWrite = persistFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
        )
        val map = read()
        map[id] = grant
        write(map)
        return grant
    }

    fun remove(id: String) {
        val map = read()
        val grant = map.remove(id) ?: return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(grant.treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        write(map)
    }

    fun clear() {
        read().keys.toList().forEach(::remove)
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun read(): LinkedHashMap<String, SafGrant> {
        val map = LinkedHashMap<String, SafGrant>()
        val raw = prefs.getString(KEY_GRANTS, null) ?: return map
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString(FIELD_ID).takeIf { it.isNotEmpty() } ?: continue
                val uri = obj.optString(FIELD_URI).takeIf { it.isNotEmpty() } ?: continue
                map[id] = SafGrant(
                    id = id,
                    treeUri = uri,
                    displayName = obj.optString(FIELD_NAME).ifEmpty { "已授权目录" },
                    canWrite = obj.optBoolean(FIELD_WRITE, false),
                )
            }
        }
        return map
    }

    private fun write(map: Map<String, SafGrant>) {
        val array = JSONArray()
        map.values.forEach { grant ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, grant.id)
                    put(FIELD_URI, grant.treeUri)
                    put(FIELD_NAME, grant.displayName)
                    put(FIELD_WRITE, grant.canWrite)
                },
            )
        }
        prefs.edit().putString(KEY_GRANTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "mimi_saf_grants"
        private const val KEY_GRANTS = "grants"
        private const val FIELD_ID = "id"
        private const val FIELD_URI = "uri"
        private const val FIELD_NAME = "name"
        private const val FIELD_WRITE = "write"

        /**
         * 由 treeUri 稳定推导 id。
         *
         * 用 32 位 FNV-1a 而不是 `String.hashCode()`：后者在字符串很长时也只会用到低位，
         * 而 treeUri 之间有很长的公共前缀（`content://com.android.externalstorage.documents/tree/`），
         * 碰撞概率明显更高。
         */
        fun stableId(treeUri: Uri): String {
            val bytes = treeUri.toString().toByteArray(Charsets.UTF_8)
            var hash = -0x7ee3623b // 2166136261
            for (b in bytes) {
                hash = hash xor (b.toInt() and 0xFF)
                hash *= 16777619
            }
            return "g" + (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        }

        /** 从文档 id 推断展示名：`primary:DCIM` → `DCIM`，`primary:` → `内部存储`。 */
        fun displayNameOf(treeUri: Uri): String {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return "已授权目录"
            val tail = docId.substringAfter(':', docId)
            return when {
                tail.isEmpty() -> "内部存储"
                else -> tail.substringAfterLast('/').ifEmpty { "已授权目录" }
            }
        }
    }
}
