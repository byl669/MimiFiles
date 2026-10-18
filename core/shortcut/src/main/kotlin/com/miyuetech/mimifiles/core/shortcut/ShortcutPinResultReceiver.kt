package com.miyuetech.mimifiles.core.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 接收桌面 pin 流程的回执。
 *
 * 由 [FolderShortcuts] 通过 `PendingIntent` 间接唤起（组件是显式的、`exported=false`，
 * 桌面以本应用的 PendingIntent 令牌投递，因此不需要对外暴露）。
 *
 * 语义：**收到即代表用户已在桌面确认添加**；用户取消时桌面不会回调。
 * 收到后转发为应用内广播 [FolderShortcuts.ACTION_PIN_RESULT]，供界面提示「已添加到桌面」。
 */
class ShortcutPinResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(FolderShortcuts.EXTRA_PINNED_SHORTCUT_ID)
        Log.i(TAG, "桌面已添加快捷方式，shortcutId=$id")
        context.sendBroadcast(
            Intent(FolderShortcuts.ACTION_PIN_RESULT)
                .setPackage(context.packageName)
                .putExtra(FolderShortcuts.EXTRA_PINNED_SHORTCUT_ID, id),
        )
    }

    private companion object {
        const val TAG = "MimiShortcut"
    }
}
