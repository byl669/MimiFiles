package com.miyuetech.mimifiles.feature.share

import com.miyuetech.mimifiles.core.shareserver.ServerType
import com.miyuetech.mimifiles.core.shareserver.ShareRequest

/**
 * 待分享内容的会话记忆（方案 §10.2 的 `ShareSessionHolder`）。
 *
 * ## 它只管一件事：**面板重开时以最新的一批文件替换列表**
 * 方案 S-7 的完整语义有两半：
 *  - "面板关闭 5 分钟后失效" → 那是一个**时间**问题，归 [com.miyuetech.mimifiles.core.shareserver.ShareEngine]
 *    管（它在进程级持有唯一的定时器，Activity 重建与进程回收都不会丢）；
 *  - "重开以最新文件替换" → 那是一个**内容**问题，归这里管。
 *
 * 把两半分开不是洁癖：时效必须活过 Activity（否则转屏就重置倒计时，
 * 用户可以靠一直转屏**无限延长**一个本该过期的分享），而"最新的一批文件"
 * 天然来自界面。混在一个类里，两者都会沾上对方错误的生命周期。
 *
 * ## 与"正在运行的服务端"的关系
 * 如果服务端正在跑，那么**事实来源是引擎里的 `activeRequest`**，不是这里——
 * 面板必须显示"真正被分享的那个路径"，否则用户改了路径却没重启服务端时，
 * 界面会显示新路径而 PC 上看到的还是旧内容，这种不一致极难自查。
 * 面板的取值顺序见 [SharePanelActivity.currentRequest]。
 */
object ShareSessionHolder {

    private var pending: ShareRequest? = null

    private var lastType: ServerType = ServerType.HTTP

    /** 记住本次进入面板时用户想分享的东西。 */
    fun remember(request: ShareRequest, type: ServerType) {
        pending = request
        lastType = type
    }

    /** 上次待分享的请求；面板被"重新打开"（而不是带了新的选中项）时用它。 */
    fun pending(): ShareRequest? = pending

    /** 上次用过的 Tab。切回来时保持用户的选择，不要每次都弹回 HTTP。 */
    fun lastType(): ServerType = lastType

    fun rememberType(type: ServerType) {
        lastType = type
    }

    /** 服务端已停止且会话结束 —— 清单失效（下一次进入必须给新的内容）。 */
    fun clear() {
        pending = null
    }
}
