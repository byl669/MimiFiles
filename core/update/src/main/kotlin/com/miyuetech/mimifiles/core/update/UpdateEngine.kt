package com.miyuetech.mimifiles.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.network.NetworkDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用内自更新的引擎：唯一的状态源 + 全部动作。
 *
 * # 为什么是进程级单例（而不是 ViewModel 里的一份）
 * "下载一个 6 MB 的包"这件事**不该随着用户离开设置页而中断**。放在 ViewModel 里，
 * 用户从设置页返回文件列表再回来，下载就重来一遍；放 Activity 里，转个屏就断。
 * 所以状态与协程作用域都挂在进程上，页面只是**观察者**。
 *
 * # 为什么自己建作用域，而不是用 `AppScope`
 * `AppScope` 的契约写得很清楚：只收"短小、幂等、失败可忽略的收尾写入"，
 * **明确排除下载这类长任务**（它不会随页面结束而停止，会变成后台耗电的幽灵）。
 * 更新下载恰好相反：它**需要**活过页面，但**不应该**活过整个会话。
 * 所以这里单开一个 `SupervisorJob + NetworkDispatchers.io` 的作用域：
 * 它是进程级的（满足"活过页面"），同时与 `AppScope` 的语义彻底分开，
 * 将来谁想收紧其中一个的约束，都不会误伤另一个。
 *
 * # 为什么不需要前台服务
 * Android 14 起后台启动前台服务受限，而这里的下载**永远由用户在界面上点出来**
 * （他就在应用里），完成时间通常在几秒量级。为一个瞬时任务挂一条常驻通知，
 * 代价（通知噪音、权限、FGS 类型申明）远大于收益。
 * 进程被杀怎么办：下载留下的 `.part` 文件在下次启动时由 [cleanup] 清掉，
 * 用户重新点一次即可 —— 这是个可以接受、且**状态明确**的失败。
 *
 * # 用法
 * ```kotlin
 * UpdateEngine.install(context, BuildConfig.UPDATE_MANIFEST_URLS)  // Application.onCreate
 * engine.state.collect { ... }                                    // 界面观察
 * engine.check() / engine.download() / engine.requestInstall()     // 用户动作
 * ```
 */
class UpdateEngine private constructor(
    private val appContext: Context,
    initialManifestUrls: List<String>,
) {

    /**
     * 清单镜像列表，按优先级排（raw 最实时 → jsDelivr → fastly）。
     *
     * ## 为什么是列表（PiliPlus 项目的实测教训）
     * 单一镜像单点故障：raw 国内直连常被墙，jsDelivr 对分支引用有小时级缓存
     * 且 purge 不保证生效。任一镜像可达就拿到结果——这是把"更新检查偶尔失败"
     * 变成"几乎不失败"的唯一便宜手段。
     *
     * ⚠️ 但"任一可用"不等于"首个可用就用"：各镜像的缓存互相独立，陈旧的那一个
     * 会安静地给出旧版本号。取值规则见 [check] 里的长注释（取 max）。
     */
    private val manifestUrls: MutableList<String> =
        initialManifestUrls
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("about:blank") }
            .toMutableList()

    /** 本次检查实际成功的那一个镜像。下载 resolveUrl 相对路径时以它为基准。 */
    @Volatile
    private var activeManifestUrl: String = manifestUrls.first()

    /**
     * 当前生效的清单地址（主镜像或调试覆盖值）。
     *
     * 是 `var` 而不是构造函数里的 `val`，只为了给冒烟留一个覆盖入口
     * （见 [overrideManifestUrlForTest]）—— 生产路径永远只有编译期的那一组值。
     */
    val manifestUrl: String get() = activeManifestUrl

    /**
     * 更新自己的作用域。用 `NetworkDispatchers.io` 而不是裸 `Dispatchers.IO`：
     * 它把并发压到 4，和 SMB / WebDAV / 分享服务端共用同一个"别把家庭带宽打满"的池子。
     */
    private val scope = CoroutineScope(SupervisorJob() + NetworkDispatchers.io)

    private val verifier = ApkVerifier(appContext)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** 当前状态。界面订阅它，不需要自己维护第二份。 */
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 已安装版本（进程生命周期内不变：覆盖安装会让进程重启）。 */
    val installedVersion: InstalledVersion by lazy { AppVersions.read(appContext) }

    /** 检查通过、等待下载的那一份。用 `@Volatile`：写在 IO 线程，读在主线程。 */
    @Volatile
    private var pendingManifest: UpdateManifest? = null

    @Volatile
    private var pendingFile: UpdateApkFile? = null

    /** 下载目录：私有 cacheDir，不放外部存储（不申请任何新权限）。 */
    private val updateDir: File get() = File(appContext.cacheDir, "update")

    // ── 动作 ───────────────────────────────────────────────────────────────

    /**
     * 拉清单、比版本。
     *
     * 检查中/下载中重复点不会叠加请求（直接返回）。这不是"防抖美化"：
     * 两路并发检查会让后到的结果覆盖先到的，用户看到的状态会来回跳。
     */
    fun check() {
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) {
            MimiLog.d(TAG, "已在检查/下载中，忽略重复的检查请求")
            return
        }
        // 清掉上一次的结果：如果这一次检查失败，`retry()` 不该拿着**上一轮**的
        // 清单去下载（那会启动一个与当前失败无关的动作）。
        pendingManifest = null
        pendingFile = null
        _state.value = UpdateState.Checking
        MimiLog.i(TAG, "检查更新：镜像 ${manifestUrls.size} 个，首选 $activeManifestUrl")
        scope.launch {
            try {
                // 多镜像取值：**取所有镜像里 versionCode 最大的那一份**。
                //
                // 成功判据是"parse 通过"而不是"HTTP 200"——镜像被墙时可能返回
                // 错误页/空体，那些都必须当成"这个镜像失败"落到下一个。
                //
                // ## 为什么不"首个成功就用"（M7.21 修正）
                // 镜像过期只会让版本号**偏小**，永远不会偏大，所以取 max 恒安全。
                // 与之相对，"首个成功就用"有一个致命的失败方向：
                // `fastly.jsdelivr.net` 与 `cdn.jsdelivr.net` 是两套独立缓存，purge
                // 只清后者；前者能滞留旧清单 12 小时（s-maxage=43200 实测）。一旦
                // 前两个镜像瞬时失败而落到它，客户端拿着旧清单得出"已是最新版本"
                // ——一个看起来完全正常的错误答案，不报错、不留痕，用户被明确
                // 告知"没有新版"而新版其实已发布。这种失败模式必须从协议层面堵掉。
                //
                // ## 代价控制
                // 只要任一镜像已经给出"有新版本"，立刻收手：其余镜像最多同版本或
                // 更旧，多问一次只是白等一次超时。只有当结论是"已是最新"时才把
                // 剩下的镜像都问一遍——差异恰恰只可能出现在这个方向上。
                val installed = installedVersion.versionCode
                var lastError: UpdateException? = null
                val candidates = ArrayList<MirrorManifest>(manifestUrls.size)
                for (url in manifestUrls) {
                    val fetched = try {
                        UpdateManifestCodec.parse(UpdateTransport.fetchManifestText(url))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UpdateException) {
                        MimiLog.w(TAG, "镜像失败，换下一个：$url（${e.message}）")
                        lastError = e
                        null
                    } catch (t: Throwable) {
                        MimiLog.w(TAG, "镜像失败，换下一个：$url（${t.message}）")
                        lastError = UpdateException("清单拉取失败：${t.message ?: t.javaClass.simpleName}", t)
                        null
                    }
                    if (fetched == null) continue
                    val bestSoFar = ManifestMirrors.newest(candidates)
                    if (bestSoFar != null && fetched.versionCode > bestSoFar.manifest.versionCode) {
                        // 前一个镜像给的是旧清单——正是那个"看起来正常的错误答案"的现场。
                        MimiLog.w(
                            TAG,
                            "镜像 $url 的清单更新（${bestSoFar.manifest.versionCode} → " +
                                "${fetched.versionCode}），${bestSoFar.url} 的内容是陈旧的",
                        )
                    }
                    candidates += MirrorManifest(url, fetched)
                    if (fetched.versionCode > installed) break
                }
                val chosen = ManifestMirrors.newest(candidates)
                if (chosen == null) {
                    // 全部镜像都失败：把**最后一个**镜像的具体原因报出去（DNS 失败、
                    // 超时……），前面只垫一句"共试了几个"。合并成一条，界面一行放得下。
                    throw UpdateException(
                        "检查更新失败（已尝试 ${manifestUrls.size} 个镜像）：${lastError?.message ?: "原因不明"}",
                        lastError,
                    )
                }
                val manifest = chosen.manifest
                activeManifestUrl = chosen.url
                when (
                    val decision = UpdatePlanner.decide(
                        manifest = manifest,
                        installedVersionCode = installed,
                        supportedAbis = Build.SUPPORTED_ABIS.toList(),
                    )
                ) {
                    is UpdatePlanner.Decision.UpToDate -> {
                        pendingManifest = null
                        pendingFile = null
                        MimiLog.i(TAG, "已是最新（本地 $installed，远端 ${manifest.versionCode}）")
                        _state.value = UpdateState.UpToDate(decision.manifest.versionName)
                    }

                    is UpdatePlanner.Decision.Available -> {
                        pendingManifest = decision.manifest
                        pendingFile = decision.file
                        MimiLog.i(
                            TAG,
                            "发现新版本 ${decision.manifest.versionName}(${decision.manifest.versionCode})，" +
                                "包 ${decision.file.abi} ${decision.file.size} bytes",
                        )
                        _state.value = UpdateState.Available(decision.manifest, decision.file)
                    }
                }
            } catch (e: CancellationException) {
                // ⚠️ 必须原样抛出（M4.1 踩过的坑：`catch (Throwable)` 把取消当成失败，
                // 于是"用户离开页面"会弹出一条指向不存在问题的错误提示）。
                throw e
            } catch (e: UpdateException) {
                MimiLog.w(TAG, "检查更新失败：${e.message}")
                _state.value = UpdateState.Failed(e.message ?: "检查更新失败")
            } catch (t: Throwable) {
                MimiLog.e(TAG, "检查更新异常", t)
                _state.value = UpdateState.Failed(t.readableReason())
            }
        }
    }

    /**
     * 下载并做三重校验。
     *
     * 成功后进入 [UpdateState.Ready]（或 [UpdateState.NeedInstallPermission]），
     * 校验失败则**删除文件**并把原因写进 [UpdateState.Failed]。
     */
    fun download() {
        if (_state.value is UpdateState.Downloading) return
        val manifest = pendingManifest ?: return
        val file = pendingFile ?: run {
            _state.value = UpdateState.Failed("没有可下载的更新包，请重新检查更新")
            return
        }
        _state.value = UpdateState.Downloading(0)
        scope.launch {
            try {
                val url = UpdatePlanner.resolveUrl(manifestUrl, file.url)
                // 文件名取自 URL 的最后一段，但**必须清掉路径分隔符与查询串**：
                // 清单里的 url 是外部输入，"../../foo" 这类值会让文件被写到 cacheDir 之外。
                val apk = File(updateDir, safeApkName(url))
                MimiLog.i(TAG, "开始下载 $url")
                // 进度节流：**不是**为了省事，而是为了让界面可用。
                //
                // 传输层是按"整数百分比变化"回调的，一个 6 MB 的包在局域网上 1~2 秒下完，
                // 那就是每秒 ~100 次 UI 更新；而每次更新都会让更新行重新测量、
                // 让进度条重绘、让 `uiautomator dump` 拿不到稳定快照（实测：下载期间
                // 快照反复失败，而用户看到的是"进度条在抖"）。
                // 100 ms 一次对人眼已经是连续动画。
                //
                // 100% 一定要上报（否则界面会停在 99%）。
                var lastReportAt = 0L
                var lastQuarter = 0
                UpdateTransport.download(
                    url = url,
                    target = apk,
                    expectedSize = file.size,
                    onProgress = { percent ->
                        val now = SystemClock.elapsedRealtime()
                        if (percent >= 100 || now - lastReportAt >= PROGRESS_MIN_INTERVAL_MS) {
                            lastReportAt = now
                            _state.value = UpdateState.Downloading(percent)
                        }
                        // 每跨过 25% 记一条：四次日志既不刷屏，又足以在事后证明
                        // "进度确实在推进"（界面上那一秒级的进度条是抓不住的）。
                        val quarter = percent / 25
                        if (quarter > lastQuarter) {
                            lastQuarter = quarter
                            MimiLog.i(TAG, "下载进度 $percent%")
                        }
                    },
                )

                when (val result = verifier.verify(apk, manifest, file)) {
                    is ApkVerifier.Result.Ok -> {
                        MimiLog.i(TAG, "三重校验通过，更新包就绪")
                        _state.value = if (needsInstallPermission()) {
                            UpdateState.NeedInstallPermission(apk, manifest)
                        } else {
                            UpdateState.Ready(apk, manifest)
                        }
                    }

                    is ApkVerifier.Result.Fail -> {
                        // 校验失败必须删文件：留着它只会让下次"重试"从一个可疑的文件开始。
                        runCatching { apk.delete() }
                        MimiLog.w(TAG, "校验失败：${result.reason}")
                        // 直接用校验器给的句子：它已经说明了是哪一道没通过、期望什么、
                        // 实际是什么。外面再套一句"安装包校验失败："只会让消息更长而更不具体。
                        _state.value = UpdateState.Failed(result.reason)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                MimiLog.w(TAG, "下载失败：${e.message}")
                _state.value = UpdateState.Failed(e.message ?: "下载失败")
            } catch (t: Throwable) {
                MimiLog.e(TAG, "下载异常", t)
                _state.value = UpdateState.Failed(t.readableReason())
            }
        }
    }

    /**
     * 重试上一次失败的那一步。
     *
     * ## 为什么不让界面直接"重跑检查"
     * 失败可能发生在两个不同的地方：**拉清单**（网络/DNS/服务器）与
     * **下载或校验**（传输/文件）。从"手上还留着什么"就能分清：
     * 清单与包信息还在 → 是后一步失败，直接重下；两手空空 → 是前一步失败，重新检查。
     *
     * 让界面一律调 `check()` 也能跑通，代价是下载失败后要**多点一次**
     * （重新检查 → 发现新版本 → 再点下载），而"校验失败"恰恰是最需要快速重试的场景。
     */
    fun retry() {
        if (pendingManifest != null && pendingFile != null) {
            MimiLog.i(TAG, "重试：直接重新下载")
            download()
        } else {
            MimiLog.i(TAG, "重试：重新检查更新")
            check()
        }
    }

    /**
     * 引导系统安装器。
     *
     * ## 两条分路（不是可选优化，是必须）
     * Android 8（API 26）起，安装未知来源应用要用户**一次性授权**。没有这条授权时
     * 直接抛 `ACTION_VIEW` 什么也不会发生（某些 ROM 上甚至静默失败），用户只会觉得
     * "点了「立即安装」没反应"。所以缺授权时先去系统设置页，回来再点一次。
     *
     * API 26 以下不存在这个开关（未知来源是一揽子设置），直接弹安装器即可 ——
     * 那正是老设备的既有行为。
     *
     * ## 为什么用应用 Context + NEW_TASK
     * 引擎是进程级的，不该持有 Activity（会泄漏）。`NEW_TASK` 让系统安装器
     * 从自己的任务栈启动；系统安装器本身会盖到前台，所以观感上没有区别。
     */
    fun requestInstall() {
        val apk = when (val current = _state.value) {
            is UpdateState.Ready -> current.apk
            is UpdateState.NeedInstallPermission -> current.apk
            else -> {
                MimiLog.d(TAG, "当前状态没有可安装的包，忽略安装请求")
                return
            }
        }
        if (!apk.exists()) {
            _state.value = UpdateState.Failed("更新包已不在设备上（可能被系统清理了缓存），请重新下载")
            return
        }

        if (needsInstallPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { appContext.startActivity(intent) }.isSuccess
            if (!opened) {
                // 个别 ROM 没有这个 Activity。明确说出来，好过用户对着一个没反应的按钮。
                _state.value = UpdateState.Failed(
                    "无法打开系统授权页，请在「设置 → 应用 → 密密文件管理 → 安装未知应用」中手动允许后重试",
                )
            } else {
                MimiLog.i(TAG, "已跳转系统「安装未知应用」授权页")
            }
            return
        }

        // FileProvider 的 authority 约定：`<applicationId>.fileprovider`。
        // 应用清单里声明的就是 `${applicationId}.fileprovider`，而运行时
        // `packageName` 恒等于 `applicationId`（debug 包的 `.debug` 后缀两边都带上），
        // 所以这里拼出来的与清单里的是同一个。
        val uri = try {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
        } catch (t: Throwable) {
            MimiLog.e(TAG, "生成安装包 URI 失败", t)
            _state.value = UpdateState.Failed("无法把更新包交给系统安装器：${t.message ?: t.javaClass.simpleName}")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure {
                MimiLog.e(TAG, "弹出系统安装器失败", it)
                _state.value = UpdateState.Failed("无法打开系统安装器：${it.message ?: it.javaClass.simpleName}")
            }
    }

    /**
     * 清掉下载目录里的全部残留。
     *
     * 在**进程启动时**调一次。除了上一次运行可能被杀在下载中途留下的 `.part`，
     * 这里也会清掉**下载完成但用户没装**的那个包 —— 这是刻意的：
     *
     *  · "更新包就绪"是**内存里的状态**，进程一没就没了。留着一个孤立的 apk
     *    只会让"这个文件还对应现在那份清单吗"变成一个需要判断的问题，
     *    而它带来的收益只是"少下一次 6 MB"；
     *  · 反过来，重下一次的成本很低，而且清单与包的配套关系是**当场**成立的，
     *    不需要维护任何跨会话的一致性。
     *
     * 结论：跨会话保留一个待安装的包，换不来什么，却引入一整类"陈旧文件"的问题。
     */
    fun cleanup() {
        val removed = runCatching {
            var count = 0
            updateDir.listFiles()?.forEach { if (it.delete()) count++ }
            count
        }.getOrDefault(0)
        if (removed > 0) MimiLog.i(TAG, "已清理 $removed 个更新下载残留")
    }

    /**
     * 临时覆盖清单地址 —— **只给冒烟脚本用**。
     *
     * ## 为什么守卫写在方法里，而不是让调用方自己注意
     * 一个"能改更新地址"的运行期入口，如果生效于正式包，就等于把更新链路
     * 变成可被劫持的：任何能启动本应用某个带 extra 的组件的人，都能把它指向自己的
     * 清单。虽然签名校验那一关仍然挡得住伪造的包，但"能力不该存在"和
     * "能力存在但会被拦住"是两件事，前者更省心。
     *
     * 所以这里**自己**检查 `FLAG_DEBUGGABLE`：非 debug 构建直接拒绝并记一条警告。
     * 把守卫放在 API 内部（而不是文档里写一句"请只在 debug 调用"）是
     * **构造上正确**：调用方忘了判断也不会留下缺口。
     *
     * 覆盖时会把状态与已下载的包一并清掉 —— 结果是针对旧地址的，
     * 留着只会让人误判（"明明改了地址怎么还显示有更新"）。
     *
     * @return 是否真的覆盖成功（非 debug 构建返回 false）
     */
    fun overrideManifestUrlForTest(url: String): Boolean {
        val debuggable =
            (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            MimiLog.w(TAG, "拒绝在非 debug 构建上覆盖清单地址：$url")
            return false
        }
        if (url.isBlank()) return false

        manifestUrls.clear()
        manifestUrls.add(url.trim())
        activeManifestUrl = url.trim()
        pendingManifest = null
        pendingFile = null
        runCatching { updateDir.listFiles()?.forEach { it.delete() } }
        _state.value = UpdateState.Idle
        MimiLog.i(TAG, "清单地址已被调试入口覆盖：$url")
        return true
    }

    private fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()

    /**
     * 从下载地址推出一个安全的本地文件名。
     *
     * ## 为什么必须过滤
     * `url` 来自清单（**外部输入**）。直接拿最后一段当文件名的话：
     *  · `../../evil.apk` 会把文件写到 `cacheDir` 之外；
     *  · `a.apk?token=x` 会得到一个带 `?` 的怪名字（某些文件系统上还写不进去）；
     *  · 空串或 `.` / `..` 会直接抛异常或指向目录。
     * 这些都不是"理论上的攻击"——清单是那个可以被替换的文件之一。
     */
    private fun safeApkName(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val cleaned = last.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')
        return if (cleaned.isEmpty() || !cleaned.endsWith(".apk", ignoreCase = true)) {
            "update.apk"
        } else {
            cleaned
        }
    }

    private fun Throwable.readableReason(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        private const val TAG = "MimiUpdate"

        /**
         * 进度上报的最小间隔（毫秒）。
         *
         * 取值理由：100 ms ≈ 10 fps 的进度更新，人眼已经读成"连续"；
         * 再密只是把主线程用来重绘，而用户看不出差别。
         */
        private const val PROGRESS_MIN_INTERVAL_MS = 100L

        /**
         * 进程级实例。在 `Application.onCreate` 里装配一次，之后全局可用。
         *
         * 用 `@Volatile` + 双重检查：装配发生在主线程，而读取可能来自任意线程
         * （界面订阅状态时）。漏掉 volatile 的后果是"偶尔读到 null"，
         * 而那会以"设置页上按钮没反应"的形式出现，很难联想到这里。
         */
        @Volatile
        private var instance: UpdateEngine? = null

        /**
         * 装配引擎。**幂等**：重复调用直接返回已有实例。
         *
         * 不重建是有意的：重建会把正在进行的下载状态丢掉，而"进程启动装配一次"
         * 本来就是唯一需要的用法（清单地址是编译期常量，不存在运行时切换）。
         *
         * @param manifestUrls 清单镜像地址，按优先级排序（见类注释的多镜像策略）
         */
        fun install(context: Context, manifestUrls: List<String>): UpdateEngine {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val engine = UpdateEngine(context.applicationContext, manifestUrls)
                // 清上一次运行留下的下载残留
                engine.cleanup()
                instance = engine
                return engine
            }
        }

        fun current(): UpdateEngine? = instance
    }
}
