package com.miyuetech.mimifiles.core.shareserver

import com.miyuetech.mimifiles.core.network.LanAddress

/**
 * 一个可启停的分享服务端。
 *
 * 方案 §10.3 的统一接口。刻意保持**极窄**：只有启停与类型。之所以不在这里放
 * `state: StateFlow`（方案原稿有）：
 *
 * 面板同时只跑一个服务端（FTP 或 HTTP 二选一，与参考应用一致），
 * 状态放进每个实现里就变成"两份状态要同步"——切 Tab 时先看谁的 StateFlow？
 * 停止失败时又是谁负责把两份都置回 Stopped？统一由 [ShareEngine] 持有唯一状态，
 * 实现类退化成**纯执行器**（起监听、停监听），这一整类竞态就消失了。
 *
 * @see ShareEngine
 */
interface ShareServer {

    val type: ServerType

    val isRunning: Boolean

    /**
     * 一次性会话令牌（用于"扫码即用"，见 [ServerState.Running.ticket]）。
     *
     * 接口上给默认实现 `null`：FTP 不需要它（FTP 客户端有专门的用户名字段，
     * 二维码在那里也没有意义），只有 HTTP 会覆盖。
     */
    val ticket: String?
        get() = null

    /**
     * 启动监听。
     *
     * @param config 生效配置
     * @param scope 已就绪的文件视图（含安全边界与根级过滤）
     * @param port 已探测好的端口（连"被占用就向上找"这件事都由 [ShareEngine] 做完，
     *        实现类只负责**照着这个端口起监听**）
     * @param hosts 要绑定的本机地址。**每个地址一个监听**，不是绑 `0.0.0.0`——
     *        绑通配地址会让蜂窝/VPN 网卡也被监听，而"手机连着 WiFi 又开着数据"是常态。
     *        带 [LanAddress.interfaceName]，好让地址卡上能标出是 WiFi 还是热点。
     * @return 实际可用的地址（与 [hosts] 一一对应）
     */
    suspend fun start(
        config: ServerConfig,
        scope: ShareScope,
        port: Int,
        hosts: List<LanAddress>,
    ): List<ShareAddress>

    suspend fun stop()
}
