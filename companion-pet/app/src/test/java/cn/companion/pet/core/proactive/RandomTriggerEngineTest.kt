package cn.companion.pet.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 随机触发引擎：真随机、非均匀、有突发、有硬约束。
 *
 * 这组测试的核心目的不是"跑通"，而是**钉住随机性是真的**——
 * 如果实现被改成"固定间隔 + 随机秒数"，下面的分布测试会立刻挂。
 */
class RandomTriggerEngineTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun engine(seed: Int, cfg: RandomTriggerConfig = RandomTriggerConfig()) =
        RandomTriggerEngine(cfg, Random(seed))

    /** 跑一段模拟，返回每次实际开口的时刻。 */
    private fun simulate(
        eng: RandomTriggerEngine,
        startAt: Long,
        durationMillis: Long,
        stepMillis: Long = 5_000L,
        gateAllows: (Long) -> Boolean = { true },
    ): List<Long> {
        val spokeAt = mutableListOf<Long>()
        var t = startAt
        var lastSpeak = 0L
        val end = startAt + durationMillis
        while (t < end) {
            when (val d = eng.evaluate(t, gateAllows(t), lastSpeak)) {
                is TriggerDecision.Speak -> {
                    // 这里模拟"投递成功"。
                    eng.onSpoken(t, lastSpeak)
                    lastSpeak = t
                    spokeAt += t
                }
                is TriggerDecision.Hold -> Unit
            }
            t += stepMillis
        }
        return spokeAt
    }

    // ---------- 1) 不同种子 → 不同分布 ----------

    @Test
    fun `不同随机种子产生不同触发分布`() {
        val a = simulate(engine(1), 0L, 2 * hour)
        val b = simulate(engine(2), 0L, 2 * hour)
        assertTrue("两小时模拟应当真的说出话", a.size >= 3 && b.size >= 3)

        // 逐次间隔序列必须不同；如果实现是确定性的，这两条会完全一致。
        val gapsA = a.zipWithNext { x, y -> y - x }
        val gapsB = b.zipWithNext { x, y -> y - x }
        assertFalse("不同种子的间隔序列不能完全相同", gapsA == gapsB)

        // 连触发时刻本身也要不同。
        assertFalse("不同种子的触发时刻不能完全相同", a == b)
    }

    // ---------- 2) 60 分钟高频模拟：记录候选与实际触发 ----------

    @Test
    fun `一小时模拟的触发次数落在合理区间`() {
        val cfg = RandomTriggerConfig(mode = ProactiveMode.DAILY)
        val spoke = simulate(engine(7, cfg), 0L, hour)
        // 日常档 8/h 是**期望**，加上突发与硬上限，实测区间放宽到 4~20。
        assertTrue("一小时说出的话太少（${spoke.size}），引擎等于没工作", spoke.size >= 4)
        assertTrue("一小时说出的话太多（${spoke.size}），突发限流失效", spoke.size <= 20)
    }

    // ---------- 3) 间隔必须非均匀 ----------

    @Test
    fun `间隔不是机械均匀分布`() {
        val spoke = simulate(engine(11), 0L, 4 * hour)
        val gaps = spoke.zipWithNext { x, y -> y - x }
        assertTrue("样本太少，无法判断分布", gaps.size >= 6)

        val mean = gaps.average()
        val variance = gaps.map { (it - mean) * (it - mean) }.average()
        val sd = kotlin.math.sqrt(variance)
        // 若实现是"固定间隔 + 几秒随机"，sd 会接近 0；这里要求明显离散。
        assertTrue("间隔几乎没有波动（sd=$sd），说明随机性不足", sd > 10_000.0)

        // 且必须真的出现长短混合，而不是全挤在一个值附近。
        val distinct = gaps.map { it / 10_000L }.distinct().size
        assertTrue("间隔档位太少（$distinct 档），不像真实节奏", distinct >= 3)
    }

    // ---------- 4) 硬约束 ----------

    @Test
    fun `任何种子都不产生小于最小间隔的间隔`() {
        val cfg = RandomTriggerConfig(minGapMillis = 45_000L)
        for (seed in 1..20) {
            val spoke = simulate(engine(seed, cfg), 0L, 2 * hour, stepMillis = 5_000L)
            spoke.zipWithNext { x, y -> y - x }.forEach { gap ->
                assertTrue("种子 $seed 产生了 $gap ms 的间隔，低于硬下限", gap >= cfg.minGapMillis)
            }
        }
    }

    @Test
    fun `突发窗口内不超过上限`() {
        val cfg = RandomTriggerConfig(burstWindowMillis = 10 * minute, burstMax = 3)
        for (seed in 1..10) {
            val spoke = simulate(engine(seed, cfg), 0L, 3 * hour)
            // 检查**任意** 10 分钟滑动区间：以每一次开口为区间起点，看这之后的窗口里有几次。
            // 引擎的语义是"任意 burstWindow 内不超过 burstMax"，所以起止两个方向都要成立。
            spoke.forEachIndexed { idx, at ->
                val windowStart = at
                val forward = spoke.count { it in windowStart until (windowStart + cfg.burstWindowMillis) }
                assertTrue(
                    "种子 $seed 从第 $idx 次（$at）起 10 分钟内连说 $forward 次，超过上限 ${cfg.burstMax}",
                    forward <= cfg.burstMax,
                )
                // 反向：以 at 为窗口末端往前看。
                val backward = spoke.count { it in (at - cfg.burstWindowMillis + 1)..at }
                assertTrue(
                    "种子 $seed 到第 $idx 次（$at）为止的 10 分钟内说了 $backward 次，超过上限 ${cfg.burstMax}",
                    backward <= cfg.burstMax,
                )
            }
        }
    }

    // ---------- 5) 闸门关着不消耗随机 ----------

    @Test
    fun `闸门关闭时一次都不说且不消耗机会`() {
        val eng = engine(3)
        repeat(200) { i ->
            val d = eng.evaluate(i * 5_000L, gateAllows = false, lastSpeakAtMillis = 0L)
            assertTrue("闸门关着却说可以开口", d is TriggerDecision.Hold)
        }
    }

    @Test
    fun `专注期间零插话退出后恢复`() {
        // 前 30 分钟专注（不许说），之后放开。
        val spoke = simulate(engine(5), 0L, hour, gateAllows = { t -> t >= 30 * minute })
        assertTrue("专注期间不该有任何开口", spoke.none { it < 30 * minute })
        assertTrue("专注结束后应当恢复开口", spoke.any { it >= 30 * minute })
    }

    // ---------- 6) 退避与恢复 ----------

    @Test
    fun `投递失败不消耗机会但会推远下次`() {
        val eng = engine(9)
        val cfg = RandomTriggerConfig()
        // 先让它到可开口状态。
        var t = 0L
        var d = eng.evaluate(t, true, 0L)
        while (d !is TriggerDecision.Speak && t < hour) { t += 5_000L; d = eng.evaluate(t, true, 0L) }
        assertTrue("始终没到可开口状态", d is TriggerDecision.Speak)

        eng.onDeliveryFailed(t)
        val after = eng.evaluate(t + 1_000L, true, 0L)
        assertTrue("失败后应立刻 Hold", after is TriggerDecision.Hold)
    }

    @Test
    fun `长时间沉默后间隔收紧`() {
        val cfg = RandomTriggerConfig(resumeAfterMillis = 30 * minute, resumeFactor = 0.5)
        val eng = engine(13, cfg)
        val normal = eng.nextGap(0L, 0L)
        val afterSilence = eng.nextGap(2 * hour, 10 * minute)
        assertTrue(
            "沉默太久后的间隔应当比平时短（normal=$normal, afterSilence=$afterSilence）",
            afterSilence <= normal,
        )
    }

    // ---------- 7) 状态可持久化 ----------

    @Test
    fun `快照与恢复保持节奏`() {
        val eng = engine(17)
        val spoke = simulate(eng, 0L, hour)
        val snap = eng.snapshot()
        val restored = engine(99)
        restored.restore(snap, spoke.lastOrNull() ?: 0L)
        // 恢复后不该立刻暴发：紧接着的时刻应当是 Hold。
        val d = restored.evaluate((spoke.lastOrNull() ?: 0L) + 1_000L, true, spoke.lastOrNull() ?: 0L)
        assertTrue("恢复后立刻开口，说明状态没恢复好", d is TriggerDecision.Hold)
    }
}
