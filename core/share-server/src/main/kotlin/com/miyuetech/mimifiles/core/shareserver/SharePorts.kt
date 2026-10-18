package com.miyuetech.mimifiles.core.shareserver

import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 空闲端口探测（方案 §10.5「端口被占用 → 自动向上探测空闲端口」）。
 *
 * ## 探测方式：真的去 bind 一次
 * `ServerSocket(0)` 让内核给我们一个空闲端口是最省事的做法，但它给的是**随机高位端口**
 * （32768+），用户设 2121 就会被无视，界面上的地址每次都变，排障无从下手。
 * 所以这里做的是"**从期望端口向上逐个试 bind**"：先尊重用户的输入，
 * 只在真的被占用时才退让，并把**实际端口**回给 UI。
 *
 * ## 为什么 bind 在 `0.0.0.0` 上试
 * 真正的监听可能只绑某一个网卡地址（见 [HttpShareServer] 的多实例设计），
 * 但"某个端口是否空闲"要看的是**全网卡**：如果只在 `192.168.2.5` 上试成功、
 * 而 `10.0.0.3` 上已被别的应用占用，后一个监听会 bind 失败。所以探测试全网卡。
 *
 * 这里有一个不可避免的 TOCTOU：探测与真正监听之间有一小段窗口，端口可能被抢走。
 * 两个选择——① 探测时留着 socket 不放，让真正的监听复用（做不到，监听要自己 bind）；
 * ② 接受窗口，并在监听失败时**把探测重做一遍**。选②：窗口是微秒级，
 * 而重试的成本只是再试下一个端口。
 */
object SharePorts {

    /**
     * 从 [preferred] 起向上找 [attempts] 个候选里的第一个空闲端口。
     *
     * @return 实际可用的端口；全部被占用时返回 `null`（由调用方翻成
     *         [ShareError.PortsExhausted]）
     */
    fun findFree(preferred: Int, attempts: Int = DEFAULT_ATTEMPTS): Int? {
        val start = preferred.coerceIn(1, 65535)
        for (offset in 0 until attempts) {
            val candidate = start + offset
            if (candidate > 65535) break
            if (isFreeOnAllInterfaces(candidate)) return candidate
        }
        return null
    }

    /**
     * 让内核挑一个空闲端口（用户把端口填成 0 时用）。
     *
     * 用 `ServerSocket(0)` 而不是"从 1024 开始找"：内核知道哪些端口在
     * `TIME_WAIT` 里、哪些被保留；自己从低位扫会撞上一堆内核根本不让 bind 的端口，
     * 表现为"扫了几百次才成功"，在慢设备上就是一次可感知的卡顿。
     */
    fun pickEphemeral(): Int = ServerSocket(0).use { it.localPort }

    /** 端口在**所有**网卡上是否都空闲。 */
    fun isFreeOnAllInterfaces(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            // 不设 reuseAddress：我们要的是"真的没人用"，而 SO_REUSEADDR 会让
            // `TIME_WAIT` 状态的端口也报成"可用"，于是 bind 成功、客户端却连不上。
            socket.bind(InetSocketAddress(port))
        }
        true
    } catch (_: BindException) {
        false
    } catch (_: SecurityException) {
        // 个别 ROM 对 1024 以下的端口直接抛 SecurityException；当作"不可用"。
        false
    } catch (_: Exception) {
        false
    }

    /** 默认向上探测多少个候选（32 个足够覆盖"2121 被占了"这类实际情况）。 */
    const val DEFAULT_ATTEMPTS = 32
}
