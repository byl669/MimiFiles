package com.miyuetech.mimifiles.core.preview

import android.content.Context
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「预览落盘缓存」—— 把远端文件按需落到**应用私有缓存**，之后所有需要随机访问的
 * 平台组件（`BitmapRegionDecoder`、`PdfRenderer`、外部应用）都直接对着这个文件干活。
 *
 * ## 为什么需要它（明明有 `SeekableSource`）
 * `SeekableSource` 解决的是**我们自己**的随机读。但有些平台能力只吃
 * `File` / `FileDescriptor` / `String path`，喂不进我们的抽象：
 *
 *  - `BitmapRegionDecoder.newInstance(InputStream, …)` 在 AOSP 实现里会把**整个流**
 *    读进内存——拿它去分块解一张 8000×6000 的照片，等于先把 50 MB 全读一遍，
 *    正好和"分块"的初衷相反。换成 `newInstance(path)` 才是真正的按块读。
 *  - `PdfRenderer` 需要一个可 seek 的 `ParcelFileDescriptor`。
 *
 * 所以这里做的是"把网络文件变成可 seek 的本地文件"，而且：
 *
 * ## 三条硬规则
 *  1. **有容量上限**（默认 256 MB，随可用空间自适应收缩）。超限的文件**不落盘**，
 *     调用方拿到 `null` 后走"降采样整图"路线——那条路同样不会 OOM，
 *     只是放大后不如分块解码清晰。**绝不会为了预览把设备写满。**
 *  2. **按内容指纹命名**（[ThumbnailKey] 同源规则：路径 + 大小 + mtime），
 *     文件被替换后旧缓存自然失效，不会出现"改了文件还看旧内容"。
 *  3. **先写 `.tmp` 再改名**。中途被杀进程不会留下半截文件被后续预览当成完整文件用
 *     ——那会表现为"PDF 打开一半变白"这种极难复现的问题。
 *
 * ## 为什么不用 `downloads` 或外部目录
 * 落缓存是为了**能删**。放 `cacheDir` 意味着系统在空间紧张时可以自行回收，
 * 我们也有明确的所有权（`clear()`）。放到用户可见目录反而要处理"用户删了怎么办"。
 */
class PreviewCache(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val appContext = context.applicationContext

    private val dir = File(appContext.cacheDir, DIR_NAME)

    /**
     * 「交给外部应用」用的暂存目录。与 [dir] **分开**，理由有三：
     *
     *  1. **命名规则不同**。[dir] 里的文件叫 `<指纹>.bin`，因为消费者是解码器，
     *     它只认内容不认名字；而外发的消费者（尤其是系统安装器）会看扩展名。
     *     APK 的文件名里没有 `.apk` 时，部分 ROM 的安装器会拒收。
     *  2. **容量口径不同**。[dir] 是"预览"缓存，256 MB 上限就够；
     *     而安装包动辄上百兆，用同一个上限会变成"大一点的 APK 永远装不上"。
     *  3. **寿命不同**。[dir] 的文件随时可以被 LRU 淘汰（反正能重新生成）；
     *     而暂存文件在**外部应用读它的过程中**不能被删——那个窗口由外部应用决定，
     *     我们只能用 TTL 兜底（见 [SHARE_TTL_MS]）。
     *
     * 两个目录各自算 `free/6` 的上限，最坏合起来占掉可用空间的三分之一。
     * 这是刻意接受的上限：单看任意一个目录，它都不会把设备写满。
     */
    private val shareDir = File(appContext.cacheDir, SHARE_DIR_NAME)

    private val lock = Mutex()

    /** 暂存目录自己的锁，与 [lock] 分开——否则一次 256 MB 的图片落盘会把安装包卡住。 */
    private val shareLock = Mutex()

    /**
     * 让 [path] 变成本地可读文件。
     *
     * @param expectedSize 已知大小（未知传 `-1`）。用于判断是否会超出容量上限。
     * @return 本地文件；超出上限、读取失败或不可用时返回 `null`。
     */
    suspend fun materialize(
        path: FsPath,
        expectedSize: Long = -1L,
        registry: FileSystemRegistry = FileSystems.registry,
    ): File? = withContext(PreviewDispatchers.io) {
        // 本机文件本来就是"可 seek 的本地文件"，复制一份纯属浪费磁盘与时间。
        if (path.scheme == LOCAL_SCHEME) {
            val direct = File(path.path)
            return@withContext if (direct.isFile) direct else null
        }
        if (expectedSize > effectiveCap()) {
            MimiLog.d(TAG, "文件超出预览缓存上限，不落盘：$path（${expectedSize / 1024 / 1024}MB）")
            return@withContext null
        }

        val key = ThumbnailKey.of(path, expectedSize, 0L)
        val target = File(dir, "$key.bin")
        lock.withLock {
            if (target.isFile && target.length() > 0L) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val cap = effectiveCap(maxBytes)
            fetch(path, target, cap, registry)
            trim(dir, cap)
            if (target.isFile && target.length() > 0L) target else null
        }
    }

    /**
     * 让 [path] 变成本地文件**并保留原文件名**，供"交给外部应用"使用。
     *
     * ## 为什么外发前必须先落地
     * 外发走的是我们自己的 `DocumentsProvider`（`content://`），而 provider 对远端文件
     * 是**按需流式读**的。这对"看一眼"的消费者没问题，但系统安装器是另一回事：
     * 它会把整个包顺序读完并算摘要，中间还会做若干次随机读去解析 `AndroidManifest`/
     * 签名块。每一次随机读在远端都是一次网络往返，于是 30 MB 的包变成上千次往返，
     * 安装器卡在「正在准备安装应用」直到自己的 session 超时——**而网络其实没断**。
     *
     * 落地之后安装器读的是本地文件，几毫秒读完。代价是"打开安装界面之前多等一次下载"，
     * 但那段时间是我们自己的、可以显示进度、可以失败重试的。
     *
     * ## 为什么不给外部应用直接传 `file://`
     * `targetSdk ≥ 24` 上 `file://` 会触发 `FileUriExposedException`。所以落地之后
     * **仍然**用 `content://` 外发，只是这个 documentId 指向本地路径
     * （`local:///<cacheDir>/external-staging/…`），provider 读它是纯本地 IO。
     *
     * @return 本地文件；超限、读取失败或不可用时返回 `null`。
     *
     * ## 失败时调用方**不**降级
     * 这条路上的消费者是系统安装器，而它对随机读的容忍度极低（见上）。把注定超时的
     * `content://` 交给它，用户的观感是"卡在『正在准备安装应用』几分钟然后失败"——
     * 比一句明确的"安装包下载失败"糟糕得多。所以 `null` 的语义是**拦住**，
     * 不是"换个慢路子再来"。文案与分支必须一致（M6 实测抓到过一条撒谎的日志）。
     */
    suspend fun materializeForShare(
        path: FsPath,
        expectedSize: Long = -1L,
        registry: FileSystemRegistry = FileSystems.registry,
    ): File? = withContext(PreviewDispatchers.io) {
        // 本机文件（含"已经落地过一次"的暂存文件）本来就是本地可读的，复制纯属浪费。
        if (path.scheme == LOCAL_SCHEME) {
            val direct = File(path.path)
            return@withContext if (direct.isFile) direct else null
        }
        val cap = effectiveCap(SHARE_MAX_BYTES)
        if (expectedSize > cap) {
            MimiLog.d(TAG, "文件超出暂存上限，不落地：$path（${expectedSize / 1024 / 1024}MB）")
            return@withContext null
        }

        // 键里带内容指纹，改名后旧缓存自然失效（与 [materialize] 同一套规则），
        // 后面再接上净化过的原名，把扩展名留给外部的消费者。
        val key = ThumbnailKey.of(path, expectedSize, 0L)
        val target = File(shareDir, "$key-${safeName(path.displayName)}")
        shareLock.withLock {
            if (target.isFile && target.length() > 0L) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            if (!shareDir.isDirectory) shareDir.mkdirs()
            fetch(path, target, cap, registry)
            purgeExpiredShares()
            trim(shareDir, cap)
            if (target.isFile && target.length() > 0L) target else null
        }
    }

    /** 仅测试与设置页"清理缓存"使用。 */
    suspend fun clear() = withContext(PreviewDispatchers.io) {
        lock.withLock {
            runCatching { dir.deleteRecursively() }
        }
        shareLock.withLock {
            runCatching { shareDir.deleteRecursively() }
        }
    }

    fun usageBytes(): Long =
        runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                shareDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    // ── 内部 ────────────────────────────────────────────────────────────

    /**
     * 把远端文件抓取到 [target]（先写 `.tmp` 再改名）。
     *
     * 抽出来给两条落盘路由（预览缓存 / 外发暂存）共用：它们的差别只在**命名与上限**，
     * 而"先 tmp 再改名""边读边校验上限"这些约束是一模一样的。写两遍迟早只改一处。
     *
     * ## 为什么要**整段重来一次**
     * 这是一次长时间、单句柄的连续读（暂存 240 MB 的安装包就是几百次 READ）。
     * SMB 连接在中途断掉是常态（服务端空闲断连、Wi-Fi 抖动、smbj 把任何传输层异常
     * 当致命错误关连接），而**连接级的断连在传输层内部修不好**：
     * `SmbjTransport.SmbjSeekableSource` 的 `reopen()` 用的还是那条已经死掉的
     * `DiskShare`，必然再失败一次（见那里的注释，这个空转是 M6 实测抓到的）。
     * 真正能自愈的是**上一层**——`SmbFileSystem.transport()` 会 `isAlive` 判死并重建会话，
     * 所以只要把整段读重新发起一次，第二次就会走一条新连接。
     *
     * ## ⚠️ 这段注释曾经写错过，把踩坑过程留在这里
     * 第一版这里写着"这条重试是**确定性**的"，依据是"`PacketReader` 抛错后必然走到
     * `Connection.close()` → `transport.disconnect()` → `isConnected` 变 false"——
     * 那是**从字节码推出来的**，并没有实测。端到端一跑就红了：
     * ```
     * 落盘失败，重试一次（期望走一条新连接）
     * reuse transport#72140100 for share 'SHARE'      ← 重试仍复用了旧连接
     * session-fatal SMBRuntimeException: DiskShare has already been closed
     * ```
     * 真相是 `Connection.close(false)` 的第一件事 `Pooled.release()` 在引用计数
     * 未归零时**直接 return**，`disconnect()` 根本没执行 —— 所以光靠"重试一次"
     * 救不回来。**"重试"能不能自愈，取决于"判活"判得对不对**，两者是耦合的：
     * 这个重试只有在 `SmbjTransport.isAlive` 补上 `share.isConnected` 之后才真正成立。
     *
     * 留这段不是自我检讨，是因为**下次有人看到"重试一次"时会问同样的问题**
     * ——"凭什么第二次就能拿到新连接"。答案在 `isAlive` 那一处，不在这一处。
     *
     * 代价是明明白白有界的：重试只有一次，且总量被 [cap] 卡住（暂存 2 GB / 预览缓存
     * 由 `maxBytes` 决定），最坏情况是把已经读到的字节再读一遍。
     * 收益对得上：用户的原话是"从 SMB 装 APK 卡在正在准备安装应用"，
     * 而失败点正是这里——一次可自愈的重试比一句"下载失败"有用得多。
     *
     * @return 是否真的产出了一个非空的 [target]。
     */
    private suspend fun fetch(
        path: FsPath,
        target: File,
        cap: Long,
        registry: FileSystemRegistry,
    ): Boolean {
        for (attempt in 0..1) {
            if (fetchOnce(path, target, cap, registry)) return true
            if (attempt == 0) {
                MimiLog.i(TAG, "落盘失败，重试一次（期望走一条新连接）：$path")
            }
        }
        return false
    }

    /** 单次尝试。失败时记一条日志并清掉 `.tmp`。 */
    private suspend fun fetchOnce(
        path: FsPath,
        target: File,
        cap: Long,
        registry: FileSystemRegistry,
    ): Boolean {
        val fileSystem = registry.resolveOrNull(path) ?: return false
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            target.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
            fileSystem.openRead(path).use { source ->
                tmp.outputStream().buffered(BUFFER).use { out ->
                    val stream = source.stream(0)
                    val buffer = ByteArray(BUFFER)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        total += read
                        // 边写边校验上限：`expectedSize` 可能不准（-1 或过期），
                        // 不能只靠开头的判断就相信"这个文件不会很大"。
                        if (total > cap) {
                            error("文件超过落盘上限（已读 ${total / 1024 / 1024}MB）")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            // ⚠️ 这里**不**说"降级为流式读取"——没有降级这回事，调用方拿到 null 会拦住
            // 外发。失败原因要原样带上（`it`），因为"读了一半断连"和"容量超限"
            // 是完全不同的两件事，只留一句笼统的"失败"会让下一次排查从零开始。
            MimiLog.w(TAG, "落盘失败：$path", it)
            tmp.delete()
        }.isSuccess && target.isFile && target.length() > 0L
    }

    /** 按 mtime 从旧到新删到容量以内。 */
    private fun trim(target: File, cap: Long) {
        val files = runCatching {
            target.listFiles()?.filter { it.isFile } ?: return
        }.getOrNull() ?: return
        var total = files.sumOf { it.length() }
        if (total <= cap) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= cap) break
            val len = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= len
        }
    }

    /**
     * 删掉过期的暂存文件。
     *
     * 暂存文件**不能在发起外发之后立刻删**：那一刻外部应用才刚拿到 Uri，
     * 真正的读取发生在它自己的进程与时机里（安装器尤其晚，要先弹确认页）。
     * 我们能确定的上界只有"用户不会隔天还在装同一个包"，所以用 TTL 兜底，
     * 再加上每次落新文件时的容量裁剪。
     */
    private fun purgeExpiredShares() {
        val now = System.currentTimeMillis()
        runCatching {
            shareDir.listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() > SHARE_TTL_MS) file.delete()
            }
        }
    }

    private fun effectiveCap(privateMax: Long = maxBytes): Long {
        val free = runCatching { appContext.cacheDir.usableSpace }.getOrDefault(privateMax)
        return privateMax.coerceAtMost((free / 6).coerceAtLeast(MIN_BYTES))
    }

    /**
     * 把任意文件名净化成"可以安全拼进路径、且保留扩展名"的形式。
     *
     * 为什么不留原样：远端文件名里可能有 `/`、`\`、控制字符，甚至 `..`
     * —— 直接 `File(shareDir, rawName)` 就是一次目录穿越。
     * 为什么取**尾部**而不是头部：扩展名在尾部，前面砍掉不心疼。
     */
    private fun safeName(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                append(if (ch.isLetterOrDigit() || ch in SAFE_CHARS) ch else '_')
            }
        }
        val trimmed = cleaned.trim().trimStart('.').trimEnd('.')
        val effective = trimmed.ifEmpty { "file" }
        return if (effective.length <= MAX_NAME_LENGTH) {
            effective
        } else {
            effective.takeLast(MAX_NAME_LENGTH)
        }
    }

    private companion object {
        const val TAG = "MimiPreviewCache"

        const val DIR_NAME = "preview-cache"

        const val SHARE_DIR_NAME = "external-staging"

        const val LOCAL_SCHEME = "local"

        /**
         * 落盘的读缓冲 = 1 MiB。
         *
         * 两个约束把它钉在这个值上：
         *  1. **不能小于 `BufferedSeekableSource` 的直读阈值**（窗口 / 4）。窗口是
         *     `min(1 MiB, 协商 maxReadSize)` 且被夹在 `[64 KiB, 1 MiB]`，所以阈值最大
         *     256 KiB；缓冲一旦低于它，`stream.read` 会掉进预读窗口那条路，变成
         *     "读 64 KiB 却拉 1 MiB 窗口"，对**顺序整读**是白拷贝。
         *  2. **不要大于一次 SMB2 READ 的真实上限**（1 MiB）。再大只会被传输层拆开，
         *     白白多占一份 IO 线程的内存。
         *
         * 于是这个值同时是"顺序整读的最优块"——而本类的两条落盘路径（预览缓存、
         * 外发暂存）都是纯顺序整读。
         */
        const val BUFFER = 1024 * 1024

        /** 默认上限 256 MB：够放几张 4K 照片或一份大 PDF，又不至于让缓存变成主要占用。 */
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024

        /**
         * 外发暂存的上限 2 GB。
         *
         * 之所以比预览缓存大一个数量级：这条路径上唯一的消费者是"用户主动点开的文件"，
         * 而其中最典型的就是安装包。给 256 MB 的后果是"大一点的 APK 永远装不上"，
         * 而且失败得很安静（退回流式外发 → 安装器超时）。真正的护栏是
         * [effectiveCap] 里的 `free/6`，那个才是"不把设备写满"的保证。
         */
        const val SHARE_MAX_BYTES = 2048L * 1024 * 1024

        const val MIN_BYTES = 32L * 1024 * 1024

        /** 暂存文件 6 小时后视为过期。 */
        const val SHARE_TTL_MS = 6L * 60 * 60 * 1000

        const val MAX_NAME_LENGTH = 96

        const val SAFE_CHARS = "._-+@ ()[]#&'!~,"
    }
}
