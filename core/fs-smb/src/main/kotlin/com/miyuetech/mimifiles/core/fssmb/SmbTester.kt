package com.miyuetech.mimifiles.core.fssmb

import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.isRetryable
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.network.NetworkDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 连接测试的结果。
 *
 * 不用 `Boolean` 是因为"失败"至少要分两类：[Failure.reason] 是给用户看的一句话，
 * [Failure.retryable] 决定 UI 要不要给"重试"按钮。把"连不上"和"密码错"
 * 都压成一个 `false`，用户就不知道该改网络还是改密码。
 */
sealed interface SmbTestResult {
    data class Success(val dialect: String) : SmbTestResult

    data class Failure(val reason: String, val retryable: Boolean) : SmbTestResult
}

/**
 * SMB 连接测试的公开入口。
 *
 * ## 为什么单独开一个对象，而不是让 UI 直接用 [SmbFileSystem]
 * [SmbFileSystem] 要求"先解析出实例、再操作"，而测试连接时用户可能刚改完密码、
 * 甚至还没保存。测试必须能**直接用一份还没落库的参数**去打一次，
 * 所以这里接收的是 [ConnectionProfile] + 密码原文，而不是 authority。
 *
 * ## 为什么不让 `:core:fs-smb` 依赖 `:core:datastore`
 * 密码由调用方（`:feature:connection`）从保险箱解密后传入。本模块永远不碰数据库，
 * 因此它无法在用户不知情的情况下"自己去读密码"——这是有意的边界。
 */
object SmbTester {

    /**
     * 打一次真实会话：建连 → 认证 → 列共享根。
     *
     * 只 `connect(host, port)` 成功是不够的——那只证明 445 端口开着。
     * 列一次目录才能同时验证认证、共享名、以及账号对这个共享的读权限。
     *
     * @return 成功时带上协商到的方言（UI 可显示"已降级至 SMB1"）
     */
    suspend fun test(profile: ConnectionProfile, password: String?): SmbTestResult =
        withContext(NetworkDispatchers.io) {
            // 服务器地址容错。用户很自然会把 Windows 上那份地址整个粘进来
            // （`\\192.168.1.100\stock`、`smb://192.168.1.100/stock`），
            // 而这里若原样当主机名去解析，报出来的会是"连不上 \\192.168.1.100\stock"
            // ——一个把真实原因藏起来的说法。地址的清洗在 [SmbAddress] 里，与保存路径共用。
            val address = SmbAddress.parse(profile.host)
            if (address.host.isBlank()) {
                return@withContext SmbTestResult.Failure("请填写服务器地址", retryable = false)
            }

            val settings = SmbSettings.parse(profile.options)
            // 共享名优先取"共享名"字段；它空着时用地址里剥出来的那一段
            // （`\\192.168.1.100\stock` 的 `stock`）。两处都能填，哪边有就用哪边。
            val share = shareNameOf(profile.rootPath).ifEmpty { address.share.orEmpty() }
            if (share.isEmpty()) {
                return@withContext SmbTestResult.Failure(
                    "请先填写共享名（例如「home」或「public」）",
                    retryable = false,
                )
            }
            try {
                val dialect = SmbConnector.probe(
                    authority = profile.id.ifEmpty { "_probe" },
                    host = address.host,
                    port = address.port ?: profile.port,
                    shareName = share,
                    username = profile.username,
                    password = password,
                    domain = profile.domain,
                    settings = settings,
                )
                SmbTestResult.Success(dialect)
            } catch (cancel: CancellationException) {
                // ⚠️ 不能被下面的兜底吞掉。测试连接跑在页面的 lifecycleScope 上，
                // 用户一离开页面协程就被取消；吞掉之后会把一次**正常的取消**
                // 变成一条"连接失败"的提示，指向一个根本不存在的问题。
                throw cancel
            } catch (e: FsException) {
                SmbTestResult.Failure(e.readableReason(), e.isRetryable)
            } catch (t: Throwable) {
                // 走到这里的是"库抛出的、还没被翻成 FsException 的"异常。
                // 绝不能把 `t.message` 直接给用户 —— 那是一串 Java 类名
                // （实测文案见 [readableSmbReason] 的注释）。
                SmbTestResult.Failure(t.readableSmbReason(), retryable = true)
            }
        }

    /** 从 `share/sub/path` 里取出共享名。 */
    internal fun shareNameOf(rootPath: String): String =
        rootPath.trim().trim('/').substringBefore('/').trim()

    /**
     * 把异常翻成一句用户能照做的话。
     *
     * 不直接把 `e.message` 抛给用户：那些消息里带内部类名与英文术语
     * （`SMBApiException: STATUS_LOGON_FAILURE`），对用户毫无帮助。
     */
    private fun FsException.readableReason(): String = when (this) {
        is com.miyuetech.mimifiles.core.fs.FsAuthException ->
            "认证失败：用户名或密码不正确${if (cause?.message?.contains("domain", true) == true) "，若在域环境中请填写域" else ""}"

        is com.miyuetech.mimifiles.core.fs.FsUnreachableException ->
            "连不上 $message：请检查地址、端口、以及手机是否和 NAS 在同一网络"

        is com.miyuetech.mimifiles.core.fs.FsNotFoundException ->
            "共享不存在，或这个账号没有访问权限"

        is com.miyuetech.mimifiles.core.fs.FsAccessDeniedException ->
            "账号没有访问该共享的权限"

        else -> message ?: "连接失败"
    }
}
