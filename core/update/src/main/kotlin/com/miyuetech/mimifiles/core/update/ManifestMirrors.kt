package com.miyuetech.mimifiles.core.update

/**
 * 某一份清单 + 它来自哪个镜像地址。
 */
internal data class MirrorManifest(
    val url: String,
    val manifest: UpdateManifest,
)

/**
 * 多镜像清单的取值规则：**取 versionCode 最大的那一份**。
 *
 * ## 为什么不是"首个成功就用"（M7.21 的正式教训）
 * 各镜像的缓存互相独立。`fastly.jsdelivr.net` 与 `cdn.jsdelivr.net` 是两套
 * 命名空间，`purge.jsdelivr.net` 只清后者，前者实测能滞留旧清单 12 小时
 * （`s-maxage=43200`）。于是"首个成功就用"存在一个**只朝一个方向**的错：
 *
 * - 陈旧镜像 → 版本号偏小 → 客户端算出"已是最新版本" → 用户被告知没有新版，
 *   而新版其实已发布。不报错、不留痕，比直接报失败危险得多；
 * - 反过来不存在"版本号偏大"的可能 —— 清单只在发布时写入，缓存只会落后。
 *
 * 所以取 max 在本协议下是**恒安全**的：它只会拉平滞后，不会凭空造出新版本。
 *
 * 同版本并列时保留**先出现的**（即镜像优先级更高的那个），这样
 * `activeManifestUrl`（下载相对路径的基准）仍指向用户配置的首选镜像。
 */
internal object ManifestMirrors {

    /** 取最新的一份；列表为空返回 `null`。并列取先出现的。 */
    fun newest(candidates: List<MirrorManifest>): MirrorManifest? {
        var best: MirrorManifest? = null
        for (candidate in candidates) {
            val current = best
            if (current == null || candidate.manifest.versionCode > current.manifest.versionCode) {
                best = candidate
            }
        }
        return best
    }
}
