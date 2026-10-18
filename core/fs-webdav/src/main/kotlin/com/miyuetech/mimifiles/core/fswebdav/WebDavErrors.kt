package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsAlreadyExistsException
import com.miyuetech.mimifiles.core.fs.FsAuthException
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.FsNotSupportedException
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.fs.FsUnreachableException
import com.miyuetech.mimifiles.core.model.FsPath
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * WebDAV 异常 → [FsException] 的映射。
 *
 * HTTP 状态码的语义比 SMB 的 NT status 直白，但有两个坑值得单独说：
 *
 * 1. **405 / 501 常被服务端当成"不支持这个 WebDAV 扩展"来用**。例如对
 *    `DELETE` 带 `Depth: infinity`，部分服务端不是回 400 而是 405。
 *    这里把它映射成 [FsNotSupportedException]，让上层有机会走"递归列举 + 逐个删"的回退，
 *    而不是直接告诉用户"删除失败"。
 *
 * 2. **409 Conflict 在 WebDAV 里表示"父目录不存在"**（RFC 4918 §9.8.5），
 *    而不是文件系统里的"同名冲突"。映射成 [FsNotFoundException] 比映射成
 *    "已存在"更接近事实——用户看到"目标目录不存在"能自己去建，看到"已存在"会一头雾水。
 */
internal fun Throwable.toWebDavException(path: FsPath): FsException = when (this) {
    is FsException -> this

    // ── 网络层：先判，否则会被下面的 IOException 分支吞成笼统的 IO 错误 ──
    is UnknownHostException,
    is ConnectException,
    is NoRouteToHostException,
    is SocketTimeoutException,
    -> FsUnreachableException(path.authority.ifEmpty { path.scheme }, this)

    is SSLException -> FsUnreachableException(path.authority.ifEmpty { path.scheme }, this)

    is FileNotFoundException -> FsNotFoundException(path, this)

    is SardineException -> httpStatusToFsException(statusCode, path, this)

    else -> FsIoException("$this: ${message.orEmpty()}", this)
}

/**
 * HTTP 状态码 → 语义化异常。
 *
 * 做成 `internal` 而不是私有：随机读那条路径（[WebDavFileSystem.openRange]）直接用
 * OkHttp 发请求，拿到的**只有状态码**，没有 `SardineException` 可包。
 * 让它复用同一张映射表，比在那边再写一个 `when(code)` 好——两张表迟早会分叉。
 */
internal fun httpStatusToFsException(status: Int, path: FsPath, cause: Throwable?): FsException =
    when (status) {
        401 -> FsAuthException(path.authority, cause)

        403 -> FsAccessDeniedException(path, cause)

        404, 410 -> FsNotFoundException(path, cause)

        405, 501 -> FsNotSupportedException(path.scheme, "operation", cause)

        // 见类注释第 2 条：WebDAV 的 409 是"父集合不存在"。
        409 -> FsNotFoundException(path.parent ?: path, cause)

        // 412 Precondition Failed：MOVE/COPY 的 Overwrite: F 命中已存在的目标。
        412 -> FsAlreadyExistsException(path, cause)

        // 423 Locked：文件被别人用 Office 打开着。这是"稍后重试有效"的场景。
        423 -> FsIoException("文件被锁定（可能正在被其他程序打开）：$path", cause)

        // 507：RFC 4918 定义的"存储空间不足"，比 500 精确得多。
        507 -> FsIoException("服务端存储空间不足：$path", cause)

        400 -> FsIoException("请求被服务端拒绝（400）：$path", cause)

        else -> if (status in 500..599) {
            FsIoException("服务端错误（$status）：${cause?.message.orEmpty()}", cause)
        } else {
            FsIoException("WebDAV 错误 $status：${cause?.message.orEmpty()}", cause)
        }
    }
