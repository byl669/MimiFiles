package com.miyuetech.mimifiles.core.preview

import android.app.Activity
import android.content.Intent
import com.miyuetech.mimifiles.core.common.Authorities
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.model.FsPath
import android.provider.DocumentsContract
import android.net.Uri

/**
 * 「交给其它应用打开」—— 预览体系里第 ② 条出路（方案 §6.1）。
 *
 * ## 为什么 uri 来自我们自己的 `DocumentsProvider`
 *  - 本机文件的 `file://` 在 targetSdk ≥ 24 上会抛 `FileUriExposedException`（等于不可用）；
 *  - 网络文件根本没有 `file://` 这个形式；
 *  - 而 `:provider` 把 `local` / `smb` / `webdav` 全都注册进了同一张注册表，
 *    `documentId = FsPath.toString()` 一条约定就覆盖全部后端，并且流式读取、
 *    随机读（`ProxyFileDescriptorCallback`）都是现成的。
 *
 * 换句话说：**一个 provider 顶掉 FileProvider + 网络临时下载两个方案**。
 * 方案 §6.1 写的"预览统一走 ContentProvider 暴露 `content://`，不落明文路径"就是这个意思。
 *
 * ## 授权为什么要遍历所有目标应用
 * `FLAG_GRANT_READ_URI_PERMISSION` 只对 `startActivity` 那一条 Intent 的接收者生效。
 * 而文件管理器类应用常常**转发**这个 Uri（例如"用 WPS 打开"其实是它自己再起一个 Intent），
 * 转发时权限就丢了，对方会拿到 `SecurityException`。
 * 所以这里对**所有能处理该类型的包**显式 `grantUriPermission` 一次，把转发路径也堵上。
 */
object ExternalOpen {

    /** 构造"查看"Intent（不启动）。调用方可先判断有没有接收者。 */
    fun viewIntent(context: android.content.Context, path: FsPath, mime: String?): Intent {
        val type = mime?.takeIf { it.isNotBlank() && it != MimeTypes.OCTET_STREAM }
            ?: MimeTypes.of(path.displayName)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(documentUri(path), type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun documentUri(path: FsPath): Uri =
        DocumentsContract.buildDocumentUri(Authorities.DOCUMENTS, path.toString())

    /**
     * 尝试外发。
     *
     * @return 是否真的交给了某个应用。`false` 表示设备上没有能处理该类型的应用
     *         （调用方应给一句可读的提示，而不是静默无反应）。
     */
    fun open(activity: Activity, path: FsPath, mime: String?): Boolean {
        val intent = viewIntent(activity, path, mime)
        val targets = activity.packageManager.queryIntentActivities(intent, 0)
        if (targets.isEmpty()) {
            MimiLog.d(TAG, "没有应用能处理 ${intent.type}：$path")
            return false
        }
        // 把类型也一并告知（部分应用只看 type 不看 data 的 scheme）。
        val uri = intent.data ?: return false
        targets.forEach { info ->
            runCatching {
                activity.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        return runCatching { activity.startActivity(intent); true }
            .onFailure { MimiLog.w(TAG, "外发打开失败：$path", it) }
            .getOrDefault(false)
    }

    private const val TAG = "MimiExternalOpen"
}
