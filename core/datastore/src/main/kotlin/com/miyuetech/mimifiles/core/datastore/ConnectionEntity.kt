package com.miyuetech.mimifiles.core.datastore

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import org.json.JSONObject

/**
 * 连接表。SMB 与 WebDAV 共用一张表——两者的字段集合完全相同（host/port/账号/域/根路径），
 * scheme 决定用哪个后端去解释它。
 *
 * ## 为什么密码单独一列且带 `_cipher` 后缀
 * 命名即约束：看到列名就知道这里放的是**密文**，任何直接读它当明文用的人都会立刻发现不对。
 * 明文只在 [ConnectionRepository] 内部解密一瞬，交给后端建会话，绝不回流到 [ConnectionProfile]。
 */
@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey val id: String,
    val scheme: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val username: String?,
    val domain: String?,
    @ColumnInfo(name = "root_path") val rootPath: String,
    /** Keystore 加密后的密码；`null` = 匿名/无密码。**永不解密到本对象之外使用**。 */
    @ColumnInfo(name = "password_cipher") val passwordCipher: String?,
    /** 非敏感调参，JSON 对象串（编码、方言、超时、是否信任自签证书…）。 */
    @ColumnInfo(name = "options_json") val optionsJson: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
) {

    /** 转成领域模型（**不含密码**）。 */
    fun toProfile(): ConnectionProfile = ConnectionProfile(
        id = id,
        scheme = scheme,
        displayName = displayName,
        host = host,
        port = port,
        username = username,
        domain = domain,
        rootPath = rootPath,
        options = decodeOptions(optionsJson),
    )

    companion object {

        fun from(
            profile: ConnectionProfile,
            passwordCipher: String?,
            sortOrder: Int = 0,
            createdAt: Long = System.currentTimeMillis(),
            lastUsedAt: Long = 0L,
        ): ConnectionEntity = ConnectionEntity(
            id = profile.id,
            scheme = profile.scheme,
            displayName = profile.displayName,
            host = profile.host,
            port = profile.port,
            username = profile.username,
            domain = profile.domain,
            rootPath = profile.rootPath,
            passwordCipher = passwordCipher,
            optionsJson = encodeOptions(profile.options),
            sortOrder = sortOrder,
            createdAt = createdAt,
            lastUsedAt = lastUsedAt,
        )

        /**
         * 调参序列化。
         *
         * 用平台自带的 `org.json` 而不是 kotlinx.serialization：后者要带编译器插件与运行时，
         * 为了存一个 `Map<String,String>` 不值得。`org.json` 在 Android 上零依赖可用，
         * 且这里的键值都来自我们自己的表单常量，没有复杂嵌套。
         */
        internal fun encodeOptions(options: Map<String, String>): String =
            if (options.isEmpty()) "{}" else JSONObject(options as Map<*, *>).toString()

        internal fun decodeOptions(json: String): Map<String, String> {
            if (json.isBlank() || json == "{}") return emptyMap()
            return try {
                val obj = JSONObject(json)
                val out = LinkedHashMap<String, String>(obj.length())
                for (key in obj.keys()) out[key] = obj.optString(key)
                out
            } catch (_: Exception) {
                // 库里的 JSON 坏了不该让连接列表整页打不开：当作"没有额外参数"，
                // 后端会用默认值工作（可能编码不对，但至少能连）。
                emptyMap()
            }
        }
    }
}
