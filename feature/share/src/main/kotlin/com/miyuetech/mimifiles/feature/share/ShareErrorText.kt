package com.miyuetech.mimifiles.feature.share

import android.content.Context
import com.miyuetech.mimifiles.core.shareserver.ShareError

/**
 * [ShareError] → 用户看得懂的一句话。
 *
 * ## 为什么集中在这里，而不是在 UI 里写 `when`
 * 方案 §10.4「错误提示一致」要求四类错误各有固定文案与解决指引，而且**面板与
 * 通知要用同一句**。三处各写一遍 `when` 的结果是：改了面板忘了通知，
 * 用户在通知栏看到的是一句五百年前的话。集中成一个函数，加一类错误时
 * `when` 会因不穷举而**编译失败**——这就是我们要的强制。
 *
 * ## 文案原则
 * 每一条都回答"**现在该做什么**"，而不只是"哪里错了"：
 * 端口被占 → 去设置里换；没有局域网 → 去连 WiFi 或开热点；
 * 绑定失败 → 换端口重试。只说"启动失败"等于把排查工作丢回给用户。
 */
internal object ShareErrorText {

    fun of(context: Context, error: ShareError): String = when (error) {
        is ShareError.PortsExhausted -> context.getString(
            R.string.share_error_ports_exhausted,
            error.preferred,
            error.tried,
        )

        is ShareError.BindFailed -> context.getString(
            R.string.share_error_bind_failed,
            error.port,
            error.detail,
        )

        ShareError.NoLanAddress -> context.getString(R.string.share_error_no_lan)

        is ShareError.NotSupported -> context.getString(
            R.string.share_error_unavailable_feature,
            error.feature,
        )

        is ShareError.RootUnavailable -> context.getString(
            R.string.share_error_root_unavailable,
            error.detail,
        )

        is ShareError.IoFailure -> context.getString(R.string.share_error_io, error.detail)
    }
}
