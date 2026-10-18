package com.miyuetech.mimifiles.feature.browser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.miyuetech.mimifiles.core.common.MimiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 搬运进度的前台服务宿主。
 *
 * ## 它不做业务，一行都不做
 * 搬运本身跑在 `BrowserViewModel` 的 `viewModelScope` 里（`FsOps` 在那里）。
 * 本服务只干三件事：
 *  1. 让进程在后台**被系统允许继续跑**（前台服务的唯一目的，也是"复制不中断"的全部含义）；
 *  2. 把 [TransferCenter.state] 渲染成一条进度通知；
 *  3. 处理通知上的「取消」。
 *
 * ## 为什么业务不搬进 Service
 * 与 `ShareForegroundService` 同样的理由，但这里还多一条：搬运的调用点是
 * **View 层的一次点击**，它天然属于 ViewModel 的生命周期。把业务搬进 Service
 * 就得再造一套"把 FsOps、路径、剪切板状态从 UI 传进 Service"的通道，
 * 而这条通道一旦出现，两边就会各自演化。分成两层之后，Service 被回收也只是
 * 少了通知，进度在 [TransferCenter] 里，界面一打开照样看得到。
 *
 * ## 不用 START_STICKY
 * 系统重建时给一个 null Intent，而搬运的状态在 `TransferCenter` 里、
 * 协程在别的进程对象里——重建出来的空壳服务渲染不出任何有意义的东西。
 * 用 `START_NOT_STICKY`：进程没了就没了，用户重新粘贴即可。
 */
class TransferForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        TransferNotifications.ensureChannel(this)
        observeProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 顺序是硬要求：`startForegroundService()` 之后必须在约 5 秒内调用
        // `startForeground()`，否则系统抛 `RemoteServiceException` 并**崩溃**
        // （Android 14 起还要求类型匹配，见下）。所以先无条件推一次，再看状态决定去留。
        promoteToForeground(TransferCenter.state.value)

        when (intent?.action) {
            TransferNotifications.ACTION_CANCEL -> {
                // 只下发取消请求，**不**在这里停服务：真正的停止要等传输循环
                // 在下一个检查点抛出取消，届时 ViewModel 会走收尾并让状态归空，
                // 本服务观察到空状态再退。若在这里直接把服务停了，通知会立刻消失，
                // 用户就看不到"正在取消…"这一段，只会觉得"点了一下、然后进度条不见了"。
                MimiLog.i(TAG, "用户从通知栏请求取消搬运")
                TransferCenter.requestCancel()
            }

            else -> {
                if (TransferCenter.state.value == null) {
                    // 引擎没在跑 —— 被"空手"拉起来了。推一次前台已满足 5 秒规则，直接收工。
                    teardown()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 只撤自己的通知，**不**去动 [TransferCenter]：状态属于业务层，
        // 服务因为转屏/内存压力被短暂回收时把它们清掉，会让"回到应用看到进度还在"
        // 变成"进度凭空消失"。
        TransferNotifications.cancel(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun observeProgress() {
        stateJob?.cancel()
        stateJob = scope.launch {
            TransferCenter.state.collect { progress ->
                if (progress == null) {
                    teardown()
                } else {
                    promoteToForeground(progress)
                }
            }
        }
    }

    private fun promoteToForeground(progress: TransferProgress?) {
        if (progress == null) return
        ServiceCompat.startForeground(
            this,
            TransferNotifications.NOTIFICATION_ID,
            TransferNotifications.build(this, progress),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun teardown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        TransferNotifications.cancel(this)
        stopSelf()
    }

    companion object {
        private const val TAG = "MimiTransfer"

        /**
         * 拉起前台服务。
         *
         * 必须在**应用处于前台**时调用（粘贴是用户的一次点击，必然满足），
         * 否则 Android 12+ 会抛 `ForegroundServiceStartNotAllowedException`。
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TransferForegroundService::class.java),
                )
            }.onFailure {
                // 系统拒绝不该让粘贴崩掉：搬运本身已经能在 ViewModel 里跑完，
                // 只是没有通知、也因此失去了"后台不被回收"的保障。
                // 这是明确的降级路径，记一条日志以便与"搬运真的被杀"区分开。
                MimiLog.w(TAG, "拉起搬运前台服务失败，降级为无通知搬运：${it.message}")
            }
        }

        /**
         * 收工时停服务。
         *
         * 走 `stopService` 而不是发一个 action：此时 [TransferCenter] 已经归空，
         * 服务自己会在下一帧观察到的瞬间 `stopSelf`；这里只是把"没订阅到"的
         * 极端情况兜住（例如服务还没起来就被取消了）。
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferForegroundService::class.java)) }
        }
    }
}
