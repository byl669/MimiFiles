package com.miyuetech.mimifiles.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级协程作用域 —— 给"**页面已经关闭，但这件事必须做完**"的小任务用。
 *
 * ## 典型场景
 * 播放器退出时要写一次播放进度。若挂在 `lifecycleScope` 上，`onDestroy` 一执行
 * 协程就被取消，那条记录永远写不进去——而"离开时的位置"恰恰是**唯一值得保存的**那一个。
 *
 * ## 为什么不是 `GlobalScope`
 * `GlobalScope` 没有父级也没有监督，异常会直接打到默认处理器上；而且它无法被测试替换。
 * 这里用 `SupervisorJob`（一个子任务失败不影响其它）+ IO 调度，语义明确。
 *
 * ## ⚠️ 使用边界
 * 只允许"短小、幂等、失败可忽略"的收尾写入。**不要**把长任务（下载、传输）放进来：
 * 进程级作用域不会随页面结束而停止，长任务会变成后台耗电的幽灵。
 */
object AppScope {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
