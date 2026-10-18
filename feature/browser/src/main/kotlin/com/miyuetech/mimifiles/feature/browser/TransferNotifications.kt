package com.miyuetech.mimifiles.feature.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

/**
 * 搬运进度通知（方案 M6 的「复制粘贴可视化」）。
 *
 * ## 为什么必须是前台服务 + 常驻通知
 * 用户点粘贴之后常常立刻切走（去回消息、去锁屏）。此时 Activity 只是 stop，
 * ViewModel 还在，协程也还在跑——**但进程随时可能被系统回收**，而传输一旦被杀，
 * 表现是"文件传了一半、目录里多出一个残缺的副本"。前台服务是让系统
 * "别回收这个进程"的唯一合法手段，顺带也就有了这条通知。
 *
 * ## 为什么带「取消」
 * 与分享通知同理：常驻通知是用户在应用之外**唯一**能终止它的入口。
 * 一个既不能关、又没有停止按钮的常驻通知，在用户眼里与流氓软件无异。
 *
 * ## 为什么重要性是 LOW 而不是 DEFAULT
 * 搬运是"用户自己刚触发的、且他心里有数"的事，不是需要打断他的消息。
 * DEFAULT 会响一声并弹横幅，而横幅出现时用户通常正盯着自己的文件列表。
 */
internal object TransferNotifications {

    const val CHANNEL_ID = "mimi_transfer"

    /**
     * 通知 id 固定。
     *
     * 前台服务的通知**必须**是同一条；用自增 id 会让每次进度刷新都变成一条新通知，
     * 用户的通知栏会被刷屏（M4 分享通知踩过同一个坑）。
     */
    const val NOTIFICATION_ID = 0x4D32 // "M2"

    const val ACTION_CANCEL = "com.miyuetech.mimifiles.transfer.action.CANCEL"

    /** 幂等建通道。Android 8 之前无需通道。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.browser_transfer_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.browser_transfer_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, progress: TransferProgress): Notification {
        ensureChannel(context)

        val title = context.getString(
            if (progress.kind == TransferKind.MOVE) {
                R.string.browser_transfer_title_move
            } else {
                R.string.browser_transfer_title_copy
            },
            progress.itemOrdinal,
            progress.totalItems,
        )

        // 取消请求发出之后文案要立刻变，否则用户点了「取消」却看到还在滚动的进度条，
        // 会以为按钮没生效——而"正在取消"与"没反应"是两种完全不同的等待体验。
        val text = if (progress.cancelRequested) {
            context.getString(R.string.browser_transfer_cancelling)
        } else {
            progress.currentName.ifEmpty { context.getString(R.string.browser_transfer_preparing) }
        }

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, DocumentsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, TransferForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transfer)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        // 进度条：有确定百分比就画确定进度，否则用不确定态。
        // 注意这里**不**用 `setProgress(0, 0, true)` 之外的写法——参见
        // `TransferProgress.currentPercent` 用 -1 而不是 0 表达"未知"的理由。
        if (progress.currentPercent >= 0 && !progress.cancelRequested) {
            builder.setProgress(100, progress.currentPercent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        if (!progress.cancelRequested) {
            builder.addAction(0, context.getString(R.string.browser_transfer_action_cancel), cancelIntent)
        }

        return builder.build()
    }

    fun cancel(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
    }
}
