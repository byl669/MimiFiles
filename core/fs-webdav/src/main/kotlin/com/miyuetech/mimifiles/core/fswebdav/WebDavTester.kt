package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsAuthException
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.fs.FsUnreachableException
import com.miyuetech.mimifiles.core.fs.isRetryable
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.network.NetworkDispatchers
import kotlinx.coroutines.withContext

/** 与 [com.miyuetech.mimifiles.core.fssmb.SmbTestResult] 同构，便于 UI 统一映射。 */
sealed interface WebDavTestResult {
    /** @param authLabel 实际生效的认证方式（`Basic` / `Digest`），供 UI 显示 */
    data class Success(val authLabel: String) : WebDavTestResult

    data class Failure(val reason: String, val retryable: Boolean) : WebDavTestResult
}

/**
 * WebDAV 连接测试的公开入口。见 `SmbTester` 的注释（同样的边界与理由）。
 *
 * ## 为什么测试要"列一次目录"而不是只 `exists("/")`
 * 很多 WebDAV 服务端对不存在的根会返回 `404`（而不是 `403` 或 `401`），
 * 只看 `exists` 会把"密码错"和"路径错"混在一起。列一次目录能同时验证：
 * 认证通了、路径存在、且是个集合（`PROPFIND` 对文件也能成功，但结果里没有子项）。
 */
object WebDavTester {

    suspend fun test(profile: ConnectionProfile, password: String?): WebDavTestResult =
        withContext(NetworkDispatchers.io) {
            val settings = WebDavSettings.parse(profile.options)
            if (profile.host.isBlank()) {
                return@withContext WebDavTestResult.Failure("请先填写服务器地址", retryable = false)
            }
            val fs = WebDavFileSystem(profile, password)
            try {
                val root = FsPath.of(WebDavFileSystem.SCHEME, profile.id.ifEmpty { "_probe" })
                fs.list(root)
                WebDavTestResult.Success(fs.authLabel() ?: if (profile.username.isNullOrBlank()) "匿名" else "Basic")
            } catch (e: FsException) {
                WebDavTestResult.Failure(e.readableReason(settings), e.isRetryable)
            } catch (t: Throwable) {
                WebDavTestResult.Failure(t.message ?: t.javaClass.simpleName, retryable = true)
            } finally {
                runCatching { fs.close() }
            }
        }

    private fun FsException.readableReason(settings: WebDavSettings): String = when (this) {
        is FsAuthException ->
            "认证失败：用户名或密码不正确" +
                if (!settings.allowInsecure) "（若服务端要求 Digest，可尝试在高级里切换认证方式）" else ""

        is FsAccessDeniedException ->
            "路径存在但无权访问。注意：部分服务端（如 Nextcloud）的 WebDAV 根不是网站根目录，" +
                "而是类似 /remote.php/dav/files/用户名"

        is FsNotFoundException ->
            "服务器可达，但路径不存在。请检查「路径」是否填对（群晖需先启用 WebDAV Server 套件）"

        is FsUnreachableException ->
            "连不上：请检查地址、端口与协议（http/https）"

        else -> message ?: "连接失败"
    }
}
