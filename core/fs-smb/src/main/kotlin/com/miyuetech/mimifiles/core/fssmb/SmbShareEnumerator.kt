package com.miyuetech.mimifiles.core.fssmb

import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsAuthException
import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.FsUnreachableException
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsPath
import jcifs.smb.SmbFile

/**
 * 列出一台 SMB 服务器上的全部共享文件夹 —— 「SMB 主目录」的数据源。
 *
 * ## 为什么只能用 jcifs-ng，而不是复用主数据通道
 * 主通道是 smbj（SMB2/3），它按 `session.connectShare(shareName)` 取一个
 * `DiskShare`，**必须先知道共享名**；而 smbj 0.15.0 并没有实现 SRVSVC 的
 * `NetShareEnumAll` DCE-RPC，所以没有「问服务器有哪些共享」的能力。
 *
 * jcifs-ng 2.1.10 则内置 `MsrpcShareEnum`：当 `SmbFile` 的 URL 是**服务器根**
 * （`smb://host:port/`，不带共享段）时，`listFiles()` 内部会经 IPC$ 的
 * `\PIPE\srvsvc` 走 NetShareEnumAll。所以这里用 jcifs-ng 单独建一次连接，
 * 只负责取共享名；点进某个共享之后，读写仍照旧走 [SmbConnector] 的 smbj/SMB1 通道。
 * 两条通道彼此独立，互不缓存、互不复用会话。
 *
 * ## 方言
 * 未勾「强制 SMB1」时先让 jcifs 自行协商（现代 NAS 多为 SMB3），失败再退 SMB1 ——
 * 与 [SmbConnector.open] 的降级方向一致。认证失败与网络不可达**不重试**：
 * 换个方言也还是密码不对 / 地址不通，多等一轮超时没有意义。
 *
 * ## 为什么服务端可能不给列
 * NetShareEnum 走 IPC$，部分 NAS 会禁用匿名枚举、或只允许管理员账号调用。
 * 这时不能让整个连接变成"打不开"：抛一句可操作的 [FsIoException]，
 * 提示用户在连接里直接写共享名（那条路径完全不经过这里）。
 */
internal object SmbShareEnumerator {

    private const val TAG = "MimiSmb"

    @Throws(FsIoException::class)
    fun listShares(profile: ConnectionProfile, password: String?): List<String> {
        val address = SmbAddress.parse(profile.host)
        val host = address.host
        if (host.isBlank()) throw FsIoException("服务器地址为空，无法列出共享")
        val port = address.port ?: profile.port.takeIf { it > 0 } ?: SmbConnector.DEFAULT_PORT
        val settings = SmbSettings.parse(profile.options)
        val errorPath = FsPath.of(SmbFileSystem.SCHEME, profile.id.ifEmpty { "_shares" })

        val modes = if (settings.forceSmb1) listOf(true) else listOf(false, true)
        var last: Throwable? = null
        for (smb1Only in modes) {
            try {
                val names = enumerate(
                    host = host,
                    port = port,
                    username = profile.username,
                    password = password,
                    domain = profile.domain,
                    settings = settings,
                    smb1Only = smb1Only,
                )
                MimiLog.i(TAG, "listed ${names.size} shares on $host (smb1Only=$smb1Only)")
                return names
            } catch (t: Throwable) {
                last = t
                val mapped = t.toSmbException(errorPath)
                MimiLog.w(
                    TAG,
                    "share enumeration failed on $host (smb1Only=$smb1Only): " +
                        "${mapped.javaClass.simpleName}: ${mapped.message}",
                )
                // 与 [SmbConnector.open] 同一条判定：这两类失败与方言无关。
                if (mapped is FsAuthException || mapped is FsUnreachableException) break
            }
        }
        throw describeFailure(last, errorPath)
    }

    private fun enumerate(
        host: String,
        port: Int,
        username: String?,
        password: String?,
        domain: String?,
        settings: SmbSettings,
        smb1Only: Boolean,
    ): List<String> {
        val ctx = SmbConnector.jcifsContext(
            host = host,
            port = port,
            username = username,
            password = password,
            domain = domain,
            settings = settings,
            smb1Only = smb1Only,
        )
        return try {
            // 服务器根：URL 只到 host[:port]/，不带共享段。
            // jcifs 会据此走 NetShareEnumAll；写成 smb://host/share/ 就变成列某个共享了。
            val root = SmbFile("smb://$host:$port/", ctx)
            val children = root.listFiles() ?: emptyArray()
            SmbShareNames.sanitize(children.map { it.name })
        } finally {
            // 枚举用的是独立连接，用完立刻放掉，不留到下一次列目录。
            runCatching { ctx.close() }
        }
    }

    /** 把最后一次库异常翻成「用户能照做的一句话」。 */
    private fun describeFailure(cause: Throwable?, path: FsPath): FsIoException {
        val mapped = cause?.toSmbException(path)
        val hint = "请在连接里直接填写共享名（这条路径不需要列举共享）。"
        val reason = when (mapped) {
            is FsAuthException -> "认证失败：用户名或密码不正确，无法列出共享。$hint"
            is FsUnreachableException -> "连不上服务器，无法列出共享。请检查地址、端口与网络。$hint"
            is FsAccessDeniedException -> "该账号没有列出共享的权限。$hint"
            null -> "无法列出该服务器的共享文件夹。$hint"
            else -> {
                // 用统一的可读化收口，避免把 Java 类名/协议转储直接摆到界面上。
                val detail = (mapped.message ?: mapped.javaClass.simpleName).toHumanSmbLine(120)
                "无法列出该服务器的共享文件夹（$detail）。服务端可能禁用了共享枚举。$hint"
            }
        }
        return FsIoException(reason, cause)
    }
}
