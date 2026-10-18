package com.miyuetech.mimifiles.core.model

/**
 * 跨存储统一的路径标识 —— **M1 冻结契约**。
 *
 * 文本形式为 `scheme://authority/path`：
 * ```
 * local:///storage/emulated/0/DCIM/Camera     本机（authority 为空）
 * smb://nas-01/share/photo                    SMB（authority = 连接 id）
 * webdav://wd-2/docs/report                   WebDAV（authority = 连接 id）
 * saf://grant-3/DCIM                          SAF 授权树（authority = 授权 id）
 * ```
 *
 * ## 冻结的不变量（所有后端实现都必须满足）
 * 1. [path] 恒以 `/` 开头；**根路径为空串**（不是 `"/"`）；末尾绝不含 `/`。
 * 2. 连续 `/`、`"."` 段被折叠；`".."` 只在本段内回退，**不能越过根**（避免 `../../..` 逃逸）。
 * 3. [toString] 的输出可被 [parse] 无损还原 —— 这是桌面快捷方式 extras、书签、
 *    最近访问记录、`DocumentsProvider` 文档 id 的统一持久化格式。**改动会破坏已发布数据的兼容性。**
 *
 * ## 为什么不用 `android.net.Uri`
 * 本类是纯 Kotlin（零 Android 依赖），因此可以在 JVM 单测里直接被穷举验证——
 * 路径规范化是最容易被边界条件咬到的地方，能跑快测比少写几行更重要。
 */
class FsPath private constructor(
    /** 后端标识，小写，如 `local` / `smb` / `webdav` / `saf`。 */
    val scheme: String,
    /** 同一后端下的实例标识（连接 id、授权 id）；本机存储为空串。 */
    val authority: String,
    /** 规范化后的绝对路径，根为空串。 */
    val path: String,
) {

    val isRoot: Boolean get() = path.isEmpty()

    /** 路径段（不含 scheme/authority）。根路径返回空列表。 */
    val segments: List<String>
        get() = if (path.isEmpty()) emptyList() else path.substring(1).split('/')

    val depth: Int get() = segments.size

    /** 末段名；根路径回退到 [authority]（再为空则回退到 [scheme]），保证 UI 永远有字可显示。 */
    val displayName: String
        get() = segments.lastOrNull() ?: authority.ifEmpty { scheme }

    val parent: FsPath?
        get() {
            if (isRoot) return null
            val idx = path.lastIndexOf('/')
            return FsPath(scheme, authority, if (idx <= 0) "" else path.substring(0, idx))
        }

    /** 扩展名（小写，不含点）；`.gitignore` 这类隐藏文件视为无扩展名。 */
    val extension: String
        get() {
            val n = displayName
            val dot = n.lastIndexOf('.')
            return if (dot <= 0 || dot == n.length - 1) "" else n.substring(dot + 1).lowercase()
        }

    val isHidden: Boolean get() = displayName.startsWith('.')

    /** 追加一个子项（单段名，不允许含 `/`）。 */
    fun child(name: String): FsPath {
        val clean = name.trim('/')
        require(clean.isNotEmpty()) { "child name must not be blank" }
        require(clean != "." && clean != "..") { "illegal child name: $clean" }
        return FsPath(scheme, authority, if (path.isEmpty()) "/$clean" else "$path/$clean")
    }

    /** 追加多段：`child("DCIM", "Camera")`。 */
    fun child(name: String, vararg more: String): FsPath =
        more.fold(child(name)) { acc, seg -> acc.child(seg) }

    /**
     * 相对解析。以 `/` 开头视为相对 `scheme://authority` 的根，否则相对当前路径。
     * 支持 `..`，但不会越过根。
     */
    fun resolve(relative: String): FsPath {
        val combined = when {
            relative.isEmpty() -> path
            relative.startsWith('/') -> relative
            path.isEmpty() -> "/$relative"
            else -> "$path/$relative"
        }
        return FsPath(scheme, authority, normalizePath(combined))
    }

    /** 向上 [levels] 层；越界则停在根。 */
    fun ancestor(levels: Int): FsPath {
        require(levels >= 0) { "levels must be >= 0" }
        var cur: FsPath = this
        repeat(levels) { cur = cur.parent ?: return cur }
        return cur
    }

    /** [this] 是否为 [other] 的祖先（同后端、同 authority，且路径前缀成立）。根是所有后代的祖先。 */
    fun isAncestorOf(other: FsPath): Boolean =
        scheme == other.scheme && authority == other.authority &&
            (path.isEmpty() || other.path.startsWith("$path/"))

    fun isDescendantOf(other: FsPath): Boolean = other.isAncestorOf(this)

    /** 最近公共祖先；不同后端返回 `null`。 */
    fun commonAncestor(other: FsPath): FsPath? {
        if (scheme != other.scheme || authority != other.authority) return null
        if (isAncestorOf(other)) return this
        if (other.isAncestorOf(this)) return other
        var cur = parent
        while (cur != null && !cur.isAncestorOf(other)) cur = cur.parent
        return cur
    }

    /** 从根到自身的完整链路（含自身），用于面包屑与导航树的展开定位。 */
    fun chainFromRoot(): List<FsPath> {
        val out = ArrayList<FsPath>(depth + 1)
        out.add(FsPath(scheme, authority, ""))
        var cur = ""
        for (seg in segments) {
            cur = "$cur/$seg"
            out.add(FsPath(scheme, authority, cur))
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is FsPath &&
            scheme == other.scheme && authority == other.authority && path == other.path)

    override fun hashCode(): Int {
        var r = scheme.hashCode()
        r = 31 * r + authority.hashCode()
        r = 31 * r + path.hashCode()
        return r
    }

    override fun toString(): String = "$scheme$SCHEME_SEP$authority$path"

    companion object {
        const val SCHEME_SEP = "://"

        /** 构造（会做规范化）。 */
        fun of(scheme: String, authority: String = "", path: String = ""): FsPath {
            val s = scheme.trim().lowercase()
            require(isLegalScheme(s)) { "illegal scheme: $scheme" }
            return FsPath(s, authority.trim(), normalizePath(path))
        }

        /** 解析文本形式；非法输入返回 `null`（供外部输入做防御式解析）。 */
        fun parse(raw: String?): FsPath? {
            if (raw.isNullOrBlank()) return null
            val idx = raw.indexOf(SCHEME_SEP)
            if (idx <= 0) return null
            val s = raw.substring(0, idx).lowercase()
            if (!isLegalScheme(s)) return null
            val rest = raw.substring(idx + SCHEME_SEP.length)
            val slash = rest.indexOf('/')
            val authority = if (slash < 0) rest else rest.substring(0, slash)
            val path = if (slash < 0) "" else rest.substring(slash)
            return FsPath(s, authority, normalizePath(path))
        }

        /** 解析并断言合法；用于契约已保证的内部数据（快捷方式 extras、数据库列）。 */
        fun parseOrThrow(raw: String): FsPath =
            parse(raw) ?: throw IllegalArgumentException("illegal FsPath: $raw")

        private fun isLegalScheme(s: String): Boolean =
            s.isNotEmpty() && s[0] in 'a'..'z' &&
                s.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }

        /** 规范化：折叠空段与 `.`，处理 `..`（不越根），根返回空串。 */
        internal fun normalizePath(raw: String): String {
            if (raw.isEmpty() || raw == "/") return ""
            // 快路径：已经是规范形式就直接返回。
            // 存在意义是列举的热路径——本机后端的每个条目都要构造 FsPath，
            // 而 `File.absolutePath` 天然已规范化。走通用的 split+join 会为每个条目
            // 分配一个数组和若干子串，5000 个目录项就是上万次多余分配。
            if (isNormalized(raw)) return raw
            val out = ArrayList<String>(8)
            for (seg in raw.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out.add(seg)
                }
            }
            return if (out.isEmpty()) "" else out.joinToString(separator = "/", prefix = "/")
        }

        /**
         * `p` 是否已是规范形式：绝对、无空段、无 `.` 段、无 `..` 段、无尾斜杠。
         *
         * 逐字符单遍扫描；不做任何分配。任何一个条件不满足就交回通用路径，
         * 因此这条快路径**只可能更快，不可能改变结果**。
         */
        private fun isNormalized(p: String): Boolean {
            if (p[0] != '/' || p[p.length - 1] == '/') return false
            val n = p.length
            var i = 0
            while (i < n) {
                if (p[i] != '/') {
                    i++
                    continue
                }
                val start = i + 1
                val next = p.indexOf('/', start)
                val end = if (next < 0) n else next
                val len = end - start
                // 空段（连续斜杠 \ 尾斜杠）非法
                if (len == 0) return false
                if (len == 1 && p[start] == '.') return false
                if (len == 2 && p[start] == '.' && p[start + 1] == '.') return false
                i = end
            }
            return true
        }
    }
}
