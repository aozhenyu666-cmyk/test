package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept.QuizCardDisplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperDafeiyuOverlayPolicyTest {
    @Test
    fun proactiveHapticsIsJvmSafeWithoutThrowing() {
        ProactiveHaptics.vibratePlanModal(null)
        ProactiveHaptics.vibrateSuperSpark(null)
        ProactiveHaptics.vibrateGentleNudge(null)
    }

    @Test
    fun superSparkOverlayPolicyParsesDedupesExcludesLockAndResolvesABButtons() {
        assertEquals(15 * 60_000L, SuperSparkOverlayPolicy.MUTE_MINUTES_MS)

        val disabled = SuperSparkOverlayPolicy.parseSpec(mapOf("id" to "sp-0", "triggered" to false))
        assertNull(disabled)

        val raw = mapOf(
            "spark" to mapOf(
                "id" to "sp-101",
                "mode" to "contrast_push",
                "badge" to "超级大肥鱼 · 反重力对比",
                "title" to "先选一条路",
                "ask" to "【口头一题】某论证的前提缺失隐含假设，怎么一眼抓出来？",
                "voiceLine" to "别发呆了，选刷题还是跟我进 Live。",
                "optionALabel" to "A 省力切刷题舱 20m",
                "optionAMode" to "quiz_only",
                "optionAMinutes" to 20,
                "optionBLabel" to "B 反锁进 Gemini Live",
                "optionBMode" to "gemini_only",
                "optionBMinutes" to 15,
                "geminiTopic" to "判断推理突击",
                "geminiBrief" to "【禁聊体系死命令】直接出题考我",
                "autoClickLive" to true,
                "anchorSource" to "错题本 #12",
            ),
        )
        val spec = SuperSparkOverlayPolicy.parseSpec(raw)
        assertNotNull(spec)
        spec!!
        assertEquals("sp-101", spec.id)
        assertEquals("quiz_only", spec.optionAMode)
        assertEquals(20, spec.optionAMinutes)
        assertEquals("gemini_only", spec.optionBMode)
        assertEquals(15, spec.optionBMinutes)

        assertTrue(
            SuperSparkOverlayPolicy.shouldShow(
                spec = spec,
                shownIds = emptySet(),
                interceptHeld = false,
                planModalShowing = false,
            ),
        )
        // Dedupe by spark.id
        assertFalse(
            SuperSparkOverlayPolicy.shouldShow(
                spec = spec,
                shownIds = setOf("sp-101"),
                interceptHeld = false,
                planModalShowing = false,
            ),
        )
        // Mutually exclusive with lock screen / plan modal / active chat
        assertFalse(
            SuperSparkOverlayPolicy.shouldShow(
                spec = spec,
                shownIds = emptySet(),
                interceptHeld = true,
                planModalShowing = false,
            ),
        )
        assertFalse(
            SuperSparkOverlayPolicy.shouldShow(
                spec = spec,
                shownIds = emptySet(),
                interceptHeld = false,
                planModalShowing = true,
            ),
        )
        assertFalse(
            SuperSparkOverlayPolicy.shouldShow(
                spec = spec,
                shownIds = emptySet(),
                interceptHeld = false,
                planModalShowing = false,
                chattingActive = true,
            ),
        )

        val (actA, actB) = SuperSparkOverlayPolicy.resolveTacticalActions(spec)
        assertEquals("A", actA.slot)
        assertEquals("quiz_only", actA.capsuleMode)
        assertEquals(20, actA.capsuleMinutes)
        assertTrue(actA.muteAndClose)
        assertFalse(actA.launchGeminiLive)

        assertEquals("B", actB.slot)
        assertEquals("gemini_only", actB.capsuleMode)
        assertEquals(15, actB.capsuleMinutes)
        assertTrue(actB.launchGeminiLive)

        val body = SuperSparkOverlayPolicy.formatCardBody(spec)
        assertTrue(body.contains("〔鲸澜〕先选一条路"))
        assertTrue(body.contains("锚点：错题本 #12"))
    }

    @Test
    fun planModalPolicyEnforces90sCooldownExclusivityAndSnoozeRules() {
        assertNull(PlanModalPolicy.parseSpec(mapOf("active" to false, "day" to "2026-11-24")))

        val modal = PlanModalPolicy.parseSpec(
            mapOf(
                "active" to true,
                "day" to "2026-11-24",
                "headline" to "必须先确认今日验收计划",
                "prompt" to "把今天 2 块硬任务敲定后才能进入专注护航",
                "snoozeLeft" to 2,
                "mustConfirm" to false,
                "energyLevel" to "mid",
                "draftBlocks" to listOf(
                    mapOf(
                        "id" to "blk-1",
                        "slot" to "afternoon",
                        "startMin" to 14 * 60 + 30,
                        "endMin" to 15 * 60 + 10,
                        "title" to "判断推理 25 题",
                        "deliverable" to "提交正确率与 3 条错因",
                        "lockMode" to "verify",
                        "allowedApps" to listOf("md.obsidian"),
                    ),
                ),
            ),
        )
        assertNotNull(modal)
        modal!!
        assertEquals(1, modal.draftBlocks.size)
        assertTrue(PlanModalPolicy.canSnooze(modal))
        assertFalse(PlanModalPolicy.canSnooze(modal.copy(mustConfirm = true)))
        assertFalse(PlanModalPolicy.canSnooze(modal.copy(snoozeLeft = 0)))

        val t0 = 1_800_000_000_000L
        assertTrue(
            PlanModalPolicy.shouldShow(
                spec = modal,
                lastShownAtMs = 0L,
                nowMs = t0,
                interceptHeld = false,
                alreadyShowing = false,
            ),
        )
        assertFalse(
            PlanModalPolicy.shouldShow(
                spec = modal,
                lastShownAtMs = t0,
                nowMs = t0 + 89_999L,
                interceptHeld = false,
                alreadyShowing = false,
            ),
        )
        assertTrue(
            PlanModalPolicy.shouldShow(
                spec = modal,
                lastShownAtMs = t0,
                nowMs = t0 + 90_000L,
                interceptHeld = false,
                alreadyShowing = false,
            ),
        )
        assertFalse(
            PlanModalPolicy.shouldShow(
                spec = modal,
                lastShownAtMs = 0L,
                nowMs = t0,
                interceptHeld = true,
                alreadyShowing = false,
            ),
        )

        val formatted = PlanModalPolicy.formatBlockLine(modal.draftBlocks.first())
        assertTrue(formatted.contains("14:30–15:10"))
        assertTrue(formatted.contains("判断推理 25 题"))
        assertTrue(formatted.contains("[验收锁]"))
        assertTrue(formatted.contains("提交正确率与 3 条错因"))

        assertNull(PlanModalPolicy.validateNegotiationText(" "))
        assertNull(PlanModalPolicy.validateNegotiationText("好"))
        assertEquals("下午判断推理40m", PlanModalPolicy.validateNegotiationText("  下午判断推理40m  "))
    }

    @Test
    fun quizCardDisplayPolicySurfacesSpendBlockedReasonClearly() {
        val blockedWhenEarned = QuizCardDisplayPolicy.formatEarnedSummary(
            mapOf(
                "required" to 10,
                "earnedAdded" to 5,
                "availableFunLeft" to 15,
                "spendBlockedReason" to "当前处于逾期验收锁，需先交付当前计划块",
            ),
        )
        assertFalse(blockedWhenEarned.first)
        assertEquals(15, blockedWhenEarned.third)
        assertTrue(blockedWhenEarned.second.contains("+5 分钟娱乐余额"))
        assertTrue(blockedWhenEarned.second.contains("逾期验收锁"))
        assertFalse(blockedWhenEarned.second.contains("可直接花余额放行"))
    }
}
