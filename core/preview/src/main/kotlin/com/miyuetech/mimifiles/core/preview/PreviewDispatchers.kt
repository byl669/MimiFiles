package com.miyuetech.mimifiles.core.preview

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * 预览与解码的调度器。
 *
 * ## 为什么把"读"和"解"分成两个池
 * 缩略图与大图的真实开销是两段性质完全不同的工作：
 *  - **读**（网络取字节、磁盘读）：阻塞、等待占比高，并发有帮助；
 *  - **解**（`BitmapFactory` / `BitmapRegionDecoder` / `MediaMetadataRetriever`）：
 *    吃 CPU 与内存，并发**没有**帮助——4 路同时解一张 8000×6000 的照片，
 *    峰值内存是 4 张全尺寸位图，OOM 概率远高于总耗时收益。
 *
 * 所以解码池只给 2 路，且**小于**读取池：让"排队等解码"成为唯一的瓶颈，
 * 内存峰值就只由解码池大小决定（这是"1 GB 文件预览不 OOM"的可控性来源）。
 */
object PreviewDispatchers {

    /** 取字节：网络后端 / 磁盘。与 `NetworkDispatchers.io` 同量级，但独立排队。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** 解码：CPU 与内存敏感，上限 2。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val decode: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /** 编码缩略图落盘：单线程即可，避免同时写多份文件把 IO 打满。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val disk: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}
