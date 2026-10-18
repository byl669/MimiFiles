package com.miyuetech.mimifiles.core.shareserver.ftp

import com.miyuetech.mimifiles.core.shareserver.RelPath
import com.miyuetech.mimifiles.core.shareserver.ShareScope
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import kotlinx.coroutines.runBlocking

/**
 * 每个会话一份的"当前目录"视图。
 *
 * ## 为什么 `cwd` 可以放心放在这里
 * [FileSystemFactory.createFileSystemView] 是**每个登录会话调用一次**的
 * （`DefaultFtpSession` 持有自己那份），所以把"当前目录"存成这个类的字段，
 * 天然就是会话级的——两个 PC 同时连进来各有各的 `PWD`，不会互相踩。
 * 这正是 ftplet 设计这套接口的意图，也是我不需要在别处维护一个
 * `sessionId -> cwd` 映射表的原因。
 *
 * ## 路径解析的唯一入口
 * FTP 客户端给的路径参数（`CWD`、`RETR`、`STOR`、`DELE`… 的实参）在这里被解析，
 * 且**只经过** [RelPath.normalize]：它负责折叠 `.`、消解 `..`、并在 `..` 想要
 * 跳出共享根时直接判非法。任何绕过它的路径拼接都是一次越狱。
 */
class ShareFileSystemView(
    private val scope: ShareScope,
    private val userName: String,
) : FileSystemView {

    private var cwd: String = RelPath.ROOT

    override fun getHomeDirectory(): FtpFile = ShareFtpFile(scope, RelPath.ROOT, userName)

    override fun getWorkingDirectory(): FtpFile = ShareFtpFile(scope, cwd, userName)

    override fun changeWorkingDirectory(dir: String): Boolean {
        val target = resolveArgument(dir) ?: return false
        val node = runCatching { runBlocking { scope.stat(target) } }.getOrNull() ?: return false
        if (!node.isDirectory) return false
        cwd = target
        return true
    }

    /**
     * 解析客户端给的路径参数，返回 `null` 表示非法或越狱。
     *
     * ⚠️ 注意"以 `/` 开头"的语义：对 FTP 而言 `/` 是**虚拟根**，也就是共享根，
     * 而不是 Android 文件系统的根。这个映射是在这里切掉的——[RelPath.normalize]
     * 收到的永远是去掉前导斜杠的**共享根相对路径**。
     */
    override fun getFile(file: String): FtpFile {
        val target = resolveArgument(file)
        return ShareFtpFile(scope, target, userName)
    }

    /** 支持 `REST`（断点续传/拖动播放）：底的 [com.miyuetech.mimifiles.core.fs.SeekableSource] 本来就支持随机读。 */
    override fun isRandomAccessible(): Boolean = true

    override fun dispose() {
        // 没有需要释放的资源：FsBridge 是进程级共享的，
        // 会话级的 SeekableSource 由各自的 RETR/STOR 流负责关闭
        // （见 ShareFtpFile 的 SourceStream/SinkStream）。
    }

    private fun resolveArgument(raw: String): String? {
        val trimmed = raw.trim()
        val combined = when {
            // 绝对路径：`/a/b` → 相对共享根
            trimmed.startsWith("/") -> trimmed.substring(1)
            trimmed.isEmpty() || trimmed == "." -> cwd
            cwd.isEmpty() -> trimmed
            else -> "$cwd/$trimmed"
        }
        // ⚠️ 反斜杠与 `..` 都在 normalize 里处理；这里只负责把"绝对/相对"归一。
        return RelPath.normalize(combined)
    }
}

/** 会话工厂。真正的状态在每个 [ShareFileSystemView] 里，这里只做创建。 */
class ShareFileSystemFactory(private val scope: ShareScope) : FileSystemFactory {

    override fun createFileSystemView(user: User): FileSystemView =
        ShareFileSystemView(scope, user?.name ?: "anonymous")
}
