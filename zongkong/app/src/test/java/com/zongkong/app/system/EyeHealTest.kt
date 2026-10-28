package com.zongkong.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 眼睛可靠性这一块的纯判断：该修还是该提醒、修完算不算成、现在算什么模式。
 *
 * 这些判断错了会直接骗到用户（“它一直在修”其实没修、“只读”被说成“完整”），
 * 所以全部抽成不碰 Android 的函数放在这里测。
 */
class EyeHealTest {
    private val me = "com.zongkong.app/com.zongkong.app.guard.GuardService"

    // ---------- SelfHeal.step：修还是提醒 ----------

    @Test
    fun runningNeedsNothing() {
        assertEquals(SelfHeal.Step.NONE, SelfHeal.step(running = true, enabled = true, canWrite = true, heals = 0, now = 1_000_000, lastHeal = 0))
    }

    @Test
    fun staleWithPermissionAndBudgetHeals() {
        assertEquals(
            SelfHeal.Step.HEAL,
            SelfHeal.step(running = false, enabled = true, canWrite = true, heals = 0, now = 1_000_000, lastHeal = 0),
        )
    }

    @Test
    fun staleWithoutPermissionOnlyWarns() {
        // 没授权“写安全设置”：开关不了，只能提醒——不能假装在修
        assertEquals(
            SelfHeal.Step.WARN,
            SelfHeal.step(running = false, enabled = true, canWrite = false, heals = 0, now = 1_000_000, lastHeal = 0),
        )
    }

    @Test
    fun disabledIsNeverAutoEnabled() {
        // 用户在设置里亲手关掉的，不替你打开
        assertEquals(
            SelfHeal.Step.WARN,
            SelfHeal.step(running = false, enabled = false, canWrite = true, heals = 0, now = 1_000_000, lastHeal = 0),
        )
    }

    @Test
    fun stopsHealingAfterThreeAttempts() {
        assertEquals(
            SelfHeal.Step.HEAL,
            SelfHeal.step(false, true, true, heals = SelfHeal.MAX_HEALS - 1, now = 10_000_000, lastHeal = 0),
        )
        assertEquals(
            SelfHeal.Step.WARN,
            SelfHeal.step(false, true, true, heals = SelfHeal.MAX_HEALS, now = 10_000_000, lastHeal = 0),
        )
    }

    @Test
    fun waitsBetweenHeals() {
        val last = 10_000_000L
        assertEquals(SelfHeal.Step.WARN, SelfHeal.step(false, true, true, 1, last + 60_000, last))
        assertEquals(SelfHeal.Step.HEAL, SelfHeal.step(false, true, true, 1, last + SelfHeal.HEAL_GAP_MS, last))
    }

    // ---------- SelfHeal.enabledList / othersOf：别动别人的无障碍设置 ----------

    @Test
    fun listKeepsOthersInOrderAndMeOnlyOnce() {
        val current = "a.A/com.a.S1:b.B/.S2:$me:$me"
        assertEquals("a.A/com.a.S1:b.B/.S2:$me", SelfHeal.enabledList(current, me))
    }

    @Test
    fun listOfEmptyCurrentIsJustMe() {
        assertEquals(me, SelfHeal.enabledList("", me))
        assertEquals(listOf(me), SelfHeal.othersOf("", me) + me)
    }

    @Test
    fun othersDropsBlanksAndDuplicates() {
        assertEquals(listOf("a.A/x.S"), SelfHeal.othersOf("::a.A/x.S::a.A/x.S:", me))
    }

    // ---------- SelfHeal.inList / normalize：系统认了没有 ----------

    @Test
    fun verificationMatchesShortAndFullClassName() {
        val reported = listOf("com.zongkong.app" to "com.zongkong.app.guard.GuardService")
        assertTrue(SelfHeal.inList(reported, "com.zongkong.app", "com.zongkong.app.guard.GuardService"))
        // 系统有的机型回报简写（.guard.GuardService），不能因为写法不同就判成“没挂上”
        assertTrue(SelfHeal.inList(reported, "com.zongkong.app", ".guard.GuardService"))
    }

    @Test
    fun verificationRejectsOtherPackagesAndMissing() {
        assertFalse(SelfHeal.inList(emptyList(), "com.zongkong.app", ".guard.GuardService"))
        assertFalse(SelfHeal.inList(listOf("com.other" to "com.other.S"), "com.zongkong.app", ".guard.GuardService"))
    }

    @Test
    fun normalizeFillsShortName() {
        assertEquals("com.zongkong.app/com.zongkong.app.guard.GuardService", SelfHeal.normalize("com.zongkong.app/.guard.GuardService"))
        assertEquals(me, SelfHeal.normalize(me))
        assertEquals("", SelfHeal.normalize(""))
    }

    // ---------- Eye：现在算什么模式 ----------

    @Test
    fun modePrefersRunningOverEverything() {
        assertEquals(Eye.Mode.FULL, Eye.mode(running = true, usageGranted = false))
        assertEquals(Eye.Mode.FULL, Eye.mode(running = true, usageGranted = true))
    }

    @Test
    fun modeFallsBackToReadOnlyWithUsageAccess() {
        assertEquals(Eye.Mode.READONLY, Eye.mode(running = false, usageGranted = true))
        assertEquals(Eye.Mode.NONE, Eye.mode(running = false, usageGranted = false))
    }

    @Test
    fun onlyFullModeCanBlock() {
        assertTrue(Eye.canBlock(Eye.Mode.FULL))
        assertFalse(Eye.canBlock(Eye.Mode.READONLY))
        assertFalse(Eye.canBlock(Eye.Mode.NONE))
    }

    @Test
    fun readOnlyWordingDoesNotPretendItBlocks() {
        val text = Eye.detail(Eye.Mode.READONLY)
        assertTrue(text.contains("拦不住"))
        assertTrue(Eye.forAi(Eye.Mode.READONLY).contains("拦不住"))
        // 空白就得说空白，不能让模型自由发挥
        assertTrue(Eye.forAi(Eye.Mode.NONE).contains("空白"))
    }

    @Test
    fun systemRecordOnlyWinsWhenAccessibilityIsDown() {
        // 无障碍在跑就用总控自己的记录（更细）；掉了才让系统记录顶上
        assertFalse(Eye.systemRecordFirst(running = true, usageGranted = true))
        assertTrue(Eye.systemRecordFirst(running = false, usageGranted = true))
        assertFalse(Eye.systemRecordFirst(running = false, usageGranted = false))
    }

    // ---------- 战绩统计 ----------

    @Test
    fun healStatsCountsOnlyRebindEvents() {
        val stats = healStats(listOf("rebind_ok", "connected", "rebind_fail", "rebind_ok", "stale"))
        assertEquals(2, stats.ok)
        assertEquals(1, stats.fail)
        assertEquals(3, stats.total)
        assertEquals(HealStats(0, 0), healStats(emptyList()))
    }

    // ---------- HealAlarm.decide：闹钟该不该拉起常驻服务 ----------

    @Test
    fun alarmDoesNothingWhilePaused() {
        val now = 5_000_000L
        assertEquals(HealAlarm.Wake.NONE, HealAlarm.decide(paused = true, keepAliveAlive = false, now = now, pausedUntil = now + 3_600_000))
        // 休假时刻已经过了：照常救急
        assertEquals(HealAlarm.Wake.START, HealAlarm.decide(paused = true, keepAliveAlive = false, now = now, pausedUntil = now - 1))
    }

    @Test
    fun alarmSkipsWhenKeepAliveStillAlive() {
        assertEquals(HealAlarm.Wake.NONE, HealAlarm.decide(paused = false, keepAliveAlive = true, now = 1, pausedUntil = 0))
        assertEquals(HealAlarm.Wake.START, HealAlarm.decide(paused = false, keepAliveAlive = false, now = 1, pausedUntil = 0))
    }

    @Test
    fun alarmReschedulesOnlyWhenNothingIsPending() {
        // 已经排着未来的一次：不重排（否则每次常驻服务起来都把它往后推 15 分钟，永远不响）
        assertFalse(HealAlarm.shouldSchedule(nextAt = 10_000_000, now = 9_000_000))
        // 响过了 / 从没排过 / 排的时刻已经过去：都要排
        assertTrue(HealAlarm.shouldSchedule(nextAt = 0L, now = 9_000_000))
        assertTrue(HealAlarm.shouldSchedule(nextAt = 9_000_000, now = 9_000_000))
    }
}
