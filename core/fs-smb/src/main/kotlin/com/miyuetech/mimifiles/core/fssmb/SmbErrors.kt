package com.miyuetech.mimifiles.core.fssmb

import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mserref.NtStatus
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsAlreadyExistsException
import com.miyuetech.mimifiles.core.fs.FsAuthException
import com.miyuetech.mimifiles.core.fs.FsCancelledException
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.FsNotSupportedException
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.fs.FsUnreachableException
import com.miyuetech.mimifiles.core.model.FsPath
import jcifs.smb.SmbException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把 SMB 两个库的异常翻译成统一的 [FsException] 体系。
 *
 * ## 为什么值得单写一个文件
 * smbj 与 jcifs-ng 的异常类型、状态码载体完全不同（前者是 `NtStatus` 枚举，
 * 后者是裸 `int`）。如果让 `SmbFileSystem` 里到处 `catch`+`when`，会写出两份
 * 语义不一致的映射。集中在这里之后，"SMB 的权限不足该报哪种错"只有一个答案。
 *
 * ## 判定顺序有讲究
 * 先看更具体的类型（认证、超时、找不到主机），最后才落到 `SMBApiException`/`SmbException`
 * 的状态码，再不行才是通用 IO 错误。反过来写的话，"网络超时"会被吞成笼统的
 * "操作失败"，用户就不知道该去查网线还是查密码。
 */
internal fun Throwable.toSmbException(path: FsPath): FsException = when (this) {
    is FsException -> this

    // ── 网络层 ────────────────────────────────────────────────────────
    is UnknownHostException,
    is ConnectException,
    is NoRouteToHostException,
    is SocketTimeoutException,
    -> FsUnreachableException(path.authority.ifEmpty { path.scheme }, this)

    is FileNotFoundException -> FsNotFoundException(path, this)

    // ── smbj ─────────────────────────────────────────────────────────
    is SMBApiException -> status.toFsException(path, this)

    // ── jcifs-ng（SMB1）──────────────────────────────────────────────
    is jcifs.smb.SmbAuthException -> FsAuthException(path.authority, this)
    is SmbException -> ntStatusOf(this).toFsException(path, this)

    // 兜底：把"还没被识别出语义"的异常翻成人话。
    //
    // ⚠️ 这里原先是 `FsIoException("$this: ${message.orEmpty()}", this)`，
    // 而 `$this` 的 `toString()` **本身就包含 message**，于是同一句话被拼了两遍：
    //
    //     无法读取此位置 java.lang.RuntimeException: r9.c: The provided handler
    //       invocation did not specify the necessary constructor h(SubscriptionContext);
    //       : r9.c: The provided handler invocation did not specify ...
    //
    // 中间那个 `;:` 就是拼接点（M5.1 真机截图里可以直接看到）。用户拿到的是
    // 一段自我重复的 Java 内部文本，既读不懂、也不知道该拿它做什么。
    //
    // 改走 [readableSmbReason]：它负责"去掉包名、认识的特例给可操作的话、
    // 不认识也至少只出现一次"，与连接测试页用的是同一套话术。
    else -> FsIoException(readableSmbReason(), this)
}

/** smbj 的 `NtStatus` 枚举 → 语义化异常。 */
private fun NtStatus.toFsException(path: FsPath, cause: Throwable): FsException = when (this) {
    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
    NtStatus.STATUS_NO_SUCH_FILE,
    NtStatus.STATUS_NOT_FOUND,
    -> FsNotFoundException(path, cause)

    NtStatus.STATUS_ACCESS_DENIED,
    NtStatus.STATUS_PRIVILEGE_NOT_HELD,
    -> FsAccessDeniedException(path, cause)

    NtStatus.STATUS_OBJECT_NAME_COLLISION,
    NtStatus.STATUS_FILE_IS_A_DIRECTORY,
    -> FsAlreadyExistsException(path, cause)

    NtStatus.STATUS_LOGON_FAILURE,
    NtStatus.STATUS_ACCOUNT_DISABLED,
    NtStatus.STATUS_PASSWORD_EXPIRED,
    -> FsAuthException(path.authority, cause)

    NtStatus.STATUS_BAD_NETWORK_NAME,
    NtStatus.STATUS_BAD_NETWORK_PATH,
    NtStatus.STATUS_NETWORK_NAME_DELETED,
    NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
    -> FsUnreachableException(path.authority.ifEmpty { path.scheme }, cause)

    // 注意：smbj 的 NtStatus 枚举**并不覆盖全部 NT status**（上面这几个是逐个
    // javap 核对过的）。像 STATUS_ACCOUNT_EXPIRED、STATUS_WRONG_PASSWORD、
    // STATUS_HOST_UNREACHABLE 在这个版本里就没有定义，硬写会编译不过。
    // 它们最终会落到下面的 `else`，变成一句带状态码的 IO 错误——可接受，
    // 因为"账号过期"在 SMB 场景本就该由管理员在服务端解决。
    NtStatus.STATUS_NOT_SUPPORTED,
    -> FsNotSupportedException(path.scheme, "operation", cause)

    NtStatus.STATUS_CANCELLED -> FsCancelledException(cause)

    NtStatus.STATUS_DIRECTORY_NOT_EMPTY ->
        FsIoException("目录非空：$path", cause)

    NtStatus.STATUS_DISK_FULL ->
        FsIoException("目标磁盘空间不足：$path", cause)

    // 共享被并发删除、文件被别的程序独占等：都是"重试可能就好了"的瞬时状态，
    // 归到 Io 而不是 NotFound，UI 的话术会是"操作失败，请重试"。
    NtStatus.STATUS_DELETE_PENDING,
    NtStatus.STATUS_SHARING_VIOLATION,
    -> FsIoException("${cause.humanSmbMessage()}（对象被占用或正在删除）", cause)

    else -> FsIoException(
        "SMB 错误 0x${(cause as? SMBApiException)?.statusCode?.toString(16)?.uppercase() ?: "?"}" +
            cause.humanSmbMessage().let { if (it.isEmpty()) "" else "：$it" },
        cause,
    )
}

/**
 * jcifs-ng 的状态码是裸 `int`（`SmbException.getNtStatus()`），这里按 NT status 数值匹配。
 *
 * 用数值常量而不是引用 jcifs 的常量类：jcifs-ng 把这些值分散在 `SmbConstants` 与
 * `jcifs.internal` 下，且不同小版本位置漂移过。NT status 本身是**协议常量**，
 * 写死数值比跟着库搬家更稳定——下面每个值都有注释标明来源。
 */
private fun Int.toFsException(path: FsPath, cause: Throwable): FsException = when (this) {
    NT_NO_SUCH_FILE,
    NT_OBJECT_NAME_NOT_FOUND,
    NT_OBJECT_PATH_NOT_FOUND,
    NT_NOT_FOUND,
    -> FsNotFoundException(path, cause)

    NT_ACCESS_DENIED,
    NT_PRIVILEGE_NOT_HELD,
    -> FsAccessDeniedException(path, cause)

    NT_OBJECT_NAME_COLLISION -> FsAlreadyExistsException(path, cause)

    NT_LOGON_FAILURE,
    NT_ACCOUNT_DISABLED,
    NT_ACCOUNT_EXPIRED,
    NT_PASSWORD_EXPIRED,
    NT_WRONG_PASSWORD,
    -> FsAuthException(path.authority, cause)

    NT_BAD_NETWORK_NAME,
    NT_BAD_NETWORK_PATH,
    NT_NETWORK_UNREACHABLE,
    NT_HOST_UNREACHABLE,
    -> FsUnreachableException(path.authority.ifEmpty { path.scheme }, cause)

    NT_NOT_SUPPORTED -> FsNotSupportedException(path.scheme, "operation", cause)

    // 0xC0000001 `STATUS_UNSUCCESSFUL`：**泛化失败**，本身没带更多信息。
    // 但本项目实测它有一个高频来源 —— 服务端不实现目录列举（Trans2FindFirst2）。
    // jcifs 附带的原话是英文 `Invalid operation for SHARE service`，对中文用户
    // 没有帮助，所以这里补一句可能的原因（旧 NAS / 第三方精简 SMB 服务很常见）。
    NT_UNSUCCESSFUL ->
        FsIoException("服务端未能完成该操作（若该服务端只有 SMB1，常见原因是它不支持目录列举）", cause)

    NT_DIRECTORY_NOT_EMPTY -> FsIoException("目录非空：$path", cause)
    NT_DISK_FULL -> FsIoException("目标磁盘空间不足：$path", cause)
    NT_CANCELLED -> FsCancelledException(cause)

    // ⚠️ 十六进制要**按无符号**打印（M5.1 实测）。
    //
    // 原先是 `0x${toString(16)}`：NT status 的第 31 位恒为 1，`Int` 于是是负数，
    // `(-1073741823).toString(16)` 得到 `-3fffffff`。用户看到 `0x-3fffffff` 这种
    // 东西既搜不到、也和任何文档对不上（真实值是 `0xC0000001`）。
    else -> FsIoException("SMB1 错误 ${asUnsignedHex()}：${cause.humanSmbMessage()}", cause)
}

/**
 * `Int` 按**无符号 32 位**渲染成十六进制。
 *
 * 不用 `"%08X".format(this)`：那会走默认 `Locale`，而本模块跑在设备上，
 * 某些区域设置下数字格式并不稳定。`Integer.toHexString` 对负数的语义正好是
 * 无符号补码表示，配一次 `padStart` 就够。
 */
internal fun Int.asUnsignedHex(): String =
    "0x" + Integer.toHexString(this).uppercase().padStart(8, '0')

/**
 * 取 jcifs 异常里的状态码。
 *
 * `getNtStatus()` 在部分异常上是 0（表示"没有协议级状态"），此时不能用它做判定，
 * 否则 0 会落进 `else` 变成一句难懂的 "SMB1 错误 0x0"。返回 -1 让调用方走通用分支。
 */
private fun ntStatusOf(exception: SmbException): Int =
    runCatching { exception.ntStatus }.getOrDefault(0).takeIf { it != 0 } ?: -1

// ── NT status 常量（MS-ERREF 2.3）──────────────────────────────────────
private const val NT_NOT_FOUND = 0xC0000225.toInt()
private const val NT_NO_SUCH_FILE = 0xC000000F.toInt()
private const val NT_OBJECT_NAME_NOT_FOUND = 0xC0000034.toInt()
private const val NT_OBJECT_PATH_NOT_FOUND = 0xC000003A.toInt()
private const val NT_ACCESS_DENIED = 0xC0000022.toInt()
private const val NT_PRIVILEGE_NOT_HELD = 0xC0000061.toInt()
private const val NT_OBJECT_NAME_COLLISION = 0xC0000035.toInt()
private const val NT_LOGON_FAILURE = 0xC000006D.toInt()
private const val NT_WRONG_PASSWORD = 0xC000006A.toInt()
private const val NT_ACCOUNT_DISABLED = 0xC0000072.toInt()
private const val NT_ACCOUNT_EXPIRED = 0xC0000193.toInt()
private const val NT_PASSWORD_EXPIRED = 0xC0000071.toInt()
private const val NT_BAD_NETWORK_NAME = 0xC00000CC.toInt()
private const val NT_BAD_NETWORK_PATH = 0xC00000BE.toInt()
private const val NT_NETWORK_UNREACHABLE = 0xC000023C.toInt()
private const val NT_HOST_UNREACHABLE = 0xC000023D.toInt()
private const val NT_NOT_SUPPORTED = 0xC00000BB.toInt()
private const val NT_UNSUCCESSFUL = 0xC0000001.toInt()
private const val NT_DIRECTORY_NOT_EMPTY = 0xC0000101.toInt()
private const val NT_DISK_FULL = 0xC000007F.toInt()
private const val NT_CANCELLED = 0xC0000120.toInt()

/**
 * jcifs 的 `SmbException.getMessage()` 在人类可读的一句话后面**粘了一整包协议转储**。
 *
 * 实测（M5.1，真机 UI 原样抓下来）：
 *
 * ```
 * SMB1 错误 0x-3fffffff：Invalid operation for SHARE service: Trans2FindFirst2[
 *   command=SMB_COM_TRANSACTION2,received=false,errorCode=0,flags=0x0018,...(共 500+ 字符)
 * ```
 *
 * 前面那半句有用，后面那半段是把整个 `Trans2FindFirst2` 请求对象 `toString()` 出来的
 * 内部字段。它有两个害处：把真正的信息挤到屏幕外；让用户以为遇到了严重错误。
 *
 * ## 为什么按格式切，而不是按长度截
 * "取前 80 字符"会把 `invalid oper` 这种半截词留在界面上。jcifs 的转储有固定形状 ——
 * `XxxCmd[command=SMB_COM_…` —— 按这个形状切，切点永远落在句子边界上。
 */
internal fun String.trimSmbPacketDump(): String =
    replace(SMB_PACKET_DUMP, "").trim().trimEnd(':', '：', ',', '，', ' ')

private val SMB_PACKET_DUMP = Regex("""\s*\w+\[\s*command=SMB_COM_.*""", RegexOption.DOT_MATCHES_ALL)

/**
 * 给用户看的单行错误信息：去掉协议转储、去掉换行、必要时按上限截断。
 *
 * 供 [toSmbException] 的兜底分支与 [readableSmbReason] 共用，保证"同一种失败
 * 不管从哪条路径进来，界面上是同一句话"。
 */
internal fun String.toHumanSmbLine(maxLen: Int = 180): String {
    val cleaned = substringBefore('\n').trimSmbPacketDump()
    return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen).trimEnd() + "…"
}

/** [toHumanSmbLine] 的异常版本。 */
internal fun Throwable.humanSmbMessage(maxLen: Int = 180): String =
    message.orEmpty().toHumanSmbLine(maxLen)

/**
 * 把"还没被翻译成 [FsException] 的原始异常"翻成一句**用户能照做的话**。
 *
 * ## 为什么必须有这一层（M4.1 实测）
 * [toSmbException] 覆盖的是"能识别出 NT 状态码"的那些失败。但库还有一类异常
 * **不带状态码**，直接落到 `catch (t: Throwable)`，而那里原来的写法是
 * `t.message ?: simpleName` ——于是用户看到的是：

 * ```
 * 连接失败：com.hierynomus.smbj.session.SMB2GuestSigningRequiredException:
 *   Cannot require message signing when authenticating with a guest account:
 *   Cannot require message signing when authenticating with a guest account
 * ```
 *
 * 一串 Java 类名，还把同一句话重复了两遍。用户既不知道该改哪里，也不知道
 * 这跟"用户名没填"有关。
 *
 * ## 两条规则
 *  1. **认识的异常给可操作的话**（下面 `when` 里的分支）；
 *  2. **不认识的也要去掉包名**：`SMBRuntimeException` 这类名字对用户无意义，
 *     而 `simpleName` + 原始 message 至少能让人去搜。包名一律不进 UI。
 */
internal fun Throwable.readableSmbReason(): String {
    val simple = javaClass.simpleName
    // 走 humanSmbMessage：它负责"切断 jcifs 的协议转储 + 单行 + 限长"。
    // 这里原来只做 `substringBefore('\n')`，而 jcifs 的转储里**没有换行**，
    // 于是整段 500+ 字符照样进了界面（M5.1 真机实测）。
    val detail = humanSmbMessage()

    return when {
        // 匿名/来宾会话不允许要求签名（SMB2 规定）。正常情况下 [SmbConnector] 已经
        // 自动降级掉了，走到这里说明是别的路径构造的会话 —— 仍然给一句能照做的话，
        // 而不是让用户去猜 Java 类名。
        this is SMB2GuestSigningRequiredException ->
            "匿名登录不能要求 SMB 签名。请在「高级选项」里关掉「启用 SMB 签名」，或填写用户名和密码"

        // smbj 把"配置非法"表达成裸的运行时异常（例如 SMB3 下关闭签名）。
        // 这类是**我们的配置**与协议冲突，不是网络问题。
        this is SMBRuntimeException ->
            detail.ifBlank { "SMB 会话建立失败" }

        // 兜底：原始 message 里常常已经带了类名（smbj 有些异常把类名写进 message），
        // 那就不要重复第二遍。
        detail.isBlank() -> "SMB 连接失败（${simple}）"
        detail.contains(simple) -> detail
        else -> "$detail（$simple）"
    }
}
