package com.miyuetech.mimifiles.core.ui

import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 列表项 = 条目 + 交互态。
 *
 * 把选中态**编进 item**而不是让 Adapter 去问外部的 `Set<FsPath>`，是为了让 DiffUtil
 * 能自动识别"只是选中变了"并只重绘那一行——否则每次勾选都得 `notifyDataSetChanged()`，
 * 在 5000 项的目录里就是肉眼可见的卡顿。
 */
data class FileRowItem(
    val entry: FsEntry,
    val selected: Boolean = false,
    /** 是否处于多选模式（决定勾选框显示与否，以及点击是"打开"还是"勾选"）。 */
    val selectionMode: Boolean = false,
    /** 位于剪切板中的条目：显示半透明，让用户知道"这东西一会儿会消失"。 */
    val pendingCut: Boolean = false,
) {
    val path: FsPath get() = entry.path
}
