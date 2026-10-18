package com.miyuetech.mimifiles.core.model

enum class FsSortField { NAME, SIZE, MODIFIED, TYPE }

/**
 * 排序规格。持久化时只存 [field] 与 [ascending] 两个值即可无损还原。
 *
 * [directoriesFirst] 默认 `true`：资源管理器的一致预期是"目录永远在上"。
 */
data class FsSort(
    val field: FsSortField = FsSortField.NAME,
    val ascending: Boolean = true,
    val directoriesFirst: Boolean = true,
) {
    /** 点击已经排序的列 → 翻转方向；点击新列 → 切列并回到升序。 */
    fun toggled(next: FsSortField): FsSort =
        if (next == field) copy(ascending = !ascending) else copy(field = next, ascending = true)

    companion object {
        val DEFAULT = FsSort()
    }
}

/**
 * 应用排序。
 *
 * 命名刻意避开 stdlib 的 `sortedBy` / `sortedWith` —— 同名扩展会造成调用点歧义，
 * 那种 bug 只在特定参数下才浮现，不值得。
 */
fun List<FsEntry>.sortedBySpec(spec: FsSort): List<FsEntry> {
    // 全部用显式 `Comparator<FsEntry>` 而非 compareBy 高阶重载：
    // 后者在多分支 when 里无法从返回值反向推断 T，会直接编译失败。
    val primary: Comparator<FsEntry> = when (spec.field) {
        FsSortField.NAME -> NAME_COMPARATOR
        FsSortField.SIZE -> Comparator { a, b -> compareValues(a.size, b.size) }
        FsSortField.MODIFIED -> Comparator { a, b -> compareValues(a.lastModified, b.lastModified) }
        FsSortField.TYPE -> Comparator { a, b ->
            val byExt = a.extension.compareTo(b.extension, ignoreCase = true)
            if (byExt != 0) byExt else a.name.compareTo(b.name, ignoreCase = true)
        }
    }

    var comparator: Comparator<FsEntry> = if (spec.ascending) primary else primary.reversed()

    // 大小未知的条目（SAF、SMB 上很常见）**无论升序还是降序都要排在最后**。
    // 如果直接把它当作 -1 参与比较，降序时它会霸占列表最顶端——恰好是最不该出现的位置。
    if (spec.field == FsSortField.SIZE) {
        comparator = KNOWN_SIZE_FIRST.then(comparator)
    }

    // 「目录在前」是最强的用户预期，放在最外层。
    if (spec.directoriesFirst) {
        comparator = DIRECTORY_FIRST.then(comparator)
    }

    // 名称兜底：保证结果**确定**。少了它，两个大小相同的文件谁在前取决于
    // 底层遍历顺序（SMB 上就是服务端返回顺序），每次刷新列表都可能跳一下。
    return sortedWith(comparator.then(NAME_COMPARATOR))
}

private val NAME_COMPARATOR: Comparator<FsEntry> =
    Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }

private val DIRECTORY_FIRST: Comparator<FsEntry> =
    Comparator { a, b ->
        if (a.isDirectory == b.isDirectory) 0 else if (a.isDirectory) -1 else 1
    }

private val KNOWN_SIZE_FIRST: Comparator<FsEntry> =
    Comparator { a, b ->
        compareValues(if (a.size < 0) 1 else 0, if (b.size < 0) 1 else 0)
    }
