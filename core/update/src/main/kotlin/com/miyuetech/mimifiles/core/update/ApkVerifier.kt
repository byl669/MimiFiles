package com.miyuetech.mimifiles.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.miyuetech.mimifiles.core.common.MimiLog
import java.io.File
import java.security.MessageDigest

/**
 * 安装包的三重校验。
 *
 * # 为什么"三重"而不是"验个哈希就行"
 * 服务器上只有一个静态文件服务，**没有任何密钥**。也就是说：能往那个目录写文件的人
 * （或者拿到 WebDAV 凭据的人）就能替换掉清单和安装包。所以"下载下来的东西可信"
 * 这件事不能靠服务器，只能靠**客户端本地能不能验证它**。
 *
 * 三道闸各挡一类问题，缺任何一道都有一个具体的攻击面：
 *
 * | 道 | 检查 | 挡住了什么 |
 * |---|---|---|
 * | ① | SHA-256 与清单一致 | 传输损坏（断网、代理截断）、中间人改包 |
 * | ② | 签名证书指纹与本应用一致 | **整体替换**：攻击者换一个自己的包（这道的强度等于 Android 的签名模型本身 —— 没有 `release.jks` 就伪造不出） |
 * | ③ | 包名 + 包内 versionCode 与清单一致，且严格高于当前版本 | 清单被指向另一个应用 / 另一份构建；以及"用同版本包把你刷成离线状态"这类降级 |
 *
 * ②③ 必须同时有：只有②时，攻击者可以拿一个**你签过的旧版本**（比如某个有漏洞的
 * 老版本）来当"更新"，客户端会老老实实装上；只有③时，攻击者自己签一个版本号更高的
 * 包就能装上。两条一起才封住。
 *
 * # 失败时的行为
 * **删文件 + 报错，绝不进入安装流程**。这是硬约定：校验逻辑一旦有"失败也先装上看看"
 * 的分支，整个签名锚点就退化成装饰。
 */
class ApkVerifier(private val context: Context) {

    sealed interface Result {
        data object Ok : Result
        data class Fail(val reason: String) : Result
    }

    /**
     * @param apk 已下载到本地的安装包
     * @param manifest 这次比对用的清单
     * @param file 清单里对应的那一条
     */
    fun verify(apk: File, manifest: UpdateManifest, file: UpdateApkFile): Result {
        if (!apk.exists() || apk.length() <= 0L) return Result.Fail("安装包文件不存在或为空")

        // ① 完整性
        val actual = sha256Hex(apk)
        if (!actual.equals(file.sha256, ignoreCase = true)) {
            return Result.Fail(
                "安装包完整性校验失败（期望 ${file.sha256.take(12)}…，实际 ${actual.take(12)}…）",
            )
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: return Result.Fail("无法读取安装包信息（文件可能已损坏）")

        // ③ 包名与版本
        if (info.packageName != context.packageName) {
            return Result.Fail("安装包不是本应用（包名 ${info.packageName}）")
        }
        val apkVersionCode = versionCodeOf(info)
        if (apkVersionCode != manifest.versionCode.toLong()) {
            return Result.Fail(
                "安装包版本($apkVersionCode)与版本清单(${manifest.versionCode})不一致，" +
                    "可能是清单与包不配套",
            )
        }
        val installedCode = AppVersions.read(context).versionCode
        if (apkVersionCode <= installedCode) {
            // 这一条是**降级保护**：没有它，任何人都能把你的应用"更新"回一个老版本
            // （而老版本可能正好有已修复的漏洞）。
            return Result.Fail("这不是新版本（安装包 $apkVersionCode ≤ 当前 $installedCode）")
        }

        // 提前挡掉"系统版本不够"，否则错误只会在系统安装器里以一句通用失败出现
        val apkMinSdk = info.applicationInfo?.minSdkVersion ?: 0
        if (apkMinSdk > Build.VERSION.SDK_INT) {
            return Result.Fail(
                "这个更新包要求 Android $apkMinSdk 及以上，本机是 Android ${Build.VERSION.SDK_INT}",
            )
        }

        // ② 签名
        @Suppress("DEPRECATION")
        val signatures = info.signatures
        if (signatures.isNullOrEmpty()) {
            return Result.Fail("安装包里没有签名信息")
        }
        val currentFingerprint = currentCertFingerprint()
            ?: return Result.Fail("无法读取当前应用的签名信息")
        val newFingerprint = fingerprintOf(signatures[0].toByteArray())
            ?: return Result.Fail("无法计算安装包的证书指纹")
        if (currentFingerprint != newFingerprint) {
            MimiLog.w(TAG, "签名不符：当前 $currentFingerprint / 安装包 $newFingerprint")
            return Result.Fail("安装包签名与当前应用不一致，已拒绝（可能是被替换过的文件）")
        }

        return Result.Ok
    }

    /** 当前运行中应用的签名证书 SHA-256 指纹（十六进制小写）。 */
    fun currentCertFingerprint(): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNATURES,
        )
        info.signatures?.firstOrNull()?.toByteArray()?.let { fingerprintOf(it) }
    }.getOrNull()

    private fun fingerprintOf(certBytes: ByteArray): String? = runCatching {
        MessageDigest.getInstance("SHA-256").digest(certBytes).toHex()
    }.getOrNull()

    /** 流式算 SHA-256（包有 6 MB，一次性读进来没必要）。 */
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().toHex()
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "MimiUpdate"
    }
}
