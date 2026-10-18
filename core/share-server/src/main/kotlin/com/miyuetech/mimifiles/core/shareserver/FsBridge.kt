package com.miyuetech.mimifiles.core.shareserver

import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.model.has
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 服务器所需的"文件视图"。
 *
 * ## 为什么要有这一层
 * 方案 §10.3 里那句"HTTP 与 FTP 两个服务端共用同一个 [FsBridge]，
 * 因此共享根是本机还是 SMB 对上层完全透明"，是整个无线分享模块**零重复代码**的关键：
 *
 *  - NanoHTTPD 的回调收到的是"要下载 `/f/a/b.jpg` 的第 1024~2047 字节"；
 *  - Apache FtpServer 的 `FtpFile.createInputStream()` 收到的是"要读这个文件"。
 *
 * 两者的形状完全不同，但落到下面都是同一件事：**对某个 [FsPath] 做 openRead / list / write**。
 * 把这件事抽出来之后，两个服务端各自只剩协议解析与 HTTP/FTP 语义，一行文件系统代码都没有。
 *
 * 也正因为共用，"共享根是本机还是 SMB"不需要任何分支——`local://` 与 `smb://conn-1/share`
 * 在 [FileSystemRegistry] 里都是 `FileSystem`，[FileSystemBridge] 走的是同一份代码。
 *
 * ## 契约
 * 全部方法 `suspend`，失败抛 `FsException` 子类（[com.miyuetech.mimifiles.core.fs.FsException]）。
 * 服务端负责把异常翻译成协议层的东西（HTTP 状态码 / FTP 550）。
 */
interface FsBridge {

    suspend fun list(dir: FsPath): List<FsEntry>

    suspend fun stat(path: FsPath): FsEntry

    /** 随机读句柄。Range 请求（视频拖动进度条）直接建立在它上面。 */
    suspend fun openRead(path: FsPath): SeekableSource

    suspend fun openWrite(path: FsPath, append: Boolean, expectedSize: Long): WriteSink

    suspend fun mkdir(path: FsPath)

    suspend fun delete(path: FsPath, recursive: Boolean)

    suspend fun rename(path: FsPath, newName: String): FsPath

    /**
     * 同后端内移动到目标目录（保留原名）。
     *
     * 不需要 [com.miyuetech.mimifiles.core.fs.FsOps] 的跨后端中转：一次分享会话的
     * 根只属于**一个**后端，所以 FTP 的 `RNFR/RNTO` 跨目录改名一定落在同后端内。
     */
    suspend fun move(src: FsPath, destDir: FsPath): FsPath

    /** 该路径是否真的可写（后端能力位 + 条目自身的只读标记）。只读模式下由 [ShareScope] 统一拦。 */
    suspend fun isWritable(path: FsPath): Boolean
}

/**
 * [FsBridge] 的默认实现：直接转发给 [FileSystemRegistry]。
 *
 * ## 并发闸门（方案 §10.5「共享 SMB 目录为根」）
 * 共享根是网络存储时，**并发必须是受控的**：FTP 的每个会话是独立线程、
 * HTTP 的每个请求也是独立线程，而一台 PC 上的 FTP 客户端默认就开 2~4 条连接，
 * 加上资源管理器的预览、缩略图请求，很容易同时压出十几个 `list()`。
 * 对 SMB/WebDAV 而言这是灾难（[com.miyuetech.mimifiles.core.network.NetworkDispatchers]
 * 的注释里解释了原因）。
 *
 * 所以这里用一个信号量把并发钉住：本机 8（纯 IO，宽一点无妨）、
 * 网络存储 2（与浏览页的限并发同量级）。**这是"让慢变得可预测"而不是"变快"**——
 * 与其让十几个请求互相拖慢到全部超时，不如串成两路稳定推进。
 */
class FileSystemBridge(
    private val registry: FileSystemRegistry,
    /**
     * 并发上限。默认按共享根的 scheme 推断：
     * `local` / `saf` → [LOCAL_PARALLELISM]；其余（`smb` / `webdav`）→ [NETWORK_PARALLELISM]。
     */
    parallelism: Int = -1,
) : FsBridge {

    /**
     * 实际生效的并发上限（供面板/日志展示"建议共享本机目录"的判断依据）。
     *
     * ⚠️ 声明顺序有意在 [gate] **之前**：Kotlin 的初始化按声明顺序执行，
     * 而 `gate` 的初始值要读 `parallelism`；反过来的话会读到尚未赋值的 0，
     * 信号量许可数为 0，**所有**文件操作都会永久挂起。
     */
    var parallelism: Int = if (parallelism > 0) parallelism else DEFAULT_PARALLELISM
        private set

    private var gate: Semaphore = Semaphore(permits = parallelism.coerceAtLeast(1))

    /** 调整并发上限。共享根从本机切到网络存储时由 [ShareEngine] 调用。 */
    fun setParallelism(value: Int) {
        val v = value.coerceAtLeast(1)
        if (v == parallelism) return
        parallelism = v
        gate = Semaphore(permits = v)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = gate.withPermit { block() }

    override suspend fun list(dir: FsPath): List<FsEntry> = guarded { registry.resolve(dir).list(dir) }

    override suspend fun stat(path: FsPath): FsEntry = guarded { registry.resolve(path).stat(path) }

    override suspend fun openRead(path: FsPath): SeekableSource = guarded { registry.resolve(path).openRead(path) }

    override suspend fun openWrite(
        path: FsPath,
        append: Boolean,
        expectedSize: Long,
    ): WriteSink = guarded { registry.resolve(path).openWrite(path, append, expectedSize) }

    override suspend fun mkdir(path: FsPath) = guarded { registry.resolve(path).mkdir(path) }

    override suspend fun delete(path: FsPath, recursive: Boolean) =
        guarded { registry.resolve(path).delete(path, recursive) }

    override suspend fun rename(path: FsPath, newName: String): FsPath =
        guarded { registry.resolve(path).rename(path, newName) }

    override suspend fun move(src: FsPath, destDir: FsPath): FsPath = guarded {
        val from = registry.resolve(src)
        val to = registry.resolve(destDir)
        // 一次分享会话的根只属于一个后端，跨后端不可能出现；真出现了说明调用方
        // 拼错了路径，此时**报错好过静默中转**（后者会在大文件上悄悄跑很久）。
        check(from === to) { "share scope must stay within one backend: ${src.scheme} -> ${destDir.scheme}" }
        from.move(src, destDir)
    }

    override suspend fun isWritable(path: FsPath): Boolean = guarded {
        val fs = registry.resolve(path)
        if (!fs.capabilities.has(FsCapability.WRITE)) return@guarded false
        // 能力位是**后端级**的；某个具体条目仍可能是只读的（SAF 授权只读树、
        // 文件系统上的只读位）。stat 一次拿到准确答案，成本是热路径上多一次元数据读——
        // 而"上传前先问一次"本来就该问准。
        runCatching { fs.stat(path).isReadOnly }.getOrDefault(false).not()
    }

    companion object {

        /** 本机 / SAF：纯本地 IO，闸门只是防"客户端一次开 20 条连接"这种蛮力。 */
        const val LOCAL_PARALLELISM = 8

        /** 网络存储：与浏览页同一量级。 */
        const val NETWORK_PARALLELISM = 2

        private const val DEFAULT_PARALLELISM = 8

        /** 按共享根推断并发上限。 */
        fun parallelismFor(root: FsPath): Int = when (root.scheme) {
            "local", "saf" -> LOCAL_PARALLELISM
            else -> NETWORK_PARALLELISM
        }
    }
}
