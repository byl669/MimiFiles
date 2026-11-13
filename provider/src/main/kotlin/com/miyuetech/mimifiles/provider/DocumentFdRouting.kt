package com.miyuetech.mimifiles.provider

import com.miyuetech.mimifiles.core.model.FsPath
import java.io.File

/**
 * 「这个 documentId 能不能直接给真实文件 fd」的判据（M7.27）。
 *
 * 抽成纯 Kotlin 对象有两个理由：
 *  1. `MimiDocumentsProvider` 继承 Android 的 DocumentsProvider，在 JVM 单测里
 *     连类加载都做不到；而这条分流规则恰恰是最容易被改错的一行。
 *  2. 它只依赖 `FsPath` 与 `java.io.File`，不碰 Binder / StorageManager，
 *     所以能像 `SystemShare.mimeOf()` 那样在 JVM 单测里钉死。
 *
 * ## 为什么 local 必须走真实 fd
 * `StorageManager.openProxyFileDescriptor()` 给的是 **FUSE 文件**：每次内核 read
 * 都要跨进程回调一次，单次上限受 `max_read` 约束（常见 128 KiB），7 GB 就是
 * 数万次 Binder 往返；更关键的是 FUSE fd 不支持 `sendfile` / `transferTo` 的
 * 零拷贝路径，互传、网盘类接收方会一直停在「准备中」。
 * 本机文件本来就在磁盘上，没有任何理由绕这一圈。
 *
 * 其余 scheme（smb / webdav / saf）没有本地文件，只能继续走代理 fd 或管道。
 */
internal object DocumentFdRouting {

    const val LOCAL_SCHEME = "local"

    fun isLocal(path: FsPath): Boolean = path.scheme == LOCAL_SCHEME

    /**
     * `File(path.path)` 与本机后端 `LocalPaths.toFile()` 的定义（`File(path)`）字面一致，
     * 这里刻意不为了一行工具函数让 provider 依赖具体后端模块；路径契约由
     * `DocumentFdRoutingTest.本机路径契约是空authority加绝对路径` 钉住。
     *
     * @return 可直接 `ParcelFileDescriptor.open()` 的文件；不是本机路径、
     *         或本机文件不存在 / 是目录时返回 null。
     */
    fun directFile(path: FsPath): File? =
        if (isLocal(path)) File(path.path).takeIf { it.isFile } else null
}
