package com.miyuetech.mimifiles.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 多镜像取值的契约：**取最大值**，并列取先出现的。
 *
 * 这些用例钉的是 2026-10-29 的真实事故：`fastly.jsdelivr.net` 留着旧清单，
 * 客户端"首个成功就用"于是显示"已是最新版本"，新版其实已发布。
 * 对应的病态写法与病态表现写在每条用例的注释里。
 */
class ManifestMirrorsTest {

    private fun m(versionCode: Int) = UpdateManifest(
        versionCode = versionCode,
        versionName = "v$versionCode",
        publishedAt = "2026-10-29",
        notes = "",
        minSupported = 0,
        files = listOf(UpdateApkFile("arm64-v8a", "pkg.apk", "a".repeat(64), 1000)),
    )

    private fun at(url: String, versionCode: Int) = MirrorManifest(url, m(versionCode))

    @Test
    fun `陈旧镜像排在前面时取新版`() {
        // 病态写法：firstOrNull / 首个解析成功即 break → 拿到 34 与 35 里的 34，
        // 若本地是 34 就得出"已是最新"，而 35 其实已经发布。
        val best = ManifestMirrors.newest(
            listOf(
                at("fastly", 34),
                at("raw", 35),
                at("cdn", 35),
            ),
        )
        assertEquals("raw", best?.url)
        assertEquals(35, best?.manifest?.versionCode)
    }

    @Test
    fun `新版排在前面时不会被后面的陈旧清单拉回去`() {
        val best = ManifestMirrors.newest(
            listOf(
                at("raw", 35),
                at("cdn", 34),
                at("fastly", 33),
            ),
        )
        assertEquals("raw", best?.url)
        assertEquals(35, best?.manifest?.versionCode)
    }

    @Test
    fun `同版本并列时保留首选镜像`() {
        // activeManifestUrl 是下载相对路径的基准，不能因为并列就飘到别的镜像。
        val best = ManifestMirrors.newest(
            listOf(
                at("raw", 35),
                at("cdn", 35),
            ),
        )
        assertEquals("raw", best?.url)
    }

    @Test
    fun `只有一个镜像可用时就是它`() {
        assertEquals("cdn", ManifestMirrors.newest(listOf(at("cdn", 34)))?.url)
    }

    @Test
    fun `一个都没有时返回 null 由调用方报失败`() {
        assertNull(ManifestMirrors.newest(emptyList()))
    }
}
