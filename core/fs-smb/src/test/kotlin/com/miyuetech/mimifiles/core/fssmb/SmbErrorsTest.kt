package com.miyuetech.mimifiles.core.fssmb

import com.miyuetech.mimifiles.core.fs.FsIoException
import com.miyuetech.mimifiles.core.model.FsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SMB 错误文案的契约。
 *
 * ## 为什么这些用例值得钉死
 * 每一条都来自 **M5.1 真机上截下来的原话**。它们有两个共同点：
 *   · 都是"编译能过、单测能过、只有装到设备上才看得见"的问题；
 *   · 都会让用户拿到的信息**看起来像严重错误，实际上毫无行动价值**。
 *
 * 错误文案是排障链路的最后一环 —— 前面所有取证做对了，最后给出一句
 * `0x-3fffffff：…Trans2FindFirst2[command=SMB_COM_TRANSACTION2,…]` 也等于没做。
 */
class SmbErrorsTest {

    private val path = FsPath.of("smb", "10.0.2.2:4450/SHARE")

    // ── 十六进制 ────────────────────────────────────────────────────────────

    @Test
    fun `NT status 按无符号打印`() {
        // 真机原话是 `0x-3fffffff`：NT status 第 31 位恒为 1，Kotlin 的 Int 是负数，
        // `toString(16)` 于是给出带负号的结果 —— 用户既搜不到也对不上任何文档。
        assertEquals("0xC0000001", 0xC0000001.toInt().asUnsignedHex())
        assertEquals("0xC0000034", 0xC0000034.toInt().asUnsignedHex())
        // 小的正数要补零到 8 位，保持"协议常量"的观感一致
        assertEquals("0x00000000", 0.asUnsignedHex())
        assertEquals("0xFFFFFFFF", (-1).asUnsignedHex())
    }

    // ── 协议转储切断 ────────────────────────────────────────────────────────

    @Test
    fun `jcifs 的协议转储被切断且只留人话`() {
        // 真机 UI 原样抓下来的字符串（截断了中段字段，形状保持真实）
        val raw = "Invalid operation for SHARE service: Trans2FindFirst2[" +
            "command=SMB_COM_TRANSACTION2,received=false,errorCode=0,flags=0x0018," +
            "flags2=0x0000,signSeq=0,tid=1,pid=54685,uid=0,mid=0,wordCount=0]"
        assertEquals("Invalid operation for SHARE service", raw.trimSmbPacketDump())
    }

    @Test
    fun `没有转储时原样保留`() {
        assertEquals("The network name cannot be found.", "The network name cannot be found.".trimSmbPacketDump())
        assertEquals("连接被拒绝", "连接被拒绝".trimSmbPacketDump())
    }

    @Test
    fun `中文标点的尾巴也被修干净`() {
        // 切断后可能留下悬空的分隔符，界面上会显得像话没说完
        assertEquals("共享名无效", "共享名无效：Trans2FindFirst2[command=SMB_COM_OPEN,flags=0]".trimSmbPacketDump())
    }

    @Test
    fun `humanSmbMessage 限长且单行`() {
        val long = "x".repeat(500)
        val got = long.toHumanSmbLine(maxLen = 100)
        assertTrue("限长生效", got.length <= 101)
        assertTrue("截断有省略号", got.endsWith("…"))
        assertEquals("换行被去掉", "第一行", "第一行\n第二行".toHumanSmbLine())
        assertEquals("异常版与字符串版一致", "第一行", RuntimeException("第一行\n第二行").humanSmbMessage())
    }

    // ── 端到端：拿真机的异常走一遍翻译 ──────────────────────────────────────

    @Test
    fun `未识别的异常不再自我重复也不再带包名`() {
        // M5.1 用户看到的第一句：`$this` 的 toString 里已含 message，又被拼了一遍
        val raw = RuntimeException(
            "r9.c: The provided handler invocation did not specify the necessary constructor " +
                "h(SubscriptionContext)",
        )
        val out = raw.toSmbException(path) as FsIoException
        val msg = out.message.orEmpty()
        assertEquals("只出现一次", 1, Regex(Regex.escape("did not specify")).findAll(msg).count())
        assertFalse("不带 Package 前缀", msg.contains("java.lang."))
        assertTrue("保留可搜的关键词", msg.contains("SubscriptionContext"))
    }

    @Test
    fun `jcifs 的 Trans2 转储不会进到用户可见文案里`() {
        val raw = RuntimeException(
            "Invalid operation for SHARE service: Trans2FindFirst2[" +
                "command=SMB_COM_TRANSACTION2,received=false,errorCode=0]",
        )
        val out = raw.toSmbException(path) as FsIoException
        val msg = out.message.orEmpty()
        assertFalse("转储字段不外泄", msg.contains("command=SMB_COM_"))
        assertTrue("保留人话", msg.contains("Invalid operation for SHARE service"))
    }
}
