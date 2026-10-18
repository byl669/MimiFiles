package com.miyuetech.mimifiles.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单解析的契约。
 *
 * 这组用例的重点**不是"能不能解析正常清单"**（那是最容易对的一格），
 * 而是"清单坏掉时会不会给出错答案"。参考实现用 `optInt("versionCode", -1)`，
 * 于是字段缺失会静默变成「已是最新」——一个看起来完全正常的错误结论。
 * 下面每一条"应当抛错"的用例，钉的都是这一类。
 */
class UpdateManifestCodecTest {

    private fun manifestJson(
        versionCode: String = "12",
        versionName: String = "0.5.0",
        extra: String = "",
    ) = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "publishedAt": "2026-10-14",
          "notes": "新增软件更新",
          "minSupported": 5,
          "files": [
            {
              "abi": "arm64-v8a",
              "url": "mimifiles-0.5.0-arm64-release.apk",
              "sha256": "${"a".repeat(64)}",
              "size": 6200000
            }
          ]$extra
        }
    """.trimIndent()

    @Test
    fun `正常清单全部字段都解析出来`() {
        val m = UpdateManifestCodec.parse(manifestJson())
        assertEquals(12, m.versionCode)
        assertEquals("0.5.0", m.versionName)
        assertEquals("2026-10-14", m.publishedAt)
        assertEquals("新增软件更新", m.notes)
        assertEquals(5, m.minSupported)
        assertEquals(1, m.files.size)
        assertEquals("arm64-v8a", m.files[0].abi)
        assertEquals("mimifiles-0.5.0-arm64-release.apk", m.files[0].url)
        assertEquals("a".repeat(64), m.files[0].sha256)
        assertEquals(6_200_000L, m.files[0].size)
    }

    @Test
    fun `versionCode 写成字符串也收`() {
        // 手写清单很常见的一种写法。收它，但必须真的能解析成整数。
        assertEquals(12, UpdateManifestCodec.parse(manifestJson(versionCode = "\"12\"")).versionCode)
    }

    // ── 下面这几条是"绝不能静默通过"的 ────────────────────────────────────

    @Test
    fun `缺 versionCode 必须报错而不是当成没有更新`() {
        val json = """
            { "versionName": "0.5.0", "files": [ { "abi": "arm64-v8a", "url": "a.apk", "sha256": "${"a".repeat(64)}" } ] }
        """.trimIndent()
        val e = runCatching { UpdateManifestCodec.parse(json) }.exceptionOrNull()
        assertTrue("应当是 UpdateException，实际 ${e?.javaClass}", e is UpdateException)
        assertTrue("错误消息要指名缺的是 versionCode，实际：${e?.message}", e!!.message!!.contains("versionCode"))
    }

    @Test
    fun `versionCode 是 0 或负数必须报错`() {
        // 0 会让 `versionCode <= installed` 恒成立 → 永远"已是最新"。
        val e = runCatching { UpdateManifestCodec.parse(manifestJson(versionCode = "0")) }.exceptionOrNull()
        assertTrue(e is UpdateException)
        assertTrue(e!!.message!!.contains("正整数"))
    }

    @Test
    fun `缺 versionName 必须报错`() {
        val json = """
            { "versionCode": 12, "files": [ { "abi": "arm64-v8a", "url": "a.apk", "sha256": "${"a".repeat(64)}" } ] }
        """.trimIndent()
        val e = runCatching { UpdateManifestCodec.parse(json) }.exceptionOrNull()
        assertTrue(e is UpdateException)
        assertTrue(e!!.message!!.contains("versionName"))
    }

    @Test
    fun `files 为空必须报错`() {
        val e = runCatching {
            UpdateManifestCodec.parse("""{ "versionCode": 12, "versionName": "0.5.0", "files": [] }""")
        }.exceptionOrNull()
        assertTrue(e is UpdateException)
        assertTrue(e!!.message!!.contains("没有可下载"))
    }

    @Test
    fun `sha256 长度不对必须报错`() {
        // 挡的是"发布脚本忘了算哈希，把占位符原样发上去"。
        // 若不在这里挡，失败会推迟到下载完成后的校验阶段，而那时报的是
        // "安装包完整性校验失败" —— 把人指向"传输损坏"，方向完全错了。
        val json = """
            { "versionCode": 12, "versionName": "0.5.0",
              "files": [ { "abi": "arm64-v8a", "url": "a.apk", "sha256": "TODO" } ] }
        """.trimIndent()
        val e = runCatching { UpdateManifestCodec.parse(json) }.exceptionOrNull()
        assertTrue(e is UpdateException)
        assertTrue(e!!.message!!.contains("sha256"))
    }

    @Test
    fun `sha256 大小写混写会被归一成小写`() {
        // 发布脚本可能输出大写。校验时用的是 equals(ignoreCase=true)，
        // 但归一化能让日志与错误消息里的值一致，排查时不用心算大小写。
        val sha = "AB".repeat(32)
        val json = """
            { "versionCode": 12, "versionName": "0.5.0",
              "files": [ { "abi": "arm64-v8a", "url": "a.apk", "sha256": "$sha" } ] }
        """.trimIndent()
        assertEquals("ab".repeat(32), UpdateManifestCodec.parse(json).files[0].sha256)
    }

    @Test
    fun `拿到服务器错误页时报错要说明拿到的是 HTML`() {
        // 静态服务在文件缺失时可能回一个 403/404 HTML 页（而不是 404 状态码，
        // 取决于配置）。此时 JSONObject 会抛，而错误消息必须能让人一眼看出
        // "这不是清单"，否则会往"清单格式写错了"的方向查。
        val e = runCatching {
            UpdateManifestCodec.parse("<html><head><title>403 Forbidden</title></head></html>")
        }.exceptionOrNull()
        assertTrue(e is UpdateException)
        assertTrue(e!!.message!!.contains("不是合法的 JSON"))
    }

    @Test
    fun `缺 minSupported 时默认 0 而不是跟着 versionCode 走`() {
        // 默认值给成 versionCode 的后果：一次忘记写 minSupported 的发布
        // 会变成"强制所有人立即更新"。
        val json = """
            { "versionCode": 12, "versionName": "0.5.0",
              "files": [ { "abi": "arm64-v8a", "url": "a.apk", "sha256": "${"a".repeat(64)}" } ] }
        """.trimIndent()
        assertEquals(0, UpdateManifestCodec.parse(json).minSupported)
    }
}
