package com.miyuetech.mimifiles.core.fs

import com.miyuetech.mimifiles.core.model.FsPath

/** 目标已存在时的处理策略。对应 UI 上的「替换 / 两者都保留 / 跳过」。 */
enum class ConflictPolicy {
    /** 覆盖同名目标。 */
    REPLACE,

    /** 保留两者：自动改名为 `名字 (1).ext`。 */
    KEEP_BOTH,

    /** 跳过该条目。 */
    SKIP,
}

/**
 * 跨后端操作。
 *
 * [FileSystem] 只管"自己这一亩三分地"；凡是**跨后端**（NAS → 本机、本机 → SAF 授权目录）
 * 或需要**统一冲突策略/进度**的动作，都收敛到这里，避免每个调用点各写一遍
 * "先判断是否同后端、再决定原生搬还是流式搬、还要处理重名"。
 *
 * ## 为什么流式中转是必须的
 * 参考应用把 SMB→本机的大文件搬运实现为"整个读进内存再写出去"，2 GB 的视频直接 OOM。
 * 这里一律 **256 KB 缓冲 + 边读边写**（为什么是 256 KB 见 [BUFFER_SIZE]），
 * 内存占用与文件大小无关。
 */
class FsOps(private val registry: FileSystemRegistry) {

    /**
     * 复制 [src] 到 [destDir]（保留原名，按 [policy] 处理冲突）。
     *
     * ## 参数顺序是刻意的
     * [isCancelled] 排在 [onProgress] **前面**，为的是让 `onProgress` 保持"最后一个参数"
     * 的位置——Kotlin 的尾随 lambda 语法只作用于最后一个参数。反过来写的话，
     * `ops.copy(a, b) { copied, total -> … }` 会把 lambda 绑到取消检查上，
     * 而取消检查的签名是 `() -> Boolean`，于是好的调用点会**编译不过**
     * （M6 加取消支持时正是这么踩了一次，由 `FsOpsTest` 抓到）。
     *
     * @return 实际落地的目标路径；[ConflictPolicy.SKIP] 命中时返回 `null`
     */
    suspend fun copy(
        src: FsPath,
        destDir: FsPath,
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        isCancelled: CancelCheck = NEVER_CANCELLED,
        onProgress: ProgressCallback = NO_PROGRESS,
    ): FsPath? {
        requireNotSelfNesting(src, destDir)
        val srcFs = registry.resolve(src)
        val destFs = registry.resolve(destDir)
        val source = srcFs.stat(src)

        // 同名直落的落点。原生 copy/move 的签名只接受"目标目录"，具体落到哪个名字
        // 由后端自己决定（通常就是同名）。所以**只有当我们算出的目标恰好就是同名直落时**
        // 才能交给后端——一旦因为冲突策略改了名，就必须由我们自己控制落点，
        // 否则"保留两者"会被后端的同名覆盖写成数据丢失，且事后极难发现。
        val direct = destDir.child(source.name)
        val target = resolveTarget(destFs, destDir, source.name, policy) ?: return null

        if (srcFs === destFs && target == direct) {
            try {
                return srcFs.copy(src, destDir)
            } catch (_: FsNotSupportedException) {
                // 落到流式中转
            }
        }

        if (source.isDirectory) {
            copyTree(srcFs, src, destFs, target, isCancelled, onProgress)
        } else {
            copyFile(srcFs, src, destFs, target, source.size, isCancelled, onProgress)
        }
        return target
    }

    /**
     * 移动。同后端优先走原生 move（原子、零拷贝）；不支持或跨后端时退化为「复制 + 删除源」。
     *
     * 参数顺序同 [copy]（`isCancelled` 在 `onProgress` 之前），理由见那里的说明。
     *
     * @return 实际落地的目标路径；跳过时返回 `null`
     */
    suspend fun move(
        src: FsPath,
        destDir: FsPath,
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        isCancelled: CancelCheck = NEVER_CANCELLED,
        onProgress: ProgressCallback = NO_PROGRESS,
    ): FsPath? {
        requireNotSelfNesting(src, destDir)
        checkCancelled(isCancelled)
        val srcFs = registry.resolve(src)
        val destFs = registry.resolve(destDir)

        // 同后端优先走原生 move（原子、零拷贝）。但同 copy 一样，只在"同名直落"时可用：
        // 冲突策略要改名时，后端的 move 会把名字写回原名。
        if (srcFs === destFs) {
            val source = srcFs.stat(src)
            val direct = destDir.child(source.name)
            val target = resolveTarget(destFs, destDir, source.name, policy)
            if (target == null) return null
            if (target == direct) {
                try {
                    return srcFs.move(src, destDir)
                } catch (_: FsNotSupportedException) {
                    // 落到复制 + 删除
                }
            }
        }

        val target = copy(src, destDir, policy, isCancelled, onProgress) ?: return null
        // 删除之前再查一次：复制刚刚成功、用户此刻点了取消——此时**不能**删源，
        // 否则用户会看到"取消之后文件反而没了"（数据丢失）。宁可留下重复的一份。
        checkCancelled(isCancelled)
        srcFs.delete(src, recursive = true)
        return target
    }

    suspend fun delete(path: FsPath, recursive: Boolean = true) {
        val fs = registry.resolve(path)
        fs.delete(path, recursive = recursive)
    }

    /** 批量删除；单个失败不中断其余，返回失败清单供 UI 汇总提示。 */
    suspend fun deleteAll(
        paths: List<FsPath>,
        recursive: Boolean = true,
    ): List<Pair<FsPath, FsException>> {
        val failures = ArrayList<Pair<FsPath, FsException>>()
        for (path in paths) {
            try {
                delete(path, recursive)
            } catch (e: FsException) {
                if (e is FsCancelledException) throw e
                failures += path to e
            }
        }
        return failures
    }

    /** 新建目录；已存在则抛 [FsAlreadyExistsException]（让调用方决定是提示还是自动改名）。 */
    suspend fun createDirectory(dir: FsPath, name: String): FsPath {
        val fs = registry.resolve(dir)
        val target = dir.child(name)
        if (fs.exists(target)) throw FsAlreadyExistsException(target)
        fs.mkdir(target)
        return target
    }

    /** 新建目录，重名自动改名（"新建文件夹 (1)"）。 */
    suspend fun createDirectoryUnique(dir: FsPath, name: String): FsPath {
        val fs = registry.resolve(dir)
        val unique = uniqueChildName(dir, name)
        val target = dir.child(unique)
        fs.mkdir(target)
        return target
    }

    suspend fun rename(path: FsPath, newName: String): FsPath {
        val parent = path.parent ?: throw FsIoException("cannot rename a root path: $path")
        val fs = registry.resolve(path)
        val target = parent.child(newName)
        if (fs.exists(target)) throw FsAlreadyExistsException(target)
        return fs.rename(path, newName)
    }

    /** 目标目录里不与现有条目冲突的名字。 */
    suspend fun uniqueChildName(dir: FsPath, desired: String): String =
        uniqueName(desired, existingNames(dir))

    /** 列举 [dir] 现有条目名；失败（网络断、权限不足）时返回空集，让后续冲突检查退化为"直接用原名"。 */
    private suspend fun existingNames(dir: FsPath): Set<String> = try {
        registry.resolve(dir).list(dir).mapTo(HashSet()) { it.name }
    } catch (e: FsException) {
        if (e is FsCancelledException) throw e
        emptySet()
    }

    // ── 内部实现 ─────────────────────────────────────────────────────────

    private suspend fun resolveTarget(
        destFs: FileSystem,
        destDir: FsPath,
        name: String,
        policy: ConflictPolicy,
    ): FsPath? {
        val direct = destDir.child(name)
        val taken = try {
            destFs.exists(direct)
        } catch (e: FsException) {
            if (e is FsCancelledException) throw e
            false
        }
        if (!taken) return direct
        return when (policy) {
            ConflictPolicy.REPLACE -> direct
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> {
                val existing = try {
                    destFs.list(destDir).mapTo(HashSet()) { it.name }
                } catch (e: FsException) {
                    if (e is FsCancelledException) throw e
                    HashSet<String>().apply { add(name) }
                }
                destDir.child(uniqueName(name, existing))
            }
        }
    }

    private suspend fun copyTree(
        srcFs: FileSystem,
        src: FsPath,
        destFs: FileSystem,
        target: FsPath,
        isCancelled: CancelCheck,
        onProgress: ProgressCallback,
    ) {
        checkCancelled(isCancelled)
        destFs.mkdir(target)
        var done = 0L
        for (child in srcFs.list(src)) {
            val childTarget = target.child(child.name)
            if (child.isDirectory) {
                copyTree(srcFs, child.path, destFs, childTarget, isCancelled, onProgress)
            } else {
                copyFile(srcFs, child.path, destFs, childTarget, child.size, isCancelled, onProgress)
                done += child.size.coerceAtLeast(0L)
                // 目录复制的总大小需要额外全量遍历才能得到，这里刻意不给：多一次遍历不划算
                onProgress(done, SeekableSource.SIZE_UNKNOWN)
            }
        }
    }

    private suspend fun copyFile(
        srcFs: FileSystem,
        src: FsPath,
        destFs: FileSystem,
        target: FsPath,
        size: Long,
        isCancelled: CancelCheck,
        onProgress: ProgressCallback,
    ) {
        checkCancelled(isCancelled)
        srcFs.openRead(src).use { source ->
            destFs.openWrite(target, append = false, expectedSize = size).use { sink ->
                val input = source.stream()
                val output = sink.stream()
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                var zeroReads = 0
                while (true) {
                    // 每读一块查一次取消。放在这里而不是"每个文件查一次"：
                    // SMB 上一个 2 GB 的视频要传十几分钟，用户点「取消」之后
                    // 界面还转十几分钟，与没做取消没有区别。
                    checkCancelled(isCancelled)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) {
                        // 防御：契约良好的 InputStream 不该发生；真发生也不能死循环
                        if (++zeroReads > ZERO_READ_LIMIT) break
                        continue
                    }
                    zeroReads = 0
                    output.write(buffer, 0, n)
                    copied += n
                    onProgress(copied, size)
                }
                output.flush()
            }
        }
    }

    /**
     * 用户是否已请求取消。
     *
     * 用"拉"（每次读一块问一次）而不是"推"（往传输层塞一个回调）：取消是一个
     * 单调的布尔量，轮询它零成本，而回调需要关心注册/注销与线程可见性——
     * 那正是"取消之后偶尔没停下来"这类偶发 bug 的来源。
     */
    private fun checkCancelled(isCancelled: CancelCheck) {
        if (isCancelled()) throw FsCancelledException()
    }

    private fun requireNotSelfNesting(src: FsPath, destDir: FsPath) {
        if (destDir.isDescendantOf(src)) {
            throw FsIoException("refusing to copy/move '$src' into its own subtree: $destDir")
        }
    }

    companion object {
        /**
         * 搬运时的读缓冲。
         *
         * ## 为什么从 64 KiB 提到 256 KiB（M6 实测）
         * 这个块大小**不是**独立选的，它要和传输层的预读窗口
         * （`BufferedSeekableSource`，大小 = `min(1 MiB, 协商 maxReadSize)`）配合：
         *
         *  - **SMB3.x**（家用 NAS 的常态，窗口 1 MiB）：64 KiB 的请求会命中窗口，
         *    单次真实往返覆盖 16 次读；提到 256 KiB 后是 4 次读 / 1 次往返
         *    —— 往返次数**再降 4 倍**，而窗口仍然是"一次请求拉满"；
         *  - **SMB2.0.2**（窗口只有 64 KiB）：256 KiB ≥ 窗口 ⇒ 走直读，由传输层
         *    循环填满，**请求总数不变**，但 Java 层的调用次数少 4 倍。
         *
         * 两个方向都不会更差，而 `copyFile` 是**纯顺序读**，不存在"预读多读了"的问题。
         * 内存代价：同时只有一条搬运在跑（`copyTree` 是串行的），多 192 KiB。
         */
        private const val BUFFER_SIZE = 256 * 1024
        private const val ZERO_READ_LIMIT = 16
        private const val MAX_CONFLICT_ATTEMPTS = 9999

        val NO_PROGRESS: ProgressCallback = { _, _ -> }

        /** 默认永不取消。 */
        val NEVER_CANCELLED: CancelCheck = { false }

        /**
         * 生成不冲突的名字：`报告.pdf` → `报告 (1).pdf`。
         *
         * 纯函数，便于穷举测试。对**无扩展名的目录**同样成立（`相册` → `相册 (1)`），
         * 这也是把"最后一个点"而不是"第一个点"作为分隔点的原因：`a.b.tar.gz` 应变成
         * `a.b.tar (1).gz` 还是 `a.b.tar.gz (1)`？取后者更符合 Windows 资源管理器的直觉，
         * 因为只有**真正参与类型判定**的扩展名才值得保护——而多重扩展名的语义只有人知道。
         */
        fun uniqueName(desired: String, existing: Set<String>): String {
            if (desired !in existing) return desired
            val dot = desired.lastIndexOf('.')
            val hasExt = dot > 0 && dot < desired.length - 1
            val base = if (hasExt) desired.substring(0, dot) else desired
            val ext = if (hasExt) desired.substring(dot) else ""
            var i = 1
            while (i <= MAX_CONFLICT_ATTEMPTS) {
                val candidate = "$base ($i)$ext"
                if (candidate !in existing) return candidate
                i++
            }
            return "$base (${System.nanoTime()})$ext"
        }
    }
}

/** 进度回调：`copied` 已传输字节；`total` 总字节，`-1` 表示未知（目录复制）。 */
typealias ProgressCallback = (copied: Long, total: Long) -> Unit

/**
 * 取消判据。
 *
 * 返回 `true` 时传输会在**下一个检查点**抛出 [FsCancelledException]。检查点有两处：
 * 每个文件开头、以及每个 [FsOps] 的读写块之间——后者保证"传一个 2 GB 的视频时点取消"
 * 也能在一秒内停下来，而不是等整个文件传完。
 */
typealias CancelCheck = () -> Boolean
