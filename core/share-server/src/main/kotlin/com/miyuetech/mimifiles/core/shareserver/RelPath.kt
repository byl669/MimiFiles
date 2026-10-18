package com.miyuetech.mimifiles.core.shareserver

import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 分享服务端的**相对路径**规范化。
 *
 * ## 为什么这一层单独存在、而且必须先做
 * HTTP 与 FTP 都是"客户端给一个字符串，服务端去访问文件"。这个字符串完全由
 * 客户端控制——`GET /f/../../../../data/data/com.miyuetech.mimifiles/databases/connections.db`
 * 是完全合法的 HTTP 请求。**每一个**把路径拼给文件系统的动作都必须先经过这里，
 * 否则就是一次任意文件读取（乃至删除）。
 *
 * 所以本对象的设计原则是：
 *  1. **纯函数、零 IO**：能在 JVM 上把边界条件（`..` 越狱、NUL 字节、反斜杠、
 *     百分号编码、超长）一条条测穿，而不需要起服务端、不需要设备；
 *  2. **失败即 `null`**，不抛异常也不"尽力而为地修一下"。越狱就当作路径不存在——
 *     既阻止了访问，也不给探测者"你的路径有点问题"这种回显（那等于告诉他
 *     他的攻击语法已经被解析了）；
 *  3. **编码解码在调用方**：HTTP 层先做百分号解码、FTP 层先按配置的 charset 解码，
 *     再交给这里。顺序不能反——先规范化再解码，`%2e%2e%2f` 就能绕过所有检查
 *     （它当时还不含 `..`）。这条在 [NormalizationOrder] 里又强调了一遍。
 */
object RelPath {

    /** 根的相对路径 —— 空串，与 [FsPath.path] 的"根为空串"约定一致。 */
    const val ROOT = ""

    /** 路径段数上限。正常目录树远到不了这里，超了只可能是畸形或恶意输入。 */
    const val MAX_DEPTH = 256

    /** 单段长度上限（Android 的 `NAME_MAX` 是 255 字节，UTF-8 下留一倍余量足够）。 */
    const val MAX_SEGMENT_LENGTH = 1024

    /**
     * 规范化客户端给的相对路径。
     *
     * @param raw 客户端给的路由/命令参数；`null` 与空串都表示"没有指定 → 根"
     *（HTTP 的 `?path=` 缺省、FTP 的 `CWD` 无参都走这里）
     * @return 规范形式（根为 [ROOT]，否则形如 `a/b/c`，**无首尾斜杠**）；
     *         越狱、含 NUL、超长等非法输入返回 `null`
     */
    fun normalize(raw: String?): String? {
        // `null` 归到根而不是归到"非法"：**根是权限最小的那个位置**，
        // 把"没给路径"判成非法只会让"忘了传参数的调用点"表现成"整个面板读不到东西"
        // 这种极难定位的症状，而不会带来任何安全收益。
        if (raw == null) return ROOT
        if (raw.isEmpty() || raw == "/") return ROOT
        // NUL 会截断底层 C 字符串（`open("a\u0000b")` 实际打开的是 `a`），
        // 是经典的绕过手法，必须显式拒绝。
        if (raw.indexOf('\u0000') >= 0) return null

        // 反斜杠按分隔符处理。
        //
        // 这**看起来**会误伤文件名里带 `\` 的文件，但那个取舍是明确的：
        // ① Android/Linux 上 `\` 是合法文件名字符，实际几乎没人用；
        // ② Windows 客户端（资源管理器 FTP、老式工具）在个别命令里会发 `\`；
        // ③ 如果把它当普通字符，`..\..\etc\passwd` 会被当成**一个文件名**，
        //    底层 `File` 在 Android 上确实不会越狱（`\` 不是分隔符），
        //    但一旦共享根是 SMB 且落在 Windows 服务端上，行为就取决于对端了。
        // 拒绝一个罕见文件名 vs 留一条取决于对端的路径，选前者。
        val unified = raw.replace('\\', '/')

        val stack = ArrayList<String>(8)
        for (segment in unified.split('/')) {
            when (segment) {
                "", "." -> Unit

                ".." -> {
                    // 越狱：栈空时再 `..` 就跳出共享根了。直接判非法，不"钳在根"。
                    // 钳在根看起来更"宽容"，但那样 `/f/../../x` 会静默变成 `/f/x`，
                    // 用户与日志都看不出发生过越狱尝试。
                    if (stack.isEmpty()) return null
                    stack.removeAt(stack.size - 1)
                }

                else -> {
                    if (segment.length > MAX_SEGMENT_LENGTH) return null
                    if (stack.size >= MAX_DEPTH) return null
                    stack.add(segment)
                }
            }
        }
        return if (stack.isEmpty()) ROOT else stack.joinToString("/")
    }

    /** 上一级；根返回 `null`（没有"根的上级"）。 */
    fun parent(rel: String): String? {
        if (rel.isEmpty()) return null
        val idx = rel.lastIndexOf('/')
        return if (idx < 0) ROOT else rel.substring(0, idx)
    }

    /** 末段名；根返回空串。 */
    fun name(rel: String): String = if (rel.isEmpty()) "" else rel.substringAfterLast('/')

    /** 追加一个单段名。名字里出现 `/` 或等于 `..` 时返回 `null`。 */
    fun child(rel: String, name: String): String? {
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return null
        if (name.indexOf('\u0000') >= 0) return null
        if (name.length > MAX_SEGMENT_LENGTH) return null
        return if (rel.isEmpty()) name else "$rel/$name"
    }

    /**
     * 两层防护合一：规范化 + 落到 [root] 上 + **再校验一次祖先关系**。
     *
     * 第三层看似多余（规范化已经不可能产出 `..`），但它守的是"以后有人改了
     * [normalize] 或换了个不走本对象的调用点"这类未来风险。一次 `isAncestorOf`
     * 是一次纯字符串前缀比较，成本可以忽略；而它挡下的是任意文件访问。
     */
    fun resolve(root: FsPath, raw: String?): FsPath? {
        val rel = normalize(raw) ?: return null
        val target = if (rel == ROOT) root else root.resolve(rel)
        return if (root.isAncestorOf(target) || root == target) target else null
    }
}

/**
 * 文档性质的提醒：**解码必须在规范化之前**。
 *
 * 这是唯一一处"写代码注释不够、值得单独立个类型"的地方，因为它是一个
 * 静默失效的时序依赖——出问题时表现是"某个不常见的 URL 能读到根以外的文件"，
 * 而不是报错。
 *
 * | 顺序 | 输入 | 结果 |
 * |---|---|---|
 * | 正确：先解码再规范化 | `%2e%2e%2fsecret` | 解码成 `../secret` → 判 `null`，拒绝 ✅ |
 * | 错误：先规范化再解码 | `%2e%2e%2fsecret` | 规范化时不含 `..`，原样通过 → 到文件系统层才解码 → 越狱 ❌ |
 */
private object NormalizationOrder
