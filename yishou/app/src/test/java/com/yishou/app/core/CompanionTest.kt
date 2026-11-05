package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTest {

    private val cfg = CompanionConfig(enabled = true)

    @Test
    fun `disabled means never ask`() {
        val st = CompanionState(fgSeconds = 999)
        assertEquals(CompanionAction.IDLE, Companion.decide(cfg.copy(enabled = false), st))
    }

    @Test
    fun `asks only after the trigger window`() {
        assertEquals(CompanionAction.IDLE, Companion.decide(cfg, CompanionState(fgSeconds = 44)))
        assertEquals(CompanionAction.ASK, Companion.decide(cfg, CompanionState(fgSeconds = 45)))
    }

    @Test
    fun `respects the gap between prompts`() {
        val st = CompanionState(fgSeconds = 100, sinceLastPromptSeconds = 179)
        assertEquals(CompanionAction.IDLE, Companion.decide(cfg, st))
        assertEquals(CompanionAction.ASK, Companion.decide(cfg, st.copy(sinceLastPromptSeconds = 180)))
    }

    @Test
    fun `respects the half hour cap`() {
        val st = CompanionState(fgSeconds = 100, promptsInHalfHour = 6)
        assertEquals(CompanionAction.IDLE, Companion.decide(cfg, st))
    }

    @Test
    fun `stops for the day after too many misses`() {
        val st = CompanionState(fgSeconds = 100, misses = 3)
        assertEquals(CompanionAction.IDLE, Companion.decide(cfg, st))
    }

    @Test
    fun `never interrupts speech or its own ui or a pending question`() {
        val base = CompanionState(fgSeconds = 100)
        assertEquals(CompanionAction.IDLE, Companion.decide(cfg, base.copy(speaking = true)))
        assertEquals(CompanionAction.IDLE, Companion.decide(cfg, base.copy(ownUiOpen = true)))
        assertEquals(CompanionAction.IDLE, Companion.decide(cfg, base.copy(hasPending = true)))
    }

    @Test
    fun `silence escalates on the configured clock`() {
        assertEquals(SilenceStep.WAIT, Companion.onSilence(cfg, 89))
        assertEquals(SilenceStep.NUDGE, Companion.onSilence(cfg, 90))
        assertEquals(SilenceStep.NUDGE, Companion.onSilence(cfg, 149))
        assertEquals(SilenceStep.CLOSE, Companion.onSilence(cfg, 150))
    }

    @Test
    fun `sane clamps reversed timings`() {
        val c = CompanionConfig(enabled = true, nudgeSeconds = 400, closeSeconds = 100).sane()
        assertTrue(c.closeSeconds > c.nudgeSeconds)
        assertTrue(c.triggerSeconds in 10..300)
    }

    @Test
    fun `fallback question quotes the screen and demands a judgement`() {
        val q = Companion.fallbackQuestion("这个视频说短视频让人注意力下降，但我看数据只有一条")
        assertTrue(q.contains("「"))
        assertTrue(q.contains("同意吗"))
    }

    @Test
    fun `no screen text means no fallback question`() {
        assertEquals("", Companion.fallbackQuestion("   "))
    }
}
