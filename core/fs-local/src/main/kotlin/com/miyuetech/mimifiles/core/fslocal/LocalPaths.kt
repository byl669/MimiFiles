package com.miyuetech.mimifiles.core.fslocal

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import java.io.File

/** 本机存储（`java.io.File` 后端）。 */
const val SCHEME_LOCAL = "local"

/** SAF 授权树后端。 */
const val SCHEME_SAF = "saf"

/**
 * `local` 后端的路径映射约定：**[FsPath.path] 就是绝对路径**。
 *
 * ```
 * local:///storage/emulated/0/DCIM/Camera  →  /storage/emulated/0/DCIM/Camera
 * local://                                 →  /             （文件系统根）
 * ```
 *
 * 之所以不做"卷 id + 相对路径"这类美化（像 `local://primary/DCIM`），是因为
 * 一出现映射就要在每处 IO 前反解、出错时又多一层"到底是哪一步丢了前缀"的排查成本；
 * 而绝对路径在日志里天然可读，也可以原样丢给 `DocumentsProvider`。
 */
fun FsPath.toFile(): File = File(path)

fun File.toLocalPath(): FsPath = FsPath.of(SCHEME_LOCAL, "", absolutePath)

/**
 * `File` → [FsEntry]，**一次 `stat(2)` 拿全属性**。
 *
 * ## 为什么不用 `java.io.File` 的 getter
 * `File` 的每个属性各是一次独立的系统调用：`isDirectory()`、`isFile()`、
 * `length()`、`lastModified()` 各一次。5000 个条目就是 20000 次 `stat`，
 * 在模拟器/冷 inode 缓存上实测要 1 秒出头——**首屏预算的 300 ms 就是被这里吃掉的**。
 * `android.system.Os.stat` 一次返回 `StructStat`（模式/大小/纳秒级时间戳都在里面），
 * 把每个条目的系统调用从 4 次降到 1 次。
 *
 * ## 时间戳为什么用 `st_mtim`
 * `st_mtime` 只有**秒**精度，同一秒内创建的多个文件会排不出先后——相册连拍、
 * 批量解压出来的目录都是这个情况。`st_mtim` 提供纳秒字段，API 21 起可用。
 *
 * ## 失败语义
 * 条目在遍历途中消失（临时目录、下载中的文件）时 `Os.stat` 抛 [android.system.ErrnoException]，
 * 由调用方[LocalFileSystem.list]跳过。这里不吞异常，是为了让 `stat()` 这类
 * "必须成功"的调用点也能复用同一个实现。
 */
internal fun File.toEntry(): FsEntry = toEntry(Os.stat(absolutePath))

private fun File.toEntry(stat: StructStat): FsEntry {
    val directory = OsConstants.S_ISDIR(stat.st_mode)
    return FsEntry(
        path = toLocalPath(),
        name = name,
        kind = when {
            directory -> FsKind.DIRECTORY
            OsConstants.S_ISREG(stat.st_mode) -> FsKind.FILE
            // 符号链接、设备节点、socket：如实标为 OTHER，而不是猜成文件
            else -> FsKind.OTHER
        },
        // 目录的大小是"目录项占用"，对用户毫无意义且各文件系统不同，一律置未知
        size = if (directory) FsEntry.SIZE_UNKNOWN else stat.st_size,
        lastModified = stat.st_mtim.tv_sec * 1000L + stat.st_mtim.tv_nsec / 1_000_000L,
        isHidden = name.startsWith('.'),
    )
}

/**
 * 转换成条目；条目已消失时返回 `null` 而不抛异常。
 *
 * 供 `list()` 使用——一个目录里某个文件恰好在遍历过程中被删，
 * 不该让整次列举失败。
 */
internal fun File.toEntryOrNull(): FsEntry? = try {
    toEntry()
} catch (_: android.system.ErrnoException) {
    null
}
