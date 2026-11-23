package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept

import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.CompanionReleaseTracker
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.DefiancePolicy
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.SessionArbiterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LockStabilizationTest {
    @Test
    fun quizAntiDoubleTapIgnoresDuplicateStaleAndKeepsBusyThroughDelay() {
        // Normal non-unlocked answer keeps UI locked (quizBusy=true) until postDelayed renderQuizPayload runs
        val advance = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-1",
            currentQuestionId = "q-1",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to false),
        )
        assertEquals(QuizResponseAction.ADVANCE_NEXT, advance)
        assertTrue(QuizAntiDoubleTapPolicy.keepBusyUntilNextQuestionRendered(advance))

        // Duplicate / ignored response from server must NOT advance or claim correct
        val dup = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-1",
            currentQuestionId = "q-1",
            res = mapOf("ok" to true, "duplicate" to true, "ignored" to true, "correct" to false),
        )
        assertEquals(QuizResponseAction.IGNORE_DUPLICATE, dup)
        assertFalse(QuizAntiDoubleTapPolicy.keepBusyUntilNextQuestionRendered(dup))

        // Stale questionId (e.g. UI already switched to q-2) must be ignored
        val stale = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-1",
            currentQuestionId = "q-2",
            res = mapOf("ok" to true, "correct" to true),
        )
        assertEquals(QuizResponseAction.IGNORE_STALE_QUESTION, stale)

        // Null / failed response allows user to retry
        val err = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-2",
            currentQuestionId = "q-2",
            res = null,
        )
        assertEquals(QuizResponseAction.RETRY_ERROR, err)
        assertFalse(QuizAntiDoubleTapPolicy.keepBusyUntilNextQuestionRendered(err))
    }

    @Test
    fun quizResponseDistinguishesDirectReleaseFromEarnedNeedSpend() {
        // Unlocked directly without earnedOnly -> release immediately
        val directUnlock = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-10",
            currentQuestionId = "q-10",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to true, "unlockMinutes" to 5),
        )
        assertEquals(QuizResponseAction.RELEASE_NOW, directUnlock)

        // 4.8: earnedOnly/needSpend -> prompt user to spend balance instead of granting a legacy pass
        val earnedOnly = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-10",
            currentQuestionId = "q-10",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to true, "earnedOnly" to true, "earnedAdded" to 5),
        )
        assertEquals(QuizResponseAction.EARNED_NEED_SPEND, earnedOnly)

        val needSpend = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-10",
            currentQuestionId = "q-10",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to false, "needSpend" to true, "earnedAdded" to 5),
        )
        assertEquals(QuizResponseAction.EARNED_NEED_SPEND, needSpend)

        // If server also returns spendUntil > 0 or passActive=true -> release now
        val withSpendPass = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-10",
            currentQuestionId = "q-10",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to false, "spendUntil" to 9_999_999_999_000L),
        )
        assertEquals(QuizResponseAction.RELEASE_NOW, withSpendPass)
    }

    @Test
    fun companionReleasePolicyNeverReopensTargetWhenAnotherLockRemains() {
        assertFalse(CompanionReleasePolicy.shouldReopenTarget("tv.danmaku.bili", hasOpenOtherLock = true, neverLock = false))
        assertFalse(CompanionReleasePolicy.shouldReopenTarget("com.bbk.launcher2", hasOpenOtherLock = false, neverLock = true))
        assertFalse(CompanionReleasePolicy.shouldReopenTarget(null, hasOpenOtherLock = false, neverLock = false))
        assertTrue(CompanionReleasePolicy.shouldReopenTarget("tv.danmaku.bili", hasOpenOtherLock = false, neverLock = false))
    }

    @Test
    fun companionReleaseTrackerFiresOnceOnLockToReleaseTransitionOnly() {
        val tracker = CompanionReleaseTracker()

        // Without any prior companion lock, repeated release=true ticks (e.g. hardCapDowngrade) must not fire
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = false))
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = false))

        // Companion enforces a lock -> transition to release=true fires exactly once
        assertFalse(tracker.shouldReleaseOnPlan(locked = true, releaseFlag = false, hasActiveCompanionLock = true))
        assertTrue(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = true))
        // Subsequent release=true ticks must NOT fire again (even if hasActiveCompanionLock wasn't cleared)
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = true))
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = false))

        // Explicit unlock consumes pending release so a later /plan release=true tick does not re-fire
        tracker.onEnforced()
        tracker.onExplicitRelease()
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = false))
    }

    @Test
    fun boutSwitchBufferGrants150SecondsOncePerBoutScope() {
        val buffer = BoutSwitchBuffer()
        assertEquals(150_000L, BoutSwitchBuffer.BUFFER_DURATION_MS)

        var elapsed = 10_000L
        assertTrue(buffer.canTrigger("bout-1", elapsed))
        assertFalse(buffer.isActive(elapsed))

        // Trigger once for bout-1
        assertTrue(buffer.trigger("bout-1", elapsed))
        assertTrue(buffer.isActive(elapsed))
        assertEquals(150, buffer.remainingSeconds(elapsed))

        // While active, neither bout-1 nor another bout can trigger concurrently
        assertFalse(buffer.canTrigger("bout-1", elapsed + 30_000L))
        assertFalse(buffer.canTrigger("bout-2", elapsed + 30_000L))
        assertTrue(buffer.isActive(elapsed + 149_999L))
        assertEquals(1, buffer.remainingSeconds(elapsed + 149_999L))

        // At 150_000ms, buffer expires cleanly
        elapsed += 150_000L
        assertFalse(buffer.isActive(elapsed))
        assertEquals(0, buffer.remainingSeconds(elapsed))

        // Same bout-1 CANNOT re-trigger (max 1 per bout)
        assertFalse(buffer.canTrigger("bout-1", elapsed))
        assertFalse(buffer.trigger("bout-1", elapsed))

        // A new bout-2 CAN trigger once
        assertTrue(buffer.canTrigger("bout-2", elapsed))
        assertTrue(buffer.trigger("bout-2", elapsed))

        // Restoring persisted scopes prevents replay across process restarts
        val restored = BoutSwitchBuffer()
        restored.restoreUsedScopes(buffer.usedScopesSnapshot())
        assertFalse(restored.canTrigger("bout-1", elapsed + 200_000L))
        assertFalse(restored.canTrigger("bout-2", elapsed + 200_000L))
        assertTrue(restored.canTrigger("bout-3", elapsed + 200_000L))
    }

    @Test
    fun interceptConvergenceRespectsReleaseBufferAndSingleArbiterTruth() {
        // Unified release and 2.5m bout buffer always preempt arbiter lock
        assertEquals(
            InterceptConvergencePolicy.ForegroundAction.UNIFIED_RELEASE,
            InterceptConvergencePolicy.decideForeground(
                disarmed = false,
                neverLock = false,
                unifiedRelease = true,
                boutBufferActive = false,
                arbiterShouldLock = true,
                arbiterReasonTag = "arbiter_lock",
            ),
        )
        assertEquals(
            InterceptConvergencePolicy.ForegroundAction.UNIFIED_RELEASE,
            InterceptConvergencePolicy.decideForeground(
                disarmed = false,
                neverLock = false,
                unifiedRelease = false,
                boutBufferActive = true,
                arbiterShouldLock = true,
                arbiterReasonTag = "arbiter_lock",
            ),
        )
        // When arbiter allows (e.g. free mode / WeChat within quota / allowlist), must NOT fall back to legacy blocking
        assertEquals(
            InterceptConvergencePolicy.ForegroundAction.ARBITER_ALLOW,
            InterceptConvergencePolicy.decideForeground(
                disarmed = false,
                neverLock = false,
                unifiedRelease = false,
                boutBufferActive = false,
                arbiterShouldLock = false,
                arbiterReasonTag = "arbiter_open",
            ),
        )
        // Shadow mode yields ARBITER_SHADOW without real lock
        assertEquals(
            InterceptConvergencePolicy.ForegroundAction.ARBITER_SHADOW,
            InterceptConvergencePolicy.decideForeground(
                disarmed = false,
                neverLock = false,
                unifiedRelease = false,
                boutBufferActive = false,
                arbiterShouldLock = false,
                arbiterReasonTag = "shadow",
            ),
        )
        // Fresh arbiter lock locks cleanly
        assertEquals(
            InterceptConvergencePolicy.ForegroundAction.ARBITER_LOCK,
            InterceptConvergencePolicy.decideForeground(
                disarmed = false,
                neverLock = false,
                unifiedRelease = false,
                boutBufferActive = false,
                arbiterShouldLock = true,
                arbiterReasonTag = "arbiter_lock",
            ),
        )
        // Expired / null arbiter verdict safely falls back
        assertEquals(
            InterceptConvergencePolicy.ForegroundAction.LEGACY_FALLBACK,
            InterceptConvergencePolicy.decideForeground(
                disarmed = false,
                neverLock = false,
                unifiedRelease = false,
                boutBufferActive = false,
                arbiterShouldLock = null,
                arbiterReasonTag = null,
            ),
        )
    }

    @Test
    fun companionReleaseIgnoresLegacyLocksWhenArbiterAllowsWhileKeepingFocusBoundary() {
        // Arbiter allows (false) -> legacy proactiveLocked & guardStrictLocked must NOT block reopen
        val blockedByLegacy = CompanionReleasePolicy.resolveHasOpenOtherLock(
            arbiterShouldLock = false,
            proactiveLocked = true,
            focusLocked = false,
            guardStrictLocked = true,
        )
        assertFalse(blockedByLegacy)
        assertTrue(CompanionReleasePolicy.shouldReopenTarget("com.tencent.mm", blockedByLegacy, neverLock = false))

        // Native FocusRuntime active independently preserves clear boundary
        val blockedByFocus = CompanionReleasePolicy.resolveHasOpenOtherLock(
            arbiterShouldLock = false,
            proactiveLocked = false,
            focusLocked = true,
            guardStrictLocked = false,
        )
        assertTrue(blockedByFocus)
        assertFalse(CompanionReleasePolicy.shouldReopenTarget("com.tencent.mm", blockedByFocus, neverLock = false))

        // Fallback (arbiterShouldLock == null) respects legacy rules
        assertTrue(
            CompanionReleasePolicy.resolveHasOpenOtherLock(
                arbiterShouldLock = null,
                proactiveLocked = true,
                focusLocked = false,
                guardStrictLocked = false,
            ),
        )
    }

    @Test
    fun sessionArbiterAndDefiancePoliciesPreventLegacyMultiHeadLocking() {
        assertEquals(
            SessionArbiterPolicy.Action.UNIFIED_RELEASE,
            SessionArbiterPolicy.decide(
                unifiedRelease = true,
                disarmed = false,
                neverLock = false,
                arbiterShouldLock = true,
                arbiterReasonTag = "arbiter_lock",
            ),
        )
        // Arbiter allows -> session must NOT trigger legacy graceHint/lockInSession
        assertEquals(
            SessionArbiterPolicy.Action.ARBITER_OPEN,
            SessionArbiterPolicy.decide(
                unifiedRelease = false,
                disarmed = false,
                neverLock = false,
                arbiterShouldLock = false,
                arbiterReasonTag = "arbiter_open",
            ),
        )
        assertEquals(
            SessionArbiterPolicy.Action.LEGACY_SESSION,
            SessionArbiterPolicy.decide(
                unifiedRelease = false,
                disarmed = false,
                neverLock = false,
                arbiterShouldLock = null,
                arbiterReasonTag = null,
            ),
        )

        // Defiance check: arbiter open overrides legacy listed=true; unified release always false
        assertFalse(
            DefiancePolicy.isDefiant(
                screenOn = true,
                unifiedRelease = false,
                arbiterShouldLock = false,
                legacyListed = true,
                hasLegacyPass = false,
            ),
        )
        assertFalse(
            DefiancePolicy.isDefiant(
                screenOn = true,
                unifiedRelease = true,
                arbiterShouldLock = true,
                legacyListed = true,
                hasLegacyPass = false,
            ),
        )
        assertTrue(
            DefiancePolicy.isDefiant(
                screenOn = true,
                unifiedRelease = false,
                arbiterShouldLock = true,
                legacyListed = false,
                hasLegacyPass = true,
            ),
        )
        assertTrue(
            DefiancePolicy.isDefiant(
                screenOn = true,
                unifiedRelease = false,
                arbiterShouldLock = null,
                legacyListed = true,
                hasLegacyPass = false,
            ),
        )
    }

    @Test
    fun quizCardDisplayPolicyHandlesMixedKindsAndSafeLocalImagePaths() {
        // 1) Kind resolution & badge labels
        assertEquals("card", QuizCardDisplayPolicy.resolveKind(mapOf("kind" to "card"), emptyList<Any>()))
        assertEquals("short_qa", QuizCardDisplayPolicy.resolveKind(mapOf("kind" to "short_qa"), emptyList<Any>()))
        assertEquals("short_qa", QuizCardDisplayPolicy.resolveKind(emptyMap<String, Any>(), emptyList<Any>()))
        assertEquals("mcq", QuizCardDisplayPolicy.resolveKind(emptyMap<String, Any>(), listOf("甲", "乙")))

        assertTrue(QuizCardDisplayPolicy.badgeLabel("card", "今日卡片").contains("0.7 题进度"))
        assertTrue(QuizCardDisplayPolicy.badgeLabel("short_qa", null).contains("小问题卡"))

        // 2) Feedback formatting for card & short_qa
        val cardOk = QuizCardDisplayPolicy.formatAnswerFeedback(mapOf("correct" to true, "selfJudged" to true, "explain" to "记住了"), "card")
        assertTrue(cardOk.first)
        assertTrue(cardOk.second.contains("+0.7 题进度"))

        val cardUnfamiliar = QuizCardDisplayPolicy.formatAnswerFeedback(mapOf("correct" to false, "selfJudged" to true, "correctAnswer" to "已阅读掌握"), "card")
        assertFalse(cardUnfamiliar.first)
        assertFalse(cardUnfamiliar.second.contains("正确答案"))
        assertTrue(cardUnfamiliar.second.contains("待复习池"))

        // 3) Image extraction & safe local file resolution (no crash on missing/traversal/URLs)
        val specs = QuizCardDisplayPolicy.extractImageSpecs(
            listOf(
                mapOf("path" to "charts/q1.png", "role" to "stem", "caption" to "趋势图"),
                mapOf("path" to "   "),
                "assets/plain.jpg",
            ),
        )
        assertEquals(2, specs.size)
        assertEquals("charts/q1.png", specs[0].path)
        assertEquals("趋势图", specs[0].caption)

        val tempDir = Files.createTempDirectory("quiz-img-test").toFile()
        try {
            val imgFile = File(tempDir, "companion/bank-assets/charts/q1.png").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val resolved = QuizCardDisplayPolicy.resolveSafeLocalImageFile("charts/q1.png", listOf(tempDir))
            assertEquals(imgFile.canonicalPath, resolved?.canonicalPath)
            assertNull(QuizCardDisplayPolicy.resolveSafeLocalImageFile("../secret.png", listOf(tempDir)))
            assertNull(QuizCardDisplayPolicy.resolveSafeLocalImageFile("https://example.com/a.png", listOf(tempDir)))
            assertNull(QuizCardDisplayPolicy.resolveSafeLocalImageFile("nonexistent/missing.png", listOf(tempDir)))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun quizProgressSpendDefaultsAndCachedFunLeftSync() {
        val p0 = QuizCardDisplayPolicy.formatProgress(0.0, 10, 5)
        assertTrue(p0.contains("0 / 10"))
        assertTrue(p0.contains("第 1 题"))

        val p9 = QuizCardDisplayPolicy.formatProgress(9.0, 10, 5)
        assertTrue(p9.contains("9 / 10"))
        assertTrue(p9.contains("第 10 题"))

        assertEquals(5, QuizCardDisplayPolicy.defaultSpendMinutes(null))
        assertEquals(5, QuizCardDisplayPolicy.defaultSpendMinutes(0))
        assertEquals(5, QuizCardDisplayPolicy.defaultSpendMinutes(4))
        assertEquals(5, QuizCardDisplayPolicy.defaultSpendMinutes(5))
        assertEquals(5, QuizCardDisplayPolicy.defaultSpendMinutes(9))
        assertEquals(10, QuizCardDisplayPolicy.defaultSpendMinutes(10))
        assertEquals(10, QuizCardDisplayPolicy.defaultSpendMinutes(25))

        val normalSummary = QuizCardDisplayPolicy.formatEarnedSummary(
            mapOf("required" to 10, "earnedAdded" to 5, "availableFunLeft" to 5),
        )
        assertTrue(normalSummary.first)
        assertEquals(5, normalSummary.third)
        assertTrue(normalSummary.second.contains("+5 分钟娱乐余额"))
        assertTrue(normalSummary.second.contains("可用娱乐余额 5 分钟"))

        val cappedSummary = QuizCardDisplayPolicy.formatEarnedSummary(
            mapOf(
                "required" to 10,
                "earnedAdded" to 0,
                "capped" to true,
                "dailyBankEarned" to 60,
                "dailyBankCap" to 60,
                "availableFunLeft" to 0,
            ),
        )
        assertFalse(cappedSummary.first)
        assertEquals(0, cappedSummary.third)
        assertTrue(cappedSummary.second.contains("上限"))
        assertFalse(cappedSummary.second.contains("+5"))

        val funCache = io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.FunBalanceCache
        funCache.clear()
        funCache.syncFromResponse(mapOf("availableFunLeft" to 5))
        assertEquals(5, funCache.cached())
        funCache.update(null)
        assertEquals(5, funCache.cached())
        funCache.syncFromResponse(mapOf("timeLedger" to mapOf("funLeft" to 10)))
        assertEquals(10, funCache.cached())
    }
}
