package com.miyuetech.mimifiles.core.fssmb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.NegotiatedProtocol
import com.hierynomus.smbj.share.DiskShare
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FsAuthException
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.fs.FsUnreachableException
import com.miyuetech.mimifiles.core.model.FsPath
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * 建立一个 SMB 会话，并在需要时从 SMB2/3 降级到 SMB1。
 *
 * ## 降级判定的边界（这里最容易做错）
 * **认证失败绝不能触发降级。** 密码不对时 smbj 抛的是 `STATUS_LOGON_FAILURE`；
 * 如果把它也当成"可能是方言问题"去试 SMB1，用户会为一个错密码多等一整轮超时，
 * 最后拿到的还是不相关的 SMB1 报错。所以判定顺序是：
 *
 * 1. 认证类错误 → 直接抛（[FsAuthException]），不降级；
 * 2. 其他失败 → 记录原因，试 SMB1；
 * 3. SMB1 也失败 → 抛**主选（smbj）的原因**。理由：用户拿到"为什么 SMB2 连不上"
 *    远比拿到"SMB1 也不行"有用——后者是他根本没打算用的路径。
 *
 * ## 什么时候压根不试 SMB2
 * 用户在高级选项里勾了「强制 SMB1」。这既省掉一次注定失败的握手，
 * 也是排障时的确定性手段（"我确定这台老机器只有 SMB1"）。
 */
internal object SmbConnector {

    private const val TAG = "MimiSmb"

    /** 与 `:core:fs-smb` 注册的 scheme 一致；用于异常映射时构造 FsPath。 */
    private const val SCHEME = "smb"

    /** SMB 默认端口。连接配置里端口为 0 时用它。 */
    const val DEFAULT_PORT = 445

    /**
     * 一次 SMB2 READ/WRITE 的载荷上限，1 MiB。
     *
     * 与 [BufferedSeekableSource.DEFAULT_WINDOW] 取同一个值，使"一个预读窗口 =
     * 一次协议往返"成立。1 MiB 也是 smbj 的默认值，这里显式钉住是为了让这个
     * 直接决定吞吐的数字**出现在代码里**，而不是藏在库的默认值里。
     */
    private const val READ_BUFFER_SIZE = 1024 * 1024

    /**
     * @param shareName 共享名（不含子路径）
     * @return 可用的传输实现；两个方言都失败时抛 [FsException]
     */
    fun open(
        authority: String,
        host: String,
        port: Int,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
        settings: SmbSettings,
    ): SmbTransport {
        val effectivePort = if (port > 0) port else DEFAULT_PORT
        MimiLog.i(TAG, "connecting smb://$host:$effectivePort/$shareName (share only in log, no credentials)")

        var smb2Failure: Throwable? = null

        if (!settings.forceSmb1) {
            try {
                return openSmb2(
                    host = host,
                    port = effectivePort,
                    shareName = shareName,
                    username = username,
                    password = password,
                    domain = domain,
                    settings = settings,
                )
            } catch (t: Throwable) {
                val e = t.toSmbException(FsPath.of(SCHEME, authority))

                // ⚠️ 先**翻译**，再判定要不要降级（M4.1 实测修正）。
                //
                // `openSmb2` 抛出来的是**库的原始异常**（`SMBApiException` / `SMBRuntimeException`），
                // 不是我们的 `FsException`。原来的写法按 `FsException` 的类型去判"认证失败"，
                // 那条判定于是**永远不会成立** —— 死代码，而且看不出是死的。
                //
                // 代价很具体：密码填错时也要先去连一遍 SMB1 才报错。实测多花 2.3 秒；
                // 换成真实 NAS（超时按 [SmbSettings] 默认 10 秒算）就是十几秒的"点了没反应"。
                //
                // 这两类失败与方言无关，重试没有意义：
                //  · 认证失败 —— 密码不对，换个方言还是不对（但**签名**不是"换个方言"，见下）；
                //  · 网络不可达 —— 地址错/端口关/不在同一网段，SMB1 同样到不了。
                if (e is FsAuthException || e is FsUnreachableException) {
                    // ⚠️ 例外：不签名时的 `STATUS_ACCESS_DENIED` 有两张面孔（M6 性能修复新增）。
                    //
                    // 服务端要求签名而我们没签时，smbj 抛的可能是
                    // `TransportException("Message signing is required, but no signing key is negotiated")`，
                    // 也可能是服务端直接回 `STATUS_ACCESS_DENIED` —— 后者与"密码错"
                    // 在**异常类型上完全一样**。区别在于：一个是"开关不对"，一个是"凭据不对"。
                    // 所以对"非签名模式下的认证失败"再试一次带签名的连接，
                    // 用**结果**来区分这两者（而不是去猜错误文本）。
                    if (!settings.signing && e is FsAuthException) {
                        retryWithSigning {
                            openSmb2(
                                host = host, port = effectivePort, shareName = shareName,
                                username = username, password = password, domain = domain,
                                settings = settings, signingOverride = true,
                            )
                        }?.let { return it }
                    }
                    throw e
                }

                // 第二张面孔：**方言不匹配**。
                //
                // 关签名时 smbj 有一条硬约束——必须把方言上限收敛到 SMB2.1
                // （`Signing cannot be disabled when using SMB3.x dialects`）。
                // 若这台服务端只肯讲 SMB3.x，协商会直接失败，用户看到的是"连不上"。
                // 这类失败同样应该先给"打开签名"一次机会，而不是立刻掉到 SMB1。
                if (!settings.signing) {
                    retryWithSigning {
                        openSmb2(
                            host = host, port = effectivePort, shareName = shareName,
                            username = username, password = password, domain = domain,
                            settings = settings, signingOverride = true,
                        )
                    }?.let { return it }
                }

                smb2Failure = e
                MimiLog.w(TAG, "SMB2/3 failed, falling back to SMB1: ${e.message}")
            }
        } else {
            MimiLog.i(TAG, "forceSmb1 is on, skipping SMB2/3 negotiation")
        }

        return try {
            openSmb1(
                host = host,
                port = effectivePort,
                shareName = shareName,
                username = username,
                password = password,
                domain = domain,
                settings = settings,
            )
        } catch (t: Throwable) {
            // 两个都挂了：优先报告 SMB2 的原因（那是主选路径，用户更可能想修它）。
            val primary = smb2Failure
            if (primary != null) {
                // ⚠️ SMB1 的失败原因**必须记进日志**（M5.1 补）。
                //
                // 只报告主因在正常情况下是对的（少一句噪音），但在"主因与真实
                // 故障无关"的时候会把线索一起丢掉。M5.1 的真机事故正是这种情形：
                // 用户勾了「强制 SMB1」，主因位置为空，抛出的又是被混淆破坏的
                // MBassador 反射错误；如果当时日志里同时有"SMB1 兜底失败"这一行，
                // 归因会立刻收敛到 jcifs 那条通道上，而不必从一句 `r9.c` 反推。
                //
                // 记日志不记 UI：两种方言都失败时，给用户两条原因只会更长更懵，
                // 而排障的人（我们）要的正是这两条。
                val fallback = t.toSmbException(FsPath.of(SCHEME, authority))
                MimiLog.w(
                    TAG,
                    "SMB1 fallback also failed: ${fallback.javaClass.simpleName}: " +
                        "${fallback.message}（UI 按 SMB2/3 主因报告）",
                )
                throw toFailure(primary, authority)
            }
            throw toFailure(t, authority)
        }
    }

    /**
     * 「关签名连不上」时的兜底：带签名再连一次。
     *
     * ## 为什么必须存在这一条
     * 把签名默认值改成"关"（[SmbOptionKeys.SIGNING]）是一次**用默认值换性能**的取舍，
     * 它必须配一条兜底，否则"服务端要求签名"的用户会从"能用但慢"直接变成"连不上"。
     *
     * 兜底覆盖两种成因，而它们**都不需要用错误文本去猜**：
     *  - 服务端在 negotiate 里声明了 `SIGNING_REQUIRED` → smbj 抛
     *    `Message signing is required, but no signing key is negotiated`，
     *    或服务端在会话建立阶段直接回 `STATUS_ACCESS_DENIED`；
     *  - 服务端只提供 SMB3.x，而关签名时我们把方言上限压到了 SMB2.1 → 协商无交集。
     *
     * 两者都表现为"带签名重试一次就可能成功"，所以判据就是**重试的结果**本身。
     *
     * @param attempt 用给定的 signing 值尝试建连；应抛出与 [openSmb2] 同类的原始异常
     * @return 成功时返回**已带一个额外「已自动启用签名」标记**的传输；失败返回 `null`
     */
    private inline fun retryWithSigning(attempt: () -> SmbTransport): SmbTransport? = try {
        val transport = attempt()
        MimiLog.w(
            TAG,
            "signing was off but the server needs it (or only speaks SMB3.x); " +
                "reconnected WITH signing — 建议在「高级选项」里打开签名以免每次都多一次握手",
        )
        transport
    } catch (t: Throwable) {
        MimiLog.i(TAG, "retry with signing also failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** 只做连通性探测：建一次会话、列一次共享根，随即释放。连接管理页的「测试」走这里。 */
    fun probe(
        authority: String,
        host: String,
        port: Int,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
        settings: SmbSettings,
    ): String {
        val transport = open(authority, host, port, shareName, username, password, domain, settings)
        return try {
            transport.probe()
            transport.dialectLabel
        } finally {
            runCatching { transport.close() }
        }
    }

    // ── SMB2/3 ──────────────────────────────────────────────────────────

    private fun openSmb2(
        host: String,
        port: Int,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
        settings: SmbSettings,
        signingOverride: Boolean = settings.signing,
    ): SmbTransport {
        // 用户名留空等价于"来宾/匿名"（与 Windows 资源管理器一致：留空就按 guest 试）。
        // 这里必须先算出来，因为下面签名那一步要据此决定。
        val anonymous = settings.anonymous || username.isNullOrBlank()
        val signing = signingOverride
        val config = SmbConfig.builder()
            // ── 超时（M6 性能修复：这里原来四个值全是 10 秒）─────────────────
            //
            // 三层超时的时间尺度差一个数量级，混用同一个值会让"慢"变成"失败"：
            //
            //  1. 连接与一般请求 → [SmbSettings.timeoutMs]（默认 10 s）
            //     `withTimeout` 同时决定 socket connect 超时与请求等待上限。
            //     列目录/stat 超过它就是真出问题了，早报错是对的。
            //  2. 数据通道读写 → [SmbSettings.dataTimeoutMs]（默认 120 s）
            //     **必须在 `withTimeout` 之后调用**：smbj 的 `withTimeout` 会把
            //     read/write/transact 三个一起设掉，先设的会被它覆盖。
            //  3. SO_TIMEOUT → **0 = 不限**（smbj 默认值）。
            //     之前设成 10 s 的后果最隐蔽：服务端在扫盘/链路抖动时只是"这一次
            //     响应慢"，socket 却直接读超时，于是整个会话被判死、播放器从头重来。
            //     既然第 2 层已经给了明确上限，这里就不该再叠一个更短的。
            .withTimeout(settings.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .withReadTimeout(settings.dataTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .withWriteTimeout(settings.dataTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .withTransactTimeout(settings.dataTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(0L, TimeUnit.MILLISECONDS)
            // ── 缓冲（显式钉住，不吃库的默认值）─────────────────────────────
            //
            // `readBufferSize` 是**一次 SMB2 READ 的载荷上限**。显式写出来而不是
            // 依赖默认值，是因为这两个数字直接决定吞吐：改它们不该需要去读库源码。
            // 与 [BufferedSeekableSource.DEFAULT_WINDOW] 取同一个值，让
            // "一个预读窗口 = 一次协议往返"成立。
            .withReadBufferSize(READ_BUFFER_SIZE)
            .withWriteBufferSize(READ_BUFFER_SIZE)
            .withTransactBufferSize(READ_BUFFER_SIZE)
            // ── 签名 ────────────────────────────────────────────────────────
            //
            // `signingEnabled` 不是"要不要验签"这么轻：它决定 `Connection` 用
            // `PacketSignatory` 还是 `NoSignatory`，也就是**每一个出站报文要不要签名**。
            // `signingRequired` 只表达"是否要求服务端也签"——我们从不主动要求
            // （要求了只会让服务端多干活，而它本来就会在需要时自己签）。
            .withSigningEnabled(signing)
            // ⚠️ 「要求签名」对**来宾/匿名会话**必须先降级为"允许"（M4.1 实测）。
            //
            // SMB2 规定 guest 会话不得要求签名，smbj 会直接抛
            // `SMB2GuestSigningRequiredException: Cannot require message signing when
            // authenticating with a guest account` —— 而且是在**建立会话之前**抛，
            // 于是整条连接必然失败。
            //
            // 后果有多严重：配套的默认值是"要求签名 = 开"，而"用户名留空"是极常见的输入
            // （NAS 的公开共享、或用户干脆忘了填）。两者一组合，**每一台这样的 NAS 都连不上**，
            // 而报错是一串 Java 类名，用户完全无法据此行动。
            //
            // 注意降级的是"**要求**"而不是"签名能力"：`signingEnabled` 仍为 true，
            // 所以服务端要求签名时我们照样会签。真实的兼容损失只有一种情形 ——
            // 服务端要求签名、而客户端是匿名 —— 那本来就无解（没有凭据可签）。
            .withSigningRequired(signing && !anonymous)
            .apply {
                // 关了签名就**不要往 SMB3.x 协商**。
                //
                // smbj 有一条硬约束：SMB3.x 方言下 `signingRequired` 不允许是 false
                // （规范要求 SMB3 必须可签）。把 false 直接传下去，握手阶段会抛
                // `IllegalStateException: Signing cannot be disabled when using SMB3.x dialects`
                // ——用户既看不懂、也无从下手，而他的本意只是"这台老设备不做签名"。
                //
                // 收敛方言上限才是诚实的表达：不签名的会话只在 SMB2.1 及以下进行。
                // 服务端只认 SMB3 时协商会失败，此时 [open] 会自动带签名重试一次
                // （而不是把"只讲 SMB3 的服务端"直接判成连不上）。
                if (!signing) {
                    withDialects(SMB2Dialect.SMB_2_0_2, SMB2Dialect.SMB_2_1)
                }
            }
            .build()

        val client = SMBClient(config)
        try {
            val connection = client.connect(host, port)
            val auth = if (anonymous) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.orEmpty().toCharArray(), domain)
            }
            val session = connection.authenticate(auth)
            val share = session.connectShare(shareName) as? DiskShare
                // 连上了但拿到的不是磁盘共享（IPC$/打印机队列）。这不是"连不上"，
                // 而是"这个共享类型我们不做"，报 unreachable 会让用户去查网络，
                // 报成明确的 IO 错误更诚实。
                ?: throw FsIoException("不支持的共享类型：$shareName（不是磁盘共享）")
            val dialect = runCatching { niceDialect(connection.negotiatedProtocol) }.getOrDefault("SMB2/3")
            MimiLog.i(TAG, "SMB2/3 connected: $host:$port/$shareName dialect=$dialect")
            return SmbjTransport(client, connection, session, share, dialect)
        } catch (t: Throwable) {
            runCatching { client.close() }
            throw t
        }
    }

    // ── SMB1 ────────────────────────────────────────────────────────────

    private fun openSmb1(
        host: String,
        port: Int,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?,
        settings: SmbSettings,
    ): SmbTransport {
        // ⚠️ 建连之前先确认 MD4 真的能算（M5.1 补）。
        //
        // NTLM 的 NT 哈希 = MD4(密码)，而 jcifs 的 MD4 是从 BouncyCastle 借的，
        // 走的是 BC 那套**运行期字符串反射**注册。R8 一旦把
        // `org.bouncycastle.jcajce.provider.digest.MD4$Mappings` 剪掉（引用它的是
        // 一段拼出来的字符串，可达性分析看不见），BC 会**静默跳过注册**，
        // 直到认证那一刻才抛 `NoSuchAlgorithmException: no such algorithm: MD4 for provider BC`。
        //
        // 那个异常对用户是天书，而且位置很深（在 `SmbFile.listFiles()` 里）。前移到这里：
        //  · 失败时给一句"升级 / 关掉强制 SMB1"的可执行建议；
        //  · 成功时也只算一次（结论按进程缓存），对正常连接零额外开销。
        //
        // 注意顺序：`Crypto.getMD4()` 会把 BC provider 缓存进 jcifs 的静态字段，
        // 所以这次调用必须**早于**任何真正的 SMB1 操作，否则缓存的就是坏的 provider。
        SmbCrypto.verify()?.let { reason ->
            throw FsIoException(reason)
        }

        val props = Properties().apply {
            // 关闭 SMB2 —— 这一条是"降级"的全部含义。jcifs-ng 默认会在 SMB1/SMB2
            // 之间自动选，所以必须显式禁掉 SMB2，否则"降级"只是重试了一次同样的东西。
            setProperty("jcifs.smb.client.enableSMB2", "false")
            setProperty("jcifs.smb.client.disableSMB1", "false")
            setProperty("jcifs.smb.client.useSMB2Negotiation", "false")
            setProperty("jcifs.smb.client.minVersion", "SMB1")
            setProperty("jcifs.smb.client.maxVersion", "SMB1")
            setProperty("jcifs.smb.client.connTimeout", settings.timeoutMs.toString())
            setProperty("jcifs.smb.client.responseTimeout", settings.timeoutMs.toString())
            setProperty("jcifs.smb.client.soTimeout", settings.timeoutMs.toString())
            // 编码：SMB1 下中文名是否乱码取决于服务端的 OEM 代码页。
            // GBK 是国产老 NAS 最常见的选择，留成可配置项。
            setProperty("jcifs.encoding", settings.encoding)
            setProperty("jcifs.smb.client.useUnicode", "true")
            setProperty("jcifs.smb.client.signingPreferred", settings.signing.toString())
        }

        val config = PropertyConfiguration(props)
        val base = BaseContext(config)
        val auth = if (settings.anonymous || username.isNullOrBlank()) {
            NtlmPasswordAuthenticator("", "", "")
        } else {
            NtlmPasswordAuthenticator(domain.orEmpty(), username, password.orEmpty())
        }
        val ctx: CIFSContext = base.withCredentials(auth)

        val baseUrl = "smb://$host:$port/$shareName"
        // 立刻打一次，把"共享不存在/权限不足"这类错误在建连阶段就暴露出来，
        // 而不是等用户点进目录才报错（那时错误更难归因）。
        try {
            SmbFile("$baseUrl/", ctx).listFiles()
        } catch (t: Throwable) {
            runCatching { ctx.close() }
            throw t
        }
        MimiLog.i(TAG, "SMB1 connected (degraded): $host:$port/$shareName encoding=${settings.encoding}")
        return JcifsTransport(ctx, baseUrl)
    }

    private fun toFailure(t: Throwable, authority: String): FsException =
        if (t is FsException) t else t.toSmbException(FsPath.of(SCHEME, authority))

    /**
     * 把 smbj 的枚举名美化成可读方言。
     *
     * 枚举字面量是 `SMB_3_1_1`，直接给用户看会像内部错误码。正则只替换**数字之间的**
     * 下划线，`SMB_` 前缀单独处理，于是 `SMB_3_1_1 → SMB 3.1.1`、
     * `SMB_2_0_2 → SMB 2.0.2` 都对；将来 smbj 新增方言（如 `SMB_3_1_2`）也不必改代码。
     */
    private fun niceDialect(protocol: NegotiatedProtocol): String {
        val raw = protocol.dialect.name
        return raw.replace(Regex("(?<=\\d)_(?=\\d)"), ".")
            .replace("SMB_", "SMB ")
            .ifBlank { raw }
    }
}
