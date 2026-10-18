package com.miyuetech.mimifiles.core.fssmb

/**
 * 服务器地址的解析与清洗。
 *
 * ## 为什么需要它
 * 「服务器地址」这一栏，用户手里真正有的东西通常是**另一个程序里那份完整的地址**：
 * Windows 资源管理器地址栏的 `\\192.168.1.100\stock`、浏览器里的
 * `smb://192.168.1.100/stock`、或者别人发给他的 `192.168.1.100:445`。
 * 复制粘贴是最自然的行为，而"只填主机名"这个要求没有任何提示能教会他。
 *
 * 不处理的后果很具体：`\\192.168.1.100\stock` 会被当成一个**主机名**丢给 DNS，
 * 失败后报"连不上 \\192.168.1.100\stock"——把真实原因（这份地址里带了共享名）
 * 完全藏起来，用户只会觉得"应用连 SMB 都不行"。
 *
 * ## 与保存路径共用同一套规则
 * 「测试」与「保存」都走 [parse]，否则会出现"测试通过、保存后打不开"——
 * 因为测试时清洗过、保存时没有。
 */
object SmbAddress {

    /**
     * @param host 清洗后的主机名/字面 IP（**可能为空**，调用方需自行校验）
     * @param share 地址里带出来的共享名；没有则为 `null`
     * @param port 地址里带出来的端口；没有则为 `null`
     */
    data class Parsed(val host: String, val share: String? = null, val port: Int? = null) {

        /** 地址是否原样可用（没有任何需要清洗的东西）。用于决定要不要回填表单。 */
        val isClean: Boolean get() = share == null && port == null
    }

    /**
     * 容错解析。
     *
     * 支持这些形态（全部实测过）：
     * ```
     * 192.168.1.100                     → host=192.168.1.100
     * 192.168.1.100:4450                → host=192.168.1.100  port=4450
     * \\192.168.1.100\stock             → host=192.168.1.100  share=stock
     * \\192.168.1.100\stock\sub         → host=192.168.1.100  share=stock   （子路径在 SMB 里属于共享内路径，归共享名）
     * smb://192.168.1.100/stock         → host=192.168.1.100  share=stock
     * smb://192.168.1.100:4450/stock    → host=192.168.1.100  port=4450  share=stock
     * //192.168.1.100/stock             → host=192.168.1.100  share=stock
     * nas.local                        → host=nas.local
     * ```
     *
     * ## 两个刻意的决定
     *  1. **不把主机名小写化**：NetBIOS/主机名在 DNS 里大小写不敏感，但 SMB 服务端的
     *     共享名**大小写敏感**，而这里同时处理两者。统一小写会把 `SHARE` 改成 `share`，
     *     在区分大小写的服务端上就是"共享不存在"。
     *  2. **不含 `\\`/`smb://` 时不做任何猜测**：`192.168.1.100/stock` 这种写法
     *     也按"第一个 `/` 之前是主机"处理——它与 UNC 同构，没有歧义。
     */
    fun parse(raw: String): Parsed {
        var text = raw.trim()
        if (text.isEmpty()) return Parsed("")

        // ① 去掉协议前缀（大小写不敏感）
        for (prefix in listOf("smb://", "cifs://", "smb:", "cifs:")) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length)
                break
            }
        }
        // ② 去掉 UNC 前缀（`\\host\share` / `//host/share`）
        text = text.trimStart('\\', '/')
        if (text.isEmpty()) return Parsed("")

        // ③ 切出第一段作为主机（其余是共享/路径）
        val slash = text.indexOfFirst { it == '/' || it == '\\' }
        val head = if (slash >= 0) text.substring(0, slash) else text
        val tail = if (slash >= 0) text.substring(slash + 1) else ""

        // ④ 主机里的 `:port`。只接受**全是数字**的尾巴：IPv6 字面量（`[::1]:445`）里
        //    也有冒号，靠这个判据可以把它排除掉而不必专门解析 IPv6。
        var host = head.trim()
        var port: Int? = null
        val colon = host.lastIndexOf(':')
        if (colon > 0 && host.indexOf(':') == colon) {
            host.substring(colon + 1).toIntOrNull()?.let {
                if (it in 1..65535) {
                    port = it
                    host = host.substring(0, colon).trim()
                }
            }
        }

        // ⑤ 剩下的一整段就是"共享名 + 可选子路径"。**不在这里切开**：
        //    切了之后 `\\nas\stock\2024` 会变成"共享名 = stock"，把 `2024` 丢掉。
        //    而 `rootPath` 本来就允许写成 `share/sub`（见 ConnectionEditActivity），
        //    整段交出去正好。
        //    分隔符统一成正斜杠：`rootPath` 的其余使用者（浏览、分享、SAF）一律按 `/` 切分，
        //    混进反斜杠会被当成**文件名字符**，造出一个带 `\` 的怪目录名。
        val share = tail.trim().trim('/', '\\').replace('\\', '/').takeIf { it.isNotEmpty() }

        return Parsed(host = host, share = share, port = port)
    }
}
