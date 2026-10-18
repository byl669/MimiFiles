package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 一个存储后端 —— **M1 冻结契约**。
 *
 * ## 设计要点
 * - **所有方法都是 suspend**：实现方自行决定 dispatcher（本机走 `Dispatchers.IO`，
 *   SMB 走受限并发池）。调用方不需要知道、也不该猜哪个后端会阻塞。
 * - **错误用异常表达**，且必须落在 [FsException] 体系内（见该文件对 UI 话术的说明）。
 * - **[capabilities] 必须如实申报**：UI 靠它灰置菜单，跨后端复制靠它选策略。
 *   申报了却做不到，比不申报更糟。
 *
 * ## 实现者清单
 * | scheme | 模块 | 状态 |
 * |---|---|---|
 * | `local` | `:core:fs-local` | M1 |
 * | `saf`   | `:core:fs-local` | M1 |
 * | `smb`   | `:core:fs-smb`   | M2 |
 * | `webdav`| `:core:fs-webdav`| M2 |
 *
 * 只需要读能力（浏览 + 打开）的后端，可以不实现写方法——基类默认抛
 * [FsNotSupportedException]，且 [capabilities] 里不申报对应位即可。
 */
interface FileSystem {

    /** 与 [FsPath.scheme] 一一对应。 */
    val scheme: String

    /** 展示名，用于错误提示与日志（如「本机存储」「NAS-01」）。 */
    val displayName: String

    /** 静态能力位，见 [FsCapability]。 */
    val capabilities: Int

    /**
     * 可浏览的「存储」入口。
     *
     * 本机存储返回内部存储 / SD 卡；远程后端返回挂载点（共享列表或根目录）。
     * 这是侧栏与导航树的数据源。
     */
    suspend fun roots(): List<FsEntry>

    /**
     * 列举 [dir] 下的条目。
     *
     * 不保证排序（排序由上层按用户偏好做，见 `List<FsEntry>.sortedBySpec`），
     * 也不做分页（本机/局域网的目录规模不足以摊平分页的复杂度）。
     */
    suspend fun list(dir: FsPath): List<FsEntry>

    suspend fun stat(path: FsPath): FsEntry

    suspend fun exists(path: FsPath): Boolean = try {
        stat(path)
        true
    } catch (_: FsNotFoundException) {
        false
    }

    /** 打开随机读句柄（视频 seek 的前提）。不支持随机访问的后端也必须返回可用的 [SeekableSource]。 */
    suspend fun openRead(path: FsPath): SeekableSource

    /**
     * 打开写入句柄。
     *
     * @param append 追加模式；不支持的实现应抛 [FsNotSupportedException]
     * @param expectedSize 已知的目标大小（用于预分配 / 上传时声明 Content-Length），未知传 `-1`
     */
    suspend fun openWrite(path: FsPath, append: Boolean = false, expectedSize: Long = -1L): WriteSink {
        throw FsNotSupportedException(scheme, "openWrite")
    }

    suspend fun mkdir(path: FsPath) {
        throw FsNotSupportedException(scheme, "mkdir")
    }

    suspend fun delete(path: FsPath, recursive: Boolean = false) {
        throw FsNotSupportedException(scheme, "delete")
    }

    suspend fun rename(path: FsPath, newName: String): FsPath {
        throw FsNotSupportedException(scheme, "rename")
    }

    /**
     * 同后端内移动。
     *
     * 跨后端移动**不要**调这里——由 [FsOps] 用「复制 + 删除」中转。
     */
    suspend fun move(src: FsPath, destDir: FsPath): FsPath {
        throw FsNotSupportedException(scheme, "move")
    }

    /** 同后端内复制。跨后端同样交给 [FsOps]。 */
    suspend fun copy(src: FsPath, destDir: FsPath): FsPath {
        throw FsNotSupportedException(scheme, "copy")
    }
}
