package com.miyuetech.mimifiles.core.preview

import android.content.Context
import android.content.Intent
import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 预览页的入参 —— **放在 `:core:preview` 而不是某个 feature 模块**。
 *
 * 理由：`feature:preview`（图片/文本/PDF/音频）与 `feature:player`（视频）都要读它，
 * 而两者是**平级**模块、不互相依赖。把这个契约放在共同的下层，就不需要
 * "player 依赖 preview" 这种为了传参而生的横向依赖。
 *
 * ## 为什么带"同级文件列表"而不是只带一个路径
 * 图片/视频预览必然要左右滑动看同目录的下一张，而"下一张是谁"取决于**浏览页当时的
 * 排序与过滤**（是否显示隐藏文件、按名称还是按时间排）。让预览页自己列目录再复刻
 * 一遍排序规则，等于把同一套逻辑实现两遍——两处不一致的表现是"列表里的顺序和
 * 滑动顺序不一样"，而且很难被归因。
 *
 * ## ⚠️ 必须截断：Intent extras 有 1 MB 的硬上限
 * `TransactionTooLargeException` 不是理论风险：一个几千张照片的目录，路径平均 60 字符，
 * 直接塞进去就是几百 KB，加上 Binder 事务开销很容易触顶——而且它**只在真机上炸**，
 * 模拟器里目录小的时候完全看不出来。
 *
 * 处理方式：超过 [MAX_SIBLINGS] 时只取以当前项为中心的窗口。用户滑到窗口边缘会停住，
 * 这是**有意的降级**：能滑两千张与"直接崩溃"，前者显然是更好的选择。
 */
data class PreviewArgs(
    val paths: List<FsPath>,
    val index: Int,
    /** 可选的"只播文件里的某一段"（动态照片内嵌视频）：起点偏移。 */
    val sliceOffset: Long = -1L,
    /** 切片长度；≤ 0 表示"从偏移到文件末尾"。 */
    val sliceLength: Long = -1L,
) {

    val current: FsPath get() = paths[index.coerceIn(0, paths.lastIndex)]

    val count: Int get() = paths.size

    val hasSlice: Boolean get() = sliceOffset >= 0 && sliceLength > 0

    companion object {

        /** 同级列表上限（2000 条 × 平均 60 字符 ≈ 240 KB，给 Binder 留足余量）。 */
        const val MAX_SIBLINGS = 2000

        const val EXTRA_PATHS = "preview.paths"
        const val EXTRA_INDEX = "preview.index"
        const val EXTRA_SLICE_OFFSET = "preview.slice.offset"
        const val EXTRA_SLICE_LENGTH = "preview.slice.length"

        /** 截断到上限；保证 [index] 仍指向原来那一项。 */
        fun of(paths: List<FsPath>, index: Int): PreviewArgs {
            if (paths.isEmpty()) return PreviewArgs(emptyList(), 0)
            val safeIndex = index.coerceIn(0, paths.lastIndex)
            if (paths.size <= MAX_SIBLINGS) return PreviewArgs(paths, safeIndex)
            val half = MAX_SIBLINGS / 2
            val start = (safeIndex - half).coerceIn(0, paths.size - MAX_SIBLINGS)
            return PreviewArgs(paths.subList(start, start + MAX_SIBLINGS), safeIndex - start)
        }

        fun single(path: FsPath, sliceOffset: Long = -1L, sliceLength: Long = -1L): PreviewArgs =
            PreviewArgs(listOf(path), 0, sliceOffset, sliceLength)

        fun putExtras(intent: Intent, args: PreviewArgs): Intent = intent.apply {
            putExtra(EXTRA_PATHS, args.paths.map { it.toString() }.toTypedArray())
            putExtra(EXTRA_INDEX, args.index)
            putExtra(EXTRA_SLICE_OFFSET, args.sliceOffset)
            putExtra(EXTRA_SLICE_LENGTH, args.sliceLength)
        }

        fun intent(context: Context, args: PreviewArgs, target: Class<*>): Intent =
            putExtras(Intent(context, target), args)

        /** 解析失败返回 `null`（调用方给一句提示后收场，而不是崩）。 */
        fun parse(intent: Intent?): PreviewArgs? {
            val raw = intent?.getStringArrayExtra(EXTRA_PATHS) ?: return null
            if (raw.isEmpty()) return null
            val paths = raw.mapNotNull { FsPath.parse(it) }
            if (paths.isEmpty()) return null
            return PreviewArgs(
                paths = paths,
                index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, paths.lastIndex),
                sliceOffset = intent.getLongExtra(EXTRA_SLICE_OFFSET, -1L),
                sliceLength = intent.getLongExtra(EXTRA_SLICE_LENGTH, -1L),
            )
        }
    }
}
