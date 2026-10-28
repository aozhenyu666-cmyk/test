package cn.companion.pet.core.partner

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProactiveRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val candidate = TopicCandidate("goal", TopicSource.GOAL_CHECK, "复习计划", observedAt = 0)

    @Test fun restartKeepsTheActualOutputWindowIncludingTimeZero() {
        val first = ProactiveGovernor()
        first.onShown(0)
        val store = ProactiveStateStore(tmp.root)
        assertTrue(store.save(first.snapshot()))
        val second = ProactiveGovernor()
        second.restore(ProactiveStateStore(tmp.root).load(), 1000)
        assertEquals(1, second.shownLastHour(1000))
        val decision = second.decide(GovernorSignals(1000, UsageContext.IDLE, true), listOf(candidate))
        assertTrue(decision is GovernorDecision.Silent)
        assertTrue((decision as GovernorDecision.Silent).reason.contains("等你接话"))
    }

    @Test fun explicitTrialSkipsCadenceButCannotBypassSafetyGates() {
        val governor = ProactiveGovernor()
        governor.onShown(0)
        val base = GovernorSignals(1000, UsageContext.IDLE, true)
        assertTrue(governor.decide(base, listOf(candidate), requested = true) is GovernorDecision.Speak)
        listOf(base.copy(inCall = true), base.copy(typing = true), base.copy(capturingVoice = true),
            base.copy(sleepHours = true), base.copy(busyReason = "陪练"), base.copy(dismissedUntil = 2000),
            base.copy(screenOn = false)).forEach {
            assertTrue(governor.decide(it, listOf(candidate), requested = true) is GovernorDecision.Silent)
        }
    }

    @Test fun failedAttemptsSurviveRestartAndSuccessResetsOnlyFailureBackoff() {
        var state = ProactiveRetry.failed(ProactiveState(), 1000, "HTTP 429")
        assertEquals(31_000L, state.nextAttemptAt)
        state = ProactiveRetry.failed(state, 31_000, "HTTP 429")
        assertEquals(91_000L, state.nextAttemptAt)
        val store = ProactiveStateStore(tmp.root)
        assertTrue(store.save(state))
        assertEquals(state, ProactiveStateStore(tmp.root).load())
        val recovered = ProactiveRetry.success(state)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(0L, recovered.nextAttemptAt)
    }

    @Test fun restartDoesNotReplayExpiredOrFutureOutputReceipts() {
        val governor = ProactiveGovernor()
        governor.restore(ProactiveState(listOf(0, 5_000_000), awaitingSince = 5_000_000), 4_000_000)
        assertEquals(0, governor.shownLastHour(4_000_000))
        assertTrue(governor.decide(GovernorSignals(4_000_000, UsageContext.IDLE, true), listOf(candidate)) is GovernorDecision.Speak)
    }

    @Test fun failedReadDoesNotCrashTheCompanion() {
        val file = java.io.File(tmp.root, "partner/proactive.json")
        file.parentFile!!.mkdirs(); file.writeText("{broken")
        val store = ProactiveStateStore(tmp.root)
        assertEquals(ProactiveState(), store.load())
        assertNotNull(store.lastError)
    }

    @Test fun bubbleStaysInsidePortraitLandscapeAndNearEitherEdge() {
        for (safe in listOf(OverlayBounds(0, 24, 360, 760), OverlayBounds(20, 0, 760, 330))) {
            for (right in listOf(true, false)) {
                val p = OverlayPlacement.place(safe, 350, 740, 80, 220, 180, right)
                assertTrue(p.x >= safe.left && p.x + 220 <= safe.right)
                assertTrue(p.y >= safe.top && p.y + 180 <= safe.bottom)
            }
        }
    }

    @Test fun unchangedFramesCostNoNewAnalysisAndMinorClockChangesAreIgnored() {
        val gate = FrameChangeGate()
        val grid = IntArray(256) { 100 }
        assertTrue(gate.changed("video", grid))
        gate.analyzed("video", grid)
        assertFalse(gate.changed("video", grid))
        assertFalse(gate.changed("video", grid.copyOf().apply { this[0] = 255 }))
        assertTrue(gate.changed("video", IntArray(256) { 150 }))
        assertTrue(gate.changed("other-app", grid))
    }

    @Test fun aFailedAnalysisDoesNotConsumeANewFrameAndFingerprintsContainNoText() {
        val gate = FrameChangeGate()
        val grid = IntArray(256) { 100 }
        assertTrue(gate.changed("video", grid))
        assertTrue(gate.changed("video", grid))
        val c = candidate.copy(source = TopicSource.SCREEN_TEXT, anchor = "私密原句")
        assertFalse(ObservationFingerprint.of(c).contains("私密"))
        assertEquals(ObservationFingerprint.of(c), ObservationFingerprint.of(c.copy(anchor = "私密 原句")))
        assertNotEquals(ObservationFingerprint.of(c), ObservationFingerprint.of(c.copy(anchor = "另一句")))
    }
}
