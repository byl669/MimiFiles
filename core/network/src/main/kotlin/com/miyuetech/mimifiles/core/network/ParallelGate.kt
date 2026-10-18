package com.miyuetech.mimifiles.core.network

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 每个连接（或每类资源）的并发闸门。
 *
 * 与 [NetworkDispatchers.io] 的分工：那个管"整个应用压给网络多少条路"，
 * 这个管"某一条连接自己最多同时跑几件事"。
 *
 * ## 典型用法
 * SMB 的一个 `Connection` 在 smbj 里不是线程安全的，同一个树连接上并发发多个
 * 请求会互相踩。方案第 5.2 节定为每 profile 一个 `Connection`、并发上限 2：
 *
 * ```
 * private val gate = ParallelGate(2)
 * suspend fun list(dir: FsPath) = gate.withPermit { ... }
 * ```
 *
 * 闸门用 `Semaphore` 而不是自己写计数器：`withPermit` 在协程被取消时会**归还许可**，
 * 手写实现最容易出的 bug 就是取消路径漏还导致闸门永久卡死。
 */
class ParallelGate(permits: Int) {

    init {
        require(permits > 0) { "permits must be > 0" }
    }

    private val semaphore = Semaphore(permits, acquiredPermits = 0)

    /** 剩余可用许可（仅用于日志与排障，不要用它做逻辑判断——读到的值随时会过期）。 */
    val available: Int get() = semaphore.availablePermits

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }
}
