package com.zongkong.core.hq

import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.DayLog
import com.zongkong.core.Engine
import com.zongkong.core.Policy
import com.zongkong.core.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HqTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val cfg = HqConfig()

    @Test
    fun dailyDueLadder() {
        assertEquals(Hq.Due.NONE, Hq.due(cfg, emptyList(), at(14, 21, 10), zone))
        assertEquals(Hq.Due.SOON, Hq.due(cfg, emptyList(), at(14, 21, 25), zone))
        assertEquals(Hq.Due.NOW, Hq.due(cfg, emptyList(), at(14, 21, 32), zone))
        assertEquals(Hq.Due.OVERDUE, Hq.due(cfg, emptyList(), at(14, 21, 40), zone))
        // 过了 3 小时不再催
        assertEquals(Hq.Due.NONE, Hq.due(cfg, emptyList(), at(15, 1, 0), zone))
        // 今天做过了
        val done = Hq.finish(Hq.start("a", HqTrigger.MANUAL, cfg, at(14, 20)), at(14, 20, 40))
        assertEquals(Hq.Due.NONE, Hq.due(cfg, listOf(done), at(14, 21, 40), zone))
        // 下午自己进去试了一次：不算今晚这次，到点照样叫
        val tried = Hq.exitEarly(Hq.start("t", HqTrigger.MANUAL, cfg, at(14, 15)), at(14, 15, 5), "试一下界面")
        assertEquals(Hq.Due.OVERDUE, Hq.due(cfg, listOf(tried), at(14, 21, 40), zone))
        assertFalse(Hq.doneToday(cfg, listOf(tried), at(14, 21, 40), zone))
        // 到点前提前退出的那次算（不然退出后又被拉回去）
        val quit = Hq.exitEarly(Hq.start("q", HqTrigger.SCHEDULED, cfg, at(14, 21, 35)), at(14, 21, 38), "有急事要出门")
        assertEquals(Hq.Due.NONE, Hq.due(cfg, listOf(quit), at(14, 21, 40), zone))
        // 正在做
        assertEquals(Hq.Due.NONE, Hq.due(cfg, listOf(Hq.start("b", HqTrigger.MANUAL, cfg, at(14, 21, 31))), at(14, 21, 40), zone))
        // 关掉了
        assertEquals(Hq.Due.NONE, Hq.due(cfg.copy(enabled = false), emptyList(), at(14, 21, 40), zone))
    }

    @Test
    fun stepsNeedTimeAndWork() {
        val t0 = at(14, 21, 30)
        var s = Hq.start("a", HqTrigger.SCHEDULED, cfg, t0)
        assertEquals(HqStep.SETTLE, s.current)
        assertFalse(Hq.canAdvance(s, t0 + 30_000, done = true))
        assertTrue(Hq.canAdvance(s, t0 + 60_000, done = true))
        s = Hq.advance(s, t0 + 60_000)
        assertEquals(HqStep.INFO, s.current)
        assertFalse(Hq.canAdvance(s, t0 + 60_000 + 3 * 60_000, done = false))
        assertTrue(Hq.canAdvance(s, t0 + 60_000 + 3 * 60_000, done = true))
        s = Hq.advance(s, t0 + 5 * 60_000, "清了 6 条")
        assertEquals("清了 6 条", s.notes[HqStep.INFO.name])
        repeat(3) { s = Hq.advance(s, t0 + 20 * 60_000) }
        assertEquals(HqStep.SEAL, s.current)
        // 在“封”这一步待满 20 秒就能结束（不用等整段 30 分钟）；也可以回去再修
        assertFalse(Hq.canFinish(s, t0 + 20 * 60_000 + 5_000))
        assertTrue(Hq.canFinish(s, t0 + 20 * 60_000 + 21_000))
        val back = Hq.revisit(s, HqStep.THINK, t0 + 22 * 60_000)
        assertEquals(HqStep.THINK, back.current)
        assertTrue(Hq.canAdvance(back, t0 + 22 * 60_000, done = true))
        assertTrue(Hq.canFinish(s, t0 + 31 * 60_000))
        val f = Hq.finish(s, t0 + 31 * 60_000, "三行总结")
        assertFalse(f.active)
        assertTrue(Hq.report(f).contains("清了 6 条"))
    }

    @Test
    fun sessionMinutesAreClamped() {
        assertEquals(15, Hq.start("a", HqTrigger.MANUAL, cfg, 0, minutes = 5).minutes)
    }

    @Test
    fun lockdownWhitelist() {
        assertTrue(Hq.allows("com.zongkong.app", "com.zongkong.app", cfg))
        assertTrue(Hq.allows("com.yishou.app", "com.zongkong.app", cfg))
        assertTrue(Hq.allows("com.google.android.dialer", "com.zongkong.app", cfg))
        assertFalse(Hq.allows("com.ss.android.ugc.aweme", "com.zongkong.app", cfg))
        assertFalse(Hq.allows("com.android.settings", "com.zongkong.app", cfg))
        assertFalse(Hq.allows("com.google.android.apps.nexuslauncher", "com.zongkong.app", cfg))
    }

    @Test
    fun absenceLadder() {
        val now = at(14, 12)
        assertEquals(Hq.Absence.NONE, Hq.absence(now - 3600_000, now, cfg))
        assertEquals(Hq.Absence.NUDGE, Hq.absence(now - 26 * 3600_000L, now, cfg))
        assertEquals(Hq.Absence.STRICT, Hq.absence(now - 50 * 3600_000L, now, cfg))
        assertEquals(Hq.Absence.FORCE, Hq.absence(now - 10 * 86_400_000L, now, cfg))
        assertEquals(Hq.Absence.NONE, Hq.absence(now - 10 * 86_400_000L, now, cfg.copy(escalation = false)))
        assertEquals(Hq.Absence.NONE, Hq.absence(0, now, cfg))
    }

    @Test
    fun sessionAndAbsenceMakeStrict() {
        val now = at(14, 12)
        val c = Config(gates = emptyList(), silenceHours = 0)
        val day = DayLog(DayClock.dateKey(now, zone))
        assertFalse(Engine.evaluate(c, day, now, zone).strict)
        val s = Engine.evaluate(c, day, now, zone, session = Hq.start("a", HqTrigger.MANUAL, cfg, now))
        assertTrue(s.strict)
        assertTrue(s.reasons.first() is Reason.Session)
        val a = Engine.evaluate(c, day, now, zone, absence = Hq.Absence.STRICT)
        assertTrue(a.strict)
        assertFalse(Engine.evaluate(c, day, now, zone, absence = Hq.Absence.NUDGE).strict)
    }

    @Test
    fun loosenHqWaitsTightenNow() {
        val now = at(14, 12)
        val c = Config()
        val off = Policy.setHq(c, c.hq.copy(enabled = false), now)
        assertTrue(off.config.hq.enabled)
        assertEquals(1, off.config.pending.size)
        val longer = Policy.setHq(c, c.hq.copy(minutes = 45), now)
        assertEquals(45, longer.config.hq.minutes)
        val earlier = Policy.setHq(c, c.hq.copy(at = 21 * 60), now)
        assertEquals(21 * 60, earlier.config.hq.at)
        val moreApps = Policy.setHq(c, c.hq.copy(allowed = c.hq.allowed + "com.tencent.mm"), now)
        assertFalse("com.tencent.mm" in moreApps.config.hq.allowed)
        // 24 小时后生效
        val applied = Policy.applyDue(off.config, now + Policy.DELAY_MILLIS + 1)
        assertFalse(applied.hq.enabled)

        // 一次保存里既提前时间（收紧）又加白名单（放宽）：时间马上生效，白名单排队——以前整份都要等 24 小时，到点就不进
        val both = Policy.setHq(c, c.hq.copy(at = 20 * 60, allowed = c.hq.allowed + "com.tencent.mm"), now)
        assertEquals(20 * 60, both.config.hq.at)
        assertFalse("com.tencent.mm" in both.config.hq.allowed)
        assertEquals(1, both.config.pending.size)
        assertTrue("com.tencent.mm" in Policy.desiredHq(both.config).allowed)
        // 设置页从排队的设置开始改：再改一项收紧的，排队的白名单不会被冲掉
        val again = Policy.setHq(both.config, Policy.desiredHq(both.config).copy(minutes = 45), now)
        assertEquals(45, again.config.hq.minutes)
        assertEquals(20 * 60, again.config.hq.at)
        assertTrue("com.tencent.mm" in Policy.desiredHq(again.config).allowed)
        val later = Policy.applyDue(again.config, now + Policy.DELAY_MILLIS + 1)
        assertTrue("com.tencent.mm" in later.hq.allowed)
        assertEquals(45, later.hq.minutes)
        // 去掉白名单里的应用是收紧：马上生效
        val fewer = Policy.setHq(c, c.hq.copy(allowed = c.hq.allowed - "notion.id"), now)
        assertFalse("notion.id" in fewer.config.hq.allowed)
        assertTrue(fewer.config.pending.isEmpty())
    }

    @Test
    fun quietHoursWrapMidnight() {
        assertTrue(Hq.quiet(cfg, at(15, 1), zone))
        assertTrue(Hq.quiet(cfg, at(14, 23, 45), zone))
        assertFalse(Hq.quiet(cfg, at(14, 21, 30), zone))
    }
}
