package io.github.ccch1mneyyy.dsh_app.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T5 local rules: quiet hours that may cross midnight, and the per-day trigger cap.
 * These are the focusmate SchedulePolicy ideas that need no new permission, ported into the pure
 * decision function so the Android side only supplies the time facts.
 */
class TriggerRulesTest {
    private val on = LoopConfig(enabled = true, targetPackage = "com.example.test", cooldownMs = 0)

    private fun decide(config: LoopConfig, minute: Int, day: String? = "2026-10-31", spent: Int = 0, now: Long = 1_700_000_000_000L) =
        TriggerRules.decide(now, config, Environment(minuteOfDay = minute, dayKey = day), 0L, false, spent)

    @Test fun quietHoursThatCrossMidnightCoverBothSides() {
        val quiet = on.copy(quietStartMinute = 23 * 60, quietEndMinute = 8 * 60) // 23:00 → 08:00
        // Late side of the window.
        assertEquals(TriggerRules.Decision.Suppress("quiet_hours"), decide(quiet, 23 * 60))
        assertEquals(TriggerRules.Decision.Suppress("quiet_hours"), decide(quiet, 23 * 60 + 59))
        assertEquals(TriggerRules.Decision.Suppress("quiet_hours"), decide(quiet, 0))
        assertEquals(TriggerRules.Decision.Suppress("quiet_hours"), decide(quiet, 7 * 60 + 59))
        // Outside it, on both sides of midnight.
        assertEquals(TriggerRules.Decision.Trigger, decide(quiet, 8 * 60))
        assertEquals(TriggerRules.Decision.Trigger, decide(quiet, 12 * 60))
        assertEquals(TriggerRules.Decision.Trigger, decide(quiet, 22 * 60 + 59))
    }

    @Test fun quietHoursInsideOneDay() {
        val quiet = on.copy(quietStartMinute = 12 * 60, quietEndMinute = 14 * 60)
        assertEquals(TriggerRules.Decision.Suppress("quiet_hours"), decide(quiet, 12 * 60))
        assertEquals(TriggerRules.Decision.Suppress("quiet_hours"), decide(quiet, 13 * 60 + 59))
        assertEquals(TriggerRules.Decision.Trigger, decide(quiet, 14 * 60))
        assertEquals(TriggerRules.Decision.Trigger, decide(quiet, 11 * 60 + 59))
    }

    @Test fun unknownTimeNeverSuppresses() {
        val quiet = on.copy(quietStartMinute = 0, quietEndMinute = 1439)
        // -1 = the caller does not know the local time; silence must not be guessed.
        assertEquals(TriggerRules.Decision.Trigger, decide(quiet, -1))
        assertEquals(TriggerRules.Decision.Trigger, decide(quiet, 5000))
    }

    @Test fun aWindowThatStartsAndEndsTogetherIsOff() {
        val same = on.copy(quietStartMinute = 60, quietEndMinute = 60)
        assertFalse(same.quietHoursSet)
        assertEquals(TriggerRules.Decision.Trigger, decide(same, 60))
        assertEquals(TriggerRules.Decision.Trigger, decide(on, 60)) // no window at all
    }

    @Test fun dailyCapCountsAndResetsOnTheNextDay() {
        val capped = on.copy(dailyCap = 2)
        assertEquals(TriggerRules.Decision.Trigger, decide(capped, 600, spent = 0))
        assertEquals(TriggerRules.Decision.Trigger, decide(capped, 600, spent = 1))
        assertEquals(TriggerRules.Decision.Suppress("daily_cap"), decide(capped, 600, spent = 2))
        assertEquals(TriggerRules.Decision.Suppress("daily_cap"), decide(capped, 600, spent = 99))
        // A new day starts from zero (the engine resets the bucket, the rule just sees the count).
        assertEquals(TriggerRules.Decision.Trigger, decide(capped, 600, day = "2026-11-01", spent = 0))
        // 0 = unlimited.
        assertEquals(TriggerRules.Decision.Trigger, decide(on.copy(dailyCap = 0), 600, spent = 500))
    }

    @Test fun rulesKeepTheirOrder() {
        val capped = on.copy(dailyCap = 1, cooldownMs = 60_000, quietStartMinute = 0, quietEndMinute = 60)
        // Each gate that fails first names itself.
        assertEquals(TriggerRules.Decision.Suppress("disabled"), decide(capped.copy(enabled = false), 30, spent = 5))
        assertEquals(TriggerRules.Decision.Suppress("quiet_hours"), decide(capped, 30, spent = 5))
        assertEquals(TriggerRules.Decision.Suppress("daily_cap"), decide(capped, 600, spent = 5))
        // Cooldown comes after the cap: with no cap left, the cooldown is what stops it.
        val now = 1_700_000_000_000L
        assertEquals(
            TriggerRules.Decision.Suppress("cooldown"),
            TriggerRules.decide(now, on.copy(cooldownMs = 60_000), Environment(minuteOfDay = 600), now - 1_000, false, 0),
        )
    }

    @Test fun configRoundTripsTheNewFields() {
        val c = LoopConfig(
            enabled = true, targetPackage = " p ", cooldownMs = 1_000, timeoutMs = 9_000,
            quietStartMinute = 1380, quietEndMinute = 480, dailyCap = 3,
        ).normalized()
        assertEquals("p", c.targetPackage)
        assertEquals(c, LoopConfig.fromMap(c.toMap()))
        // Version-1 files simply have no new keys.
        val old = LoopConfig.fromMap(mapOf("enabled" to true, "targetPackage" to "x", "cooldownMs" to 5, "timeoutMs" to 6))
        assertNull(old.quietStartMinute)
        assertNull(old.quietEndMinute)
        assertEquals(0, old.dailyCap)
        // Out-of-range values are clamped, not accepted.
        val bad = LoopConfig(quietStartMinute = 5000, quietEndMinute = -3, dailyCap = 9999).normalized()
        assertNull(bad.quietStartMinute)
        assertNull(bad.quietEndMinute)
        assertEquals(200, bad.dailyCap)
    }
}
