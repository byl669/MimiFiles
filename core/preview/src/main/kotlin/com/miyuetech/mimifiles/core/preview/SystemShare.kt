package com.miyuetech.mimifiles.core.preview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * 「系统分享」—— 长按文件 → 分享 → 弹 **Android 原生分享面板**（微信、蓝牙、 nearby、
 * 邮件、其它能收文件的应用），而不是把手机变成 HTTP/FTP 服务器。
 *
 * 后者是另一件事，现在叫「高级分享」，仍然走 `:feature:share` 的面板。
 *
 * ## 为什么不用 FileProvider，也不新增权限
 * `:provider` 的 `MimiDocumentsProvider` 已经把 `local` / `smb` / `webdav` / `saf`
 * 全部映射成 `content://`（documentId = `FsPath.toString()`），而且 API 26+ 走
 * `ProxyFileDescriptor` 支持随机读。所以 [ExternalOpen.documentUri] 一行就能拿到可外发的
 * URI —— 这正是 M4 之前「分享要对外发 URI，所以先不做」那个顾虑如今不成立的理由。
 *
 * ## 为什么远端文件要先落地（M7.25 的选择）
 * `content://` 直发是零拷贝的，但接收方读的是**我们自己的 Provider**：SMB 上的每一次
 * 随机读都是一次网络往返。项目里已经吃到过一次亏 —— 系统安装器从 SMB 读 APK 时
 * 「正在准备安装应用」转圈直到超时（见 `PreviewCache.materializeForShare` 的注释）。
 * 微信、剪辑类应用同样会先探大小再分块读，大文件很容易重演。
 *
 * 所以：[SystemShare.needsStaging] 为真（非 `local`）的文件先落到应用私有缓存，
 * 再把**本地文件**的 URI 发出去。本机文件不复制，点完立刻弹面板。
 *
 * ## 为什么要显式 grantUriPermission 给所有候选应用
 * `FLAG_GRANT_READ_URI_PERMISSION` 只覆盖 `startActivity` 那一个接收者；文件管理器类
 * 应用常把 URI **再转发**一次（"用 XX 打开"其实是它自己再起 Intent），转发时权限就丢了。
 * 沿用 [ExternalOpen] 的做法：对 `queryIntentActivities` 拿到的每个包都显式授权一次。
 * 但该列表只作「尽力而为」—— Android 11 的包可见性可能把它过滤成空，
 * 真正的"有没有接收方"判据是 `startActivity` 是否抛 [ActivityNotFoundException]。
 */
object SystemShare {

    /** 未知类型用通配，别让 `application/octet-stream` 把分享目标过滤得只剩一两个。 */
    const val ANY_TYPE = "*/*"

    private const val LOCAL_SCHEME = "local"

    private const val TAG = "MimiSystemShare"

    /**
     * 系统分享只吃**文件流**：目录没有可读的字节流，Provider 的 `openDocument`
     * 对目录也会失败。选中项里只要有一个目录就整体不提供这个动作，
     * 而不是静默跳过（静默跳过会让人以为漏发了东西）。
     */
    fun canShare(entries: List<FsEntry>): Boolean =
        entries.isNotEmpty() && entries.all { it.isFile }

    /**
     * 推导分享用的 MIME。
     *
     * - 单选：后端给的 MIME 优先，退化到按扩展名查表；查不出就用通配。
     * - 多选：全同用精确类型；同一大类（如 jpeg + png）用 `image/*`；否则 `*/*`。
     *
     * 这是**纯 Kotlin** 函数（不碰 Uri / Intent），所以能直接在 JVM 单测里钉死。
     */
    fun mimeOf(entries: List<FsEntry>): String {
        if (entries.isEmpty()) return ANY_TYPE
        val types = entries.map { entry ->
            val raw = entry.mimeType
                ?.takeIf { it.isNotBlank() && it != MimeTypes.OCTET_STREAM }
                ?: MimeTypes.of(entry.name)
            if (raw == MimeTypes.OCTET_STREAM) ANY_TYPE else raw
        }
        if (types.size == 1) return types.first()
        val first = types.first()
        if (first != ANY_TYPE && types.all { it == first }) return first
        val major = first.substringBefore('/')
        return if (major.isNotEmpty() && major != "*" && types.all { it.substringBefore('/') == major }) {
            "$major/*"
        } else {
            ANY_TYPE
        }
    }

    /** 本机文件可直接外发；其余后端（smb / webdav / saf）先落地。 */
    fun needsStaging(path: FsPath): Boolean = path.scheme != LOCAL_SCHEME

    /** 落地后的本地文件要变回 `FsPath`，才能交给同一个 Provider 发出去。 */
    fun stagedPath(file: File): FsPath = FsPath.of(LOCAL_SCHEME, "", file.absolutePath)

    /**
     * 单选 / 多选分别走哪个 action。
     *
     * 抽成纯 Kotlin 函数是为了能在 JVM 单测里钉住"多个文件必须用 SEND_MULTIPLE"——
     * 写成 SEND + 多个 EXTRA_STREAM 是最常见的错法，界面能弹但对方只收到第一个。
     * `Intent.ACTION_*` 是编译期常量，测试里不会触发 android.jar 的 stub。
     */
    fun actionFor(count: Int): String =
        if (count == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE

    /** 组装 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`（不含启动动作，便于单独验证）。 */
    fun sendIntent(uris: List<Uri>, mime: String): Intent {
        require(uris.isNotEmpty()) { "分享至少要有一个文件" }
        val intent = if (actionFor(uris.size) == Intent.ACTION_SEND) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        return intent.apply {
            setType(mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Android 7 起不少接收方（尤其国内 IM）只从 ClipData 里认 URI 授权；
            // 光有 EXTRA_STREAM 会拿到 SecurityException。描述里带上真实 MIME，
            // 免得系统按 text/uri-list 去匹配目标。
            clipData = clipOf(uris, mime)
        }
    }

    private fun clipOf(uris: List<Uri>, mime: String): ClipData {
        val clip = ClipData("MimiFiles", arrayOf(mime), ClipData.Item(uris.first()))
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        return clip
    }

    private fun chooser(send: Intent, title: CharSequence): Intent =
        Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 部分 ROM 的 Chooser 重建 Intent 时会丢 ClipData，这里补回去。
            send.clipData?.let { clipData = it }
        }

    /** 准备结果：要么全部就绪，要么明确说出是哪个文件没准备好。 */
    sealed interface PrepareResult {
        data class Ready(val uris: List<Uri>, val mime: String, val stagedCount: Int) : PrepareResult
        data class Failed(val name: String, val reason: String? = null) : PrepareResult
    }

    /**
     * 把选中项变成可外发的 URI 列表。
     *
     * 任一文件落地失败就**整体中止**：多选分享里静默漏发一个，比明说失败更难排查。
     * 调用方负责显示进度与失败提示（这层不持有 Activity 的 UI）。
     */
    suspend fun prepare(
        context: Context,
        entries: List<FsEntry>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PrepareResult {
        if (!canShare(entries)) return PrepareResult.Failed(entries.firstOrNull()?.name ?: "—")
        val cache = PreviewCache(context.applicationContext)
        val uris = ArrayList<Uri>(entries.size)
        var staged = 0
        entries.forEachIndexed { index, entry ->
            val path = try {
                if (needsStaging(entry.path)) {
                    val file = cache.materializeForSystemShare(entry.path, entry.size)
                        ?: return PrepareResult.Failed(entry.name)
                    staged++
                    stagedPath(file)
                } else {
                    entry.path
                }
            } catch (t: CancellationException) {
                // Activity 被销毁 / 协程被取消不是"准备失败"，别在已销毁的界面上弹 toast。
                throw t
            } catch (t: Throwable) {
                MimiLog.w(TAG, "准备分享文件失败：${entry.path}", t)
                return PrepareResult.Failed(entry.name, t.message)
            }
            uris += ExternalOpen.documentUri(path)
            onProgress(index + 1, entries.size)
        }
        val mime = mimeOf(entries)
        // 实测断言用：本机文件应看到 staged=0（零拷贝），多文件应看到 uris=N。
        MimiLog.d(TAG, "准备完成：${uris.size} 个 URI，mime=$mime，落地 $staged 个")
        return PrepareResult.Ready(uris, mime, staged)
    }

    /**
     * 弹出系统分享面板。
     *
     * @return 是否真的拉起了面板。`false` = 设备上没有任何应用能接收这个类型。
     */
    fun send(activity: Activity, ready: PrepareResult.Ready, title: CharSequence): Boolean {
        val intent = sendIntent(ready.uris, ready.mime)
        grantToTargets(activity, intent, ready.uris)
        return runCatching {
            activity.startActivity(chooser(intent, title))
            true
        }.getOrElse { t ->
            if (t is ActivityNotFoundException) {
                MimiLog.d(TAG, "没有应用能接收 ${ready.mime} 的分享")
            } else {
                MimiLog.w(TAG, "拉起系统分享面板失败", t)
            }
            false
        }
    }

    /** 尽力给所有候选接收方授权；返回列表仅供日志，不当作"有没有接收方"的判据。 */
    private fun grantToTargets(context: Context, send: Intent, uris: List<Uri>) {
        val targets = runCatching {
            context.packageManager.queryIntentActivities(send, 0)
        }.getOrDefault(emptyList())
        MimiLog.d(TAG, "分享候选应用 ${targets.size} 个，type=${send.type}")
        targets.forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            uris.forEach { uri ->
                runCatching {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }
}
