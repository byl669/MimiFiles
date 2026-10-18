package com.miyuetech.mimifiles.core.update

import java.net.URI

/**
 * "有没有新版、该下哪个包"的判定。
 *
 * ## 为什么单独拆出来
 * 这三件事（版本比对、选包、地址解析）**全部可以离线推演**，却决定了更新链路
 * 会不会给出错误答案。把它们做成不依赖 Android 的纯函数，就能用单测把每条边界
 * 钉死；留在引擎里跟 HTTP、文件系统混在一起，就只能靠真机试。
 *
 * 尤其 [resolveUrl]：它看起来只是拼字符串，但拼错的表现是"下载 404"，
 * 而 404 又会被人当成"服务器上没传包"——一个纯函数 + 一行断言就能永久避免。
 */
object UpdatePlanner {

    /** 判定结果。刻意只有两种情况：更新链路上不存在"状态不明"。 */
    sealed interface Decision {
        /** 远端不比本地新。 */
        data class UpToDate(val manifest: UpdateManifest) : Decision

        data class Available(val manifest: UpdateManifest, val file: UpdateApkFile) : Decision
    }

    /**
     * 比对版本并选出要下载的包。
     *
     * ## 判据是 `>` 而不是 `>=`
     * 相等表示"就是当前这一版"，不是新版本。写成 `>=` 的后果是：每次点「检查更新」
     * 都会提示发现新版本（其实是同一版），然后下载一遍、校验时被
     * "不是新版本"拒绝 —— 用户会经历一次莫名其妙的失败。
     *
     * @param supportedAbis 本机支持的 ABI，**按优先级排列**（直接用
     *        `Build.SUPPORTED_ABIS` 即可，它已经是这个顺序）
     */
    fun decide(
        manifest: UpdateManifest,
        installedVersionCode: Long,
        supportedAbis: List<String>,
    ): Decision {
        if (manifest.versionCode.toLong() <= installedVersionCode) {
            return Decision.UpToDate(manifest)
        }
        return Decision.Available(manifest, pickFile(manifest, supportedAbis))
    }

    /**
     * 选当前设备能装的那个包。
     *
     * ## 不做"arm64 设备也去拿 v7a 包"的兜底
     * 相册 App 里有这条兜底（它确实同时发 arm64 与 v7a 两个包）。本应用**只出
     * arm64 单包**，那种兜底在这里没有可命中的场景，却会引入一个新的风险：
     * 真·64 位专用设备（`SUPPORTED_ABIS` 里没有 `armeabi-v7a`）装上一个 32 位包
     * 之后会在启动时崩，而崩溃发生在"更新成功"之后 —— 用户只会觉得
     * "更新把应用搞坏了"，且没有任何办法自己退回去。
     *
     * 所以这里严格按 `SUPPORTED_ABIS` 匹配，匹配不到就明确报错，
     * 并把两边的 ABI 都写出来，让人一眼看出是发错包还是挑错包。
     */
    fun pickFile(manifest: UpdateManifest, supportedAbis: List<String>): UpdateApkFile {
        for (abi in supportedAbis) {
            manifest.files.firstOrNull { it.abi == abi }?.let { return it }
        }
        throw UpdateException(
            "版本清单里没有适配本机架构的安装包（本机：${supportedAbis.joinToString("、")}；" +
                "清单：${manifest.files.joinToString("、") { it.abi }}）",
        )
    }

    /**
     * 把清单里的 `url` 解析成绝对地址。
     *
     * ## 为什么要支持相对路径
     * 清单与包永远放在同一个目录下。如果每条 `url` 都写绝对地址，那么"换域名 /
     * 换端口 / 把目录段轮换一遍"（capability URL 泄露时的止损手段）就必须
     * 同步改清单里每一条 —— 漏一条就是 404。相对路径只改清单自身地址一处。
     *
     * @param manifestUrl 清单的绝对地址（解析基准）
     * @param url 清单里写的那一条（相对或绝对都收）
     */
    fun resolveUrl(manifestUrl: String, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return try {
            URI(manifestUrl).resolve(url).toString()
        } catch (e: Exception) {
            throw UpdateException("无法解析下载地址：$url（基准 $manifestUrl）", e)
        }
    }
}
