package com.miyuetech.mimifiles.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 预读窗口的判据测试。
 *
 * ## 为什么这一层值得被测死
 * 它是 M6 的 SMB 性能事故里**唯一会改变"消费端看到的行为"的修复**：
 *   - 关掉 `soTimeout`、放大 data timeout、关签名 —— 这三条只是**不让连接自杀**；
 *   - 预读窗口 —— 这一条才真正把吞吐从"被往返次数钉死"里解放出来。
 *
 * 也正因为它改变了 `read` 的返回形状（会返回**短读**），它有把文件**静默读少**的风险。
 * 事故里旧实现正是栽在这一点上：把服务端因协商上限/信用不足而给的短读当成 EOF，
 * 结果"文件复制到一半就结束了，还不报错"。所以下面每个用例都配了一条**反向判据**
 * ——不是"结果对了就算过"，而是"换个错误实现它必须失败"。
 *
 * 反向判据不成立的用例会在注释里**如实说明**，不假装它有。
 */
class BufferedSeekableSourceTest {

    private val window = 64 * 1024

    /**
     * 可编排的假后端。
     *
     * [maxChunk] 模拟"服务端单次最多只肯给这么多"——SMB2 里这条限制真实存在
     * （`min(config.readBufferSize, 协商 maxReadSize)`，再被信用数截短），
     * 而它正是旧实现把短读误判成 EOF 的触发条件。
     */
    private class FakeSource(
        private val data: ByteArray,
        private val maxChunk: Int = Int.MAX_VALUE,
    ) : SeekableSource {

        /** 每次读请求的 `(position, length)`，按发生顺序。 */
        val requests = ArrayList<Pair<Long, Int>>()

        var closedTimes = 0
            private set

        override val size: Long get() = data.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            requests += position to length
            if (position >= data.size) return -1
            if (length <= 0) return 0
            val n = minOf(length.toLong(), maxChunk.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun close() {
            closedTimes++
        }
    }

    private fun payload(size: Int, seed: Int = 7): ByteArray = Random(seed).nextBytes(size)

    private fun source(
        size: Int = 4 * window,
        maxChunk: Int = Int.MAX_VALUE,
        windowSize: Int = window,
        onClose: ((String) -> Unit)? = null,
    ): Pair<BufferedSeekableSource, FakeSource> {
        val fake = FakeSource(payload(size), maxChunk)
        return BufferedSeekableSource(fake, windowSize, onClose) to fake
    }

    // ── 判据 1：预读真的把多次小块读合并成一次后端请求 ────────────────────

    @Test
    fun `顺序 4 KiB 读满 4 个窗口，后端只被调用窗口数次`() {
        val (buffered, fake) = source(size = 4 * window)
        val chunk = 4 * 1024
        val out = ByteArray(chunk)

        var pos = 0L
        var reads = 0
        while (pos < 4L * window) {
            val n = buffered.read(pos, out, 0, chunk)
            assertTrue("读到 EOF 太早：pos=$pos", n > 0)
            pos += n
            reads++
        }
        assertEquals(4L * window, pos)
        assertEquals(4 * window / chunk, reads)          // 消费端确实读了 64 次
        assertEquals(4, fake.requests.size)              // 后端只被叫了 4 次
        assertTrue(fake.requests.all { it.second == window })

        // 反向判据：**不套预读窗口**时，同样的消费端会把后端叫满 64 次。
        // 这个对照必须真的跑一遍 —— 否则没法排除"4 次是巧合"。
        val plain = FakeSource(payload(4 * window))
        var p = 0L
        while (p < 4L * window) {
            val n = plain.read(p, out, 0, chunk)
            assertTrue(n > 0)
            p += n
        }
        assertEquals("对照组本身的假设不成立", 64, plain.requests.size)
        assertTrue(
            "预读没有把往返次数降下来：${fake.requests.size} vs ${plain.requests.size}",
            fake.requests.size * 4 < plain.requests.size,
        )
    }

    // ── 判据 2：短读不是 EOF（旧实现就是在这里静默截断文件的）────────────

    @Test
    fun `后端每次只给 1000 字节时，窗口退化成多次请求但内容完整`() {
        val size = 3 * window
        val expected = payload(size)
        val fake = FakeSource(expected, maxChunk = 1000)
        val buffered = BufferedSeekableSource(fake, window)

        // 刻意用 4 KiB 的小块循环读（小于直读阈值 window/4 = 16 KiB），
        // 这样才会走窗口路径 —— 用 readBytes 一次性要 196 KiB 会命中直读，
        // 那个用例就测不到"窗口配上短读后端"这条路径了。
        val got = ByteArray(size)
        var pos = 0
        while (pos < size) {
            val want = minOf(4 * 1024, size - pos)
            val n = buffered.read(pos.toLong(), got, pos, want)
            assertTrue("短读被当成了 EOF：只读到 $pos / $size", n > 0)
            pos += n
        }

        assertTrue("内容与源文件不一致", got.contentEquals(expected))

        // 反向判据：窗口每次只能装下 1000 字节，所以请求数必然远多于"理想情况"的 3 次。
        // 请求数不涨，说明这个用例根本没走到短读路径（例如 maxChunk 没生效）。
        assertTrue(
            "窗口没有因为短读而退化（请求数 ${fake.requests.size}）",
            fake.requests.size > 150,
        )
        assertTrue(fake.requests.all { it.second == window })
    }

    @Test
    fun `stream 视图在短读后端上也能读满全文件`() {
        val size = 2 * window + 123
        val expected = payload(size, seed = 11)
        val buffered = BufferedSeekableSource(FakeSource(expected, maxChunk = 4096), window)

        val got = buffered.stream(0).use { it.readBytes() }

        assertEquals(size, got.size)
        assertTrue(got.contentEquals(expected))
    }

    // ── 判据 3：命中窗口不产生任何后端请求 ──────────────────────────────

    @Test
    fun `同一位置重复读命中窗口，后端请求数不变`() {
        val (buffered, fake) = source()
        val buf = ByteArray(512)

        val first = buffered.read(0, buf, 0, 512)
        val afterFirst = fake.requests.size
        val second = buffered.read(0, buf, 0, 512)
        val third = buffered.read(2048, buf, 0, 512)

        assertEquals(512, first)
        assertEquals(512, second)
        assertEquals(512, third)
        assertEquals(1, afterFirst)
        assertEquals("命中窗口却又去问了后端", afterFirst, fake.requests.size)
    }

    @Test
    fun `窗口内任意偏移读的都是正确字节`() {
        val size = 4 * window
        val expected = payload(size)
        val fake = FakeSource(expected)
        val buffered = BufferedSeekableSource(fake, window)

        assertTrue(buffered.read(0, ByteArray(64), 0, 64) > 0)   // 开窗
        val before = fake.requests.size
        // 偏移都挑在窗口**内部**、且"读 64 字节不会跨到窗口外"的位置：
        // `window - 64` 是最后一个能整块读到的位置，`window - 1` 就只能返回 1 字节
        // （那种情况由"跨窗口边界"那条用例专门覆盖）。
        for (offset in listOf(0, 1, 12345, window - 100, window - 64)) {
            val got = buffered.readBytes(offset.toLong(), 64)
            assertEquals(64, got.size)
            assertTrue(
                "偏移 $offset 的字节不对",
                got.contentEquals(expected.copyOfRange(offset, offset + 64)),
            )
        }
        assertEquals("窗口内的读不该触发新的后端请求", before, fake.requests.size)
    }

    // ── 判据 4：跨窗口边界的读是**短读**，但上层拼装结果必须正确 ──────────

    @Test
    fun `跨窗口边界的单次读只返回窗口剩余，但 readBytes 能拼出正确结果`() {
        val size = 4 * window
        val expected = payload(size)
        val fake = FakeSource(expected)
        val buffered = BufferedSeekableSource(fake, window)

        assertTrue(buffered.read(0, ByteArray(64), 0, 64) > 0)   // 开窗 [0, window)

        val start = window - 100
        val short = buffered.read(start.toLong(), ByteArray(4096), 0, 4096)
        // 契约允许短读；这里把它钉死，免得将来有人以为本类会跨窗口拼满，
        // 从而在别处写出"一次 read 没读满就以为出错"的代码。
        assertEquals(100, short)

        val assembled = buffered.readBytes(start.toLong(), 4096)
        assertEquals(4096, assembled.size)
        assertTrue(
            "跨窗口拼装后的字节不对",
            assembled.contentEquals(expected.copyOfRange(start, start + 4096)),
        )
    }

    // ── 判据 5：窗口只保留一个，seek 出去再回来要重新取 ──────────────────

    @Test
    fun `seek 到窗口外会重新开窗，再读回原位置也要重新取`() {
        val (buffered, fake) = source()
        val buf = ByteArray(64)

        assertTrue(buffered.read(0, buf, 0, 64) > 0)                  // miss → 窗口 [0, 64K)
        assertEquals(1, fake.requests.size)

        assertTrue(buffered.read(2L * window, buf, 0, 64) > 0)         // miss → 窗口搬到 2×64K
        assertEquals(2, fake.requests.size)

        assertTrue(buffered.read(0, buf, 0, 64) > 0)                  // 又 miss（旧窗口已被顶掉）
        assertEquals(
            "窗口没有被顶掉，说明存在多窗口缓存（与设计不符，且会让内存上限失控）",
            3,
            fake.requests.size,
        )
    }

    // ── 判据 6：够大的请求直读；**窗口被协商值压小时窗口必须仍然有效** ────────

    @Test
    fun `请求达到一整个窗口时直读，略小则走窗口`() {
        // 文件给到 8 个窗口：4×window 那个偏移必须**落在文件内**，否则后端直接给 -1，
        // 这个用例就变成在测 EOF 而不是在测分派。
        val (buffered, fake) = source(size = 8 * window)
        val buf = ByteArray(window + 1)

        val big = buffered.read(0, buf, 0, window)
        assertEquals(window, big)
        assertEquals(1, fake.requests.size)
        assertEquals("请求等于一整个窗口时应当直读（预读只会白拷一次）", window, fake.requests[0].second)

        val small = window - 1
        val viaWindow = buffered.read(4L * window, buf, 0, small)
        assertEquals(small, viaWindow)
        assertEquals(2, fake.requests.size)
        assertEquals("小请求应当先拉满一个窗口", window, fake.requests[1].second)
    }

    @Test
    fun `协商上限把窗口压到 64 KiB 时，16 KiB 的请求仍然走窗口`() {
        // ⚠️ 这是 M6 实测修正的**回归保护**，对应一次真实的性能事故。
        //
        // SMB2.0.2 的 `MAX_READ_SIZE` 就是 65536，于是 `SmbConnector` 传下来的
        // 协商值把窗口压到 64 KiB。旧判据是 `length >= windowSize / 4`（= 16 KiB），
        // 在这个尺寸下**任何 ≥16 KiB 的读都被直接放行**，窗口一次都没命中过。
        // 设备上的日志正是 `window=64KiB hits=0 misses=0 buffered=0KiB`，
        // 60 MB 复制退化成 960 次串行往返（实测 1.4 MB/s）。
        //
        // 这条用例在旧代码上必然变红（后端会被直接调用、请求长度是 16 KiB），
        // 在新代码上变绿（后端被调用一次、请求长度是整个窗口）。
        val small = 64 * 1024
        val fake = FakeSource(payload(4 * small))
        val buffered = BufferedSeekableSource(fake, small)

        val n = buffered.read(0, ByteArray(16 * 1024), 0, 16 * 1024)

        assertEquals(16 * 1024, n)
        assertEquals("窗口没被用上 —— 旧判据（windowSize/4）的回归", 1, fake.requests.size)
        assertEquals("应当一次拉满整个窗口", small, fake.requests[0].second)
    }

    // ── 判据 7：EOF 之后窗口必须失效，下一次读要能重新开窗 ────────────────

    @Test
    fun `读到末尾返回 -1，之后还能重新开窗读到正确内容`() {
        val size = window + 10
        val expected = payload(size)
        val fake = FakeSource(expected)
        val buffered = BufferedSeekableSource(fake, window)

        // 先在尾部开一次窗（走窗口路径），让"EOF 时是否复位窗口"这件事真的有意义。
        val tail = buffered.readBytes((size - 16).toLong(), 16)
        assertEquals(16, tail.size)
        assertTrue(tail.contentEquals(expected.copyOfRange(size - 16, size)))

        assertEquals(-1, buffered.read(size.toLong(), ByteArray(16), 0, 16))

        // 这是**回归保护**而非严格的反向判据：EOF 之后不复位窗口也不会返回错数据
        // （旧窗口覆盖的仍是文件内的真实字节），但会让"当前位置"停留在错误的地方，
        // 一旦将来给窗口加上更激进的复用策略就会变成脏数据。这里只钉住"能正常恢复"。
        val again = buffered.readBytes(0, 64)
        assertEquals(64, again.size)
        assertTrue(again.contentEquals(expected.copyOfRange(0, 64)))
    }

    // ── 判据 8：退化输入不碰后端 ────────────────────────────────────────

    @Test
    fun `零长或负长读返回 0 且不访问后端`() {
        val (buffered, fake) = source()
        assertEquals(0, buffered.read(0, ByteArray(8), 0, 0))
        assertEquals(0, buffered.read(1024, ByteArray(8), 0, -5))
        assertTrue("退化输入不该产生网络请求", fake.requests.isEmpty())
    }

    @Test
    fun `从未读过就关闭时，窗口一次都没有分配`() {
        var summary: String? = null
        val (buffered, fake) = source(onClose = { summary = it })

        buffered.close()

        assertEquals(1, fake.closedTimes)
        assertNotNull(summary)
        assertTrue("期望一次未命中都没有，实际：$summary", summary!!.contains("hits=0 misses=0"))
        // 窗口"按需分配"的直接证据：一次都没读过 ⇒ 累计缓冲量为 0。
        assertTrue("窗口被提前分配了：$summary", summary!!.contains("buffered=0KiB"))
    }

    // ── 判据 9：close 的清理与回调都不能被对方拖垮 ──────────────────────

    @Test
    fun `后端 close 抛异常时仍然执行统计回调`() {
        var called = false
        val thrower = object : SeekableSource {
            override val size: Long = 0L
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() = throw IllegalStateException("后端已崩")
        }
        val buffered = BufferedSeekableSource(thrower, window, onClose = { called = true })

        buffered.close()

        assertTrue("后端 close 失败把统计回调一起吞掉了", called)
    }

    @Test
    fun `统计回调抛异常时 close 仍然成功返回`() {
        val (buffered, fake) = source(onClose = { error("取证代码自己崩了") })

        buffered.close()   // 不应抛出

        assertEquals(1, fake.closedTimes)
    }

    @Test
    fun `close 转发给后端，重复调用由后端负责幂等`() {
        val (buffered, fake) = source()
        buffered.close()
        buffered.close()
        // 本类不做"已关闭"标记：smbj 的 `File.close()` 与 WebDAV 的响应体关闭
        // 都是幂等的，而上层（如 SAF 的 `use{}` 加 Activity 的 onDestroy）确实会重复关。
        // 真正要保证的是"不抛异常"。
        assertEquals(2, fake.closedTimes)
    }

    // ── 判据 10：size 透传 ────────────────────────────────────────────

    @Test
    fun `size 原样透传，未知大小保持未知`() {
        val (buffered, _) = source()
        assertEquals(4L * window, buffered.size)

        val unknown = object : SeekableSource {
            override val size: Long = SeekableSource.SIZE_UNKNOWN
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() = Unit
        }
        assertEquals(SeekableSource.SIZE_UNKNOWN, BufferedSeekableSource(unknown, window).size)
    }
}
