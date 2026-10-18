package com.miyuetech.mimifiles.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.miyuetech.mimifiles.core.shortcut.FolderShortcuts
import com.miyuetech.mimifiles.feature.browser.DocumentsActivity

/**
 * 启动页。
 *
 * 三个职责：
 *  1. **冷启动窗口**：用启动主题的 windowBackground 覆盖，避免白屏；
 *  2. **路由**：跳转到 [DocumentsActivity]；
 *  3. **桌面快捷方式入口**：本 Activity 是应用唯一导出的 LAUNCHER 组件，
 *     桌面图标（[FolderShortcuts] 创建）点下来时目标信息挂在 extras 上，
 *     在这里**原样转发**给浏览界面——`DocumentsActivity` 保持 `exported=false` 不外露。
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(buildBrowserIntent(intent))
        finish()
    }

    private fun buildBrowserIntent(from: Intent?): Intent =
        Intent(this, DocumentsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // 快捷方式目标（可空）——只有从桌面图标进来时才带
            FolderShortcuts.readTarget(from)?.let { target ->
                putExtra(FolderShortcuts.EXTRA_FS_ID, target.fsId)
                putExtra(FolderShortcuts.EXTRA_PATH, target.path)
                putExtra(FolderShortcuts.EXTRA_NAME, target.displayName)
            }
        }
}
