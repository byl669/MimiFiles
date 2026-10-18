package com.miyuetech.mimifiles.core.fs

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级的后端注册表。
 *
 * ## 为什么是全局单例
 * `FileSystemRegistry` 本身是个普通对象、处处可 new，但**应用里必须只有一张表**：
 * 浏览页用 `local` 方案解析出的实例，和复制粘贴、无线分享、SAF `DocumentsProvider`
 * 拿到的是不是同一个连接/会话，直接决定"NAS 会不会被连三次""凭据缓存命不命中"。
 * 与其让每个调用点自己持有再想办法传递，不如在这里定死一个。
 *
 * 单测不受影响：`FileSystemRegistry` 的测试仍然自己 new 实例，不碰这里。
 *
 * ## 为什么安装要一次性
 * 各后端由**使用它的功能模块**自行注册（`:feature:browser` 注册本机，
 * `:feature:connection` 注册 SMB/WebDAV）。这些模块可能同时被拉起，
 * 而注册是幂等覆盖语义、重复注册无害；[installOnce] 只是为了让"建连接池 /
 * 读授权表"这类**有副作用**的初始化只跑一次，顺带避免每次进浏览页都读一遍
 * SAF 授权（那是 SharedPreferences IO，不该出现在启动路径上）。
 */
object FileSystems {

    val registry = FileSystemRegistry()

    private val installed = AtomicBoolean(false)

    /** 执行一次性的后端装配；重复调用直接返回 `false`。 */
    fun installOnce(block: () -> Unit): Boolean {
        if (!installed.compareAndSet(false, true)) return false
        block()
        return true
    }

    /** 仅测试用：把"已安装"标记清掉，让下一次 [installOnce] 能重放。 */
    fun resetForTest() {
        installed.set(false)
        registry.clear()
    }
}
