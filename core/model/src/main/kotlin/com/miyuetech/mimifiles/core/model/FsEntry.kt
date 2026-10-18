package com.miyuetech.mimifiles.core.model

/** 条目类别。`OTHER` 用于符号链接、设备节点、Socket 等无法归入前两类的对象。 */
enum class FsKind { DIRECTORY, FILE, OTHER }

/**
 * 一次目录列举产出的条目。
 *
 * 设计取舍：**刻意不把「已安装应用」「相册聚合」这类虚拟条目放进来**——
 * 虚拟视图由上层组合，`FileSystem` 只负责如实反映一个真实后端。
 *
 * 未知元数据用哨兵值而不是 `null`：`size`/`lastModified` 在 SMB、SAF 上经常拿不到，
 * 用 `-1`/`0` 可以让排序与 UI 格式化保持无分支。
 */
data class FsEntry(
    val path: FsPath,
    val name: String = path.displayName,
    val kind: FsKind,
    /** 字节数；[SIZE_UNKNOWN] 表示后端未提供。目录恒为 [SIZE_UNKNOWN]。 */
    val size: Long = SIZE_UNKNOWN,
    /** epoch 毫秒；[TIME_UNKNOWN] 表示后端未提供。 */
    val lastModified: Long = TIME_UNKNOWN,
    /** 后端已知的 MIME；`null` 表示留给上层按扩展名推断（`:core:preview` 的活儿）。 */
    val mimeType: String? = null,
    val isHidden: Boolean = false,
    val isReadOnly: Boolean = false,
    /** 子项数量；[COUNT_UNKNOWN] 表示未统计（避免为列表页多付一次 IO）。 */
    val childCount: Int = COUNT_UNKNOWN,
) {
    val isDirectory: Boolean get() = kind == FsKind.DIRECTORY
    val isFile: Boolean get() = kind == FsKind.FILE

    val extension: String get() = extensionOf(name)

    /** 是否有可信的大小（用于决定是否显示"计算中"）。 */
    val hasKnownSize: Boolean get() = size >= 0

    companion object {
        const val SIZE_UNKNOWN = -1L
        const val TIME_UNKNOWN = 0L
        const val COUNT_UNKNOWN = -1

        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
        }
    }
}
