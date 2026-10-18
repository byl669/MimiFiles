package com.miyuetech.mimifiles.core.ui

import android.graphics.Bitmap
import com.miyuetech.mimifiles.core.model.FsEntry

/**
 * 缩略图来源 —— 一个**只有一个方法**的接口，用来把"缩略图怎么来的"挡在 UI 层之外。
 *
 * ## 为什么不让 `:core:ui` 直接依赖 `:core:preview`
 * `:core:preview` 为了实现视频抽帧依赖了 Media3（`media3-datasource`）。
 * 若 `:core:ui` 依赖它，那么**每一个用到 UI 组件的模块**都会被拖进 Media3 的编译期
 * 与打包面——包括那些根本不播视频的模块。用这个接口把依赖方向倒过来：
 * UI 只声明"我需要一张缩略图"，由功能层（`:feature:browser`）注入真正会解码的实现。
 *
 * ## 契约
 *  - 必须在**任意线程**都能安全调用（实现方自己负责切线程）；
 *  - 返回 `null` 表示"没有缩略图可用"（不是错误），调用方应保留类型图标；
 *  - 允许被取消：调用方随时可能因为视图被回收而取消协程。
 */
fun interface ThumbnailProvider {

    /**
     * @param sizePx 请求的边长（像素）。实现方可据此选择更小的采样，省内存。
     */
    suspend fun load(entry: FsEntry, sizePx: Int): Bitmap?
}
