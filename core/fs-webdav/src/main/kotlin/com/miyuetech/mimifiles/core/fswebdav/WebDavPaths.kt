package com.miyuetech.mimifiles.core.fswebdav

import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsPath

/**
 * `FsPath` ↔ WebDAV URL 的换算。
 *
 * ## 为什么必须逐段编码
 * 方案第 5.3 节把这条列为"兼容坑"的第一条，它是**最容易出且最难查**的一类：
 * 中文/韩文文件名、带空格的名字、带 `#` 或 `?` 的名字，如果整体丢给
 * `URLEncoder.encode(fullPath)`，斜杠会被编成 `%2F`，服务端看到的是一个
 * 名叫 `a%2Fb%2Fc` 的**单个文件**，于是 `PROPFIND` 返回 404 或空列表——
 * 现象是"目录打不开"，而真正的原因在编码。
 *
 * 所以这里逐段编码、再用 `/` 拼回去。
 *
 * ## 另一条：不要把已编码的 href 再编码一遍
 * `PROPFIND` 的 207 响应里，`<D:href>` 常常**已经是 percent-encoded** 的
 * （例如 `/dav/%E6%96%87%E4%BB%B6.txt`）。解析回来时若再交给 `URLDecoder`
 * 之外的处理链路，或者把"已编码的 href"当作新路径再编码，就会变成双重编码
 * （`%25E6...`）。[WebDavPaths.decodeHref] 负责把它还原成明文路径段。
 */
internal object WebDavPaths {

    /** 依据连接档案拼出 URL 前缀：`https://host[:port]/rootPath`（无尾斜杠）。 */
    fun baseUrl(profile: ConnectionProfile): String {
        val secure = profile.options[WebDavOptionKeys.ALLOW_INSECURE]?.lowercase() != "true"
        // 端口决定协议：443 → https，80 → http，其余按是否允许明文判断。
        // 用户填 8443 这类自定义 HTTPS 端口时，靠 allowInsecure 反推比靠端口号猜更准。
        val scheme = when {
            profile.port == 443 -> "https"
            profile.port == 80 -> "http"
            secure -> "https"
            else -> "http"
        }
        val authority = if (profile.port > 0 && profile.port != 443 && profile.port != 80) {
            "${profile.host}:${profile.port}"
        } else {
            profile.host
        }
        val root = profile.rootPath.trim().trim('/')
        return if (root.isEmpty()) "$scheme://$authority" else "$scheme://$authority/$root"
    }

    /** `FsPath` → 完整 URL。[asDirectory] 控制是否补尾斜杠（目录必须补，见类注释）。 */
    fun urlOf(baseUrl: String, path: FsPath, asDirectory: Boolean): String {
        val encoded = path.segments.joinToString("/") { encodeSegment(it) }
        val body = if (encoded.isEmpty()) "$baseUrl/" else "$baseUrl/$encoded"
        return if (asDirectory && !body.endsWith("/")) "$body/" else body
    }

    /**
     * 把 URL 还原成相对 base 的明文路径段。
     *
     * 用于解析 `PROPFIND` 返回的 `href`。要做三件事：
     * 1. 去掉 scheme/host（有的服务端返回绝对 URL）；
     * 2. 去掉 base 前缀（Nextcloud 这类会把 `/remote.php/dav/files/user/` 整段带上）；
     * 3. **先解码再切段**——反过来会在名字里含 `%2F` 时切错。
     */
    fun segmentsOf(href: String, baseUrl: String): List<String> {
        var value = href.trim()
        // 1) 绝对 URL → 只留路径
        val schemeIdx = value.indexOf("://")
        if (schemeIdx >= 0) {
            val pathStart = value.indexOf('/', schemeIdx + 3)
            value = if (pathStart < 0) "/" else value.substring(pathStart)
        }
        // 2) 去 query / fragment（有些服务端会带 ?dc=...）
        value = value.substringBefore('?').substringBefore('#')
        // 3) 解码后切段；解码失败（非法 %）就按原样处理，不让一个坏 href 炸掉整次列举
        val decoded = runCatching { decode(value) }.getOrDefault(value)
        val basePath = runCatching { pathPartOf(baseUrl) }.getOrDefault("")
        val relative = when {
            basePath.isEmpty() -> decoded
            decoded == basePath -> ""
            decoded.startsWith("$basePath/") -> decoded.substring(basePath.length)
            else -> decoded
        }
        return relative.split('/').filter { it.isNotEmpty() && it != "." }
    }

    private fun pathPartOf(url: String): String {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx < 0) return url.trimEnd('/')
        val pathStart = url.indexOf('/', schemeIdx + 3)
        return if (pathStart < 0) "" else url.substring(pathStart).trimEnd('/')
    }

    /** 单段编码。不是 `URLEncoder` 默认的那套（它把空格编成 `+`，路径里必须编成 `%20`）。 */
    fun encodeSegment(segment: String): String = buildString(segment.length + 8) {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt() and 0xFF
            val unreserved = ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code ||
                ch in '0'.code..'9'.code ||
                ch == '-'.code || ch == '_'.code || ch == '.'.code || ch == '~'.code
            if (unreserved) {
                append(ch.toChar())
            } else {
                append('%').append(HEX[ch shr 4]).append(HEX[ch and 0x0F])
            }
        }
    }

    /** `%XX` 解码（不处理 `+`——路径里的 `+` 是字面加号，不是空格）。 */
    fun decode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) + lo)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private const val HEX = "0123456789ABCDEF"
}
