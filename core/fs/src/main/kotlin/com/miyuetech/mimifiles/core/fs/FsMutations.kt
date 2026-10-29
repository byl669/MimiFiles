package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 「某个目录的内容被改过了」的一次性标记 —— 只在本进程内流转，落不到磁盘上。
 *
 * ## 为什么不用 `setResult(RESULT_OK)` 通知浏览页刷新
 * 浏览页拉起预览页、预览页把视频**转手**给播放器之后自己 `finish()`（见
 * `PreviewActivity.launchPlayer`：不收手的话，从播放页返回会看到一个只剩海报的
 * 空预览页）。于是栈是 `Documents → Player`，而播放页的 `setResult` 只能回到
 * **已经销毁的** `PreviewActivity` 上，Android 直接丢弃 —— 浏览页永远收不到。
 *
 * 曾经试过让预览页用 `startActivityForResult` 转发结果，但那样预览页就不能在
 * 拉起播放器后立刻结束：用户每删完一个文件夹的片子返回列表，都会先闪一下海报页。
 * 审片是高频操作，闪一下的代价比这套标记大得多。
 *
 * ## 语义
 * 生产者（播放页删除成功）只调 [markChanged]，消费者（浏览页 `onResume`）只调
 * [consumeChanged]。消费即清除，所以：
 * - 连删三个片子，浏览页回到列表时也只刷新一次，不会刷三遍；
 * - 浏览页因为别的原因（权限设置、SAF 授权）恢复时，没有标记就不会白刷一遍
 *   NAS 目录 —— 远端列一次目录是几百毫秒的往返。
 *
 * ## 为什么要有 [MAX_DIRS] 这个上限
 * 消费者不一定在场：在搜索结果里点开别的目录里的片子、或者从通知/画中画回来，
 * 被改的目录可能压根不是浏览页当前所在的那个。那种标记没人认领，
 * 不设上限就是一条只增不减的路径泄漏。留着少量旧标记反而是对的 ——
 * 用户随后走回那个目录时，一次多余的刷新胜过看到三个已删的鬼影条目。
 */
object FsMutations {

    /** 最多记住多少个"被改过的目录"。超过就丢最老的。 */
    private const val MAX_DIRS = 16

    private val changed = object : LinkedHashSet<FsPath>() {
        override fun add(element: FsPath): Boolean {
            // 重新标记同一个目录时，把它挪到队尾（最近的优先保留）
            remove(element)
            val added = super.add(element)
            if (size > MAX_DIRS) {
                val oldest = firstOrNull()
                if (oldest != null) remove(oldest)
            }
            return added
        }
    }

    /** 记下 [dir] 的内容变了。传 `null`（例如路径没有父目录）是空操作。 */
    fun markChanged(dir: FsPath?) {
        if (dir == null) return
        synchronized(changed) { changed.add(dir) }
    }

    /**
     * 如果 [dir] 被标记过，清除标记并返回 `true`（调用方据此刷新）。
     *
     * 清除是**必须**的：留着会让下次恢复时再白刷一遍。
     */
    fun consumeChanged(dir: FsPath?): Boolean {
        if (dir == null) return false
        return synchronized(changed) { changed.remove(dir) }
    }
}
