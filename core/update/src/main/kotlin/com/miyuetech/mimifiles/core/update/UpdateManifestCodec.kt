package com.miyuetech.mimifiles.core.update

import org.json.JSONObject

/**
 * `manifest.json` 的解析。
 *
 * ## 为什么是"严格解析"而不是 `optXxx` 兜底
 * 参考实现（相册 App）用的是 `o.optInt("versionCode", -1)` 这类写法。它的问题是
 * **失败方向恰好是错的那一边**：字段缺失时 `versionCode` 取到 -1，而 -1 不大于本地版本，
 * 于是界面显示「已是最新版本」——一个**看起来很正常的错误答案**。
 * 线上表现就是"新版本发布了，但所有人都说没有更新"，而排查时服务器上的文件明明在。
 *
 * 所以这里反过来：**必填字段缺一个就抛**，并且把"缺哪个、实际是什么"写进消息。
 * 一个说得出原因的失败，好过一个安静的错答案。
 *
 * ## 必填与选填的分界
 * - 必填：`versionCode`（升级判定的唯一依据）、`versionName`（界面要显示）、
 *   `files`（没包等于没得升）。只要有一个不成立，这份清单就没有可用信息；
 * - 选填：`publishedAt` / `notes`（纯展示，空了就空了）、`minSupported`（默认 0 = 不强更）。
 */
object UpdateManifestCodec {

    /** SHA-256 十六进制串的合法长度。 */
    private const val SHA256_HEX_LENGTH = 64

    /**
     * 解析清单。
     *
     * @throws UpdateException 结构非法或必填字段缺失
     */
    fun parse(json: String): UpdateManifest {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            // 拿到的可能是 nginx 的 404/403 HTML 页（静态服务出错时会这样）
            throw UpdateException(
                "版本清单不是合法的 JSON（可能拿到的是服务器的错误页）：${json.take(80).replace('\n', ' ')}",
                e,
            )
        }

        // versionCode 允许是 JSON 数字，也允许是字符串（有些手写清单会带上引号）。
        // 这两种都收，但**必须真的能解析成正整数**。
        val versionCode = when (val raw = root.opt("versionCode")) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        } ?: throw UpdateException("版本清单缺少 versionCode，或它不是整数（实际：${describe(root.opt("versionCode"))}）")
        if (versionCode <= 0) {
            throw UpdateException("版本清单的 versionCode 必须是正整数（实际：$versionCode）")
        }

        val versionName = root.optString("versionName").trim()
        if (versionName.isEmpty()) {
            throw UpdateException("版本清单缺少 versionName")
        }

        val filesArray = root.optJSONArray("files")
            ?: throw UpdateException("版本清单缺少 files 数组")
        if (filesArray.length() == 0) {
            throw UpdateException("版本清单的 files 是空的，没有可下载的安装包")
        }

        val files = ArrayList<UpdateApkFile>(filesArray.length())
        for (i in 0 until filesArray.length()) {
            val item = filesArray.optJSONObject(i)
                ?: throw UpdateException("files[$i] 不是对象")
            files += parseFile(item, i)
        }

        return UpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            publishedAt = root.optString("publishedAt").trim(),
            notes = root.optString("notes").trim(),
            // 缺省 0：不强制。**不**给一个"聪明"的默认值（比如等于 versionCode），
            // 那会让一次忘记写 minSupported 的发布变成"强制所有人立即更新"。
            minSupported = root.optInt("minSupported", 0),
            files = files,
        )
    }

    private fun parseFile(item: JSONObject, index: Int): UpdateApkFile {
        val url = item.optString("url").trim()
        if (url.isEmpty()) throw UpdateException("files[$index] 缺少 url")

        val abi = item.optString("abi").trim()
        if (abi.isEmpty()) throw UpdateException("files[$index] 缺少 abi")

        val sha = item.optString("sha256").trim().lowercase()
        if (sha.length != SHA256_HEX_LENGTH || !sha.all { it in '0'..'9' || it in 'a'..'f' }) {
            // 这一条挡掉的是"发布脚本忘了算哈希、把占位符原样发上去"这类事故：
            // 那种清单能通过长度检查，但校验时必然失败，而失败信息会指向"下载损坏"，
            // 把人往完全错的方向带。在这里就说清楚是清单的问题。
            throw UpdateException("files[$index] 的 sha256 不是 64 位十六进制（实际：${sha.take(24)}…）")
        }

        return UpdateApkFile(
            abi = abi,
            url = url,
            sha256 = sha,
            size = item.optLong("size", 0L).coerceAtLeast(0L),
        )
    }

    /** 把"实际拿到了什么"写进错误消息 —— 排查时这一句往往就够定位了。 */
    private fun describe(value: Any?): String = when (value) {
        null -> "不存在"
        is String -> "\"$value\""
        else -> value.toString()
    }
}
