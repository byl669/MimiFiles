package com.miyuetech.mimifiles.core.fssmb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.common.SMBRuntimeException
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.BufferedSeekableSource
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import java.io.IOException
import java.util.EnumSet

/**
 * 与 `SmbConnector` / `SmbFileSystem` 用同一个 tag，便于 `adb logcat -s MimiSmb` 一次看全。
 *
 * 放在**文件级**而不是 `companion object` 里：下面两个嵌套句柄类要用它，
 * 而"嵌套类能看见外部类的 private companion 成员"在 Kotlin 里并非可靠保证
 * （它依赖于外部类的`private`可见性规则），文件级 `private` 没有这层不确定性。
 */
private const val TAG = "MimiSmb"

/**
 * 单次 Read/Write 的字节上限：1 MiB。
 *
 * ## 旧注释是错的，这里留档（M6 反编译订正）
 * 原注释写的是"smbj 的 `File.read` 对单次长度有上限，**超过会直接抛运行时异常**"，
 * 因此取了 256 KiB"留足余量"。反编译 smbj 0.15.0 后钉死的实际行为是：
 *
 * ```java
 * // Share.readAsync
 * new SMB2ReadRequest(dialect, fileId, sessionId, treeId, offset,
 *                     Math.min(length, this.readBufferSize));   // ← 静默截断
 * // File.read
 * int bytesRead = Math.min(length, data.length);
 * return bytesRead;                                             // ← 不抛，返回短读
 * ```
 *
 * 也就是说**不抛异常，只是少读**。旧注释把"静默截断"误记成了"抛异常"，
 * 于是为了避免一个不存在的异常，白付了 4 倍的协议往返。
 *
 * ## 为什么改成 1 MiB
 * 上限公式是 `Share.readBufferSize = min(config.readBufferSize, 协商 maxReadSize)`，
 * 而 [SmbConnector] 已把 `config.readBufferSize` 显式钉在 1 MiB。所以这里
 * 请求 1 MiB 是"要么一次拿满、要么被服务端按自己的上限少给"——两种都不会错。
 *
 * 放在文件级而不是 companion 里，是因为下面两个嵌套句柄类也要用。
 */
private const val IO_CHUNK = 1024 * 1024

/**
 * 预读窗口下界 64 KiB。
 *
 * 64 KiB 是 SMB2 的"单信用额度载荷"（`SMB2Packet.SINGLE_CREDIT_PAYLOAD_SIZE`），
 * 也是任何服务端都必须支持的最小单次读。窗口比它还小就纯粹是在浪费往返。
 */
private const val MIN_WINDOW = 64 * 1024

/**
 * 预读窗口上界 1 MiB。
 *
 * 与 [SmbConnector] 钉住的 `readBufferSize` 一致。不再往上取的理由是**内存**：
 * 窗口是按打开句柄分配的，缩略图列表能同时开好几个句柄，8 MiB 的窗口
 * 在低端盒子上会变成真实的 OOM 风险，而 1 MiB 已足够把"每 64 KiB 一次往返"
 * 压成"每 1 MiB 一次"。
 */
private const val MAX_WINDOW = 1024 * 1024

/**
 * SMB2 / SMB3 传输实现（smbj）。
 *
 * ## 为什么所有写操作都带 `FILE_NON_DIRECTORY_FILE` / `FILE_DIRECTORY_FILE`
 * SMB2 的 CREATE 请求里，`CreateOptions` 决定了服务端把这次打开当成文件还是目录。
 * 少数实现（Samba 的某些版本）在缺失这个标志时会"猜"，猜错的结果是把目录当文件打开、
 * 或者对目录发出 Read 请求。显式声明是零成本且能消除一整类兼容问题的做法。
 *
 * ## 为什么 `read` 要循环而不是一次到底
 * smbj 会按服务端协商出的上限把单次 READ 截短（见 [IO_CHUNK] 的注释），
 * 而且**连接信用额度不足时还会截得更短**（`SMB2MultiCreditPacket.getPayloadSize`
 * 取 `min(maxPayloadSize, 64 KiB × 本次分配到的信用数)`）。所以"请求 N 字节、
 * 拿到 N 字节"这件事必须由**循环**来保证，不能假定一次调用读满。
 *
 * ## 为什么读句柄自带"重开一次"的容错
 * SMB2 的文件句柄会因服务端策略（oplock break、会话回收、共享重挂载）单方面失效，
 * 而**消费者的行为差异极大**：Media3 遇到 IOException 会退避重试整段加载，
 * 但系统 `PackageInstaller` 与应用商店不会——它只会永远停在"正在准备安装应用"。
 * 句柄级重开把这一类"句柄死了、连接还好"的失败挡在传输层，代价只是一次
 * CREATE 往返；**连接真的断了时，重开会立刻失败**（`send` 在已关闭的通道上
 * 同步抛错，不是超时等待），所以不会把一次真故障拖成 120 秒。
 */
internal class SmbjTransport(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    override val dialectLabel: String,
) : SmbTransport {

    /**
     * 「这条会话现在还能用吗」。
     *
     * ## ⚠️ 只看 `connection.isConnected` 是**错的**（M6 实测抓到）
     * M6 之前这里只判 `connection.isConnected`，注释写着"连接断了，挂在它上面的
     * 会话与树连接必然也废，所以它足以代表整条链路的可用性"。**这句话反过来不成立**：
     * 会话/树连接会**单独**死掉，而 `Connection` 仍然报 `isConnected == true`。
     *
     * 实测（模拟器 + 本机 impacket，`SmbFileSystem` 日志逐行）：
     * ```
     * 00:46:07.793  reuse transport#72140100 for share 'SHARE'    ← isAlive 判活，复用了它
     * 00:46:07.793  session-fatal SMBRuntimeException: DiskShare has already been closed
     * ```
     * 宿主侧探针把两个标志一起打出来，组合是稳定的：
     * `share.isConnected()=false  conn.isConnected()=true`。
     *
     * 成因是 smbj 的 `Connection.handleError`（reader 线程的致命错误出口）走
     * `close()` → `close(false)`，而 `close(false)` 第一件事是 `Pooled.release()`：
     * 引用计数没归零就**直接 return**，`transport.disconnect()` 压根没执行，
     * 于是 `connected` 这个 `AtomicBoolean` 永远是 `true`。而同一段代码在
     * 走到 `disconnect()` 之前**已经**遍历关掉了所有 `Session`（连带 `DiskShare`）
     * —— 这就是"share 死了、conn 还活着"的来源。
     *
     * ## 为什么两个都要判
     * `Share.isConnected()` 是 `!disconnected`，而 `disconnected` 只在 `Share.close()`
     * 里被置位；`Share.send()` 的第一行就是
     * `if (!isConnected()) throw SMBRuntimeException(getClass().getSimpleName() + " has already been closed")`
     * （反编译 0.15.0 核对过）。所以：
     *  - 只用 `share` —— 连接层真的断了但 `DiskShare` 还没被关时，会漏判；
     *  - 只用 `conn` —— 就是本次这个 bug。
     * 两个都要求为真，才叫"这条会话还能用"。
     *
     * 包一层 `runCatching`：这条判据会被放在**每次取会话**的热路径上，
     * 它自己抛异常的概率虽低，但一旦抛出去就会把一次正常的列举变成失败。
     */
    override val isAlive: Boolean
        get() = runCatching { connection.isConnected && share.isConnected }.getOrDefault(false)

    override fun list(path: String): List<SmbDirEntry> {
        val raw = share.list(wire(path))
        val out = ArrayList<SmbDirEntry>(raw.size)
        for (info in raw) {
            val name = info.fileName ?: continue
            // SMB 服务端总会返回 "." 与 ".."（借 DOS 语义），必须剔除，
            // 否则列表里会多出两个点开头、点进去还回到原地的怪条目。
            if (name == "." || name == "..") continue
            val attrs = info.fileAttributes
            val isDir = attrs and SmbAttributes.DIRECTORY != 0L
            out += SmbDirEntry(
                name = name,
                isDirectory = isDir,
                size = if (isDir) SmbDirEntry.UNKNOWN else info.endOfFile,
                lastModified = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L),
                isHidden = attrs and SmbAttributes.HIDDEN != 0L,
                isReadOnly = attrs and SmbAttributes.READONLY != 0L,
            )
        }
        return out
    }

    override fun stat(path: String): SmbDirEntry {
        val info = share.getFileInformation(wire(path))
        val standard = info.standardInformation
        val basic = info.basicInformation
        val attrs = basic.fileAttributes
        val isDir = standard.isDirectory
        val name = path.trimEnd('/').substringAfterLast('/').substringAfterLast('\\')
        return SmbDirEntry(
            name = name.ifEmpty { path },
            isDirectory = isDir,
            size = if (isDir) SmbDirEntry.UNKNOWN else standard.endOfFile,
            lastModified = runCatching { basic.lastWriteTime.toEpochMillis() }.getOrDefault(0L),
            isHidden = attrs and SmbAttributes.HIDDEN != 0L,
            isReadOnly = attrs and SmbAttributes.READONLY != 0L,
        )
    }

    override fun exists(path: String): Boolean = try {
        // 用 getFileInformation 而不是 fileExists/folderExists 两次询问：
        // 一次请求就能同时回答"在不在"和"是什么"，而后者在目录上要先失败一次。
        share.getFileInformation(wire(path))
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 打开读句柄，并套一层**顺序预读窗口**。
     *
     * ## 窗口取多大：问协商结果，不猜
     * `Share.readBufferSize = min(config.readBufferSize, 协商 maxReadSize)` 是
     * smbj 自己算出来的"一次请求最多能拿多少"。这里取同一个数的下界
     * （[BufferedSeekableSource.DEFAULT_WINDOW] × `min(1, maxReadSize/1 MiB)` 的语义，
     * 见下面 [prefetchWindow]），于是"一个窗口 = 一次协议往返"这条等式**按构造成立**：
     * 无论对面是老款 SMB2.0.2（上限 64 KiB）还是 SMB3.1.1（上限 8 MiB），
     * 窗口都不会大到需要拆成多次请求，也不会小到浪费往返。
     *
     * 这一步是 M6 性能修复里唯一会**改变消费端看到的行为**的（前三条只是不让它失败）。
     */
    override fun openRead(path: String): SeekableSource {
        val wirePath = wire(path)
        val file = share.openFile(
            wirePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        return BufferedSeekableSource(
            delegate = SmbjSeekableSource(share, wirePath, file),
            windowSize = prefetchWindow(),
            onClose = { stats -> MimiLog.d(TAG, "close read $wirePath | $stats") },
        )
    }

    /**
     * 预读窗口 = min(1 MiB, 服务端协商出的 maxReadSize)，再夹到 [MIN_WINDOW, MAX_WINDOW]。
     *
     * `negotiatedProtocol` 在连接建立后必定非空（`ConnectionContext.negotiated` 先于
     * 任何会话设置它），但仍包一层兜底：这条判据在每次打开文件时都会跑到，
     * 它自己抛异常就会把"打开一个文件"变成失败，代价与收益完全不对称。
     */
    private fun prefetchWindow(): Int {
        val negotiated = runCatching { connection.negotiatedProtocol.maxReadSize }
            .getOrDefault(BufferedSeekableSource.DEFAULT_WINDOW)
        return negotiated.coerceIn(MIN_WINDOW, MAX_WINDOW)
    }

    override fun openWrite(path: String, append: Boolean, expectedSize: Long): WriteSink {
        val file = share.openFile(
            wire(path),
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            if (append) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        // 追加模式：SMB2 没有 "O_APPEND" 语义，只能自己记住起始偏移，
        // 之后每次写都显式带上位置。这也是为什么 WriteSink 需要一个 length 字段。
        val startOffset = if (append) runCatching { file.length }.getOrDefault(0L) else 0L
        return SmbjWriteSink(file, startOffset)
    }

    override fun mkdir(path: String) {
        share.mkdir(wire(path))
    }

    override fun delete(path: String, recursive: Boolean) {
        val isDir = try {
            share.getFileInformation(wire(path)).standardInformation.isDirectory
        } catch (_: Exception) {
            false
        }
        if (isDir) {
            share.rmdir(wire(path), recursive)
        } else {
            share.rm(wire(path))
        }
    }

    override fun rename(path: String, newName: String) {
        val isDir = try {
            share.getFileInformation(wire(path)).standardInformation.isDirectory
        } catch (_: Exception) {
            false
        }
        val entry = if (isDir) {
            share.openDirectory(
                wire(path),
                EnumSet.of(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
            )
        } else {
            share.openFile(
                wire(path),
                EnumSet.of(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
        }
        entry.use { it.rename(newName, true) }
    }

    override fun move(src: String, destDir: String): Boolean = try {
        // SMB2 的 SET_INFO(FileRenameInformation) 接受**相对共享根的完整路径**，
        // 所以跨目录移动是一次原子操作，不需要"复制 + 删除"。
        val name = src.substringAfterLast('/')
        val target = if (destDir.isEmpty()) name else "$destDir/$name"
        val isDir = share.getFileInformation(wire(src)).standardInformation.isDirectory
        val entry = if (isDir) {
            share.openDirectory(
                wire(src),
                EnumSet.of(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
            )
        } else {
            share.openFile(
                wire(src),
                EnumSet.of(AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
        }
        entry.use { it.rename(wire(target), true) }
        true
    } catch (_: Exception) {
        // 有些服务端/网关会拒绝跨目录 rename（尤其是挂载了别的东西的共享）。
        // 这里不抛，交给上层回退「复制 + 删除」——那才是用户能感知到的正确行为。
        false
    }

    override fun copyServerSide(src: String, destDir: String): Boolean = try {
        val name = src.substringAfterLast('/')
        val target = if (destDir.isEmpty()) name else "$destDir/$name"
        val source = share.openFile(
            wire(src),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        source.use { s ->
            val dest = share.openFile(
                wire(target),
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            dest.use { d -> s.remoteCopyTo(d) }
        }
        true
    } catch (_: Exception) {
        // SMB2 COPYCHUNK 是 SMB 3.0 才有的能力，老服务端会直接返错。
        // 返回 false 让上层退回流式复制——慢，但结果正确。
        false
    }

    override fun probe() {
        share.list("")
    }

    /**
     * 关掉整条链路，**顺序不能反**。
     *
     * SMB2 的树连接挂在会话上、会话挂在 TCP 连接上。先关连接再关树连接，
     * smbj 会尝试在已断开的通道上发 TREE_DISCONNECT，然后抛一个与真实原因无关的
     * 异常——而我们在 `release`/退出时都会调用它，日志会被这种噪音淹掉。
     */
    override fun close() {
        // 留一条带调用栈的痕迹。`DiskShare has already been closed` 这类错误只有在
        // "谁把它关了"可见之后才查得动（M2 实测踩过：第二次进 SMB 目录报这句，
        // 而 `SmbFileSystem.close()` 完全没被调用过）。
        MimiLog.w(TAG, "SmbjTransport#${hashCode()} close from ${callSite()}")
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    /**
     * 内部路径 → SMB 线格式。
     *
     * 全部换成反斜杠：虽然 smbj 自己也能消化 `/`，但 `DiskEntry.rename()` 会把名字
     * 直接拼进 SET_INFO 请求体，混着两种分隔符在某些服务端上会被当成**字面文件名**
     * （于是造出一个名字里带斜杠的怪文件）。统一在边界上换掉，内部一律用 `/` 便于处理。
     */
    private fun wire(path: String): String = path.replace('/', '\\')

    /** 最近的若干栈帧压成一行——只为回答"谁调的 close"，不需要完整栈。 */
    private fun callSite(): String = Throwable().stackTrace
        .drop(1)
        .take(6)
        .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    // ── 读句柄 ──────────────────────────────────────────────────────────

    /**
     * SMB2 读句柄，带**一次**透明重开。
     *
     * ## 为什么需要重开
     * 句柄失效（oplock break、服务端回收、共享被重挂载）与连接断开是两件事，
     * 但它们在 smbj 里抛出的异常长得一样（都是 `SMBRuntimeException` /
     * `TransportException` 家族）。区分方式是**试一次**：重开走的是同一个
     * `DiskShare`，连接还好就能成功（一次 CREATE 往返，几十毫秒，用户无感）；
     * 连接真断了则 `send` 立刻抛（不等待超时）。所以"试一次"是零风险的判定手段。
     *
     * 只重试一次：能用一次重开救回来的故障，第二次也会被同样救回来；
     * 救不回来的（权限被撤、文件被删）重试一万次也一样，只会把报错时间拖长。
     */
    private class SmbjSeekableSource(
        private val share: DiskShare,
        private val wirePath: String,
        initial: File,
    ) : SeekableSource {

        private var file: File = initial

        private val total: Long = runCatching { initial.length }.getOrDefault(SeekableSource.SIZE_UNKNOWN)

        override val size: Long get() = total

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            if (total >= 0 && position >= total) return -1
            return try {
                readFrom(file, position, buffer, offset, length)
            } catch (first: Throwable) {
                if (!isHandleFailure(first)) throw first
                // 重开成功后**从 position 重读整段**（而不是从"已读到哪"续读）。
                //
                // 之所以敢整段重读：`read` 对同一个 `position` 是幂等的——文件内容不会
                // 因为读了一次而改变，重读只会往同一段缓冲区写同样的字节。代价是一次
                // 重复的窗口读，换来的是"不用去追踪半路失败时已落到缓冲区的字节数"
                // 这个极易写错的账。而半路失败后再往后写，一旦 offset 算错，
                // 上层的表现是**画面花屏**（数据错位）——那是本项目里最难归因的一类 bug。
                val reopened = reopen() ?: throw first
                MimiLog.w(
                    TAG,
                    "read $wirePath@$position failed (${first.javaClass.simpleName}: ${first.message}); " +
                        "reopened handle and retried once",
                )
                readFrom(reopened, position, buffer, offset, length)
            }
        }

        /**
         * 这个失败是不是"句柄/连接坏了"，即重开一次有可能救回来。
         *
         * 判据来自 smbj 的实际异常层次（反编译 0.15.0 核对过）：
         *  - `TransportException extends IOException` —— 网络层与 socket 超时都走这里；
         *  - `SMBApiException extends SMBRuntimeException extends RuntimeException` ——
         *    服务端回 `STATUS_FILE_CLOSED` / `STATUS_INVALID_HANDLE`（句柄被回收）正是这一类；
         *  - `SMBRuntimeException` 本身承载 `"DiskShare has already been closed"`。
         *
         * 其余一律不重试：`IllegalArgumentException`（参数写错）、`OutOfMemoryError`
         * 之类重开一万次也一样，只会掩盖真实原因。
         */
        private fun isHandleFailure(t: Throwable): Boolean =
            t is IOException || t is SMBRuntimeException

        /**
         * 从 [position] 起读，**循环直到**读满 [length] 或真到 EOF。
         *
         * 三个细节都是实测订正过的：
         *  1. **不能把短读当 EOF**。smbj 会因为服务端协商上限、以及连接信用额度不足
         *     （`min(maxPayloadSize, 64 KiB × 信用数)`）而返回短读——那是"这次只给了这么多"，
         *     不是"文件到头了"。旧实现 `if (n < want) break` 会把这种短读判成 EOF，
         *     结果是**文件被静默截断**（视频播到某处就"结束了"，且极难归因）。
         *  2. **EOF 的唯一可靠信号是 `n <= 0`**：smbj 在 `STATUS_END_OF_FILE` 时返回 `-1`
         *     （`File.read` 里显式翻译），长度为 0 的响应返回 `0`。两者都终止循环。
         *  3. ⚠️ **循环里必须自己判 EOF 并停手**（M6 实测补上，这是 #56 的真根因）。
         *
         * ## 为什么第 3 条不可省：一次"起点 = 文件末尾"的读会把整条连接带走
         * 循环的终止条件是 `n <= 0`，而**发请求这件事发生在拿到 `n` 之前**。
         * 于是当 `position + done == total` 而 `done < length` 时，它照样会发出
         * `read(offset = total, len = length - done)` —— 一个起点正好落在 EOF 的读。
         *
         * 外层 `read()` 里那句 `if (total >= 0 && position >= total) return -1`
         * **只挡住"起点就 ≥ EOF"，挡不住"在循环中途走到 EOF"**。这两件事看起来
         * 很像，实际是两条不同的路径，而后者才是常态：任何"块大小不能整除文件长度"
         * 的读取都会在最后一块踩进来（`PreviewCache` 用 1 MiB 缓冲读 19.5 MB 的安装包，
         * 最后一块必然如此）。
         *
         * 后果**远超"多一次无用往返"**：服务端对越界读回的是一个 ERROR 响应，
         * 而 smbj 解析它时会在 `SMB2Error.readErrorContext` 里 `BufferUnderflow` →
         * 异常从 **reader 线程**抛出 → `Connection.handleError` → **整条连接被关**。
         * 宿主侧探针单发一次越界读的实测（对 impacket `SimpleSMBServer`，SMB 2.0.2）：
         * ```
         * 正常读完 19546917 字节（EOF=19546917）
         * 越界读（offset=19546917）→ Unable to deserialize SMB2 Packet Data
         * ★ share.isConnected()=false  conn.isConnected()=true
         * ```
         * 也就是说：**文件明明已经完整读完，最后一步却把会话炸掉**，用户看到的是
         * "从 SMB 装 APK 卡在正在准备安装应用"。
         *
         * 所以这里的判据是"还差多少没读"与"文件还剩多少"取小 —— 到 EOF 就停，
         * 一个字节都不越界。这不是"防御性编程"，这是**我们唯一能控制的一端**：
         * impacket 的错误响应格式、以及 smbj 对它的解析，都不在我们手里。
         */
        private fun readFrom(
            source: File,
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            var done = 0
            while (done < length) {
                // 到 EOF 就停：绝不再发一次"起点 = 文件末尾"的读（见上面第 3 条）。
                if (total >= 0 && position + done >= total) break
                val n = source.read(buffer, position + done, offset + done, length - done)
                if (n <= 0) break
                done += n
            }
            return if (done == 0) -1 else done
        }

        /**
         * 重开同一个文件；失败返回 `null`（由调用方抛出**原始**异常，那才是有信息量的那个）。
         *
         * ## ⚠️ 它只能救"句柄坏了"，救不了"连接断了"
         * 这里用的是**同一个 `DiskShare`**，而 `DiskShare` 绑死在一条 `Session`/`Connection` 上。
         * 连接一旦死掉（服务端空闲断连、Wi-Fi 抖动、smbj 的 `PacketReader` 把任何
         * `TransportException` 当致命错误 `Connection.handleError → close()`），
         * 在死连接上 `openFile` **必然**再失败一次 —— 于是一个本该"重连后续读"的自愈
         * 变成了纯粹多花一次往返的空转。
         *
         * M6 实测抓到了这个空转：整段 21 MB 读取以
         * `SMBRuntimeException: … TransportException: Unable to deserialize SMB2 Packet Data`
         * 失败，而日志里**没有**那条"reopened handle and retried once"——
         * 说明 `reopen()` 返回了 `null`，被静默吞掉。连接级的中断只能由**调用方**处理：
         * `SmbFileSystem.transport()` 会 `isAlive` 判死并重建会话，所以"整段重来一次"
         * 是这条路线上唯一能自愈的做法（见 `PreviewCache.fetch`）。
         */
        private fun reopen(): File? = try {
            runCatching { file.close() }
            share.openFile(
                wirePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).also { file = it }
        } catch (t: Throwable) {
            // 记下**为什么**没救回来。这里曾经是一句 `catch (_: Throwable) { null }`，
            // 结果"句柄坏了"与"连接断了"在日志上长得一模一样（都是没有日志），
            // 排查时只能靠猜——而这次猜错了方向，先怀疑了请求长度。
            MimiLog.d(TAG, "reopen $wirePath failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }

        override fun close() {
            runCatching { file.close() }
        }
    }

    // ── 写句柄 ──────────────────────────────────────────────────────────

    private class SmbjWriteSink(private val file: File, private var position: Long) : WriteSink {

        private var written = 0L

        override val bytesWritten: Long get() = written

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            var done = 0
            while (done < length) {
                val want = minOf(length - done, IO_CHUNK)
                // 注意：`File.write` 返回 long（SMB2 单次写可达 8 MB），
                // 与 `read` 返回 int 是不对称的，混淆会直接编译不过——这里保留 long。
                val n = file.write(buffer, position, offset + done, want)
                if (n <= 0L) throw IOException("SMB write returned $n at $position")
                position += n
                done += n.toInt()
                written += n
            }
        }

        override fun flush() {
            runCatching { file.flush() }
        }

        override fun close() {
            file.close()
        }
    }
}
