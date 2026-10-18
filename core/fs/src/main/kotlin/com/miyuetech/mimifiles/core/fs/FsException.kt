package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsPath
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 文件系统层统一异常体系。
 *
 * ## 为什么必须是 sealed
 * UI 要给用户**确定的话术**：没权限 → 引导去授权；连不上 → 提示检查网络/地址；
 * 密码错 → 提示重新输入；对象不存在 → 静默刷新列表。这四类如果都被压成一句
 * 「操作失败」，用户就只能干瞪眼。`when` 对 sealed 穷举，漏一个分支编译期就报错——
 * 这是强迫后续每个后端都给出准确语义的最便宜手段。
 *
 * 全部继承 [IOException]：既能被现有 IO 代码统一捕获，又能直接喂给协程取消链路。
 */
sealed class FsException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 目标不存在。列表刷新时通常应被静默吞掉（目录可能刚被别的进程删了）。 */
class FsNotFoundException(
    val path: FsPath,
    cause: Throwable? = null,
) : FsException("not found: $path", cause)

/** 存在但无权访问。UI 应引导授权（Android 11+ 全盘访问 / SAF 目录授权 / 连接重新登录）。 */
class FsAccessDeniedException(
    val path: FsPath,
    cause: Throwable? = null,
) : FsException("access denied: $path", cause)

/** 目标已存在。粘贴/新建时的冲突分支入口。 */
class FsAlreadyExistsException(
    val path: FsPath,
    cause: Throwable? = null,
) : FsException("already exists: $path", cause)

/** 该后端不支持此操作。UI 应据此**提前灰置**菜单项，而不是让用户点了才报错。 */
class FsNotSupportedException(
    val scheme: String,
    val operation: String,
    cause: Throwable? = null,
) : FsException("scheme '$scheme' does not support: $operation", cause)

/** 凭据被拒（SMB 密码错 / WebDAV 401）。与 [FsAccessDeniedException] 区分：后者是文件权限，前者是账号。 */
class FsAuthException(
    val authority: String,
    cause: Throwable? = null,
) : FsException("authentication failed for: $authority", cause)

/** 主机不可达 / 超时 / 服务未开。 */
class FsUnreachableException(
    val authority: String,
    cause: Throwable? = null,
) : FsException("unreachable: $authority", cause)

/** 兜底 IO 失败（磁盘满、管道断、编码错）。 */
class FsIoException(message: String, cause: Throwable? = null) : FsException(message, cause)

/** 被协程取消 / 用户主动中止。**不应**向用户弹错误提示。 */
class FsCancelledException(cause: Throwable? = null) : FsException("operation cancelled", cause)

/**
 * 把后端抛出的原始异常翻译成上面这套语义。
 *
 * 放在这里而不是各后端各写一遍：`FileNotFoundException` 在 SMB 上同样表示"共享里没这个名"，
 * 映射规则是全局一致的，散落各处只会漂移。
 */
fun Throwable.toFsException(path: FsPath): FsException = when (this) {
    is FsException -> this
    is FileNotFoundException -> FsNotFoundException(path, this)
    is SecurityException -> FsAccessDeniedException(path, this)
    is UnknownHostException -> FsUnreachableException(path.authority.ifEmpty { path.scheme }, this)
    is SocketTimeoutException -> FsUnreachableException(path.authority.ifEmpty { path.scheme }, this)
    else -> FsIoException("$this: ${message.orEmpty()}", this)
}

/** 供 UI 层直接用 `when` 穷举的展示分类。 */
val FsException.isRetryable: Boolean
    get() = this is FsUnreachableException || this is FsIoException
