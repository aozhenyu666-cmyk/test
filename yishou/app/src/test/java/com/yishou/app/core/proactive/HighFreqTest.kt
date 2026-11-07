package com.yishou.app.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 高频会话：看的节拍和说的节拍分开，说的仍交给唯一调度器。 */
class HighFreqTest {

    private val cfg = HighFreqConfig(enabled = true, lookEverySec = 30, minGapSec = 90, sessionMinutes = 30)
    private val t0 = 1_800_000_000_000L

    @Test fun `session must be started explicitly`() {
        assertFalse(HighFreq.running(cfg, HighFreqSession(), t0))
        val s = HighFreq.start(cfg, t0)
        assertTrue(HighFreq.running(cfg, s, t0))
        assertFalse(HighFreq.running(cfg.copy(enabled = false), s, t0))
    }

    @Test fun `looks every 30 seconds`() {
        val s = HighFreq.start(cfg, t0)
        assertTrue("第一拍立刻看", HighFreq.dueLook(cfg, s, t0))
        val looked = HighFreq.onLook(s, t0)
        assertFalse("29 秒还没到", HighFreq.dueLook(cfg, looked, t0 + 29_000))
        assertTrue("30 秒到了", HighFreq.dueLook(cfg, looked, t0 + 30_000))
    }

    @Test fun `session ends at its deadline`() {
        val s = HighFreq.start(cfg, t0)
        val end = t0 + 30 * 60_000L
        assertTrue(HighFreq.running(cfg, s, end - 1))
        assertFalse(HighFreq.running(cfg, s, end))
        assertFalse(HighFreq.dueLook(cfg, s, end))
        assertEquals(0, s.remainingMs(end))
    }

    @Test fun `speaking interval becomes seconds not minutes`() {
        val p = HighFreq.toProactive(cfg, ProactiveConfig())
        assertEquals(90, p.minGapSeconds)
        assertEquals(40, p.maxPerHour) // 3600 / 90
        assertEquals(1, p.presenceMinutes) // 会话里不用再等满平时的 5 分钟
        assertEquals(44, p.maxPerDay) // 日常 24 + 这一场预算 20
        // 90 秒内不开口，90 秒后才可以
        val base = ProactiveState(lastAskAt = t0, promptsThisHour = 0)
        val ctx = ProactiveContext(
            now = t0 + 89_000, hour = 14, foregroundPkg = "com.tencent.mm",
            watched = true, foregroundMinutes = 10, taskTitle = "行测", stuck = "",
        )
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(p, base, ctx, 0))
        val later = ctx.copy(now = t0 + 90_000)
        assertTrue(ProactiveEngine.decide(p, base, later, 0) is ProactiveDecision.Ask)
    }

    @Test fun `session budget raises the daily cap instead of eating it`() {
        assertEquals(20, HighFreq.sessionBudget(cfg)) // 30 分钟 / 90 秒
        val p = HighFreq.toProactive(cfg, ProactiveConfig())
        val ctx = ProactiveContext(
            now = t0, hour = 14, foregroundPkg = "com.tencent.mm",
            watched = true, foregroundMinutes = 10, taskTitle = "行测", stuck = "",
        )
        // 平时 24 次当天就用光了；会话里 30 次还可以开口
        val used = ProactiveState(promptsToday = 30)
        assertTrue(ProactiveEngine.decide(p, used, ctx, 0) is ProactiveDecision.Ask)
        // 会话外的同一份计数会被日常上限挡住
        assertEquals(ProactiveDecision.Silent, ProactiveEngine.decide(ProactiveConfig(), used, ctx, 0))
    }

    @Test fun `bad numbers are clamped`() {
        val c = HighFreqConfig(enabled = true, lookEverySec = 5, minGapSec = 10, sessionMinutes = 1).sane()
        assertEquals(10, c.lookEverySec)
        assertEquals(30, c.minGapSec)
        assertEquals(5, c.sessionMinutes)
    }

    @Test fun `screen text change decides whether to look at pixels`() {
        assertFalse(ScreenDelta.changed("第一题 3+2=?", "第一题 3+2=?"))
        assertTrue(ScreenDelta.changed("第一题 3+2=?", "第二题 7×8=?"))
        assertTrue("从有字变成读不到：算一次变化", ScreenDelta.changed("第一题", "  "))
        assertTrue(ScreenDelta.changed(null, "有字"))
        // 游戏、视频本来就读不到字：不能每拍都算变化，否则每 30 秒都要识图一次
        assertFalse(ScreenDelta.changed("", "  "))
        assertFalse(ScreenDelta.changed(null, ""))
    }
}
