package com.miyuetech.mimifiles.core.shareserver

import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 认证模式。
 *
 * ## 为什么默认不是匿名
 * 方案 §10.5 的最后一行：明文 FTP + 匿名 = **同网段任意设备可读写你的文件**，
 * 而"同网段"在会议室、咖啡馆、宿舍楼里完全不等于"可信"。参考应用默认开匿名，
 * 我们不跟：默认生成随机账密，用户要开匿名得自己点。
 *
 * 之所以两个模式都做而不是直接砍掉匿名：局域网里"手机 ↔ 自己的电脑"是最主要的
 * 用法，此时匿名能省掉一次输入；把它做成**显式选择**而不是默认，收益与风险就都对上了。
 */
sealed interface AuthMode {

    /** 免登录。面板上会同时给出风险提示。 */
    data object Anonymous : AuthMode

    /** 账号密码。密码由面板生成（8 位，字母数字混合），可改。 */
    data class Credentials(val user: String, val password: String) : AuthMode

    /** 日志与通知里用的短描述；**绝不包含密码**。 */
    val describe: String
        get() = when (this) {
            Anonymous -> "匿名"
            is Credentials -> user
        }
}

/**
 * TLS 配置（FTPS / HTTPS）。
 *
 * M4 只登记类型不实现：方案把 S-3 FTPS 排到 M5。先定义在这里是为了让
 * [ServerConfig] 的形态**一次定型**——将来加 FTPS 是"填一个字段"，
 * 而不是"改一遍所有构造函数调用点"。
 */
data class TlsConfig(
    /** 自签证书的密钥库（PKCS12）。 */
    val keyStorePath: String,
    val keyStorePassword: String,
    val keyAlias: String,
    /** 指纹（SHA-256），面板上展示给用户与 PC 端核对。 */
    val fingerprintSha256: String,
)

/**
 * 一次分享会话的服务端配置。
 *
 * 刻意做成**不可变数据类**：配置一变（改端口 / 切只读 / 换编码）就必须重启服务端，
 * 不可变能让"重启用的是哪份配置"没有任何歧义，也让 `StateFlow` 的状态比较是纯值比较。
 */
data class ServerConfig(

    /**
     * 期望端口；`0` 表示"随便挑一个空闲的"。
     *
     * 传具体值时若被占用会**向上探测**（+1、+2……），最终实际端口在
     * [ServerState.Running.addresses] 里回给 UI —— 参考应用也是这个行为：
     * 用户设了 2121 结果被占用时，界面显示的是真实端口，而不是一句"启动失败"。
     */
    val port: Int = 0,

    val auth: AuthMode = AuthMode.Anonymous,

    /**
     * 只读。
     *
     * 打开后：[FsBridge] 的写方法一律抛
     * [com.miyuetech.mimifiles.core.fs.FsNotSupportedException]，HTTP 端的
     * 上传 / 新建 / 删除 / 重命名接口返回 403，FTP 端按只读用户建。
     * **这不是"前端藏按钮"**——那只是看起来安全。
     */
    val readOnly: Boolean = false,

    /**
     * 是否只监听局域网地址。
     *
     * 判定依据是"本机是否处于私网段"（[LanAddresses]）。关掉它意味着绑定 `0.0.0.0`
     * 并把所有网卡地址都列出来——包含 VPN / 蜂窝地址。默认开，因为把服务暴露到
     * 运营商网络上是明确不该发生的事。
     */
    val bindLanOnly: Boolean = true,

    /** FTPS / HTTPS；`null` 表示明文（M4 的默认形态）。 */
    val tls: TlsConfig? = null,

    /**
     * 会话有效期（毫秒）。
     *
     * 语义同参考应用：**面板关闭后开始计时**，超时后服务端停止、待分享清单失效；
     * 重开面板时以最新的一批文件替换列表（见 `ShareSessionHolder`）。
     * 默认 5 分钟（方案 S-7）。
     */
    val sessionTtlMillis: Long = 5 * 60_000L,

    /** 设备名，出现在 FTP 欢迎语与网页标题里（"密密文件管理 · Pixel"）。 */
    val deviceName: String = "MimiFiles",
) {

    /** 生效的端口：`0` 时取该类型的推荐端口。 */
    fun resolvedPreferredPort(type: ServerType): Int =
        if (port > 0) port else type.preferredPort

    /** Basic 认证的 `WWW-Authenticate` realm。 */
    val realm: String get() = "MimiFiles"

    companion object {

        /** 面板默认：随机账密、非只读、UTF-8、5 分钟。 */
        fun defaultFor(type: ServerType): ServerConfig = ServerConfig(
            port = type.preferredPort,
            auth = AuthMode.Credentials(user = "mimi", password = randomPassword()),
        )

        private const val PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        /**
         * 生成 8 位随机密码。
         *
         * 刻意**排除**了 `i l o 0 1` 这类易混字符：这个密码是要用户从手机屏幕上
         * 手抄到 PC 的 FTP 客户端里的，抄错一位的表现是"密码错误"，
         * 而他不会想到是看错了字。用 [java.security.SecureRandom] 而不是 `Random`：
         * 它守的是局域网里的文件，不该是可预测的。
         */
        fun randomPassword(length: Int = 8): String {
            val rnd = java.security.SecureRandom()
            val sb = StringBuilder(length)
            repeat(length) { sb.append(PASSWORD_ALPHABET[rnd.nextInt(PASSWORD_ALPHABET.length)]) }
            return sb.toString()
        }
    }
}

/**
 * 待分享内容的来源描述。
 *
 * 面板从两处进入（方案 §10.4「入口一致」）：
 * ① 导航窗格「无线分享」→ 以**当前所在目录**为共享根；
 * ② 文件多选 → 分享 → 以**选中项的共同父目录**为共享根，并在根上挂一层可见名过滤。
 */
data class ShareRequest(
    /** 共享根（面板上「路径」一行显示的就是它，可编辑）。 */
    val root: FsPath,
    /**
     * 根下允许出现的名字；`null` 表示不过滤（整目录都分享）。
     *
     * 多选多个文件时用它把根列表收敛到"用户真正选中的那些"，
     * 而不是把他所在的整个目录（可能有大半年没清理的东西）都摊开给 PC 看。
     */
    val visibleNames: Set<String>? = null,
) {
    companion object {

        /**
         * 由选中项推导共享根。
         *
         * | 选中 | 共享根 | 过滤 |
         * |---|---|---|
         * | 0 项 | 调用方给的当前目录 | 无 |
         * | 1 个目录 | 该目录本身 | 无 |
         * | 1 个文件 | 它所在的目录 | 它自己 |
         * | 多个 | 共同父目录 | 选中项的名字 |
         *
         * "1 个目录 → 根就是它"这一条很关键：用户选了一个文件夹点分享，
         * 期望的是"进去就能看到这个文件夹里的东西"，而不是"先看到一个只含
         * 它自己的列表，再点一层"。这是资源管理器里分享文件夹的既定语义。
         *
         * ⚠️ 注意"1 个文件"必须**单独成一支**：`reduceOrNull` 在只有一个元素时
         * 返回的就是那个元素本身，于是根会变成那个文件——而 FTP/HTTP 的根必须
         * 是目录（否则 `PWD` 该显示什么、`list` 该列什么，全是没法回答的问题）。
         * 这个坑是 M4 单测抓出来的。
         */
        fun ofSelection(currentDir: FsPath, selected: List<FsPath>, selectedIsDir: Boolean): ShareRequest {
            if (selected.isEmpty()) return ShareRequest(currentDir)
            if (selected.size == 1 && selectedIsDir) return ShareRequest(selected[0])
            val ancestor = if (selected.size == 1) {
                selected[0].parent ?: currentDir
            } else {
                selected.reduceOrNull { acc, next -> acc.commonAncestor(next) ?: currentDir } ?: currentDir
            }
            return ShareRequest(ancestor, selected.mapTo(LinkedHashSet()) { it.displayName })
        }
    }
}
