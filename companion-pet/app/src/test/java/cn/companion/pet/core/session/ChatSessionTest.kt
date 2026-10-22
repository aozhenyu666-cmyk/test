package cn.companion.pet.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    private val gemini = "com.google.android.apps.bard"

    private fun session(mode: SessionMode = SessionMode.TEXT, allowed: Set<String> = setOf("notes")) =
        ChatSession(mode, 0, AssistantApp.GEMINI.sessionPackages(gemini), allowed)

    @Test
    fun stayingInGeminiDoesNothing() {
        val s = session()
        assertEquals(SessionAction.None, s.update(1_000, gemini, true))
        assertEquals(SessionAction.None, s.update(2_000, AssistantApp.GOOGLE_APP, true))
    }

    @Test
    fun leavingCountsDownThenPullsBack() {
        val s = session()
        assertEquals(SessionAction.Countdown(10, false), s.update(0, "wechat", true))
        assertEquals(SessionAction.Countdown(5, false), s.update(5_000, "wechat", true))
        assertEquals(SessionAction.PullBack, s.update(10_000, "wechat", true))
        assertEquals(1, s.pullBacks)
        assertEquals(SessionAction.Back, s.update(11_000, gemini, true))
        assertEquals(ChatSession.State.IN_GEMINI, s.state)
    }

    @Test
    fun returningBeforeDeadlineCancels() {
        val s = session()
        s.update(0, "wechat", true)
        assertEquals(SessionAction.Back, s.update(3_000, gemini, true))
        assertEquals(0, s.pullBacks)
        assertEquals(1, s.leaves)
    }

    @Test
    fun allowedAppGetsLongerGraceAndShrinksWhenMovingOn() {
        val s = session()
        assertEquals(SessionAction.Countdown(90, true), s.update(0, "notes", true))
        assertEquals(SessionAction.Countdown(60, true), s.update(30_000, "notes", true))
        // 从笔记切到微信：只剩普通宽限
        assertEquals(SessionAction.Countdown(10, false), s.update(31_000, "wechat", true))
    }

    @Test
    fun tooManyPullsAsksInstead() {
        val s = session()
        var t = 0L
        repeat(3) {
            s.update(t, "wechat", true)
            assertEquals(SessionAction.PullBack, s.update(t + 10_000, "wechat", true))
            s.update(t + 11_000, gemini, true)
            t += 20_000
        }
        s.update(t, "wechat", true)
        assertEquals(SessionAction.AskEnd, s.update(t + 10_000, "wechat", true))
        assertEquals(ChatSession.State.ASKING, s.state)
        // 没回应 2 分钟：自动结束
        val ended = s.update(t + 10_000 + 120_000, "wechat", true)
        assertTrue(ended is SessionAction.Ended && ended.summary.reason == EndReason.NO_ANSWER)
    }

    @Test
    fun keepGoingResetsPullHistory() {
        val s = session(allowed = emptySet())
        var t = 0L
        repeat(3) { s.update(t, "x", true); s.update(t + 10_000, "x", true); s.update(t + 11_000, gemini, true); t += 20_000 }
        s.update(t, "x", true)
        assertEquals(SessionAction.AskEnd, s.update(t + 10_000, "x", true))
        s.keepGoing(t + 11_000)
        assertEquals(SessionAction.PullBack, s.update(t + 21_000, "x", true))
    }

    @Test
    fun pauseHoldsThenCountsDown() {
        val s = session()
        s.update(0, "wechat", true)
        s.pause(1_000)
        assertEquals(SessionAction.None, s.update(200_000, "wechat", true))
        assertEquals(SessionAction.Countdown(10, false), s.update(301_000, "wechat", true))
    }

    @Test
    fun liveModeNeverPulls() {
        val s = session(SessionMode.LIVE)
        assertEquals(SessionAction.None, s.update(0, "wechat", true))
        assertEquals(SessionAction.None, s.update(60_000, "wechat", true))
        assertEquals(0, s.pullBacks)
    }

    @Test
    fun screenOffTooLongEnds() {
        val s = session()
        assertEquals(SessionAction.None, s.update(0, gemini, false))
        val r = s.update(15 * 60_000L, gemini, false)
        assertTrue(r is SessionAction.Ended && r.summary.reason == EndReason.SCREEN_OFF)
    }

    @Test
    fun afterScreenOnFirstLeaveGetsLongGrace() {
        val s = session()
        s.update(0, gemini, false)
        assertEquals(SessionAction.Countdown(90, false), s.update(60_000, "launcher", true))
    }

    @Test
    fun awayTooLongEndsAndSummaryCountsAway() {
        val s = session(SessionMode.LIVE)
        s.update(0, "browser", true)
        val r = s.update(30 * 60_000L, "browser", true)
        assertTrue(r is SessionAction.Ended)
        r as SessionAction.Ended
        assertEquals(EndReason.AWAY_TOO_LONG, r.summary.reason)
        assertEquals(30 * 60_000L, r.summary.awayMillis)
    }

    @Test
    fun userEndSummary() {
        val s = session()
        s.update(0, "wechat", true)
        s.update(4_000, gemini, true)
        val r = s.end(60_000)
        assertEquals(60_000L, r.summary.durationMillis)
        assertEquals(4_000L, r.summary.awayMillis)
        assertEquals(1, r.summary.leaves)
        assertEquals(SessionAction.None, s.update(61_000, "wechat", true))
    }
}
