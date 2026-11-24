package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostLiveRelayPolicyTest {
    private val selfPkg = "io.github.ccch1mneyyy.dsh_app"
    private val now = 1_700_500_000_000L

    @Test
    fun blocksShortAccidentalLiveTapsAndAllowsRealSessions() {
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 20L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = false,
                planModalShowing = false,
                emergencyActive = false,
                inNightWindow = false,
                frontPkg = "com.bbk.launcher2",
                selfPkg = selfPkg,
            ),
        )
        assertTrue(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 45L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = false,
                planModalShowing = false,
                emergencyActive = false,
                inNightWindow = false,
                frontPkg = "com.google.android.apps.bard",
                selfPkg = selfPkg,
            ),
        )
    }

    @Test
    fun enforcesCooldownAndRespectsYieldBoundaries() {
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 120L,
                nowMs = now,
                lastPromptAtMs = now - 60_000L,
                interceptHeld = false,
                planModalShowing = false,
                emergencyActive = false,
                inNightWindow = false,
                frontPkg = "com.bbk.launcher2",
                selfPkg = selfPkg,
            ),
        )
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 120L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = true,
                planModalShowing = false,
                emergencyActive = false,
                inNightWindow = false,
                frontPkg = "com.bbk.launcher2",
                selfPkg = selfPkg,
            ),
        )
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 120L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = false,
                planModalShowing = true,
                emergencyActive = false,
                inNightWindow = false,
                frontPkg = "com.bbk.launcher2",
                selfPkg = selfPkg,
            ),
        )
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 120L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = false,
                planModalShowing = false,
                emergencyActive = true,
                inNightWindow = false,
                frontPkg = "com.bbk.launcher2",
                selfPkg = selfPkg,
            ),
        )
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 120L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = false,
                planModalShowing = false,
                emergencyActive = false,
                inNightWindow = true,
                frontPkg = "com.bbk.launcher2",
                selfPkg = selfPkg,
            ),
        )
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 120L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = false,
                planModalShowing = false,
                emergencyActive = false,
                inNightWindow = false,
                frontPkg = "com.fenbi.android.solar",
                selfPkg = selfPkg,
            ),
        )
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 120L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = false,
                planModalShowing = false,
                emergencyActive = false,
                inNightWindow = false,
                frontPkg = "com.custom.study",
                selfPkg = selfPkg,
                workOrQuizPackages = setOf("com.custom.study"),
            ),
        )
        assertFalse(
            PostLiveRelayPolicy.shouldPrompt(
                durationSec = 120L,
                nowMs = now,
                lastPromptAtMs = 0L,
                interceptHeld = false,
                planModalShowing = false,
                emergencyActive = false,
                inNightWindow = false,
                frontPkg = selfPkg,
                selfPkg = selfPkg,
            ),
        )
    }

    @Test
    fun formatsDurationInCleanChinese() {
        assertEquals("50 秒", PostLiveRelayPolicy.formatDuration(50L))
        assertEquals("2 分钟", PostLiveRelayPolicy.formatDuration(120L))
        assertEquals("2 分 15 秒", PostLiveRelayPolicy.formatDuration(135L))
    }
}
