package com.miyuetech.mimifiles.core.fssmb

import android.os.SystemClock
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystem
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.FsNotSupportedException
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsCapability
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.network.NetworkDispatchers
import com.miyuetech.mimifiles.core.network.ParallelGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SMB 后端。
 *
 * ## 一个连接 = 可能多个共享 = 多个传输会话
 * `FsPath` 的第一段是**共享名**（`smb://nas-01/photos/2024` 里的 `photos`），
 * 而不是把共享名钉死在连接配置里。这样"一个 NAS 连接"就能访问它的全部共享，
 * 用户不必为每个共享各建一条连接（参考应用正是这么做的，用户会看到一长串重复项）。
 *
 * 代价是会话要按共享缓存：SMB2 的树连接（TREE_CONNECT）是 **per-share** 的，
 * 一个共享一个 `DiskShare`，不能复用。
 *
 * ## 并发闸门
 * 每个共享的会话上最多同时跑 [MAX_CONCURRENCY] 个请求。smbj 的 `DiskShare`
 * 在同一个树连接上并发发请求时，请求会共享 `SMB2FileId` 表与消息序号，
 * 并发数一高就容易撞 Oplock/序号问题。限到 2 既够用（一路元数据 + 一路数据），
 * 也把"用户乱点导致自伤"的可能性去掉。
 */
class SmbFileSystem internal constructor(
    private val profile: ConnectionProfile,
    /** 解密后的密码；`null` 表示匿名或不保存密码。**不得进入任何日志或持久化**。 */
    private val password: String?,
    private val io: CoroutineDispatcher = NetworkDispatchers.io,
) : FileSystem {

    override val scheme: String = SCHEME

    override val displayName: String get() = profile.displayName

    /**
     * 如实申报。
     *
     * 没有 `THUMBNAIL`（SMB 不提供缩略图，M3 的图片预览要自己解码）、
     * 没有 `WATCH`（ChangeNotify 在手机息屏后不可靠，不如不做）、
     * 没有 `SET_ATTRIBUTES`（改远端文件的只读/隐藏位是低频操作，M6 再说）。
     *
     * 定义在 companion 里，由 [SmbFileSystemResolver] 复用——两处各写一份必然会漂移，
     * 而"未连接时灰置菜单"和"已连接时灰置菜单"给出不同结果是最难查的一类 UI bug。
     */
    override val capabilities: Int = CAPABILITIES

    private val settings = SmbSettings.parse(profile.options)

    private val gate = ParallelGate(MAX_CONCURRENCY)

    private val connectMutex = Mutex()

    /**
     * 每个共享一条会话记录。
     *
     * ## 为什么不能只有一个"最后使用时间"
     * M6 之前这里是一个全局 `lastUsedAt` + 一个 `evictIfIdle()` 关掉**全部**会话。
     * 那个设计有一个只在真机上才暴露的后果：
     *
     * > 正在播 SMB 上的电影（读句柄已交出，但**读操作不经过 [transport]**，
     * > 所以全局 `lastUsedAt` 停在"打开那一刻"）→ 用户顺手点进另一个共享 →
     * > [evictIfIdle] 发现"空闲超过 60 秒" → **把正在播的那条会话也关了**。
     *
     * 现象是"看着看着突然黑屏重来"，且只在"边看边逛"时复现——正是 M6 真机反馈
     * 里"影片经常卡顿"的一个独立成因。修法是两件事：
     *  1. **时间戳按共享记**（[lastUsedAt]），而不是一个全局值；
     *  2. **交出句柄时记账**（[leases]），有未关闭句柄的会话**永不被空闲回收**。
     */
    private class Session(
        val transport: SmbTransport,
        var lastUsedAt: Long,
        /** 已交出、尚未关闭的读/写句柄数。> 0 表示"正被使用中"。 */
        var leases: Int = 0,
    )

    private val sessions = HashMap<String, Session>()

    /** 实际使用的方言（连接成功后才可知）。连接管理页用它显示"已降级至 SMB1"。 */
    @Volatile
    var activeDialect: String? = null
        private set

    // ── 只读路径 ────────────────────────────────────────────────────────

    /**
     * 挂载点。
     *
     * 返回的是"用户在连接里填的那个位置"（通常是共享名），显示名取连接名，
     * 于是侧栏看到的是「NAS-01」而不是「photo」——后者在多个 NAS 上都叫这个名字时会歧义。
     */
    override suspend fun roots(): List<FsEntry> {
        val root = profile.rootPath.trim('/')
        if (root.isEmpty()) return emptyList()
        val path = FsPath.of(scheme, profile.id, "/$root")
        return listOf(
            FsEntry(
                path = path,
                name = profile.displayName,
                kind = FsKind.DIRECTORY,
                isReadOnly = false,
            ),
        )
    }

    override suspend fun list(dir: FsPath): List<FsEntry> = withContext(io) {
        val (share, inner) = split(dir)
        val transport = transport(share)

        // 「两笔账」埋点（M1 定下的工程约定，见方案 §8.4.1）：
        // 网络后端这里的两笔账含义变了——`net` 是协议往返（服务端 + 链路），
        // `own` 是我们把 SmbDirEntry 映射成 FsEntry 的成本。将来"某个目录很慢"
        // 时，这一行日志能直接告诉我们是该换网络还是该改代码。
        val netStartedAt = SystemClock.elapsedRealtime()
        val raw = guard(share, dir) { transport.list(inner) }
        val netMs = SystemClock.elapsedRealtime() - netStartedAt

        val ownStartedAt = SystemClock.elapsedRealtime()
        val entries = ArrayList<FsEntry>(raw.size)
        for (item in raw) {
            entries += item.toFsEntry(dir.child(item.name))
        }
        val ownMs = SystemClock.elapsedRealtime() - ownStartedAt

        MimiLog.d(TAG, "list $dir -> ${entries.size} items | net=$netMs own=$ownMs ms")
        entries
    }

    override suspend fun stat(path: FsPath): FsEntry = withContext(io) {
        val (share, inner) = split(path)
        // stat 挂在共享根上时（inner 为空）不能问服务端：SMB 的 QUERY_INFO
        // 对"共享根本身"的表述在 smbj/jcifs 上不一致，直接按目录返回更稳。
        if (inner.isEmpty()) {
            return@withContext FsEntry(
                path = path,
                name = profile.displayName,
                kind = FsKind.DIRECTORY,
            )
        }
        val transport = transport(share)
        val entry = guard(share, path) { transport.stat(inner) }
        entry.toFsEntry(path)
    }

    override suspend fun exists(path: FsPath): Boolean = withContext(io) {
        val (share, inner) = split(path)
        if (inner.isEmpty()) return@withContext true
        val transport = transport(share)
        guard(share, path) { transport.exists(inner) }
    }

    // ── 读写 ────────────────────────────────────────────────────────────

    override suspend fun openRead(path: FsPath): SeekableSource = withContext(io) {
        val (share, inner) = split(path)
        if (inner.isEmpty()) throw FsIoException("cannot read a share root: $path")
        val transport = transport(share)
        val source = guard(share, path) { transport.openRead(inner) }
        // 从这里到 `close()` 之间，这条会话被"占用"：不允许被空闲回收悄悄关掉
        // （真机现象见 [Session] 的注释）。占用的记账与释放必须成对，成功与失败路径都要覆盖，
        // 所以释放写在包装器的 close() 里，而不是某条正常分支上。
        lease(share)
        return@withContext LeasedSeekableSource(source) { releaseLease(share) }
    }

    override suspend fun openWrite(
        path: FsPath,
        append: Boolean,
        expectedSize: Long,
    ): WriteSink = withContext(io) {
        val (share, inner) = split(path)
        if (inner.isEmpty()) throw FsIoException("cannot write a share root: $path")
        val transport = transport(share)
        val sink = guard(share, path) { transport.openWrite(inner, append, expectedSize) }
        lease(share)
        LeasedWriteSink(sink) { releaseLease(share) }
    }

    // ── 结构变更 ────────────────────────────────────────────────────────

    override suspend fun mkdir(path: FsPath) = withContext(io) {
        val (share, inner) = split(path)
        if (inner.isEmpty()) throw FsIoException("cannot create a share root: $path")
        val transport = transport(share)
        guard(share, path) { transport.mkdir(inner) }
    }

    override suspend fun delete(path: FsPath, recursive: Boolean) = withContext(io) {
        val (share, inner) = split(path)
        // 删共享根 = 删掉整个共享，这是不可逆且影响所有使用者的操作。
        // 明确拒绝，而不是"尽力而为"。
        if (inner.isEmpty()) throw FsIoException("refusing to delete a share root: $path")
        val transport = transport(share)
        guard(share, path) { transport.delete(inner, recursive) }
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = withContext(io) {
        val (share, inner) = split(path)
        if (inner.isEmpty()) throw FsIoException("cannot rename a share root: $path")
        val transport = transport(share)
        guard(share, path) { transport.rename(inner, newName) }
        path.parent?.child(newName) ?: throw FsIoException("cannot rename a share root: $path")
    }

    /**
     * 同后端内移动。
     *
     * 只在**同一共享内**走原生 rename（SMB2 的 SET_INFO 是原子的，零数据搬运）。
     * 跨共享时直接抛 [FsNotSupportedException]，交给 `FsOps` 用「复制 + 删除」中转——
     * 共享之间的移动本质就是跨后端，在这里偷偷实现一遍等于把 FsOps 的冲突策略、
     * 进度回调、自嵌套防护全部重写一次，而且两份实现必然漂移。
     */
    override suspend fun move(src: FsPath, destDir: FsPath): FsPath = withContext(io) {
        val (srcShare, srcInner) = split(src)
        val (destShare, destInner) = split(destDir)
        if (srcShare != destShare) throw FsNotSupportedException(scheme, "move across shares")

        val transport = transport(srcShare)
        val moved = guard(srcShare, src) { transport.move(srcInner, destInner) }
        if (!moved) throw FsNotSupportedException(scheme, "move")
        destDir.child(src.displayName)
    }

    /**
     * 同后端内复制。
     *
     * 优先用服务端复制（SMB2 COPYCHUNK，数据不过在手机内存里过一遍——
     * 复制 4 GB 视频时这是"秒级 vs 十分钟"的差别）。不支持就抛 [FsNotSupportedException]，
     * 由 `FsOps` 退化为 64 KB 缓冲的流式复制。
     *
     * 目录不做服务端复制：COPYCHUNK 只作用于文件，递归要自己走，
     * 而 `FsOps.copyTree` 已经做好了。
     */
    override suspend fun copy(src: FsPath, destDir: FsPath): FsPath = withContext(io) {
        val (srcShare, srcInner) = split(src)
        val (destShare, destInner) = split(destDir)
        if (srcShare != destShare) throw FsNotSupportedException(scheme, "copy across shares")

        val transport = transport(srcShare)
        val meta = guard(srcShare, src) { transport.stat(srcInner) }
        if (meta.isDirectory) throw FsNotSupportedException(scheme, "directory copy via server")

        val ok = guard(srcShare, src) { transport.copyServerSide(srcInner, destInner) }
        if (!ok) throw FsNotSupportedException(scheme, "server-side copy")
        destDir.child(src.displayName)
    }

    // ── 生命周期 ────────────────────────────────────────────────────────

    /** 连接被删除 / 用户手动断开时调用。 */
    fun close() {
        // 留一条带调用栈的痕迹：`DiskShare has already been closed` 这类错误只有在
        // "谁把它关了"可见之后才查得动（M2 实测踩过：第二次进 SMB 目录报这句，
        // 而缓存里明明还留着那个已关闭的对象）。
        //
        // ⚠️ 这个方法**无视占用计数**，是"全部关掉"的语义：它的两个调用点
        // （用户主动断开、会话被判定为已死）都明确要求整条连接消失。
        // 因此**不要**在"顺手回收一下"的场景里调它——那种场景请用 [evictIfIdle]
        // 或 [dropSession]，它们是尊重占用的。
        val snapshot = synchronized(sessions) {
            val busy = sessions.values.count { it.leases > 0 }
            val all = sessions.values.toList()
            sessions.clear()
            all to busy
        }
        val (all, busy) = snapshot
        MimiLog.d(
            TAG,
            "closing SMB sessions (count=${all.size}, busy=$busy) from ${callSite()}",
        )
        all.forEach { runCatching { it.transport.close() } }
        activeDialect = null
    }

    /** 取最近的若干栈帧，压成一行——只为回答"谁调的"，不需要完整栈。 */
    private fun callSite(): String = Throwable().stackTrace
        .drop(1)
        .take(6)
        .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    /** 供连接管理页展示"这条连接现在用的是哪个方言"。 */
    fun dialectLabel(): String? = activeDialect

    // ── 内部 ────────────────────────────────────────────────────────────

    /**
     * 取某个共享的会话，必要时建立。
     *
     * ## 两道保险，缺一不可（M2 实测补齐第二条）
     * 1. **空闲主动回收**：某条共享的会话距离**它自己**上次使用超过 [IDLE_TIMEOUT_MS]
     *    就关掉（[evictIfIdle]，只关空闲且未被占用的那些）。
     *    理由是 NAS 侧通常有十几分钟的空闲断连，而我们如果一直握着一条服务端已单方面
     *    关闭的连接，用户下一次操作会等到 TCP 超时才失败（十几秒），体验比"多一次握手"
     *    差得多（握手在局域网里是几十毫秒）。
     * 2. **取会话时检查存活**（[SmbTransport.isAlive]）：这一条是实测补上的。
     *    真正先把连接判死的**曾经**是 smbj 自己——M6 之前 `withSoTimeout` 被设成
     *    `settings.timeoutMs`（10 s），而 smbj 的 socket 读线程是把
     *    `SocketTimeoutException` 当**致命错误**处理的（`PacketReader` 收到任何
     *    `TransportException` 就 `Connection.handleError` → `close()`），
     *    于是**只要 10 秒没有入站报文，整条连接就自杀**。M6 已把 `soTimeout`
     *    恢复成 smbj 默认的 `0`（不限），所以这条路径不再由我们自己制造；
     *    但服务端侧的空闲断连、WiFi 掉线仍然会造出死会话，**isAlive 检查依旧必要**。
     */
    private suspend fun transport(share: String): SmbTransport {
        synchronized(sessions) { sessions[share] }?.let { existing ->
            if (existing.transport.isAlive) {
                existing.lastUsedAt = SystemClock.elapsedRealtime()
                MimiLog.d(TAG, "reuse transport#${existing.transport.hashCode()} for share '$share'")
                return existing.transport
            }
            // 会话已经死了（服务端空闲断连、WiFi 掉线、或曾经的 smbj soTimeout 自杀）。
            // **必须在这里丢掉重建，不能交出去**：交出去的结果必然是
            // `DiskShare has already been closed`，而用户看到的是
            // "网络目录打不开了、再点一次才好"（M2 实测：切走几十秒再切回，必失败一次）。
            MimiLog.i(
                TAG,
                "cached transport#${existing.transport.hashCode()} is dead, reconnecting share '$share'",
            )
            dropSession(share, reason = "isAlive 判死")
        }
        return connectMutex.withLock {
            synchronized(sessions) { sessions[share] }?.let { existing ->
                if (existing.transport.isAlive) {
                    existing.lastUsedAt = SystemClock.elapsedRealtime()
                    MimiLog.d(
                        TAG,
                        "reuse transport#${existing.transport.hashCode()} for share '$share' (after lock)",
                    )
                    return@withLock existing.transport
                }
                dropSession(share, reason = "isAlive 判死（锁内复核）")
            }
            evictIfIdle()
            val fresh = SmbConnector.open(
                authority = profile.id,
                host = profile.host,
                port = profile.port,
                shareName = share,
                username = profile.username,
                password = password,
                domain = profile.domain,
                settings = settings,
            )
            synchronized(sessions) {
                sessions[share] = Session(fresh, SystemClock.elapsedRealtime())
            }
            activeDialect = fresh.dialectLabel
            MimiLog.d(TAG, "new transport#${fresh.hashCode()} for share '$share'")
            fresh
        }
    }

    /** 丢掉**指定共享**的会话。 */
    private fun dropSession(share: String, reason: String) {
        val dropped = synchronized(sessions) { sessions.remove(share) }
        if (dropped == null) return
        MimiLog.d(
            TAG,
            "drop session for share '$share' ($reason, leases=${dropped.leases})",
        )
        runCatching { dropped.transport.close() }
    }

    /**
     * 回收空闲会话：**逐共享**判断，且**跳过仍被占用的**。
     *
     * 两个条件缺一不可：
     *  - 逐共享：一个全局时钟会把"正在播电影的那条会话"当成空闲（读操作不刷新它）。
     *  - 跳过占用：[leases] > 0 说明还有未关闭的读/写句柄挂在这条会话上，
     *    关掉它 = 正在播的视频被打断、正在传的文件被腰斩。
     *
     * 这条规矩的代价是"极端情况下一条闲置会话会多留一会儿"，而收益是
     * "边看片边逛目录不会互相干扰"——后者是真机上真实发生的问题，前者只占一点 socket。
     */
    private fun evictIfIdle() {
        val now = SystemClock.elapsedRealtime()
        synchronized(sessions) {
            val stale = sessions.filterValues { slot ->
                slot.leases == 0 && now - slot.lastUsedAt >= IDLE_TIMEOUT_MS
            }.keys
            if (stale.isEmpty()) return
            MimiLog.d(
                TAG,
                "idle >= ${IDLE_TIMEOUT_MS} ms, dropping ${stale.size} session(s): $stale " +
                    "（busy=${sessions.size - stale.size} 条因被占用或刚用过而保留）",
            )
            for (share in stale) {
                sessions.remove(share)?.let { runCatching { it.transport.close() } }
            }
        }
    }

    /** 交出句柄时记一笔占用。 */
    private fun lease(share: String) {
        synchronized(sessions) { sessions[share]?.leases++ }
    }

    /** 句柄关闭时归还占用。 */
    private fun releaseLease(share: String) {
        synchronized(sessions) {
            val slot = sessions[share] ?: return
            // 夹到 0：close() 被重复调用时不能把计数压成负数——负数会让
            // "跳过占用"这条判据永远成立，等于把空闲回收彻底关掉（且看不出是 bug）。
            if (slot.leases > 0) slot.leases--
        }
    }

    /**
     * 只加"关闭时归还占用"这一件事的透传包装。
     *
     * 刻意不复用 `BufferedSeekableSource` 做这件事：那一层已经包在**传输层**
     * （`SmbjTransport.openRead`）里了，它的 `onClose` 被日志占用；在这里再包一层
     * 同名类型会让"窗口到底套了几层"变得不可读，而多套一层窗口还会白付一次内存拷贝。
     */
    private class LeasedSeekableSource(
        private val delegate: SeekableSource,
        private val onRelease: () -> Unit,
    ) : SeekableSource by delegate {
        override fun close() {
            try {
                delegate.close()
            } finally {
                // finally：即使底层 close 抛了，占用也必须归还，否则这条会话
                // 就再也回收不掉了（内存与 socket 双泄漏）。
                onRelease()
            }
        }
    }

    /** [LeasedSeekableSource] 的写侧对应物。 */
    private class LeasedWriteSink(
        private val delegate: WriteSink,
        private val onRelease: () -> Unit,
    ) : WriteSink by delegate {
        override fun close() {
            try {
                delegate.close()
            } finally {
                onRelease()
            }
        }
    }

    /** 把库异常翻成 [FsException]，并统一走并发闸门。 */
    private suspend fun <T> guard(share: String, path: FsPath, block: suspend () -> T): T = gate.withPermit {
        try {
            block()
        } catch (t: Throwable) {
            // 会话级错误意味着缓存里的会话已经不可用：立刻丢掉，**下一个操作会自愈**
            // —— 因为 `transport()` 现在会检查 `isAlive`，发现死会话就重建。
            //
            // ⚠️ 只丢**这一条共享**的会话（M6 修正）。理由是每个共享各自一条 TCP 连接
            // （`SmbConnector.open` 每次都会 new 一个 `SMBClient`），
            // 所以"共享 A 的连接断了"对共享 B 没有任何信息量。旧实现调 `close()`
            // 会把所有共享一起关掉——在看片时逛另一个共享，一次元数据失败就能
            // 把正在播的那条连接也一并干掉，现象同样是"看着看着黑屏重来"。
            //
            // 这里**不做"就地重试"**，原因很具体：`block` 是由调用方捕获了 transport
            // 对象的闭包（`val transport = transport(share); guard { transport.list(…) }`），
            // 重试用的还是同一个废对象，必然再失败一次——那只是一段看起来在重试、
            // 实际没有重试的代码（M2 实测的日志里能看到"丢弃会话并重试一次"之后
            // 没有任何重建痕迹，就是这么来的）。要真正重试就得让 guard 自己取会话，
            // 那属于把会话生命周期收进这一个方法的改造；在此之前，不写会骗人的代码。
            if (isSessionFatal(t)) {
                MimiLog.w(
                    TAG,
                    "session-fatal ${t.javaClass.simpleName}: ${t.message} → 丢弃共享 '$share' 的会话",
                )
                dropSession(share, reason = "session-fatal ${t.javaClass.simpleName}")
            }
            throw t.toSmbException(path)
        }
    }

    /**
     * 判断这个异常是否意味着"会话已经废了"。
     *
     * 判据取得保守：只认网络层异常与 smbj 的连接类异常。**认证失败与文件不存在
     * 不算**——那些是业务结果，会话本身还好好的，误关会话会让用户为一次"改错名"
     * 白付一次握手。
     */
    private fun isSessionFatal(t: Throwable): Boolean = when (t) {
        is java.net.SocketException,
        is java.net.SocketTimeoutException,
        is java.io.EOFException,
        is javax.net.ssl.SSLException,
        -> true

        // FsException 是我们自己的语义层，此时已经丢失了"底层是断线还是没权限"的信息；
        // 宁可多留一次会话，也不要因为一次"文件不存在"就把会话关掉。
        is FsException -> false

        // 「服务端把这次请求拒了」：文件不存在、权限不足、名字冲突……会话本身是好的。
        // **必须放在下面那条之前**：`SMBApiException` 是 `SMBRuntimeException` 的子类，
        // 顺序反了会把正常的业务失败当成会话报废，让用户为一次改名白付一次握手。
        is SMBApiException -> false

        // smbj 把"对象已废"表达成**裸的** `SMBRuntimeException`（典型消息是
        // `DiskShare has already been closed`）。它既不是网络异常、类名里也不含
        // `TransportException`，所以**原判据完全没覆盖到它**——后果很具体：
        // 会话废掉之后 `close()` 永远不会被触发，缓存里那个废对象也永远不会被替换，
        // 用户会**一直**拿到同一个错误（正是本函数要避免的情况）。
        is SMBRuntimeException -> true

        else -> t.javaClass.name.contains("TransportException") ||
            // `cause !== t` 是防御自引用：异常链被写错成自环时不能死递归。
            (t.cause?.takeIf { it !== t }?.let(::isSessionFatal) ?: false)
    }

    private fun split(path: FsPath): Pair<String, String> {
        val segments = path.segments
        if (segments.isEmpty()) {
            throw FsIoException("SMB path has no share segment: $path")
        }
        return segments[0] to segments.drop(1).joinToString("/")
    }

    private fun SmbDirEntry.toFsEntry(path: FsPath): FsEntry = FsEntry(
        path = path,
        name = name,
        kind = if (isDirectory) FsKind.DIRECTORY else FsKind.FILE,
        size = if (isDirectory) FsEntry.SIZE_UNKNOWN else size,
        lastModified = lastModified,
        isHidden = isHidden,
        isReadOnly = isReadOnly,
    )

    companion object {
        const val SCHEME = "smb"

        private const val TAG = "MimiSmb"

        /** 每共享的并发上限：一路元数据 + 一路数据，见类注释。 */
        private const val MAX_CONCURRENCY = 2

        /** 空闲多久丢弃会话。取 60 s，短于 NAS 常见的 15 分钟空闲断连。 */
        private const val IDLE_TIMEOUT_MS = 60_000L

        /** SMB 后端的静态能力位。解析器与实现共用这一份。 */
        internal val CAPABILITIES: Int =
            FsCapability.LIST or FsCapability.READ or FsCapability.WRITE or
                FsCapability.CREATE_DIRECTORY or FsCapability.DELETE or FsCapability.RENAME or
                FsCapability.SEEK or FsCapability.METADATA
    }
}
