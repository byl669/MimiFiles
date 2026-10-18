package com.miyuetech.mimifiles.feature.share

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.shareserver.ServerState
import com.miyuetech.mimifiles.core.shareserver.ServerType
import com.miyuetech.mimifiles.core.shareserver.ShareEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 快捷设置磁贴：无线分享的一键开关（M6 · P1-9）。
 *
 * ## 为什么"开"这一半可能是打开面板，而不是直接启动
 * 分享服务端必须有一个**共享根**（一个真实存在的目录），而这个根只能由用户在面板里
 * 选或从某个目录进来时带过来。磁贴没有那个上下文。
 *
 * 所以这里分两种情况，而不是"假装能开"：
 *  - 进程里**已经知道**一个根（用户这次会话里分享过，或者正在跑）→ 磁贴直接切；
 *  - 不知道 → 打开面板让用户选。面板本来就有"选路径"这一步
 *    （`dialog_share_path`），所以这不是绕路，而是把必做的一步交给唯一能做的地方。
 *
 * 反过来的"关"永远是直接的：停止不需要任何上下文。
 *
 * ## 状态从哪来
 * [ShareEngine] 是进程级单例，磁贴、面板、通知读的是**同一个** `StateFlow`。
 * 磁贴只在"面板被拉下来"（[onStartListening]）期间订阅它 —— 快捷设置不显示时
 * 订阅没有意义，而磁贴服务的生命周期由系统决定，长期持有一个 Job 只会漏。
 *
 * ## 为什么 onStartListening 里要重新 install 引擎
 * 磁贴是最容易被系统**单独拉起**的组件之一（用户从下拉面板点它时，
 * 应用进程可能刚被创建、一个 Activity 都没跑过）。`ShareEngine.install` 是幂等的，
 * 在这里调一次能保证后续的 `start/stop` 不会因为"没装 Context"而静默失败。
 */
class ShareTileService : TileService() {

    private var scope: CoroutineScope? = null

    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        ShareEngine.install(this)
        val active = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        render(ShareEngine.state.value)
        stateJob?.cancel()
        // 面板/通知改变状态时磁贴要跟着变，否则会出现"服务已经停了、磁贴还亮着"。
        stateJob = active.launch {
            ShareEngine.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        ShareEngine.install(this)
        val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { this.scope = it }
        val running = ShareEngine.state.value
        if (running.isRunning) {
            scope.launch {
                MimiLog.i(TAG, "磁贴：停止分享")
                // 不需要自己停前台服务：`ShareForegroundService` 观察的是同一个状态流，
                // 状态一变成 Stopped 它就会自己退前台并 stopSelf（见那边的注释）。
                ShareEngine.stop(reason = "tile")
            }
            return
        }
        // 已知根 → 直接启动；否则把用户送到面板去选。
        val request = ShareEngine.request() ?: ShareSessionHolder.pending()
        if (request == null) {
            openPanel()
            return
        }
        scope.launch {
            val type = ShareSessionHolder.lastType()
            MimiLog.i(TAG, "磁贴：启动 $type 分享（根=${request.root}）")
            ShareEngine.start(type, ShareEngine.defaultConfig(type), request)
            // 起来之后仍然要把前台服务拉起来，否则应用一进后台监听就可能被回收。
            // 顺带把面板显示出来：用户点了磁贴之后需要看到地址才能连上去。
            shareForegroundStart()
            openPanel()
        }
    }

    private fun shareForegroundStart() {
        runCatching { startForegroundService(Intent(this, ShareForegroundService::class.java)) }
            .onFailure { MimiLog.w(TAG, "磁贴：拉起分享前台服务失败", it) }
    }

    private fun openPanel() {
        val intent = Intent(this, SharePanelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34 起必须用这个（`startActivity` 从磁贴发起会被拒：
                // 后台启动 Activity 受限）。老系统上这个方法不存在，所以要走分支。
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { MimiLog.w(TAG, "磁贴：打开分享面板失败", it) }
    }

    /**
     * 把 [ServerState] 画到磁贴上。
     *
     * 只在"状态真的变了"时写回：`Tile.updateTile()` 是一次跨进程调用，
     * 而在 `Running` 状态下这个流会因为"网络变化刷新地址""面板开关刷新失效时间"
     * 等原因反复发同一类值。不去重的话，下拉面板开着的时候会有一串无意义的 IPC。
     */
    private fun render(state: ServerState) {
        val tile = qsTile ?: return
        val active = state.isRunning
        val wanted = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        var changed = tile.state != wanted
        tile.state = wanted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val subtitle = when (state) {
                is ServerState.Running -> "${state.port}"
                ServerState.Starting -> getString(R.string.share_tile_starting)
                is ServerState.Error -> getString(R.string.share_tile_error)
                ServerState.Stopped -> getString(R.string.share_tile_idle)
            }
            if (tile.subtitle != subtitle) {
                tile.subtitle = subtitle
                changed = true
            }
        }
        Icon.createWithResource(this, R.drawable.ic_share_wifi).let { icon ->
            tile.icon = icon
        }
        if (changed) tile.updateTile()
    }

    private companion object {
        const val TAG = "MimiShareTile"
    }
}
