package com.miyuetech.mimifiles.core.fssmb

/**
 * 传输层的目录项。
 *
 * 刻意**不是** `FsEntry`：`FsEntry` 需要 `FsPath`，而 `FsPath` 要带 authority（连接 id）。
 * 传输层只认识"共享内的相对路径"，由 [SmbFileSystem] 负责拼上 scheme/authority。
 * 这样 jcifs-ng 与 smbj 两个实现都能只关心自己的字符串路径，不必各自拼一遍 FsPath。
 */
data class SmbDirEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 字节数；未知为 [UNKNOWN]。目录恒为 [UNKNOWN]。 */
    val size: Long = UNKNOWN,
    /** epoch 毫秒；未知为 0。 */
    val lastModified: Long = 0L,
    val isHidden: Boolean = false,
    val isReadOnly: Boolean = false,
) {
    companion object {
        const val UNKNOWN = -1L
    }
}

/**
 * SMB 传输层抽象。
 *
 * ## 为什么要抽这一层
 * 方案第 5.2 节要求「先 SMB2/3 握手，协商失败自动降级 SMB1」。如果让
 * `SmbFileSystem` 直接调 smbj，那么"降级"就意味着**整套 FileSystem 逻辑要写第二遍**
 * （路径换算、异常映射、FsEntry 构造、递归删除……），两份代码必然漂移。
 *
 * 把差异压到这一层之后：
 *  - [SmbjTransport]（SMB2/3，主力）与 [JcifsTransport]（SMB1，兼容老 NAS/盒子）
 *    各自只实现 8 个方法；
 *  - `SmbFileSystem` 只写一遍，签名的 `dialectLabel` 用于向 UI 说明"已降级"。
 *
 * ## 路径约定
 * 所有方法的 `path` 参数都是**共享内的相对路径**：`""` 表示共享根，`"a/b"` 表示子目录，
 * 一律用 `/` 分隔、**不带前导斜杠**（SMB 的 UNC 语义就是如此，两个库也都接受这种形式）。
 */
interface SmbTransport : AutoCloseable {

    /** 用于 UI 展示的方言说明，如 `SMB 3.1.1` 或 `SMB1（已降级，性能较低）`。 */
    val dialectLabel: String

    /**
     * 这条会话现在还能用吗。
     *
     * 存在的理由非常具体：smbj 会在**连接空闲超过 `withTimeout`/`withSoTimeout`**
     * （本项目的默认值是 10 s）之后把底层连接标记为断开，缓存里那个 `DiskShare`
     * 到那时已经废了——再拿去发请求只会得到
     * `DiskShare has already been closed`。
     *
     * M2 实测的表现因此是：「从网络目录切走、几十秒后再切回来，**必然**报一次
     * 读不出来，再点一次才好」。与其在失败之后补救，不如在**取会话时**就发现它已经死了
     * （见 `SmbFileSystem.transport` 里的丢弃重建）。
     */
    val isAlive: Boolean

    fun list(path: String): List<SmbDirEntry>

    fun stat(path: String): SmbDirEntry

    fun exists(path: String): Boolean

    /** 随机读句柄。两个实现都必须给出真正可 seek 的实现——这是视频拖动的地基。 */
    fun openRead(path: String): SmbSeekableSource

    fun openWrite(path: String, append: Boolean, expectedSize: Long): SmbWriteSink

    fun mkdir(path: String)

    fun delete(path: String, recursive: Boolean)

    /** 同目录内改名。 */
    fun rename(path: String, newName: String)

    /** 跨目录移动（同一共享内），返回是否成功。失败由上层回退为「复制 + 删除」。 */
    fun move(src: String, destDir: String): Boolean

    /** 服务端复制（SMB2 COPYCHUNK）。返回 `false` 表示服务端不支持，需上层回退流式。 */
    fun copyServerSide(src: String, destDir: String): Boolean

    /** 连通性测试用：列一次共享根，能列出来就算通。 */
    fun probe()
}

/** 传输层的读句柄别名，便于两个实现共用 `SeekableSource` 的适配代码。 */
typealias SmbSeekableSource = com.miyuetech.mimifiles.core.fs.SeekableSource

/** 传输层的写句柄别名。 */
typealias SmbWriteSink = com.miyuetech.mimifiles.core.fs.WriteSink

/**
 * SMB 文件属性位（MS-FSCC 2.6）。
 *
 * 直接用字面常量而不是 `com.hierynomus.msfscc.FileAttributes` 枚举：
 * jcifs-ng 那边给的是自己的 `int` 常量，两边要对齐的是**协议位**而不是某个库的枚举。
 * 用协议位反而让两个实现落在同一个判据上。
 */
internal object SmbAttributes {
    const val READONLY = 0x0001L
    const val HIDDEN = 0x0002L
    const val SYSTEM = 0x0004L
    const val DIRECTORY = 0x0010L
}
