package com.miyuetech.mimifiles.core.fssmb

import com.miyuetech.mimifiles.core.common.MimiLog
import jcifs.util.Crypto
import java.security.MessageDigest

/**
 * SMB1（jcifs-ng）所需加密能力的一次性自检。
 *
 * ## 为什么值得单独存在
 * NTLM 认证的第一步是 `NT 哈希 = MD4(UTF-16LE 密码)`。jcifs **不自带 MD4**，
 * 而是向 BouncyCastle 借（`jcifs.util.Crypto.getMD4()`）。而 BC 的算法注册是
 * **运行期字符串反射**，R8 的可达性分析看不见那条字符串 —— 于是"类被剪掉、
 * 算法从未注册"是个**编译期全绿、只在真机 release 包上炸**的失败模式
 * （M5.1 实测：默认设置下 SMB 完全不可用）。
 *
 * 这类失败一旦发生，用户看到的是：
 *
 *     java.security.NoSuchAlgorithmException: no such algorithm: MD4 for provider BC
 *
 * 一句 Java 内部文本，既读不懂、也不知道该做什么。本对象把关口前移：
 * **建连之前先真算一次**，算不出来就直接给一句能照做的话。
 *
 * ## 为什么"真算一次"而不是"判断类在不在"
 * `Class.forName` 只能证明类存在，证明不了算法可用 —— BC 的失败恰恰发生在
 * "类在、但 `$Mappings` 被剪掉所以没注册"这一层。用 RFC 1320 的官方向量
 * `MD4("abc")` 跑一遍，才算真的覆盖了整条链（provider → 注册表 → 实例化 → 计算）。
 *
 * ## 为什么结论要缓存
 * 结果在进程生命周期内不会变（它只取决于安装包本身），而 SMB 连接是高频操作。
 * 用 `@Volatile` + 双检锁，避免每次建连都做一次 SHA 级别的无用功。
 */
internal object SmbCrypto {

    private const val TAG = "MimiSmbCrypto"

    /**
     * RFC 1320 附录 A.5 的官方向量：`MD4("abc")`。
     *
     * 选它而不是空串向量：空串会走到"没有 update 就 digest"的分支，实现里
     * 长度填充那段几乎不被执行；"abc" 是完整走完一轮压缩函数的输入，覆盖更好。
     */
    private val MD4_OF_ABC = byteArrayOf(
        0xA4.toByte(), 0x48, 0x01, 0x7A, 0xAF.toByte(), 0x21, 0xD8.toByte(), 0x52,
        0x5F, 0xC1.toByte(), 0x0A, 0xE8.toByte(), 0x7A, 0xA6.toByte(), 0x72, 0x9D.toByte(),
    )

    @Volatile
    private var checked = false

    /** null = 通过；非 null = 给用户看的原因。 */
    @Volatile
    private var failureReason: String? = null

    /**
     * 自检并发安全：`openSmb1` 可能被多个连接请求同时进入（多标签页/后台探测），
     * 双检锁保证只算一次，其余线程直接复用结论。
     */
    fun verify(): String? {
        if (checked) return failureReason
        synchronized(this) {
            if (checked) return failureReason
            failureReason = runCheck()
            checked = true
        }
        return failureReason
    }

    private fun runCheck(): String? = try {
        val md: MessageDigest = Crypto.getMD4()
        md.update("abc".toByteArray(Charsets.US_ASCII))
        val got = md.digest()
        if (got.contentEquals(MD4_OF_ABC)) {
            MimiLog.i(TAG, "MD4 self-check passed (RFC 1320 vector)")
            null
        } else {
            // 算出结果但不对：比"算不出来"更严重（可能是 provider 被别的库抢先注册）。
            // 一样给用户可操作的出路，不把它当成内部错误抛。
            val hex = got.joinToString("") { "%02x".format(it) }
            MimiLog.e(TAG, "MD4 self-check got wrong digest: $hex (expect a448017aaf21d8525fc10ae87aa6729d)")
            "SMB1 认证所需的 MD4 算法计算结果异常。请升级到最新版本；若已是最新，请在「高级选项」里关掉「强制 SMB1」"
        }
    } catch (t: Throwable) {
        // jcifs 会把底层 NoSuchAlgorithmException 包成 CIFSUnsupportedCryptoException，
        // 所以这里 catch Throwable 而不是具体类型 —— 目的就是"无论怎么坏都给同一句话"。
        MimiLog.e(TAG, "MD4 self-check failed: ${t.javaClass.name}: ${t.message}", t)
        "SMB1 认证所需的 MD4 算法在当前安装包中不可用。请升级到最新版本；若已是最新，请在「高级选项」里关掉「强制 SMB1」改用 SMB2/3"
    }
}
