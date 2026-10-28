package cn.companion.pet.core.partner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户要求：一分钟一次；不回也不停；只在真不能说时闭嘴。 */
class PulseCadenceTest {
    private val min = 60_000L
    private fun on(now: Long) = PulseSignals(now, screenOn = true)

    @Test fun speaksEveryMinuteWhenHeReplies() {
        val p = PulseCadence(60_000)
        var spoken = 0; var now = 0L
        while (now < 60 * min) {
            if (p.decide(on(now)) is PulseDecision.Speak) { p.onShown(now); p.onUserReplied(); spoken++ }
            now += 1_000
        }
        assertEquals(60, spoken)
    }

    @Test fun keepsTalkingWhenIgnoredButStretchesUpToThreeTimes() {
        val p = PulseCadence(60_000)
        var spoken = 0; var now = 0L
        while (now < 60 * min) {
            if (p.decide(on(now)) is PulseDecision.Speak) { p.onShown(now); spoken++ }
            now += 1_000
        }
        assertTrue("still talks: $spoken", spoken >= 20) // 一小时完全不理，仍至少 20 次
        assertTrue("slower: $spoken", spoken < 60)
        assertTrue(p.currentInterval() <= 3 * 60_000)
    }

    @Test fun replyRestoresCadence() {
        val p = PulseCadence(60_000)
        var now = 0L
        repeat(12) { p.decide(on(now)); p.onShown(now); now += p.currentInterval() }
        assertTrue(p.currentInterval() > 60_000)
        p.onUserReplied()
        assertEquals(60_000, p.currentInterval())
    }

    @Test fun firstLineComesImmediately() {
        assertTrue(PulseCadence().decide(on(0)) is PulseDecision.Speak)
    }

    @Test fun hardSilence() {
        val p = PulseCadence()
        fun silent(s: PulseSignals) = assertTrue(s.toString(), p.decide(s) is PulseDecision.Wait)
        silent(on(0).copy(typing = true))
        silent(on(0).copy(inCall = true))
        silent(on(0).copy(capturingVoice = true))
        silent(on(0).copy(sleepHours = true))
        silent(on(0).copy(quietUntil = 10 * min))
        silent(on(0).copy(paused = true))
        silent(on(0).copy(busyReason = "训练题"))
        silent(PulseSignals(0, screenOn = false))
    }

    @Test fun failedDisplayRetriesAfterShortPauseNotEverySecond() {
        val p = PulseCadence()
        assertTrue(p.decide(on(0)) is PulseDecision.Speak)
        p.onFailed("悬浮窗被拒")
        assertTrue(p.decide(on(5_000)) is PulseDecision.Wait)
        assertTrue(p.decide(on(15_000)) is PulseDecision.Speak)
    }

    @Test fun intervalIsConfigurableWithinBounds() {
        val p = PulseCadence()
        p.configure(5); assertEquals(30_000, p.intervalMillis)
        p.configure(120); assertEquals(120_000, p.intervalMillis)
        p.configure(9999); assertEquals(600_000, p.intervalMillis)
    }

    @Test fun reasonIsVisible() {
        val p = PulseCadence()
        p.decide(on(0).copy(typing = true))
        assertEquals("你在打字", p.lastReason)
    }
}

class PulseLinesTest {
    @Test fun prefersRealAnchorsAndQuotesThemExactly() {
        val line = PulseLines.compose(PulseContext(screenQuote = "自律的本质是提前做好选择"), emptySet(), 0)
        assertTrue(line.contains("自律的本质是提前做好选择"))
    }

    @Test fun userWordsComeFirst() {
        val line = PulseLines.compose(PulseContext(screenQuote = "屏幕上的话", lastUserSaid = "我今天不想学习"), emptySet(), 0)
        assertTrue(line.contains("我今天不想学习"))
    }

    @Test fun doesNotRepeatRecentLines() {
        val c = PulseContext(screenQuote = "自律的本质是提前做好选择")
        val seen = mutableSetOf<String>()
        repeat(4) { seen += PulseLines.compose(c, seen, 0) }
        assertEquals(4, seen.size)
    }

    @Test fun fallsBackToSmallTalkWithoutAnchors() {
        val a = PulseLines.compose(PulseContext(), emptySet(), 0)
        val b = PulseLines.compose(PulseContext(), setOf(a), 0)
        assertTrue(a.isNotBlank())
        assertNotEquals(a, b)
        assertFalse(a.contains("%s"))
    }
}
