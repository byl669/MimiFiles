package com.miyuetech.mimifiles.core.shareserver

import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsNotSupportedException
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.fs.WriteSink
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath

/** 服务器视图里的一条条目。已经带好了相对路径，协议层不需要再算。 */
data class ShareNode(
    /** 相对共享根的路径，根为 `""`。 */
    val rel: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
    val isHidden: Boolean,
) {
    companion object {
        fun of(entry: FsEntry, rel: String): ShareNode = ShareNode(
            rel = rel,
            name = entry.name,
            isDirectory = entry.isDirectory,
            size = entry.size,
            lastModified = entry.lastModified,
            mimeType = entry.mimeType,
            isHidden = entry.isHidden,
        )
    }
}

/**
 * 一次分享会话的"文件视图"——**安全边界与根级过滤的唯一落点**。
 *
 * ## 它守三件事
 * 1. **越狱**：所有来自客户端的路径都必须经 [resolve]，它是 [RelPath.resolve] 的唯一入口；
 * 2. **根级过滤**：多选若干文件再分享时（[ShareRequest.visibleNames]），根列表只列出
 *    用户真正选中的那些，而不是把他所在的整个目录摊开。过滤**只作用于根**：
 *    一旦用户点进某个被选中的子目录，里面就是该目录的完整内容——这是他与 FTP
 *    客户端的既定预期（"我分享了那个文件夹"）。但 [stat] / [openRead] 上也查一遍，
 *    否则知道名字就能直接 `/f/secret.txt` 拿到没在列表里的文件；
 * 3. **只读**：写方法在 [readOnly] 或后端不可写时抛 [FsNotSupportedException]。
 *    **不是**"前端藏按钮"——那只是看起来安全：`curl -X PUT` 绕过一个隐藏的按钮
 *    是零成本的。
 *
 * ## 为什么过滤要在这里做而不是在 HTTP 层
 * FTP 也要过滤。若只在 HTTP 的列表接口里过滤，那"多选 3 张照片分享"通过 FTP
 * 就会把整个相册露出去——而用户看到的是同一个面板、同一句"已启动"。
 * 放在这里，两个协议天然一致。
 */
class ShareScope(
    val root: FsPath,
    /** 根下允许出现的名字；`null` = 整目录。 */
    val visibleNames: Set<String>?,
    val readOnly: Boolean,
    private val bridge: FsBridge,
    /** 后端是否真的可写（能力位 + 根条目只读位）。`false` 时写操作一律拒绝。 */
    private val backendWritable: Boolean = true,
) {

    /** 服务端是否接受写操作。 */
    val writable: Boolean get() = !readOnly && backendWritable

    /** `rel` → [FsPath]；越狱或非法返回 `null`。 */
    fun resolve(rel: String?): FsPath? = RelPath.resolve(root, rel)

    /** [FsPath] → `rel`；不属于本共享根时返回 `null`。 */
    fun toRel(path: FsPath): String? {
        if (path.scheme != root.scheme || path.authority != root.authority) return null
        if (path == root) return RelPath.ROOT
        if (!root.isAncestorOf(path)) return null
        val prefix = root.path
        return path.path.substring(prefix.length + 1)
    }

    /** 该名字是否允许出现在**根**列表里。 */
    fun visibleAtRoot(name: String): Boolean = visibleNames == null || name in visibleNames

    /**
     * 列目录。
     *
     * @return `null` 表示路径非法或越狱（协议层翻成 404 / FTP 550）；
     *         目录不存在等 [com.miyuetech.mimifiles.core.fs.FsException] 由上层翻译
     */
    suspend fun list(rel: String?): List<ShareNode>? {
        val normalized = RelPath.normalize(rel) ?: return null
        if (!allowed(normalized)) return null
        val dir = resolve(normalized) ?: return null
        val entries = bridge.list(dir)
        val atRoot = normalized == RelPath.ROOT
        return entries.asSequence()
            .filter { !atRoot || visibleAtRoot(it.name) }
            .mapNotNull { entry -> RelPath.child(normalized, entry.name)?.let { ShareNode.of(entry, it) } }
            .sortedWith(NODE_ORDER)
            .toList()
    }

    /** 单条目的信息；非法路径或不在可见范围内返回 `null`。 */
    suspend fun stat(rel: String?): ShareNode? {
        val normalized = RelPath.normalize(rel) ?: return null
        if (!allowed(normalized)) return null
        val path = resolve(normalized) ?: return null
        return ShareNode.of(bridge.stat(path), normalized)
    }

    /** 是否已存在（上传前判断用）。 */
    suspend fun exists(rel: String?): Boolean = stat(rel) != null

    /** 打开随机读。非法路径或目录返回 `null`。 */
    suspend fun openRead(rel: String?): SeekableSource? {
        val normalized = RelPath.normalize(rel) ?: return null
        if (!allowed(normalized)) return null
        val node = stat(normalized) ?: return null
        if (node.isDirectory) return null
        val path = resolve(normalized) ?: return null
        return bridge.openRead(path)
    }

    /** 打开写入（上传）。不可写或路径非法时抛异常。 */
    suspend fun openWrite(rel: String?, expectedSize: Long, append: Boolean = false): WriteSink {
        val path = requireWritable(rel)
        // 目标是目录时若直接交给 openWrite，本机后端会抛一个语焉不详的
        // FileNotFoundException —— 上传到已存在同名目录是很自然会发生的误操作，
        // 值得在协议层之前就给出确定的语义。
        if (runCatching { bridge.stat(path).isDirectory }.getOrDefault(false)) {
            throw FsAccessDeniedException(path)
        }
        return bridge.openWrite(path, append, expectedSize)
    }

    /** 在 [parentRel] 下新建目录 [name]。 */
    suspend fun mkdir(parentRel: String?, name: String): String {
        val parentNorm = RelPath.normalize(parentRel) ?: throw deny()
        val parent = requireWritable(parentNorm)
        val childRel = RelPath.child(parentNorm, name) ?: throw deny()
        if (!allowed(childRel)) throw deny()
        bridge.mkdir(parent.child(name))
        return childRel
    }

    /** 删除 [rel]（不允许删根）。 */
    suspend fun delete(rel: String?, recursive: Boolean) {
        val normalized = RelPath.normalize(rel) ?: throw deny()
        if (normalized == RelPath.ROOT) throw deny()
        bridge.delete(requireWritable(normalized), recursive)
    }

    /** 重命名 [rel] 为 [newName]；返回新的相对路径。 */
    suspend fun rename(rel: String?, newName: String): String {
        val normalized = RelPath.normalize(rel) ?: throw deny()
        if (normalized == RelPath.ROOT) throw deny()
        val parentRel = RelPath.parent(normalized) ?: RelPath.ROOT
        val targetRel = RelPath.child(parentRel, newName) ?: throw deny()
        if (!allowed(targetRel)) throw deny()
        val moved = bridge.rename(requireWritable(normalized), newName)
        return toRel(moved) ?: targetRel
    }

    /** 把 [srcRel] 移到 [destDirRel] 下（保留原名）；返回新相对路径。 */
    suspend fun move(srcRel: String?, destDirRel: String?): String {
        val srcNorm = RelPath.normalize(srcRel) ?: throw deny()
        val destNorm = RelPath.normalize(destDirRel) ?: throw deny()
        if (srcNorm == RelPath.ROOT) throw deny()
        val src = requireWritable(srcNorm)
        val destDir = requireWritable(destNorm)
        // 把自己移进自己的子树是经典的自噬操作：后端要么抛一个含糊的 IO 错，
        // 要么（更糟）先把目录删了再复制。在协议层之前就挡掉。
        if (destDir == src || destDir.isDescendantOf(src)) throw deny()
        val moved = bridge.move(src, destDir)
        return toRel(moved) ?: throw deny()
    }

    /** 根级过滤 + 语义化：`rel` 是否在共享范围内。 */
    private fun allowed(rel: String): Boolean {
        if (rel == RelPath.ROOT) return true
        // 只看顶段：一旦顶段被允许，下面的层级就跟着通（用户分享了那个文件夹）。
        return visibleAtRoot(rel.substringBefore('/'))
    }

    private fun requireWritable(rel: String?): FsPath {
        if (!writable) {
            throw FsNotSupportedException(
                root.scheme,
                if (readOnly) "write（分享已设为只读）" else "write（后端不可写）",
            )
        }
        val normalized = RelPath.normalize(rel) ?: throw deny()
        if (!allowed(normalized)) throw deny()
        return resolve(normalized) ?: throw deny()
    }

    /**
     * 越狱统一用 [FsAccessDeniedException]。
     * 回一句"路径非法"等于告诉对方"你的语法已经被解析了"，会让他继续试别的写法。
     */
    private fun deny(): FsAccessDeniedException = FsAccessDeniedException(root)

    companion object {

        /**
         * 目录在前、名字不区分大小写 —— 与 PC 端资源管理器的默认观感一致。
         * FTP 的 `LIST` 与网页列表都用这个顺序，免得"手机上看到的顺序和 PC 上不一样"。
         */
        val NODE_ORDER: Comparator<ShareNode> = compareByDescending<ShareNode> { it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** 由分享请求构造（会先问后端"根可写吗"）。 */
        suspend fun create(
            bridge: FsBridge,
            request: ShareRequest,
            readOnly: Boolean,
        ): ShareScope {
            val backendWritable = if (readOnly) false else bridge.isWritable(request.root)
            return ShareScope(
                root = request.root,
                visibleNames = request.visibleNames,
                readOnly = readOnly,
                bridge = bridge,
                backendWritable = backendWritable,
            )
        }
    }
}
