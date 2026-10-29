package com.miyuetech.mimifiles.feature.player

import com.miyuetech.mimifiles.core.common.MimeTypes

/**
 * 「删掉这一部之后接着播谁」—— 审片删除的唯一一处决策逻辑（M7.24）。
 *
 * ## 为什么单独抽出来
 * 这段判断有三条容易写错的分支，而且**每一条都不可撤销**（删错了文件就没了）：
 *  1. 删完之后，原来的 `index + 1` 已经**左移一格**，返回值必须减一；
 *  2. 同级列表里混着图片（`previewArgsFor` 把整个目录的媒体都带上了），
 *     不按 mime 过滤就会"下一个"跳到一张照片上，播放器直接报错；
 *  3. 后面没有视频了要返回 `-1`，由调用方退出页面 —— **不能**倒退回去播上一个，
 *     那会让用户在"删最后一部"之后莫名其妙回到已经看过的那部。
 *
 * 抽成纯函数后这三条都能用单测钉死（`DeleteAdvanceTest`），
 * 不必为了验证它们去搭一台设备、造一个目录。
 */
internal object DeleteAdvance {

    /**
     * @param names 同级列表里的文件名，顺序与列表一致，**包含**被删掉的那一个。
     * @param index 被删掉的那一个在 [names] 里的下标。
     * @return 删除之后应该接着播的下标（基于**删除后的新列表**）；`-1` = 后面没有能播的了。
     */
    fun nextAfterDelete(names: List<String>, index: Int): Int {
        if (index !in names.indices) return -1
        // 只往后找：用户的心智是"删掉、看下一部"，不是"删掉、回头"。
        for (j in index + 1 until names.size) {
            if (MimeTypes.isPlayableMedia(MimeTypes.of(names[j]))) {
                // 左移一格：删掉 names[index] 之后，原来的 names[j] 落在 j - 1。
                return j - 1
            }
        }
        return -1
    }
}
