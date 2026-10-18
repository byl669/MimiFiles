package com.miyuetech.mimifiles.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.miyuetech.mimifiles.core.common.MimiLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据保险箱：把 SMB / WebDAV 的密码加密后交给数据库存。
 *
 * ## 为什么不用 `androidx.security:security-crypto`
 * 方案第 4.1 节写的是「Keystore / EncryptedSharedPreferences」二选一，这里选前者：
 *  1. `security-crypto` 长期停留在 `1.1.0-alpha`，把 alpha 依赖带进要分发的包不合适；
 *  2. 它会连带引入 tink + protobuf-lite，为"存几个字符串"付出几百 KB 不划算——
 *     而本项目的验收标准里有明确的体积红线；
 *  3. 我们真正需要的只是「AES-GCM 加解密 + 从 KeyStore 取钥」，直接用平台 API
 *     就是下面这一百来行，且**每一行都在我们自己的掌控里**（出问题能查、能改）。
 *
 * ## 密钥与密文的形态
 * - **密钥**：AES-256，生成后**永不离开 AndroidKeyStore**（`setUserAuthenticationRequired(false)`
 *   表示不需要每次解锁时验证指纹——文件管理器每次进目录都要连 NAS，加生物识别会毁掉体验。
 *   安全边界交给"设备已解锁 + 应用沙箱"这一层）。
 * - **密文**：`v1:<base64(iv‖ciphertext)>`。IV 由 Cipher 随机生成（12 字节），
 *   与密文拼在一起存。**前缀带版本号**是为了将来能换算法而不必清库。
 * - **AAD**：把连接 id 作为附加认证数据。这样即使有人把 A 连接的密文复制到 B 连接行上，
 *   解密也会失败——防的是"密文移植"这种本地篡改，成本几乎为零。
 *
 * ## 密钥丢失怎么办（真实会发生）
 * 恢复出厂、用户在系统里"清除凭据"、部分 ROM 的系统更新，都会让 AndroidKeyStore 里的
 * 旧密钥失效。此时解密抛 `KeyStoreException` / `AEADBadTagException`。
 * **这里的选择是"解密失败返回 null"**，由上层删掉坏凭据并提示重新输入密码——
 * 绝不能让它抛到 UI 变成崩溃，那会让用户连"删掉这条连接"都做不到。
 */
class CredentialVault(
    private val alias: String = DEFAULT_ALIAS,
    private val keyStoreProvider: () -> KeyStore = { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } },
) {

    private val lock = Any()

    @Volatile
    private var cachedKey: SecretKey? = null

    // ── 加密 ────────────────────────────────────────────────────────────

    /** 加密；失败返回 `null`（调用方应视为"这条连接不能保存密码"）。 */
    fun encrypt(plain: String, associatedData: String? = null): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        associatedData?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + cipherText
        PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    } catch (t: Throwable) {
        MimiLog.w(TAG, "encrypt failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    // ── 解密 ────────────────────────────────────────────────────────────

    /** 解密；任何失败（格式错、密钥丢失、AAD 不匹配）统一返回 `null`。 */
    fun decrypt(token: String, associatedData: String? = null): String? = try {
        if (!token.startsWith(PREFIX)) {
            null
        } else {
            val packed = Base64.decode(token.substring(PREFIX.length), Base64.NO_WRAP)
            if (packed.size <= IV_LENGTH) {
                null
            } else {
                val iv = packed.copyOfRange(0, IV_LENGTH)
                val body = packed.copyOfRange(IV_LENGTH, packed.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                associatedData?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
                String(cipher.doFinal(body), Charsets.UTF_8)
            }
        }
    } catch (t: Throwable) {
        MimiLog.w(TAG, "decrypt failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    // ── 维护 ────────────────────────────────────────────────────────────

    /** 保险箱是否可用（Keystore 存在且能取到/生成密钥）。连接页用它决定"能不能保存密码"。 */
    fun isAvailable(): Boolean = try {
        secretKey() != null
    } catch (t: Throwable) {
        MimiLog.w(TAG, "keystore unavailable: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * 销毁密钥。
     *
     * 只在用户主动"清除全部凭据"时调用。调用后**之前加密的密文全部作废**，
     * 这是设计意图而不是缺陷——钥匙扔了，锁里的东西就该打不开。
     */
    fun reset() = synchronized(lock) {
        try {
            keyStoreProvider().deleteEntry(alias)
        } catch (t: Throwable) {
            MimiLog.w(TAG, "reset failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        cachedKey = null
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun secretKey(): SecretKey = cachedKey ?: synchronized(lock) {
        cachedKey?.let { return it }
        val ks = keyStoreProvider()
        val existing = (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: generateKey(ks)
        cachedKey = key
        key
    }

    private fun generateKey(ks: KeyStore): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证：文件管理器每次进目录都要连 NAS，加生物识别会毁掉体验。
                .setUserAuthenticationRequired(false)
                // 默认即为 true（禁止调用方自带 IV），这里显式写出来是为了让"IV 必须随机"
                // 这条安全属性在代码里可见，而不是隐式依赖默认值。
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        val key = generator.generateKey()
        MimiLog.i(TAG, "generated new AES-256 credential key: $alias")
        return key
    }

    companion object {
        private const val TAG = "MimiVault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH = 12
        private const val PREFIX = "v1:"
        private const val DEFAULT_ALIAS = "mimi_credential_key_v1"

        /** 校验一个字符串是否为保险箱产物（不做解密，仅用于 UI 判断"这行有没有存密码"）。 */
        fun looksEncrypted(token: String?): Boolean = token != null && token.startsWith(PREFIX)
    }
}
