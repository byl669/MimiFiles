package com.miyuetech.mimifiles.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 采集（M6 · 方案 §8.1.1 标为「必做」、§8.2 第 2 条）。
 *
 * ## 它到底在干什么
 * `BaselineProfileRule` 会在**目标应用进程内**开一次 ART 采样，然后按下面的脚本
 * 反复操作应用（默认最多 15 轮、连续 3 轮结果一致即收敛）。每一轮记下"哪些方法
 * 的哪些分支真被执行过"，最后并成一份 `*-baseline-prof.txt`。
 *
 * 这份文件经 AGP 编成 `assets/dexopt/baseline.prof` 随 APK 下发；设备安装时
 * `androidx.profileinstaller` 把它交给 ART，于是**首屏要用到的那批方法在安装时就
 * 预编译好了**，启动时不再从解释执行起步。方案 §8.2 第 6 条的目标
 * （冷启动 ≤ 400 ms）靠的就是这一步。
 *
 * ## 为什么是两条 journey、两个 `@Test`，而不是一个方法里调两次 collect
 * `collect()` 的输出文件名默认取 `当前测试类_当前测试方法`。写在同一个方法里的
 * 两次调用会拿到**同一个** uniqueName，第二次的产出会把第一次**直接覆盖掉** ——
 * 表现是"任务绿了、APK 里也有 profile、只是 startup 那半段凭空消失"。
 * 拆成两个 `@Test` 才是两份互不干扰的文件（`startup-baseline-prof.txt`
 * 与 `browse-baseline-prof.txt`，AGP 会把它们合并）。
 *
 * ## 为什么 `includeInStartupProfile = true` 只给 startup 那一条
 * startup profile 在打包时决定了**哪些类必须落在 primary dex** 里。
 * 把浏览、预览、分享这些代码也算进去，只会把 primary dex 撑大、拖慢安装期校验，
 * 换不来启动收益。官方注释同样要求它"只用于启动流程"。
 *
 * ## 为什么这里不处理权限对话框
 * 本项目**没有任何一处调用运行时权限请求**（存储走"三档自动降级"，见
 * `core:fs-local` 的 `StorageAccess`）：拿不到全盘访问就退到 SAF 档、再退到应用私有档，
 * 界面永远有东西可画。所以首启不会弹权限框，采集脚本不必去点它 ——
 * 反过来，如果这里写一段"点掉权限框"的逻辑，它在本工程里是**死代码**。
 *
 * ## 对运行环境的要求（已核对，不是猜的）
 * 采集走的是 `androidx.benchmark.macro.collect()`，源码里只有一条环境分支：
 * `Build.VERSION.SDK_INT >= 33` 时用 `extractProfile(pkg)`（**不需要 root**），
 * 否则退到 `extractProfileRooted()`。本机模拟器是 API 34，走前者。
 *
 * 那套"模拟器上的测量结果不可信"的 `Errors.EMULATOR` 检查**不在这条路径上**
 * （它只在 Macrobenchmark 的耗时测量里），所以这里**不写**
 * `androidx.benchmark.suppressErrors` —— 写了就是一条吓唬下一个人、
 * 却什么也没关掉的开关。真要说清的是：Baseline Profile 要的是
 * **执行路径的覆盖**而不是耗时数值，同一份代码在模拟器与真机上走到的分支是一样的。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        rule.collect(
            packageName = APP_PACKAGE,
            outputFilePrefix = "startup",
            includeInStartupProfile = true,
        ) {
            // 先回桌面再启动：`startActivityAndWait` 带 `-S` 会先杀掉进程，
            // 但桌面那一帧仍然会被算进"启动路径"的背景，回桌面能让采样更干净。
            pressHome()
            startActivityAndWait()
            // 等到真正是我们应用的窗口为止 —— SplashActivity 是 **透明启动主题 +
            // finish() 后立刻跳转**，`startActivityAndWait` 返回时可能还停在它上面。
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), WAIT_MS)
            device.waitForIdle()
        }
    }

    @Test
    fun browse() {
        rule.collect(
            packageName = APP_PACKAGE,
            outputFilePrefix = "browse",
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), WAIT_MS)
            device.waitForIdle()

            // 列表的一次真实滚动。用 `device.swipe` 而不是 `UiObject2.fling`：
            // 前者从 uiautomator 2.0 起签名就没变过，后者在各版本里改过入参
            // （`setGestureMargin` → `setGestureMargins`），采集脚本不该是
            // 最容易被库升级搞坏的一环。坐标取屏高 1/4 ~ 3/4，避开状态栏与底部操作栏。
            val w = device.displayWidth
            val h = device.displayHeight
            repeat(2) {
                device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, SWIPE_STEPS)
                device.waitForIdle()
            }
            repeat(2) {
                device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, SWIPE_STEPS)
                device.waitForIdle()
            }
        }
    }

    private companion object {
        /**
         * `:app` 的 applicationId。**不带 `.debug` 后缀** —— 采集跑的是插件自动建出来的
         * `benchmark` 变体（它继承 release、只有 debug 建了后缀），包名与生产包一致。
         */
        const val APP_PACKAGE = "com.miyuetech.mimifiles"

        /** 首屏出现窗口的等待上限。冷启动实测远小于它，这里只是兜底。 */
        const val WAIT_MS = 5_000L

        /** 滑动分几步。给足步数让滚动是"滑动"而不是"瞬移"，否则列表的绑定/复用路径走不全。 */
        const val SWIPE_STEPS = 30
    }
}
