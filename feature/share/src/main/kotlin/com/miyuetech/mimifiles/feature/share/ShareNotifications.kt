package com.miyuetech.mimifiles.feature.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.miyuetech.mimifiles.core.shareserver.ServerState

/**
 * 分享前台服务的通知（方案 S-5 的 P0 部分）。
 *
 * ## 为什么必须是前台服务而不是普通后台服务
 * 分享服务端在"用户已经切到电脑浏览器"时仍在工作，也就是应用处于后台。
 * 从 Android 8 起，后台应用会被系统限制（网络、CPU 都可能被掐），
 * 而一个靠 `Service` 撑着的监听套接字在被掐之后**不会有任何提示**——
 * 用户只会看到"PC 上突然连不上了"。前台服务是唯一能让系统允许它继续跑的形态，
 * 顺带也就有了这条通知。
 *
 * ## 通知为什么必须带「停止」
 * 前台服务的通知是用户**唯一**能确认"东西还在跑"的地方，也是他在手机不在手上时
 * 唯一能终止它的入口。少了这个按钮，用户要从通知点进面板才能停 —— 而面板恰好
 * 可能就是那个"已经关闭并开始计时"的状态。一个不带停止按钮的常驻通知，
 * 在用户眼里与流氓软件没有区别。
 *
 * ## 通道重要性用 LOW 而不是 DEFAULT
 * 它不是消息，是状态。用 DEFAULT 会响一声 + 弹横幅，而分享启动时面板就在用户眼前
 * ——那条横幅纯属噪声。LOW 在通知栏可见、不打扰。
 */
internal object ShareNotifications {

    const val CHANNEL_ID = "mimi_share"

    /**
     * 通知 id。
     *
     * 用一个固定值而不是自增值：前台服务的通知**必须**是同一条，
     * 用自增 id 会让每次状态刷新都变成"新通知"，用户的通知栏会被刷屏。
     */
    const val NOTIFICATION_ID = 0x4D31 // "M1"

    const val ACTION_STOP = "com.miyuetech.mimifiles.share.action.STOP"

    /** 幂等建通道。Android 8 之前无需通道，直接返回。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.share_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.share_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: ServerState): Notification {
        ensureChannel(context)

        val protocolLabel = (state as? ServerState.Running)
            ?.addresses?.firstOrNull()?.protocol?.label
            ?: ShareProtocolLabel.last

        val title = if (state.isRunning) {
            context.getString(R.string.share_notification_title, protocolLabel)
        } else {
            context.getString(R.string.share_state_stopped)
        }

        val text = when (state) {
            is ServerState.Running -> if (state.addresses.isEmpty()) {
                context.getString(R.string.share_address_empty)
            } else {
                state.addresses.joinToString(" · ") { it.hostPort }
            }

            ServerState.Starting -> context.getString(R.string.share_starting)
            ServerState.Stopped -> context.getString(R.string.share_state_stopped)
            is ServerState.Error -> ShareErrorText.of(context, state.reason)
        }

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, SharePanelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, ShareForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share_wifi)
            .setContentTitle(title)
            .setContentText(text)
            // 长文本用 BigText：多网卡时地址有好几行，折叠态只显示第一行会让人
            // 以为"只有一个地址"。开热点时那两个地址（WiFi + ap0）恰恰是最需要
            // 都看到的。
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .addAction(0, context.getString(R.string.share_notification_action_stop), stopIntent)
            .setOngoing(state.isRunning)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun cancel(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
    }
}

/**
 * 记住最后一次跑的协议类型。
 *
 * 作用很窄但必要：服务**停止之后** `ServerState` 是 `Stopped`（不带类型），
 * 而那条"已停止"的通知如果标题变成光秃秃的「已停止」，用户就看不出它原本是
 * FTP 还是 HTTP。用一个进程级小对象记一下，比往 `Stopped` 里加类型字段干净
 * ——状态机的形态不该为了通知文案而变。
 */
internal object ShareProtocolLabel {
    var last: String = "HTTP"
}
