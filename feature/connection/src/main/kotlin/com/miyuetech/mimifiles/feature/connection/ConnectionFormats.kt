package com.miyuetech.mimifiles.feature.connection

import com.miyuetech.mimifiles.core.fssmb.SmbSettings
import com.miyuetech.mimifiles.core.fswebdav.WebDavSettings
import com.miyuetech.mimifiles.core.model.ConnectionProfile

/**
 * 连接的展示格式。
 *
 * 放在一处而不是散在 Adapter 与 Activity 里：列表页、编辑页标题、"重复连接"确认框
 * 都要用同一套说法，散开写必然出现"列表里叫 192.168.1.100:445、确认框里叫 192.168.1.100"
 * 这种不一致，用户会怀疑自己在操作两个不同的东西。
 */
internal object ConnectionFormats {

    /** 协议短名。 */
    fun schemeLabel(scheme: String): String = when (scheme) {
        SCHEME_SMB -> "SMB"
        SCHEME_WEBDAV -> "WebDAV"
        else -> scheme.uppercase()
    }

    /**
     * 端点串：`192.168.1.100:445/share`。
     *
     * 端口只在**非默认**时显示。把 `:445` 一直挂着只是噪音——用户写地址时不需要它，
     * 排障时看到它才有意义（所以不能省掉，只在默认值时省）。
     */
    fun endpoint(profile: ConnectionProfile): String {
        val host = if (profile.port > 0) "${profile.host}:${profile.port}" else profile.host
        val path = profile.rootPath.trim().trim('/')
        return if (path.isEmpty()) host else "$host/$path"
    }

    /** 列表里的第二行：`SMB · 192.168.1.100/share`。 */
    fun subtitle(profile: ConnectionProfile): String =
        "${schemeLabel(profile.scheme)} · ${endpoint(profile)}"

    /**
     * 徽标文字（string 资源 id）；无徽标时返回 `null`。
     *
     * 只标"与默认不同、且用户真的需要知道"的状态：
     *  - **明文**：WebDAV 开了允许 HTTP——这是安全降级，必须让用户看见；
     *  - **匿名**：SMB 匿名登录——很多人不知道自己填的是匿名，出问题时这是关键线索。
     *
     * 刻意**不标** "已签名""UTF-8" 这类：它们是默认值，标出来只是把列表弄脏。
     *
     * 返回资源 id 而不是成品字符串，是为了让这两处文案跟着系统语言走；
     * 早先版本把它们写死在代码里，中英混排时会出现半截中文半截英文的列表。
     *
     * 判定本身交给后端的 `parse()` 而不是在这里比较 `== "true"`：那套宽松解析
     * （认 `1`/`yes`/`on`）是后端读取参数时用的同一套，两处用同一套才不会出现
     * "徽标说没开明文，后端却按明文连了"这种自相矛盾。
     */
    fun badgeRes(profile: ConnectionProfile): Int? = when (profile.scheme) {
        SCHEME_WEBDAV ->
            if (WebDavSettings.parse(profile.options).allowInsecure) R.string.connection_badge_insecure else null

        SCHEME_SMB ->
            if (SmbSettings.parse(profile.options).anonymous) R.string.connection_badge_anonymous else null

        else -> null
    }

    /** 未填显示名时的兜底：用端点当名字，总比列表里一行空白好。 */
    fun fallbackLabel(profile: ConnectionProfile): String =
        profile.displayName.ifBlank { endpoint(profile) }

    const val SCHEME_SMB = "smb"
    const val SCHEME_WEBDAV = "webdav"
}
