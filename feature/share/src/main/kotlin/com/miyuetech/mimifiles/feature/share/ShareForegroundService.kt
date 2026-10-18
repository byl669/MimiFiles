package com.miyuetech.mimifiles.feature.share

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.datastore.SettingsRepository
import com.miyuetech.mimifiles.core.shareserver.ServerState
import com.miyuetech.mimifiles.core.shareserver.ShareEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 分享服务的前台服务宿主（方案 S-5 的 P0：状态可见 + 一键停止）。
 *
 * ## 它不做业务
 * 服务端由 [ShareEngine] 起停，本服务只干三件事：
 *  1. 让进程在后台**被系统允许继续跑**（前台服务的唯一目的）；
 *  2. 把 [ShareEngine.state] 渲染成一条通知；
 *  3. 处理通知上的「停止」与"从最近任务划掉"两种终止入口。
 *
 * 之所以不把服务端搬进 Service（很多同类实现是这么做的）：服务端的生命周期
 * 必须长于任何一个界面**但短于进程**，而 Service 的生命周期会被系统、
 * 被 `stopSelf`、被用户划掉三种方式终结。挂在 Service 上就会出现
 * "服务被系统重启，但没人重新启动监听"这类状态不一致。分成两层之后，
 * 即使 Service 被回收，[ShareEngine] 里的监听仍然在跑，面板一打开就能看到真实状态。
 *
 * ## 为什么继承 `Service` 而不是 `LifecycleService`
 * 后者要拖进 `androidx.lifecycle:lifecycle-service` 一整个 artifact，
 * 而这里要的只是"一个能在销毁时取消的协程作用域"——三行代码的事。
 * 为了 `repeatOnLifecycle` 这种在 Service 里毫无意义的 API 付一个依赖，不划算。
 *
 * ## `START_STICKY` 刻意不用
 * 系统重建服务时给一个 null Intent，而我们的动作（启动/停止）全部来自界面，
 * 重建时无法判断该做什么。用 `START_NOT_STICKY`：进程死了就死了，
 * 下次用户进面板重新启动即可——这比"系统悄悄重启了一个没有监听的空壳服务"好。
 */
class ShareForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsRepository

    private var stateJob: Job? = null

    /**
     * 是否**曾经**在运行态下推上去过前台。
     *
     * 作用是把两类"非运行态"区分开（这个区分很关键）：
     *  - 服务刚被拉起、引擎还没开始跑 → 推前台满足 5 秒规则后就可以退了；
     *  - 服务本来在跑、引擎被停掉（用户点停止 / 会话过期）→ 这时才该把通知撤掉。
     * 少了这个标志，后一种情况的"已停止"通知会留在通知栏里，而用户明明点了停止。
     */
    private var everRunning = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        ShareEngine.install(this)
        ShareNotifications.ensureChannel(this)
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 顺序是硬要求：`startForegroundService()` 之后必须在约 5 秒内调用
        // `startForeground()`，否则系统会抛 `RemoteServiceException` 并**崩溃**。
        // 所以先无条件推一次（哪怕当前状态是"未启动"），再根据状态决定要不要退。
        promoteToForeground(ShareEngine.state.value)

        when (intent?.action) {
            ShareNotifications.ACTION_STOP -> stopEverything("notification")

            else -> {
                val state = ShareEngine.state.value
                if (!state.isRunning && state !is ServerState.Starting) {
                    // 引擎没在跑 —— 说明这次是被"空手"拉起来的。推一次前台已满足
                    // 规则，直接收工。
                    scope.launch { teardown() }
                }
            }
        }
        // 见类注释：不做 START_STICKY。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务里划掉了应用。
     *
     * 这就是设置项「退出应用自动停服」的落点（方案 §10.4「与设置统一」）。
     * 判断依据用 `onTaskRemoved` 而不是 `Application.onTerminate`：后者在真机上
     * **几乎永远不会被调用**（Android 不保证回调），而前者是"用户真的想关掉它"
     * 的那个瞬间。默认值在设置里是**开**（见 `SettingsRepository.shareStopOnExit`）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.launch {
            val stopOnExit = runCatching { settings.shareStopOnExit.first() }.getOrDefault(true)
            if (stopOnExit) {
                MimiLog.i(TAG, "任务被移除且已开启「退出自动停服」，停止分享")
                ShareEngine.stop("task_removed")
                teardown()
            } else {
                MimiLog.i(TAG, "任务被移除，但用户关闭了「退出自动停服」，保持分享运行")
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 只回收自己的通知；**不停**服务端。服务端由引擎管，
        // 它可能因为转屏或界面重建而在这里被短暂回收，此时停掉监听
        // 会让 PC 上正在传的文件断掉——那是用户完全无法理解的。
        ShareNotifications.cancel(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            ShareEngine.state.collect { state ->
                val running = state.isRunning || state is ServerState.Starting
                (state as? ServerState.Running)?.addresses?.firstOrNull()?.let {
                    ShareProtocolLabel.last = it.protocol.label
                }
                if (running) {
                    everRunning = true
                    promoteToForeground(state)
                } else if (everRunning) {
                    // 服务端已停（用户手动停 / 会话过期）：服务自己也该退了，
                    // 否则会留下一条"已停止"的常驻通知 + 一个占着前台名额的空服务。
                    teardown()
                }
            }
        }
    }

    private fun promoteToForeground(state: ServerState) {
        ServiceCompat.startForeground(
            this,
            ShareNotifications.NOTIFICATION_ID,
            ShareNotifications.build(this, state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun teardown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        ShareNotifications.cancel(this)
        stopSelf()
    }

    private fun stopEverything(reason: String) {
        scope.launch {
            ShareEngine.stop(reason)
            teardown()
        }
    }

    companion object {
        private const val TAG = "MimiShareService"

        /**
         * 拉起前台服务。
         *
         * 必须在**应用处于前台**时调用（面板点「启动」的那一刻），
         * 否则 Android 12+ 会抛 `ForegroundServiceStartNotAllowedException`。
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ShareForegroundService::class.java),
                )
            }.onFailure {
                // 系统拒绝拉起前台服务不该让面板崩掉：服务端本身已经起来了，
                // 只是没有通知。面板会退化成"仅界面内可见状态"（方案 §10.5
                // 「通知权限未授予」的降级路径）。
                MimiLog.w(TAG, "拉起前台服务失败，降级为无通知运行：${it.message}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ShareForegroundService::class.java)
                        .setAction(ShareNotifications.ACTION_STOP),
                )
            }
        }
    }
}
