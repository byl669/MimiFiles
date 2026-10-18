package com.miyuetech.mimifiles.feature.preview

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ZoomableContainer 双指捏合的端到端验证。
 *
 * ## 为什么非要上 instrumentation
 * `adb shell input` 只支持单指（tap / swipe），捏合从未有过任何自动化验证——
 * M3.1 修的"平移有两个主人"、这次的"抬指跳变 / 轻点+捏合跳变"全部只能靠真机人手发现。
 *
 * ## 注入通道的三次试错（读这段省得重走）
 * 1. `sendPointerSync`（同步注入）：InputDispatcher 把 `POINTER_DOWN` 整批丢弃，
 *    日志 "Dropping event because a pointer for device -1 is already down"——
 *    注入流在 dispatcher 眼里是 deviceId=-1 的单一流，**不允许用后续事件加指**。
 * 2. `UiAutomation.dispatchGesture`：API 34 的 `UiAutomation` 上根本没有这个方法
 *    （它在 AccessibilityService 上），编译不过。
 * 3. **最终方案**：自己合成完整的多点触控 MotionEvent 流
 *    （DOWN → MOVE → POINTER_DOWN → MOVE… → POINTER_UP → MOVE → UP），
 *    用 `UiAutomation.injectInputEvent(event, false)` **异步**注入——
 *    这正是 uiautomator `GestureController` 内部的同款通道（反编译确认）。
 *    注入按真实时间 pacing（16ms/步），检测器看到的时间戳与真实节奏一致。
 *
 * ## 断言的都是"性质"，不是具体像素
 * - **跟手（缩放）**：终态 scale ≈ 终态指间距 / 初始指间距（钳制在 1..3）。
 * - **跟手（漂移）**：捏合全程"双指中点下的内容点"始终在中点下——
 *   `t1 = f1 - (f0 - t0)·s1/s0`（精确等式，见 ZoomTransform）。
 * - **抬指不跳**：一根手指抬起后，剩余手指的平移 1:1 跟手（tx 不横跳半指距）。
 * - **轻点后捏合不触发双击跳变**：tap→pinch 的 DOWN 序列不得把 scale 瞬间跳到 2。
 *
 * ## 坐标口径
 * 注入的是**屏幕坐标**；事件进入容器后是**容器本地**坐标（InputDispatcher 减窗口
 * 原点、ViewGroup 再减子 View 偏移）。断言前用 [containerOrigin] 统一换算。
 */
@RunWith(AndroidJUnit4::class)
class ZoomableContainerPinchTest {

    private val ins = InstrumentationRegistry.getInstrumentation()

    /** 捏合轨迹的起手保持：B 落下（~16ms）时手指尚未张开，基准间距才准确。 */
    private val PRE_HOLD_MS = 48L

    /** 捏合轨迹的收尾保持：末段张开量通过 MOVE（而非 UP）送达容器。 */
    private val END_HOLD_MS = 120L

    // ── 注入基建 ────────────────────────────────────────────────

    /** 手指轨迹的拐点：`(x, y)` 在手势开始后 `atMs` 毫秒时经过。 */
    private data class Waypoint(val x: Float, val y: Float, val atMs: Long)

    private fun wp(x: Float, y: Float, atMs: Long) = Waypoint(x, y, atMs)

    /** 一根手指：拐点序列线性插值成 16ms 步进的采样轨迹。首个拐点必须在 t=0。 */
    private class Finger(val path: List<Waypoint>) {
        init {
            check(path.first().atMs == 0L) { "手指轨迹必须从 t=0 开始" }
        }

        val endMs: Long get() = path.last().atMs

        fun positionAt(t: Long): Pair<Float, Float> {
            for (i in 0 until path.size - 1) {
                val p0 = path[i]
                val p1 = path[i + 1]
                if (t in p0.atMs..p1.atMs) {
                    val f = if (p1.atMs == p0.atMs) 0f else (t - p0.atMs).toFloat() / (p1.atMs - p0.atMs)
                    return (p0.x + (p1.x - p0.x) * f) to (p0.y + (p1.y - p0.y) * f)
                }
            }
            return path.last().x to path.last().y
        }
    }

    private fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
        clear()
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
    }

    private fun props(id: Int) = MotionEvent.PointerProperties().apply {
        clear()
        this.id = id
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    /** 单根手指的手势（tap / 单指拖动）。 */
    private fun perform(a: Finger) = perform(a, null)

    /**
     * 合成并注入一次完整的手势流。A 从 t=0 到 [Finger.endMs]；B 可选，
     * 在自己的时间窗内进场与抬起（提前抬起 = 捏合中抬指）。
     */
    private fun perform(a: Finger, b: Finger?) {
        check(a.endMs >= (b?.endMs ?: 0)) { "A 必须最后离场" }
        val downTime = SystemClock.uptimeMillis()
        val stepMs = 16L
        val endT = a.endMs
        var bDown = false
        var bUp = false
        var t = 0L

        fun inject(actionBase: Int, actionIndex: Int?, pa: Pair<Float, Float>, pb: Pair<Float, Float>?) {
            val evTime = downTime + t
            // ⚠️ 单指/多指必须走**同一个** obtain 重载（显式带 source）。
            // 简洁重载 obtain(dt,t,action,x,y,meta) 的 source 是 0，而多指事件是
            // SOURCE_TOUCHSCREEN —— dispatcher 按 deviceId+source 识别设备流，
            // source 不一致会被当成"另一台设备"发起的加指，整批丢弃
            // （"Dropping event because a pointer for device -1 is already down"）。
            val propsArr: Array<MotionEvent.PointerProperties>
            val coordsArr: Array<MotionEvent.PointerCoords>
            if (pb == null) {
                propsArr = arrayOf(props(0))
                coordsArr = arrayOf(coords(pa.first, pa.second))
            } else {
                propsArr = arrayOf(props(0), props(1))
                coordsArr = arrayOf(coords(pa.first, pa.second), coords(pb.first, pb.second))
            }
            var action = actionBase
            if (actionIndex != null) action = actionBase or (actionIndex shl ACTION_POINTER_INDEX_SHIFT)
            val event = MotionEvent.obtain(
                downTime,
                evTime,
                action,
                propsArr.size,
                propsArr,
                coordsArr,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
            assertTrue("注入失败", ins.uiAutomation.injectInputEvent(event, false))
            event.recycle()
        }

        // t=0：A 落下。
        var pa = a.positionAt(0)
        inject(ACTION_DOWN, null, pa, null)
        while (t < endT) {
            t = minOf(t + stepMs, endT)
            // 按真实时间 pacing，保证检测器看到的 eventTime 与到达节奏一致。
            val target = downTime + t
            while (SystemClock.uptimeMillis() < target) sleep(2)
            if (b != null && !bDown && t >= b.path.first().atMs) {
                // B 进场：POINTER_DOWN（两指坐标都在事件里，actionIndex=1）。
                bDown = true
                pa = a.positionAt(t)
                inject(ACTION_POINTER_DOWN, 1, pa, b.positionAt(t))
                continue
            }
            if (b != null && bDown && !bUp && t >= b.endMs) {
                // B 提前抬起：POINTER_UP。
                bUp = true
                pa = a.positionAt(t)
                inject(ACTION_POINTER_UP, 1, pa, b.positionAt(b.endMs))
                continue
            }
            // 普通 MOVE：带上当前在场的手指。
            pa = a.positionAt(t)
            if (b != null && bDown && !bUp) {
                inject(ACTION_MOVE, null, pa, b.positionAt(t))
            } else {
                inject(ACTION_MOVE, null, pa, null)
            }
        }
        // A 抬起收尾。
        pa = a.positionAt(endT)
        inject(ACTION_UP, null, pa, null)
        sleep(150) // 等手势落定（含 GD 的节拍）
    }

    /**
     * 两指同时长捏合：从 `(a0x,y)/(b0x,y)` 张开（或收拢）到 `(a1x,y+driftY)/(b1x,·)`。
     *
     * ## 为什么轨迹首尾各有一段"保持"
     * 注入流里 B 总在 A 落下后 ~16ms 才进场（POINTER_DOWN）：若手指从 t=0 就开始
     * 张开，容器记录的捏合基准间距就已经吃掉了开头一段张开量（终值系统性偏小，
     * P4 实测 212 而非 200）；同理手势末尾最后一两个 MOVE 之后紧跟的是
     * POINTER_UP/UP（缩放只在 MOVE 驱动），不保持的话末段张开量不会生效
     * （实测终帧在 2.30 处停更）。真人捏合本来就是"起手定住 → 张开 → 收住"，
     * 所以轨迹设计为：**起手保持 [PRE_HOLD_MS] → 张开 → 收尾保持 [END_HOLD_MS]**。
     * 有了这两段，"终态 scale == 终态间距 / 初始间距"才是确定性的。
     */
    private fun pinch(
        a0x: Float,
        b0x: Float,
        y: Float,
        a1x: Float,
        b1x: Float,
        durationMs: Long = 400,
        driftY: Float = 0f,
    ) = perform(
        Finger(
            listOf(
                wp(a0x, y, 0),
                wp(a0x, y, PRE_HOLD_MS),
                wp(a1x, y + driftY, durationMs + PRE_HOLD_MS),
                wp(a1x, y + driftY, durationMs + PRE_HOLD_MS + END_HOLD_MS),
            ),
        ),
        Finger(
            listOf(
                wp(b0x, y, 0),
                wp(b0x, y, PRE_HOLD_MS),
                wp(b1x, y + driftY, durationMs + PRE_HOLD_MS),
                wp(b1x, y + driftY, durationMs + PRE_HOLD_MS + END_HOLD_MS),
            ),
        ),
    )

    private fun tap(x: Float, y: Float) =
        perform(Finger(listOf(wp(x, y, 0), wp(x + 0.5f, y, 40))))

    private fun sleep(ms: Long) = Thread.sleep(ms)

    private fun containerOrigin(scenario: ActivityScenario<PinchProbeActivity>): Pair<Int, Int> {
        var out = 0 to 0
        scenario.onActivity { a ->
            val loc = IntArray(2)
            a.container.getLocationOnScreen(loc)
            out = loc[0] to loc[1]
        }
        return out
    }

    private fun readTransform(scenario: ActivityScenario<PinchProbeActivity>): FloatArray {
        var out = FloatArray(3)
        scenario.onActivity { a -> out = a.container.debugTransform().copyOf() }
        return out
    }

    private fun listenScale(scenario: ActivityScenario<PinchProbeActivity>): MutableList<Float> {
        val samples = mutableListOf<Float>()
        scenario.onActivity { a -> a.container.onScaleChanged = { s -> samples.add(s) } }
        return samples
    }

    private fun awaitFocus(scenario: ActivityScenario<PinchProbeActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var focused = false
            scenario.onActivity { a -> focused = a.window.decorView.hasWindowFocus() }
            if (focused) return
            sleep(100)
        }
        throw IllegalStateException("探针窗口 5s 内没拿到焦点")
    }

    // ── 场景 ────────────────────────────────────────────────────

    /**
     * **P1 · 对称捏合，缩放跟手**（回归护栏：修平移不许伤缩放）。
     * 间距 300 → 600：终态 scale≈2，轨迹单调不回落。
     */
    @Test
    fun symmetricPinch_scaleFollowsSpan_monotonic() {
        ActivityScenario.launch(PinchProbeActivity::class.java).use { scenario ->
            awaitFocus(scenario)
            sleep(400)
            val samples = listenScale(scenario)
            pinch(a0x = 390f, b0x = 690f, y = 700f, a1x = 240f, b1x = 840f)
            val t = readTransform(scenario)
            assertTrue("终态 scale=${t[0]} 应≈2（跟手）", abs(t[0] - 2f) < 0.12f)
            for (i in 1 until samples.size) {
                assertTrue(
                    "scale 轨迹在 i=$i 处回落：${samples[i - 1]} -> ${samples[i]}",
                    samples[i] >= samples[i - 1] - 0.005f,
                )
            }
        }
    }

    /**
     * **P2 · 捏合带漂移，内容跟中点**。
     * 两指张开的同时整体下移 100px："初始中点下的内容点"终止在"终态中点下"：
     * `ty1 = f1y - f0y·s1`（t0=(0,0)、s0=1，本地坐标）。
     */
    @Test
    fun pinchWithDrift_contentStaysUnderMidpoint() {
        ActivityScenario.launch(PinchProbeActivity::class.java).use { scenario ->
            awaitFocus(scenario)
            sleep(400)
            val offY = containerOrigin(scenario).second
            pinch(a0x = 390f, b0x = 690f, y = 700f, a1x = 240f, b1x = 840f, driftY = 100f)
            val t = readTransform(scenario)
            val f0y = 700f - offY
            val f1y = 800f - offY
            val expectedTy = f1y - f0y * t[0]
            assertTrue("终态 scale=${t[0]} 应≈2", abs(t[0] - 2f) < 0.12f)
            // 容差 32px 的依据：注入事件是**批量交付**的（M7.1 已知），末批
            // MOVE 的漂移增量会被"整体吸收"成 1-2 次大步，步进相位抖动实测
            // 可到 ~28px（M7.2/M7.5 各红过一次，均复跑转绿）。这是注入精度
            // 的下限，不是引擎偏差——锚点双算类真 bug 的偏差是几百 px 量级
            // （ty 会差出半屏），32px 仍能把它们稳稳拦住。
            assertTrue(
                "ty=${t[2].roundToInt()} 应≈${expectedTy.roundToInt()}（内容跟中点；" +
                    "偏差过大 = 缩放锚点与漂移平移双重计算）",
                abs(t[2] - expectedTy) < 32f,
            )
        }
    }

    /**
     * **P3 · 捏合中抬指，不平移跳变**。
     * B 在 360ms 时提前抬起，A 继续走到 600ms 并纵向拖 60px。
     * 断言用**事件锚定**采样：容器在 POINTER_UP 时刻定格变换快照，
     * "终态 − 快照"就是抬指后的全部增量 —— 对输入管线的突发交付免疫
     * （早期的墙上时钟采样（430ms 处读一次）会与事件批交付竞态，
     * 快照可能读到"已经全部应用完"的终态，dTy 恒 0，假红）。
     * 抬指后 A 只纵向动：tx 增量必须为 0；ty 增量 ≈ 60（1:1 跟手）。
     * 若平移基线还带着"双指中点"（Fix D 缺失），抬指瞬间 tx 会横跳半指距（~300px）。
     */
    @Test
    fun pointerUpThenSingleMove_noJump() {
        ActivityScenario.launch(PinchProbeActivity::class.java).use { scenario ->
            awaitFocus(scenario)
            sleep(400)
            val fingerA = Finger(
                listOf(wp(390f, 700f, 0), wp(240f, 700f, 360), wp(240f, 760f, 600)),
            )
            val fingerB = Finger(listOf(wp(690f, 700f, 0), wp(810f, 700f, 360)))
            perform(fingerA, fingerB)
            lateinit var atUp: FloatArray
            var final = FloatArray(3)
            scenario.onActivity { s ->
                atUp = s.container.debugTransformAtPointerUp.copyOf()
                final = s.container.debugTransform().copyOf()
            }
            val dTx = final[1] - atUp[1]
            val dTy = final[2] - atUp[2]
            assertTrue(
                "抬指后 tx 增量 ${dTx.roundToInt()}（应≈0；不为 0 = 平移基线带着已抬手指）",
                abs(dTx) < 6f,
            )
            assertTrue(
                "抬指后 ty 增量 ${dTy.roundToInt()}（应≈60，1:1 跟手）",
                abs(dTy - 60f) < 12f,
            )
        }
    }

    /**
     * **P4 · 轻点后立刻捏合，不触发双击跳变**。
     * 真机高频序列：手指轻点定位 → 立刻捏合。上一下势是 tap + 间隔 <300ms + 距离在
     * slop 内时，GD 会在捏合第一指 DOWN 上回调 onDoubleTap——不设防的话 scale 会在
     * 捏合开始前瞬间跳到 2（"一下子放大/还原"的直接来源）。期望：无跳变，终态≈2.5。
     */
    @Test
    fun tapThenPinch_noDoubleTapJump() {
        ActivityScenario.launch(PinchProbeActivity::class.java).use { scenario ->
            awaitFocus(scenario)
            sleep(400)
            val samples = listenScale(scenario)
            tap(540f, 800f)
            sleep(80) // 真机节奏：轻点后 ~100ms 内起手捏合
            pinch(a0x = 440f, b0x = 640f, y = 800f, a1x = 290f, b1x = 790f)
            val t = readTransform(scenario)
            assertTrue("终态 scale=${t[0]} 应≈2.5（间距 200→500）", abs(t[0] - 2.5f) < 0.15f)
            for (i in 1 until samples.size) {
                assertTrue(
                    "scale 轨迹在 i=$i 处回落：${samples[i - 1]} -> ${samples[i]}",
                    samples[i] >= samples[i - 1] - 0.005f,
                )
            }
        }
    }

    /**
     * **P5 · 连续两次捏合（调手势），终值连续**。
     * 第一次捏到 2×，抬手 60ms 内再次捏合（×1.4）：上一次手势移动量早已超出 tap
     * 区域，GD 不得把它当双击——终态应≈2.8 且无回落。
     */
    @Test
    fun regrip_continuesScale() {
        ActivityScenario.launch(PinchProbeActivity::class.java).use { scenario ->
            awaitFocus(scenario)
            sleep(400)
            val samples = listenScale(scenario)
            pinch(a0x = 390f, b0x = 690f, y = 700f, a1x = 240f, b1x = 840f)
            sleep(60)
            pinch(a0x = 390f, b0x = 690f, y = 700f, a1x = 330f, b1x = 750f)
            val t = readTransform(scenario)
            assertTrue("终态 scale=${t[0]} 应≈2.8（2.0 × 1.4）", abs(t[0] - 2.8f) < 0.15f)
            for (i in 1 until samples.size) {
                assertTrue(
                    "scale 轨迹在 i=$i 处回落：${samples[i - 1]} -> ${samples[i]}",
                    samples[i] >= samples[i - 1] - 0.005f,
                )
            }
        }
    }
}
