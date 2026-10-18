package com.miyuetech.mimifiles.core.shareserver.http

import com.miyuetech.mimifiles.core.shareserver.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Range` 解析与 Basic 认证。
 *
 * 这两块的错误都不会"编译报错、也不会崩"，而是静默地表现为
 * "视频拖不了进度"或"密码错了也放行"——所以必须钉死。
 */
class RangeSpecTest {

    @Test
    fun `闭区间`() {
        val r = RangeSpec.parse("bytes=100-199", 1000) as RangeSpec.Satisfiable
        assertEquals(100L, r.start)
        assertEquals(199L, r.endInclusive)
    }

    @Test
    fun `开区间到末尾`() {
        val r = RangeSpec.parse("bytes=900-", 1000) as RangeSpec.Satisfiable
        assertEquals(900L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `后缀形式取最后 N 字节`() {
        // 这是最容易写反的一条：`-500` 不是"从 -500 开始"，而是"最后 500 字节"。
        val r = RangeSpec.parse("bytes=-500", 1000) as RangeSpec.Satisfiable
        assertEquals(500L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `后缀长度超过文件大小时从 0 起`() {
        val r = RangeSpec.parse("bytes=-5000", 1000) as RangeSpec.Satisfiable
        assertEquals(0L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `末尾越界被钳到文件末尾`() {
        val r = RangeSpec.parse("bytes=900-99999", 1000) as RangeSpec.Satisfiable
        assertEquals(900L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun `起点越界是 416`() {
        assertTrue(RangeSpec.parse("bytes=1000-", 1000) is RangeSpec.Unsatisfiable)
        assertTrue(RangeSpec.parse("bytes=5000-6000", 1000) is RangeSpec.Unsatisfiable)
    }

    @Test
    fun `终点小于起点是 416`() {
        assertTrue(RangeSpec.parse("bytes=500-100", 1000) is RangeSpec.Unsatisfiable)
    }

    @Test
    fun `语法非法按 RFC 忽略而不是报错`() {
        // 报错会让"某个客户端偶尔发了个奇怪的头"变成"视频完全播不了"。
        assertTrue(RangeSpec.parse(null, 1000) is RangeSpec.Ignored)
        assertTrue(RangeSpec.parse("", 1000) is RangeSpec.Ignored)
        assertTrue(RangeSpec.parse("items=0-10", 1000) is RangeSpec.Ignored)
        assertTrue(RangeSpec.parse("bytes=", 1000) is RangeSpec.Ignored)
        assertTrue(RangeSpec.parse("bytes=abc-def", 1000) is RangeSpec.Ignored)
        assertTrue(RangeSpec.parse("bytes=no-dash", 1000) is RangeSpec.Ignored)
    }

    @Test
    fun `多段只取第一段`() {
        // 浏览器播放媒体时只发单段；返回 multipart/byteranges 需要拼 boundary
        // 并精确计算每段的头长度，收益为零。
        val r = RangeSpec.parse("bytes=0-99,200-299", 1000) as RangeSpec.Satisfiable
        assertEquals(0L, r.start)
        assertEquals(99L, r.endInclusive)
    }

    @Test
    fun `大小写与前缀空格都被容忍`() {
        val r = RangeSpec.parse("Bytes=0-9", 1000) as RangeSpec.Satisfiable
        assertEquals(9L, r.endInclusive)
        assertTrue(RangeSpec.parse("  bytes=0-9  ", 1000) is RangeSpec.Satisfiable)
    }

    @Test
    fun `空文件上的任何 Range 都不可满足`() {
        assertTrue(RangeSpec.parse("bytes=0-", 0) is RangeSpec.Unsatisfiable)
        assertTrue(RangeSpec.parse("bytes=-1", 0) is RangeSpec.Unsatisfiable)
    }
}

class HttpAuthTest {

    private val credentials = AuthMode.Credentials(user = "mimi", password = "p@ss:word")

    @Test
    fun `匿名模式放行一切`() {
        assertTrue(HttpAuth.verify("whatever", AuthMode.Anonymous))
        assertTrue(HttpAuth.verify("", AuthMode.Anonymous))
    }

    @Test
    fun `正确凭据通过`() {
        assertTrue(HttpAuth.verify("mimi:p@ss:word", credentials))
    }

    @Test
    fun `密码里含冒号时只按第一个冒号切分`() {
        // `p@ss:word` 被切成 `mimi` + `p@ss:word`。如果按最后一个冒号切，
        // 密码就会变成 `word`，于是"密码里带冒号的连接一个都登不上"。
        assertTrue(HttpAuth.verify("mimi:p@ss:word", credentials))
        assertFalse(HttpAuth.verify("mimi:p@ss", credentials))
    }

    @Test
    fun `用户名密码任一不符都拒绝`() {
        assertFalse(HttpAuth.verify("MIMI:p@ss:word", credentials))
        assertFalse(HttpAuth.verify("mimi:P@ss:word", credentials))
        assertFalse(HttpAuth.verify("mimi:", credentials))
        assertFalse(HttpAuth.verify(":p@ss:word", credentials))
    }

    @Test
    fun `缺少冒号一律拒绝`() {
        assertFalse(HttpAuth.verify("mimi", credentials))
        assertFalse(HttpAuth.verify("", credentials))
    }

    @Test
    fun `空密码的凭据可以精确匹配`() {
        val empty = AuthMode.Credentials(user = "u", password = "")
        assertTrue(HttpAuth.verify("u:", empty))
        assertFalse(HttpAuth.verify("u:x", empty))
    }

    @Test
    fun `定长比较在长度不同时也返回假`() {
        assertFalse(HttpAuth.constantTimeEquals("a", "ab"))
        assertTrue(HttpAuth.constantTimeEquals("", ""))
        assertTrue(HttpAuth.constantTimeEquals("中文", "中文"))
        assertFalse(HttpAuth.constantTimeEquals("中文", "中"))
    }
}
