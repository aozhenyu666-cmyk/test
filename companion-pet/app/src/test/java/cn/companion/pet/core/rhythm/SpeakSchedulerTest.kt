package cn.companion.pet.core.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random

class SpeakSchedulerTest {
    private val zone = ZoneOffset.UTC
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 22, h, m).toInstant(zone).toEpochMilli()
    private fun scheduler(seed: Int = 1) = SpeakScheduler(RhythmConfig(), zone, Random(seed))

    @Test
    fun firstSpeakWithinOneToTwoMinutesAfterScreenOn() {
        repeat(50) { seed ->
            val s = scheduler(seed)
            val t0 = at(10)
            s.onScreenOn(t0)
            val due = s.dueAt(false)!! - t0
            assertTrue(due in 60_000L..120_000L)
        }
    }

    @Test
    fun intervalBetweenSixAndTwelveMinutes() {
        repeat(50) { seed ->
            val s = scheduler(seed)
            val t0 = at(10)
            s.onSpoke(t0)
            val due = s.dueAt(false)!! - t0
            assertTrue(due in 6 * 60_000L..12 * 60_000L)
        }
    }

    @Test
    fun focusDoublesInterval() {
        val s = scheduler()
        val t0 = at(10)
        s.onSpoke(t0)
        val normal = s.dueAt(false)!! - t0
        val focus = s.dueAt(true)!! - t0
        assertEquals(normal * 2, focus)
    }

    @Test
    fun ignoredMakesNextHalf() {
        repeat(30) { seed ->
            val s = scheduler(seed)
            s.onIgnoredFully(at(10))
            val due = s.dueAt(false)!! - at(10)
            assertTrue(due in 3 * 60_000L..6 * 60_000L)
        }
    }

    @Test
    fun skipRetriesInOneMinute() {
        val s = scheduler()
        s.retrySoon(at(10))
        assertEquals(at(10) + 60_000L, s.dueAt(true))
    }

    @Test
    fun quietHoursBlockDue() {
        val s = scheduler()
        s.onScreenOn(at(3))
        assertFalse(s.isDue(at(3, 10), false))
        s.onScreenOn(at(7))
        assertTrue(s.isDue(at(7, 5), false))
    }

    @Test
    fun screenOffCancels() {
        val s = scheduler()
        s.onScreenOn(at(10))
        s.onScreenOff()
        assertNull(s.dueAt(false))
        assertFalse(s.isDue(at(11), false))
    }

    @Test
    fun quietWindowAcrossMidnight() {
        assertTrue(SpeakScheduler.inQuietHours(at(23, 30), 23 * 60, 7 * 60, zone))
        assertTrue(SpeakScheduler.inQuietHours(at(2), 23 * 60, 7 * 60, zone))
        assertFalse(SpeakScheduler.inQuietHours(at(12), 23 * 60, 7 * 60, zone))
        assertFalse(SpeakScheduler.inQuietHours(at(12), 0, 0, zone))
    }

    @Test
    fun escalationOrder() {
        assertEquals(EscalationLevel.WAITING, IgnoreEscalation.levelAt(0, 59_999))
        assertEquals(EscalationLevel.ATTENTION, IgnoreEscalation.levelAt(0, 60_000))
        assertEquals(EscalationLevel.NUDGE, IgnoreEscalation.levelAt(0, 120_000))
        assertEquals(EscalationLevel.RETREAT, IgnoreEscalation.levelAt(0, 180_000))
    }
}
