package com.miyuetech.mimifiles.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * 网络 IO 的调度器集合。
 *
 * ## 为什么不能直接用 `Dispatchers.IO`
 * `Dispatchers.IO` 默认 64 并发（`kotlinx.coroutines.io.parallelism`）。这个数字是为
 * "大量短小的阻塞 IO"设计的，用在**网络存储**上是错的：
 *
 *  - 一次 `list()` 在 NAS 上要几百毫秒到几秒。64 个并发意味着用户快速点几下目录、
 *    再来一次多选复制，就能同时压出几十个 SMB 请求。SMB 服务端（尤其群晖这种
 *    单连接串行化的实现）会直接排队甚至把连接踢掉；
 *  - WebDAV 更脆：多数服务端对同源并发有硬限制，撞上就是 429/503。
 *
 * 所以网络后端一律跑在 [io]（限并发 4）上。**限并发不是保守，而是让"慢"变得可预测**：
 * 与其让 40 个请求互相拖慢、超时后各自重试放大成雪崩，不如串成 4 路稳定推进。
 *
 * ## 为什么不是"每连接一个池"
 * 连接级的并发上限（如 SMB 每 profile 最多 2 路）用 [ParallelGate] 表达，
 * 因为那是**语义约束**（服务端能承受几条会话），与线程调度是两件事。混在一起写，
 * 最后会变成"为了限 SMB 的并发，把本机复制也拖慢了"。
 */
object NetworkDispatchers {

    /**
     * 全局网络并发上限。
     *
     * 取值理由：4 = 2 路元数据（列目录/stat）+ 2 路数据（上传/下载）互不饿死。
     * 超过 4 对吞吐没有可测收益，对服务端的压力却是线性增长。
     */
    const val DEFAULT_PARALLELISM = 4

    @OptIn(ExperimentalCoroutinesApi::class)
    val io: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(DEFAULT_PARALLELISM)

    /** 独立的单线程调度器：给"必须串行"的连接生命周期操作用（建连、销毁会话）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val serial: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}
