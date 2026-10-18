package com.miyuetech.mimifiles.core.shareserver.ftp

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.shareserver.AuthMode
import com.miyuetech.mimifiles.core.shareserver.ServerConfig
import com.miyuetech.mimifiles.core.shareserver.http.HttpAuth
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission

/**
 * 只认 [ServerConfig.auth] 这一份配置的用户管理。
 *
 * ## 为什么不用 `PropertiesUserManager`
 * 那套实现把用户存进一个 XML/properties 文件，支持多用户、密码加密、增删改——
 * 而我们的需求是"一个会话、一组凭据，会话结束就该失效"。用它还得先把密码
 * 写进磁盘（多一处凭据落盘），再用完删掉。自己实现 [UserManager] 一共不到
 * 八十行，且**凭据从不落盘**（只活在 [ServerConfig] 的内存对象里）。
 *
 * ## 认证与授权是两件事
 * - **认证**（[authenticate]）：用户名密码对不对。匿名模式下任何人都过；
 * - **授权**（`BaseUser.authorities`）：能不能写。只读会话**不授予** `WritePermission`，
 *   于是 `STOR` / `MKD` / `DELE` / `RNFR` 会被 FtpServer 自己在命令层拒掉并回 550
 *   —— **多一层与 [com.miyuetech.mimifiles.core.shareserver.ShareScope] 无关的防线**。
 *   之所以要这层冗余：`ShareScope` 守的是"这个动作合不合法"，而权限位守的是
 *   "这个用户配不配有这个能力"。二者日后各自演化时，任何一边写错都不会导致越权。
 *
 * ## 匿名模式下仍然要求"用户名非空"
 * FTP 客户端在匿名登录时发的用户名的确常常就是 `anonymous`，但也可能是 `ftp`、
 * 甚至一个邮箱。这里一律接受，并把实际用户名回填给会话（它会出现在 `USER` 应答
 * 与 `getOwnerName()` 里），这样用户在 PC 端的日志里能看出是谁连的。
 */
class FtpUserManager(private val config: ServerConfig) : UserManager {

    /**
     * 授予这个用户的 Authority 列表。
     *
     * ## 两个都必须给，少一个就完全登不进去
     *
     * | Authority | 不给的后果 |
     * |---|---|
     * | [WritePermission] | `STOR`/`MKD`/`DELE`/`RNFR` 被命令层拒掉（**只读会话正是靠不给它**） |
     * | [ConcurrentLoginPermission] | **每一次** `USER` 都被拒：`421 Maximum login limit has been reached.` |
     *
     * 第二条是 M4 联调时踩到的坑，值得写清楚：`USER` 命令在通过
     * `maxLogins` 的总量检查之后，还会构造一个 `ConcurrentLoginRequest` 并调用
     * `user.authorize(request)`；`BaseUser.authorize` 在没有 Authority 能处理该
     * 请求类型时**返回 `null`**，而命令层把 `null` 解释成"不允许"。
     * 于是"只授予写权限"这个看起来更保守的写法，代价是**所有登录一律失败**，
     * 而错误信息（Maximum login limit）完全指不到真正的原因。
     *
     * 数值取 0 表示"不限"吗？不是——在 `ConcurrentLoginPermission` 里 0 才是"不限"，
     * 这里刻意给一个与我们 `ConnectionConfig.maxLogins` **相同**的真实上限：
     * 两处一致时，"为什么第 9 个连接被拒"这个问题只有一个答案。
     */
    private val authorities: List<Authority> = buildList {
        if (!config.readOnly) add(WritePermission())
        // 上限与 `FtpShareServer` 的 `ConnectionConfigFactory.setMaxLogins` 同源（8）。
        // 单个 IP 的上限也设同一个值：一台 PC 的 FTP 客户端默认开 2~4 条连接，
        // 8 足够，而"同一 IP 无限开"是我们唯一需要防的形态（端口耗尽）。
        add(ConcurrentLoginPermission(MAX_LOGINS, MAX_LOGINS))
    }


    /** 认得的用户名。匿名模式下是"任意非空"，这里只用于 [getAllUserNames] 这类查询。 */
    private val knownName: String = when (val mode = config.auth) {
        AuthMode.Anonymous -> ANONYMOUS
        is AuthMode.Credentials -> mode.user
    }

    override fun getUserByName(name: String?): User? {
        if (name.isNullOrEmpty()) return null
        return when (val mode = config.auth) {
            AuthMode.Anonymous -> buildUser(name)
            is AuthMode.Credentials -> if (HttpAuth.constantTimeEquals(name, mode.user)) buildUser(name) else null
        }
    }

    override fun getAllUserNames(): Array<String> = arrayOf(knownName)

    override fun doesExist(name: String?): Boolean =
        !name.isNullOrEmpty() && getUserByName(name) != null

    override fun authenticate(authentication: Authentication): User {
        return when (val mode = config.auth) {
            AuthMode.Anonymous -> {
                // `AnonymousAuthentication` 与"用户随便输了个用户名"都接受。
                val name = (authentication as? UsernamePasswordAuthentication)?.username
                    ?.takeIf { it.isNotBlank() } ?: ANONYMOUS
                buildUser(name)
            }

            is AuthMode.Credentials -> {
                val credentials = authentication as? UsernamePasswordAuthentication
                    ?: throw AuthenticationFailedException("本分享需要账号密码")
                val user = credentials.username ?: ""
                val pass = credentials.password ?: ""
                // 与 HTTP 侧共用同一个定长比较实现：两侧的认证逻辑不该有第二份。
                val ok = HttpAuth.constantTimeEquals(user, mode.user) and
                    HttpAuth.constantTimeEquals(pass, mode.password)
                if (!ok) {
                    // ⚠️ 日志里**绝不能**出现密码，也不要出现完整用户名（可被用来枚举）。
                    MimiLog.w(TAG, "FTP 登录失败：用户名长度=${user.length}")
                    throw AuthenticationFailedException("用户名或密码不正确")
                }
                buildUser(user)
            }
        }
    }

    /** 本实现不支持在线增删用户（凭据来自一次分享会话，改它没有意义）。 */
    override fun save(user: User?) {
        throw FtpException("本分享的用户凭据由面板生成，不支持在线修改")
    }

    override fun delete(name: String?) {
        throw FtpException("本分享的用户凭据由面板生成，不支持在线删除")
    }

    override fun getAdminName(): String = knownName

    /**
     * 单一用户即是管理员。
     *
     * 之所以要返回真：FtpServer 的若干"管理类"命令（`SITE` 系列）会检查它。
     * 我们的命令工厂里这些命令并没有被替换成受限版本，但**授予管理员身份不会
     * 带来任何额外能力**——真正的边界始终是 [ShareFileSystemView] 里的
     * 共享根与 [com.miyuetech.mimifiles.core.shareserver.ShareScope] 的读写开关。
     */
    override fun isAdmin(name: String?): Boolean = name == knownName || config.auth is AuthMode.Anonymous

    /**
     * 构造一个可登录的用户。
     *
     * 这里刻意**用显式的 setter 调用**而不是 Kotlin 合成属性（`user.name = …`）：
     * `BaseUser` 上 `getEnabled()/setEnabled()` 这类方法在 Kotlin 侧会生成
     * `enabled` 还是 `isEnabled` 取决于命名特例，写成属性访问在未来换库版本时
     * 可能出现"编译通过但设错了字段"的假象；显式 setter 没有这层歧义。
     */
    private fun buildUser(name: String): User = BaseUser().apply {
        setName(name)
        // 密码不回填：认证已经做完了，把明文密码留在这个对象的字段里，
        // 只会多一条"谁能读到它"的路径（FtpServer 的日志与 `toString()` 都会带上）。
        setPassword(null)
        // 虚拟根："/" 是**共享根**，不是 Android 文件系统根
        // （映射发生在 ShareFileSystemView.resolveArgument）。
        setHomeDirectory("/")
        setEnabled(true)
        setAuthorities(this@FtpUserManager.authorities)
        // 0 = 用连接配置里的空闲超时（不单独覆盖）。
        setMaxIdleTime(0)
    }

    companion object {
        private const val TAG = "MimiShareFtp"
        private const val ANONYMOUS = "anonymous"

        /**
         * 并发登录上限 —— 必须与 `FtpShareServer.MAX_LOGINS` 保持一致。
         *
         * 复制一份常量而不是把 `FtpShareServer` 的那个改成 public：两处表达的是
         * **同一个产品决策**（"一台 PC 够用、别把连接耗光"），而不是"某处实现细节"。
         * 让它们各归各类、各自注明对照关系，比把它们绑成一个名字更清楚地表达了这一点。
         */
        private const val MAX_LOGINS = 8
    }
}
