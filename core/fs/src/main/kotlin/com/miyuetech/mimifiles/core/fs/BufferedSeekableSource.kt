package com.miyuetech.mimifiles.core.fs

/**
 * 给任意 [SeekableSource] 套一层**顺序预读窗口**。
 *
 * ## 为什么必须有这一层（M6 实测新增，SMB 性能事故的根因之一）
 * 消费端要的块，往往**远小于**网络一次往返划算的块：
 *
 * | 消费端 | 它一次要多少 | 不预读时我们向服务端要多少 |
 * |---|---|---|
 * | Media3 `DefaultAllocator` | 64 KiB | 64 KiB |
 * | `PackageInstaller` 读 APK | 32~64 KiB | 32~64 KiB |
 * | `BitmapFactory` / `MediaMetadataRetriever` | 8~64 KiB | 8~64 KiB |
 * | `FsOps.copyFile` | 64 KiB | 64 KiB |
 *
 * 于是"吞吐"被钉死成 `块大小 / 往返时延`：局域网 RTT 5 ms 时 64 KiB/5 ms ≈ 12 MB/s，
 * 而 RTT 20 ms 时只剩 3 MB/s —— **与链路带宽无关，纯粹被往返次数限制**。
 * 预读把 1 MiB 摊到多次（此处 16~128 次）消费端请求上，往返次数下降同样倍数。
 *
 * ## 为什么不干脆把消费端的缓冲区调大
 * 因为**那不是我们的代码**：`BitmapFactory` 的内部缓冲、系统 `PackageInstaller`
 * 的读法、Media3 的 `Allocator` 都是给定的。唯一能改的地方是"我们交给它们什么"。
 *
 * ## 为什么顺序性是关键，而乱序必须直读
 * 预读只在**顺序访问**上成立：命中窗口就白拿，否则每一次读都要先拉一整窗口，
 * 对随机读（缩略图跳着看、播放器 seek）反而**多读了**。所以这里的判据不是
 * "猜"，而是**看这一次请求是否落在当前窗口内**：
 *
 *  - 落在窗口内 → 直接内存拷贝，零网络；
 *  - 不落在窗口内 → 从请求位置**重新开窗**（只读消费者真正要的那一段起始处）
 *    ——一次 seek 的代价与"没这一层"完全相同，**最坏情况不比现在慢**。
 *
 * ## 两个刻意的取舍
 * 1. **请求本身够大就直读**：判据是 `length >= windowSize`（不是"窗口的几分之一"，
 *    理由见 [read] 里的注释 —— 原先写的 `windowSize / 4` 会在 SMB2.0.2 这种
 *    "单次请求上限只有 64 KiB" 的环境里把窗口**整个废掉**）。
 * 2. **窗口按需分配**（第一次真正需要时才 new）。缩略图那种"打开→读几百字节→关闭"
 *    的用法一次都不会分配，避免"列表里 50 个视频各留 1 MiB"这种内存尖峰。
 *
 * 句柄按单线程使用（每个消费端一个句柄），但 `read` 仍加锁：代理 fd 的
 * `onRead` 可能被系统在多个线程上驱动，一次读坏比一点锁开销贵得多。
 */
class BufferedSeekableSource(
    private val delegate: SeekableSource,
    private val windowSize: Int = DEFAULT_WINDOW,
    /**
     * 关闭时回调一次 [stats]（用于把"预读到底有没有生效"记进日志）。
     *
     * 之所以做成回调、而不是本类直接依赖 `MimiLog`：`:core:fs` 不依赖 `:core:common`
     * （它只依赖 `:core:model`），为了记一行日志去加一条模块依赖是不划算的。
     * 回调里抛异常不会被传播——取证代码绝不该把一次正常的 close 变成失败。
     */
    private val onClose: ((String) -> Unit)? = null,
) : SeekableSource {

    private var window: ByteArray? = null

    /** 窗口首字节在文件中的偏移；`-1` 表示窗口无效。 */
    private var windowStart = -1L

    /** 窗口内有效字节数。 */
    private var windowLength = 0

    // ── 埋点：让"预读真的生效了"变成可取证的事实（而不是靠观感）──────────
    private var hits = 0L
    private var misses = 0L
    private var bufferedBytes = 0L

    override val size: Long get() = delegate.size

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0

        // 请求本身就够大就直读 —— 此时预读只会多一次数组拷贝，没有任何收益。
        //
        // ## 为什么是 `>= windowSize` 而不是"窗口的几分之一"（M6 实测修正）
        // 最初这里写的是 `windowSize / 4`，隐含假设"窗口远大于单次请求上限"。
        // 这个假设在 SMB2.0.2 上**不成立**：那一版协议的 `MAX_READ_SIZE` 就是 65536，
        // 于是 `SmbConnector` 传下来的 `readBufferSize` 被服务端协商值压到 64 KiB，
        // 窗口也跟着变成 64 KiB，而直读阈值成了 16 KiB —— **任何 ≥16 KiB 的读全部直读，
        // 窗口一次都没被用过**。设备实测的日志就是铁证：
        //     close read bigfile-60mb.bin | window=64KiB hits=0 misses=0 buffered=0KiB
        // 复制 60 MB 因此退化成 960 次串行 64 KiB 往返，只有 1.4 MB/s。
        //
        // 改成"请求 ≥ 一整个窗口就直读"之后，两种协商结果都落在正确的一侧：
        //   · SMB2.0.2（窗口 64 KiB）：64 KiB 请求直读（本来就是最优），
        //     而 4~63 KiB 的请求能吃到窗口 —— 这正是小请求消费端（缩略图探测、
        //     代理 fd 的 `onRead`）的场景；
        //   · SMB3.x（窗口 1 MiB）：64 KiB 请求走窗口，16 次合并成 1 次真实往返。
        //     这才是消费端块大小（Media3 的 64 KiB）与协议上限（1 MiB）**不一致**时
        //     预读真正赚钱的地方。
        if (length >= windowSize) {
            return delegate.read(position, buffer, offset, length)
        }

        synchronized(this) {
            val windowArray = window
            // ① 命中当前窗口——这是预读唯一真正省下往返的时刻。
            if (windowArray != null && windowLength > 0 &&
                position >= windowStart && position < windowStart + windowLength
            ) {
                val relative = (position - windowStart).toInt()
                val n = minOf(length, windowLength - relative)
                System.arraycopy(windowArray, relative, buffer, offset, n)
                hits++
                return n
            }

            // ② 未命中：从**请求位置**重新开窗。
            val array = windowArray ?: ByteArray(windowSize).also { window = it }
            val n = delegate.read(position, array, 0, windowSize)
            if (n <= 0) {
                windowStart = -1L
                windowLength = 0
                return -1
            }
            windowStart = position
            windowLength = n
            misses++
            bufferedBytes += n.toLong()

            val take = minOf(length, n)
            System.arraycopy(array, 0, buffer, offset, take)
            return take
        }
    }

    override fun close() {
        // 先算统计再清窗口：stats() 里的 bufferedBytes 是累计量、不受清空影响，
        // 但把顺序写成"先取材、后清理"能避免将来有人给 stats 加上依赖窗口的字段时踩坑。
        val summary = stats()
        window = null
        windowLength = 0
        windowStart = -1L
        runCatching { delegate.close() }
        runCatching { onClose?.invoke(summary) }
    }

    /**
     * 预读统计。仅用于日志与排障（"到底有没有生效"）。
     *
     * `hits / (hits + misses)` 就是**命中率**：顺序大文件读应该接近 `1 - 1/窗口内请求数`；
     * 若长期接近 0，说明这个消费端是乱序访问，这一层对它没有收益（也没有损失）。
     */
    fun stats(): String =
        "window=${windowSize / 1024}KiB hits=$hits misses=$misses buffered=${bufferedBytes / 1024}KiB"

    companion object {
        /**
         * 默认窗口 1 MiB。
         *
         * 取这个值的两个依据：
         *  - 它**正好等于一次 SMB2 READ 的真实上限**。这个上限在 smbj 里是
         *    `Share.readBufferSize = min(config.readBufferSize, 协商得到的 maxReadSize)`
         *    （反编译 0.15.0 `Share` 构造器实测），而 `SmbConnector` 已把
         *    `readBufferSize` 显式钉在 1 MiB，SMB2.1/SMB3 服务端的 `maxReadSize`
         *    也普遍 ≥ 1 MiB。**调用方仍应按"服务端可能更小"来用**：本类不做假设，
         *    SMB 传输层会把窗口退化成"同一窗口内多次请求"（见 `SmbjSeekableSource`）。
         *  - 它同时是**每个打开句柄**的内存上限。句柄并发在上游是有界的
         *    （缩略图走 `PreviewDispatchers.io` 的 4 路、播放器 1~2 路、
         *    安装器 1~2 路），因此总量可控；且窗口**按需分配**，只读文件头的
         *    用法一次都不会分配。
         */
        const val DEFAULT_WINDOW = 1024 * 1024
    }
}
