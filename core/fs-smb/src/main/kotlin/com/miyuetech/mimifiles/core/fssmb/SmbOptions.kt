package com.miyuetech.mimifiles.core.fssmb

/**
 * SMB 连接的非敏感调参键。
 *
 * 这些键落在 `ConnectionProfile.options` 里（数据库的 `options_json` 列），
 * 由连接编辑页的「高级」区写入。**键名是持久化契约**，改名字会导致老库里读不到参数。
 */
object SmbOptionKeys {

    /** 是否匿名登录（`"true"`/`"false"`）。为真时忽略用户名密码。 */
    const val ANONYMOUS = "anonymous"

    /**
     * 是否**主动**签名。
     *
     * ## 默认关（M6 性能修复的关键一项）
     * smbj 里这个开关不是"要不要验签"这么轻——它直接决定用哪个签名器
     * （`Connection` 构造：`config.isSigningEnabled() ? PacketSignatory : NoSignatory`）。
     * 打开时**每一个出站报文都被包一层签名**（读请求也签，虽然它的载荷很小），
     * 关掉时链路上完全不做签名/验签。
     *
     * 对家庭局域网里的一台 NAS，"防中间人"这件事的价值远小于它带来的 CPU 开销；
     * 而**服务端要求签名**的情形有两条自动兜底（[SmbConnector] 会带签名重试一次），
     * 所以把这个开关的默认值定成**关**是安全且正确的。
     */
    const val SIGNING = "signing"

    /** 连接与普通请求的超时毫秒（默认 [DEFAULT_TIMEOUT_MS]）。 */
    const val TIMEOUT_MS = "timeoutMs"

    /**
     * **数据通道**（读/写）的超时毫秒（默认 [DEFAULT_DATA_TIMEOUT_MS]）。
     *
     * ## 为什么必须与 [TIMEOUT_MS] 分开（M6 性能修复的关键一项）
     * 这两件事的时间尺度差了**一个数量级以上**，而 M6 之前它们共用一个 10 秒的值：
     *
     * | 场景 | 合理上限 |
     * |---|---|
     * | 建连、列目录、`stat` | 十几秒（超了就是真出问题了，早点告诉用户） |
     * | 读一个 1 MiB 块、写一个 1 MiB 块 | **可能几十秒**（服务端在扫盘、链路在抖动、影片本身码率高） |
     *
     * 把 10 秒套在数据通道上，后果不是"慢一点"，而是**每次超时都被当成会话报废**：
     * `SmbFileSystem.isSessionFatal` 认得 `SocketTimeoutException`/`TransportException`，
     * 于是会话被丢掉、播放器被打断、整个文件从头重读。用户感知就是"影片卡顿"
     * 而平均速度被反复重传拖到几百 KB/s —— 这正是真机反馈里的现象。
     */
    const val DATA_TIMEOUT_MS = "dataTimeoutMs"

    /** 强制 SMB1（跳过 SMB2/3 协商）。用于确认只有 SMB1 的老设备，省一次失败的握手。 */
    const val FORCE_SMB1 = "forceSmb1"

    /**
     * 文件名编码（`"UTF-8"` / `"GBK"`）。
     *
     * 只影响 jcifs-ng 的 SMB1 路径——SMB2 协议层就是 UTF-16，没有编码选项。
     * 保留它是为了兼容国产老 NAS 在 SMB1 下用 GBK 存中文名的情况
     * （这是参考应用里真实存在的排障项）。
     */
    const val ENCODING = "encoding"

    /** 填共享名之外，是否把 `share/sub` 这样的子目录也允许作为挂载点。默认允许。 */
    const val SHARE_SUBPATH = "shareSubpath"

    const val DEFAULT_TIMEOUT_MS = 10_000

    /**
     * 数据通道默认 120 秒。
     *
     * 取值理由：即使服务端只有 **25 KB/s**（真机上观察到的"最差那几百 KB"的下限再低一个量级），
     * 读满 1 MiB 的预读窗口也只需 41 秒 —— 也就是说这个上限**不会**在正常网络抖动下触发，
     * 它只在服务端真的没响应时才生效。而取消与"网络无响应"提示由传输引擎负责，
     * 不依赖这个超时来保证界面可用性。
     */
    const val DEFAULT_DATA_TIMEOUT_MS = 120_000

    const val DEFAULT_ENCODING = "UTF-8"

    val ALL_KEYS: Set<String> =
        setOf(ANONYMOUS, SIGNING, TIMEOUT_MS, DATA_TIMEOUT_MS, FORCE_SMB1, ENCODING, SHARE_SUBPATH)
}

/** 已解析的连接参数。把 `Map<String,String>` 的字符串解析收在一处，避免散落的 `toBoolean()`。 */
data class SmbSettings(
    val anonymous: Boolean,
    val signing: Boolean,
    val timeoutMs: Int,
    val dataTimeoutMs: Int,
    val forceSmb1: Boolean,
    val encoding: String,
) {
    companion object {

        fun parse(options: Map<String, String>): SmbSettings = SmbSettings(
            anonymous = options[SmbOptionKeys.ANONYMOUS].toFlag(default = false),
            // ⚠️ 默认**关**（M6 性能修复）。
            //
            // M5.1 之前的默认值是"开"，理由是"现代 NAS 普遍支持，不签名的 SMB2
            // 完整性只靠网络层"。这个理由本身没错，但它**没有把代价算进去**：
            // 打开时 smbj 会给每个出站报文套 `PacketSignatory`，而家庭局域网里
            // 这条链路的真实威胁模型是"自己家的 NAS 与自己的手机"。
            // 代价则是实打实的吞吐损失，且真机反馈已经证实（影片卡顿）——
            // 于是默认值改判为关，**服务端要求签名时由 [SmbConnector] 自动带签名重试**，
            // 需要显式开启的用户仍可在「高级选项」里打开。
            signing = options[SmbOptionKeys.SIGNING].toFlag(default = false),
            timeoutMs = options[SmbOptionKeys.TIMEOUT_MS]?.toIntOrNull()
                ?.coerceIn(1_000, 120_000)
                ?: SmbOptionKeys.DEFAULT_TIMEOUT_MS,
            dataTimeoutMs = options[SmbOptionKeys.DATA_TIMEOUT_MS]?.toIntOrNull()
                ?.coerceIn(SmbOptionKeys.DEFAULT_TIMEOUT_MS, 600_000)
                ?: SmbOptionKeys.DEFAULT_DATA_TIMEOUT_MS,
            forceSmb1 = options[SmbOptionKeys.FORCE_SMB1].toFlag(default = false),
            encoding = options[SmbOptionKeys.ENCODING]?.takeIf { it.isNotBlank() }
                ?: SmbOptionKeys.DEFAULT_ENCODING,
        )

        /**
         * 宽松的布尔解析。
         *
         * 数据库里的值可能来自手改的 JSON 或老版本写入的 `"1"`/`"yes"`，
         * 遇到无法识别的值回落默认值，而不是抛异常让整条连接打不开。
         */
        private fun String?.toFlag(default: Boolean): Boolean = when (this?.trim()?.lowercase()) {
            null, "" -> default
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }
}
