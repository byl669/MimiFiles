package com.miyuetech.mimifiles.core.fslocal

import android.content.Context
import android.os.Build
import android.os.Environment
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import java.io.File

/**
 * 枚举可浏览的存储卷。
 *
 * ## 卷的识别方式
 * 系统没有"列出所有存储卷"的公开 API。可靠办法是拿 `getExternalFilesDirs()`——
 * 它返回每个卷上的**应用私有目录**，形如：
 * ```
 * /storage/emulated/0/Android/data/<pkg>/files        ← 内部存储
 * /storage/1A2B-3C4D/Android/data/<pkg>/files         ← SD 卡
 * ```
 * 从路径里砍掉 `/Android/...` 之后剩下的就是卷根。
 *
 * 这条路子在 Android 11+ 依然可行：虽然 `/Android/data` 本身不再可读，
 * 但**卷根**在拿到全盘访问权限后照常可读——我们只是借它当路标。
 */
object LocalRoots {

    /** 内部存储卷根（老设备上这就是唯一可写的公共目录）。 */
    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    /**
     * 全部可访问的卷根，顺序稳定（内部存储永远排第一）。
     *
     * @param includeVolumes 是否包含 SD 卡等外部卷；降级到应用私有档时应传 `false`
     */
    fun volumeRoots(context: Context, includeVolumes: Boolean = true): List<File> {
        val roots = LinkedHashSet<File>()
        val primary = primaryRoot()
        if (primary != null) roots += primary

        if (includeVolumes) {
            val dirs = runCatching { context.getExternalFilesDirs(null) }.getOrNull()
            dirs?.forEach { dir ->
                if (dir != null) volumeRootOf(dir)?.let { roots += it }
            }
        }
        return roots.toList()
    }

    /** 从 `/storage/XXXX/Android/data/<pkg>/files` 反推 `/storage/XXXX`。 */
    internal fun volumeRootOf(appDir: File): File? {
        var current: File? = appDir
        while (current != null) {
            if (current.name.equals("Android", ignoreCase = true)) return current.parentFile
            current = current.parentFile
        }
        return null
    }

    /** 卷根 → 展示用的 [FsEntry]。 */
    fun rootEntries(
        context: Context,
        includeVolumes: Boolean = true,
        appPrivateDir: File? = null,
    ): List<FsEntry> {
        val entries = ArrayList<FsEntry>()
        val primary = primaryRoot()
        for (volume in volumeRoots(context, includeVolumes)) {
            val isPrimary = primary != null && volume.absolutePath == primary.absolutePath
            val label = if (isPrimary) "内部存储" else volume.name
            entries += FsEntry(
                path = volume.toLocalPath(),
                name = label,
                kind = FsKind.DIRECTORY,
                lastModified = volume.lastModified(),
            )
        }
        if (entries.isEmpty() && appPrivateDir != null) {
            // 完全无权访问公共存储时，至少让用户能进自己的目录；
            // 否则"本机"这一栏会是空的，看起来像应用坏了。
            entries += FsEntry(
                path = appPrivateDir.toLocalPath(),
                name = "本应用目录",
                kind = FsKind.DIRECTORY,
            )
        }
        return entries
    }

    /** 权限完全受限时的兜底根：应用私有目录。 */
    fun appPrivateRoot(context: Context): File = context.filesDir

    /** 供 UI 判断某条目是否是"卷根"（决定要不要显示容量信息）。 */
    fun isVolumeRoot(path: String): Boolean =
        path.matches(Regex("^/storage/[^/]+$")) ||
            path == Environment.getExternalStorageDirectory()?.absolutePath

    internal fun sdkInt(): Int = Build.VERSION.SDK_INT
}
