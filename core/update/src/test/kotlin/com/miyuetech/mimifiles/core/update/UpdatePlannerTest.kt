package com.miyuetech.mimifiles.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策逻辑的契约：版本比对、选包、地址解析。
 *
 * 这三件都不依赖 Android，却各自对应一类线上事故，所以全部用单测钉住：
 * 对应的病态写法与病态表现写在每条用例的注释里。
 */
class UpdatePlannerTest {

    private fun file(abi: String, url: String = "pkg-$abi.apk") =
        UpdateApkFile(abi = abi, url = url, sha256 = "a".repeat(64), size = 1000)

    private fun manifest(
        versionCode: Int,
        files: List<UpdateApkFile> = listOf(file("arm64-v8a")),
        minSupported: Int = 0,
    ) = UpdateManifest(
        versionCode = versionCode,
        versionName = "v$versionCode",
        publishedAt = "2026-10-14",
        notes = "",
        minSupported = minSupported,
        files = files,
    )

    // ── 版本比对 ───────────────────────────────────────────────────────────

    @Test
    fun `远端更高就是有更新`() {
        val d = UpdatePlanner.decide(manifest(13), installedVersionCode = 12, supportedAbis = listOf("arm64-v8a"))
        assertTrue(d is UpdatePlanner.Decision.Available)
        assertEquals(13, (d as UpdatePlanner.Decision.Available).manifest.versionCode)
    }

    @Test
    fun `版本相等是已是最新而不是有更新`() {
        // 写成 `>=` 的后果：每次点「检查更新」都提示发现新版本（其实是同一版），
        // 下载一遍、校验时被"不是新版本"拒绝 —— 用户白白经历一次失败。
        val d = UpdatePlanner.decide(manifest(12), installedVersionCode = 12, supportedAbis = listOf("arm64-v8a"))
        assertTrue("相等必须判为已是最新，实际 $d", d is UpdatePlanner.Decision.UpToDate)
    }

    @Test
    fun `远端更低也是已是最新（服务端回滚了包）`() {
        val d = UpdatePlanner.decide(manifest(11), installedVersionCode = 12, supportedAbis = listOf("arm64-v8a"))
        assertTrue(d is UpdatePlanner.Decision.UpToDate)
    }

    @Test
    fun `已是最新时不做选包`() {
        // 清单里只有 v7a 包、本机只有 arm64：如果先选包再比版本，
        // "已是最新"这个正常的结论会被"没有适配架构的包"这个错误盖掉。
        val d = UpdatePlanner.decide(
            manifest(12, files = listOf(file("armeabi-v7a"))),
            installedVersionCode = 12,
            supportedAbis = listOf("arm64-v8a"),
        )
        assertTrue(d is UpdatePlanner.Decision.UpToDate)
    }

    // ── 选包 ───────────────────────────────────────────────────────────────

    @Test
    fun `按 SUPPORTED_ABIS 的顺序取第一个命中的包`() {
        val m = manifest(13, files = listOf(file("armeabi-v7a"), file("arm64-v8a")))
        val picked = UpdatePlanner.pickFile(m, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("arm64-v8a", picked.abi)
    }

    @Test
    fun `清单里没有本机架构的包时明确报错并列出两边的值`() {
        val e = runCatching {
            UpdatePlanner.pickFile(manifest(13, files = listOf(file("armeabi-v7a"))), listOf("arm64-v8a"))
        }.exceptionOrNull()
        assertTrue(e is UpdateException)
        // 两边的 ABI 都要出现：这样才能一眼区分是"发错包"还是"挑错包"。
        assertTrue("要写出本机 ABI，实际：${e!!.message}", e.message!!.contains("arm64-v8a"))
        assertTrue("要写出清单 ABI，实际：${e.message}", e.message!!.contains("armeabi-v7a"))
    }

    @Test
    fun `本机只报 arm64 时不会去拿 v7a 包`() {
        // 真·64 位专用设备装上 32 位包会在启动时崩，而崩溃发生在"更新成功"之后 ——
        // 用户只会觉得"更新把应用搞坏了"，且没有任何办法自己退回去。
        val m = manifest(13, files = listOf(file("armeabi-v7a")))
        val e = runCatching { UpdatePlanner.pickFile(m, listOf("arm64-v8a")) }.exceptionOrNull()
        assertTrue(e is UpdateException)
    }

    // ── 地址解析 ───────────────────────────────────────────────────────────

    @Test
    fun `相对地址相对清单自身解析`() {
        assertEquals(
            "https://update.example.com/app/update/deadbeef/mimifiles-0.5.0.apk",
            UpdatePlanner.resolveUrl(
                "https://update.example.com/app/update/deadbeef/manifest.json",
                "mimifiles-0.5.0.apk",
            ),
        )
    }

    @Test
    fun `相对地址里的子目录保持不变`() {
        assertEquals(
            "https://host/a/b/sub/pkg.apk",
            UpdatePlanner.resolveUrl("https://host/a/b/manifest.json", "sub/pkg.apk"),
        )
    }

    @Test
    fun `绝对地址原样返回`() {
        val absolute = "https://other.example.com/x/pkg.apk"
        assertEquals(absolute, UpdatePlanner.resolveUrl("https://host/a/manifest.json", absolute))
    }

    @Test
    fun `清单端口会带进解析结果`() {
        // 9998 这个端口一旦漏掉，表现是"下载 404" —— 而 404 又会被当成
        // "服务器上没传包"，方向完全错了。所以这一条值得单独钉。
        assertEquals(
            "https://update.example.com/app/update/seg/pkg.apk",
            UpdatePlanner.resolveUrl("https://update.example.com/app/update/seg/manifest.json", "pkg.apk"),
        )
    }

    // ── 强制更新 ───────────────────────────────────────────────────────────

    @Test
    fun `低于 minSupported 时判定为强制更新`() {
        val state = UpdateState.Available(manifest(13, minSupported = 12), file("arm64-v8a"))
        assertTrue(state.isForced(installedCode = 10))
        assertTrue(!state.isForced(installedCode = 12))
    }

    @Test
    fun `非更新态永远不是强制`() {
        assertTrue(!UpdateState.Idle.isForced(1))
        assertTrue(!UpdateState.UpToDate("1.0").isForced(1))
        assertTrue(!UpdateState.Failed("x").isForced(1))
    }
}
