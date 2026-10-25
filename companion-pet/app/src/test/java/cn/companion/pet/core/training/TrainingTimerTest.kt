package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingTimerTest {

    @Test
    fun firesAtFirstThreshold() {
        val t = TrainingTimer(firstSilenceMillis = 25_000)
        t.arm(1_000)
        assertFalse(t.shouldNudge(25_999))
        assertTrue(t.shouldNudge(26_000))
    }

    @Test
    fun readingDoesNotTriggerEarly() {
        val t = TrainingTimer(firstSilenceMillis = 25_000, readMillisPerChar = 12, maxReadBonusMillis = 20_000)
        t.arm(0, stemLength = 100)
        // 100 字 → 1200ms 读题加成，25s + 1.2s
        assertFalse(t.shouldNudge(26_000))
        assertTrue(t.shouldNudge(26_200))
    }

    @Test
    fun readBonusIsCapped() {
        val t = TrainingTimer(firstSilenceMillis = 1_000, readMillisPerChar = 100, maxReadBonusMillis = 2_000)
        t.arm(0, stemLength = 10_000)
        assertEquals(3_000, t.threshold())
    }

    @Test
    fun recordingPausesTimer() {
        val t = TrainingTimer(firstSilenceMillis = 25_000)
        t.arm(0)
        t.pause(5_000)
        // 暂停 60 秒，不应该累积成超时
        assertFalse(t.shouldNudge(65_000))
        t.resume(65_000)
        // 恢复后有效等待 = 5s + 之后的时间
        assertFalse(t.shouldNudge(84_999))
        assertTrue(t.shouldNudge(85_000))
    }

    @Test
    fun pausedStateNeverNudges() {
        val t = TrainingTimer(firstSilenceMillis = 1_000)
        t.arm(0)
        t.pause(500)
        assertFalse(t.shouldNudge(100_000))
    }

    @Test
    fun scaffoldResetsToShorterThreshold() {
        val t = TrainingTimer(firstSilenceMillis = 25_000, afterScaffoldMillis = 20_000)
        t.arm(0)
        t.afterScaffold(30_000)
        assertEquals(TrainingTimer.Stage.WAITING_AFTER_SCAFFOLD, t.currentStage)
        assertFalse(t.shouldNudge(49_999))
        assertTrue(t.shouldNudge(50_000))
    }

    @Test
    fun recoveryStopsNudging() {
        val t = TrainingTimer(firstSilenceMillis = 1_000)
        t.arm(0)
        t.toRecovery()
        assertFalse(t.shouldNudge(100_000))
        assertTrue(t.isRecovery())
    }

    @Test
    fun disarmResets() {
        val t = TrainingTimer(firstSilenceMillis = 1_000)
        t.arm(0)
        t.disarm()
        assertEquals(0, t.waited(50_000))
        assertFalse(t.shouldNudge(50_000))
    }
}