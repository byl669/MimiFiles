package com.miyuetech.mimifiles.core.ui

import com.miyuetech.mimifiles.core.common.Formatters
import com.miyuetech.mimifiles.core.model.FsEntry

/**
 * 列表上要显示的文案。
 *
 * 集中在一处而不是散在 Adapter 的 `bind()` 里：三种视图（列表/网格/详细信息）
 * 都显示同一份信息，只是排版不同；文案一旦分家，就会出现"网格里显示大小、
 * 列表里却只显示时间"这类不一致。
 */
object FileLabels {

    /** 详细信息的「类型」列。 */
    fun typeLabel(entry: FsEntry): String = when (FileTypeIcons.categoryOf(entry)) {
        FileCategory.FOLDER -> "文件夹"
        FileCategory.IMAGE -> "图片"
        FileCategory.VIDEO -> "视频"
        FileCategory.AUDIO -> "音频"
        FileCategory.PDF -> "PDF 文档"
        FileCategory.ARCHIVE -> "压缩包"
        FileCategory.APK -> "安装包"
        FileCategory.CODE -> "源代码"
        FileCategory.TEXT -> "文本"
        FileCategory.OTHER ->
            if (entry.extension.isEmpty()) "文件" else "${entry.extension.uppercase()} 文件"
    }

    /** 列表行的副标题：`2.4 MB · 10-13 18:22`。 */
    fun subtitle(entry: FsEntry): String {
        val sizeText = sizeText(entry)
        val timeText = Formatters.dateTime(entry.lastModified)
        return when {
            sizeText == null && timeText == "--" -> if (entry.isDirectory) "文件夹" else ""
            sizeText == null -> timeText
            timeText == "--" -> sizeText
            else -> "$sizeText · $timeText"
        }
    }

    /** 文件返回大小文本；目录返回 `null`（目录大小未知，显示它只会误导）。 */
    fun sizeText(entry: FsEntry): String? =
        if (entry.isDirectory) null else Formatters.size(entry.size)

    /** 详细信息「大小」列：目录留空。 */
    fun sizeColumn(entry: FsEntry): String = sizeText(entry).orEmpty()

    /** 详细信息「修改时间」列。 */
    fun timeColumn(entry: FsEntry): String = Formatters.fullDateTime(entry.lastModified)

    /** 状态栏摘要：`12 项（3 个文件夹，9 个文件）· 已选 2 项`。 */
    fun statusSummary(total: Int, directories: Int, selected: Int): String {
        val base = Formatters.itemSummary(total, directories)
        return if (selected > 0) "$base · 已选 $selected 项" else base
    }
}
