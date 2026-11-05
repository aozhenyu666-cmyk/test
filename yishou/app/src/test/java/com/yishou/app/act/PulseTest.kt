package com.yishou.app.act

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseTest {
    private val s0 = PulseState(startedAt = 0)
    private fun x(now: Long, speaking: Boolean = false, lastSpeech: Long = -100_000, lastScreen: Long = now, onTask: Boolean = true, waiting: Boolean = false) =
        PulseSignals(now, speaking, lastSpeech, lastScreen, onTask, waiting)

    @Test fun offTaskIsPulledBackRightAway() = assertEquals(Beat.REFOCUS, Pulse.decide(s0, x(5_000, onTask = false)))

    @Test fun neverInterruptsWhileSpeakingOrJustAfter() {
        assertEquals(Beat.QUIET, Pulse.decide(s0, x(200_000, speaking = true)))
        assertEquals(Beat.QUIET, Pulse.decide(s0, x(200_000, lastSpeech = 195_000)))
    }

    @Test fun asksOnScheduleAndNudgesWhenStill() {
        assertEquals(Beat.QUIET, Pulse.decide(s0, x(30_000)))
        assertEquals(Beat.ASK, Pulse.decide(s0, x(Pulse.BASE_MS)))
        // 屏幕一分钟没动、人也不出声：推一下
        assertEquals(Beat.NUDGE, Pulse.decide(s0, x(70_000, lastScreen = 5_000)))
    }

    @Test fun capsPromptsAndCheckpointsEveryTenMinutes() {
        var s = s0
        repeat(6) { i -> s = Pulse.prompted(s, Beat.ASK, 60_000L * (i + 1)) }
        assertEquals(Beat.QUIET, Pulse.decide(s, x(7 * 60_000L)))
        assertEquals(Beat.CHECKPOINT, Pulse.decide(s0, x(10 * 60_000L)))
    }

    @Test fun intervalStretchesWhenAnsweredAndShrinksWhenMissed() {
        val longer = Pulse.answered(s0, 1)
        assertTrue(longer.intervalMs > Pulse.BASE_MS)
        var s = s0
        repeat(3) { s = Pulse.missed(s) }
        assertTrue(s.intervalMs < Pulse.BASE_MS)
        assertTrue(Pulse.shouldReadStall(s))
        assertEquals(Beat.PAUSED, Pulse.decide(Pulse.pause(s, 99_999), x(50_000, onTask = false)))
    }

    private val screen = "某县城的咖啡店三年倒了一半。有人说：这说明县城的人根本不喝咖啡。"
    private val said = "我觉得是因为根本不喝这个说法太绝对了"

    @Test fun onlySpecificLinesAreSent() {
        assertNotNull(PulsePrompt.accept("「根本不喝」——原文哪一句让你觉得太绝对？", screen, said))
        // 没引用
        assertNull(PulsePrompt.accept("你觉得这个推理哪里有问题？", screen, said))
        // 引了不存在的话
        assertNull(PulsePrompt.accept("「房租上涨」是关键吗？", screen, said))
        // 空话
        assertNull(PulsePrompt.accept("「根本不喝」，加油，继续努力！", screen, said))
        // 太长
        assertNull(PulsePrompt.accept("「根本不喝」" + "这是一句特别特别长的话".repeat(5), screen, said))
    }

    @Test fun fallbackStillQuotesSomethingReal() {
        val line = PulsePrompt.fallback(Beat.ASK, screen, said, "做言语第3套前5题")
        assertTrue(line, line.contains("「"))
        assertNotNull(PulsePrompt.accept(line, screen, said))
        assertTrue(PulsePrompt.fallback(Beat.REFOCUS, screen, said, "做言语第3套前5题").contains("做言语第3套前5题"))
    }
}
