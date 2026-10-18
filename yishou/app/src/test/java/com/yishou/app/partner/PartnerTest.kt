package com.yishou.app.partner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerTest {

    private val plan = PartnerPlan(Material.ARTICLE, "这段论证漏了哪个前提", 25, Gear.ACCOMPANY)
    private val allowed = setOf("reader", "companion", "self")

    @Test
    fun openingIncludesThePlanAndKeepsTheCompanionRules() {
        val text = Opening.build(plan, null, null)
        assertTrue(text.startsWith("现在开始伴读。你是坐我旁边的同学，不是助手。"))
        assertTrue(text.contains("别替我总结，先让我自己讲"))
        assertTrue(text.contains("一次一处"))
        assertTrue(text.contains("结束时问我一句\"聊出了什么\""))
        assertTrue(text.contains(plan.goal))
        assertTrue(text.contains("25 分钟"))
        assertTrue(text.contains("大部分时间安静，关键句再问。"))
        assertFalse(text.contains("上次你说"))
    }

    @Test
    fun openingSelectsTheMaterialPrompt() {
        val prompts = mapOf(
            Material.VIDEO to "每讲完一个知识点就停下来让我复述，复述不出来就倒回去。",
            Material.ARTICLE to "每读完一段，让我说这一段和上一段是什么关系。",
            Material.DRILL to "我做之前先让我说思路，做完先别给答案，问我哪一步最不确定。",
            Material.CHESS to "我走每步之前问我对方最好的应手是什么。",
        )
        prompts.forEach { (material, prompt) ->
            val text = Opening.build(plan.copy(material = material), null, null)
            assertTrue(material.name, text.contains(prompt))
            prompts.filterKeys { it != material }.values.forEach { assertFalse(text.contains(it)) }
        }
        val other = Opening.build(plan.copy(material = Material.OTHER), null, null)
        prompts.values.forEach { assertFalse(other.contains(it)) }
        assertTrue(other.contains(plan.goal))
    }

    @Test
    fun openingSelectsTheGearWithoutMixingInstructions() {
        val prompts = mapOf(
            Gear.ACCOMPANY to "大部分时间安静，关键句再问。",
            Gear.LURE to "多用二选一、打赌和‘如果……会怎样’来勾我说话。",
            Gear.STRICT to "每一段都要我答了才往下，答不上来就停在这里。",
        )
        prompts.forEach { (gear, prompt) ->
            val text = Opening.build(plan.copy(gear = gear), null, null)
            assertTrue(gear.name, text.contains(prompt))
            prompts.filterKeys { it != gear }.values.forEach { assertFalse(text.contains(it)) }
        }
        assertEquals(listOf("陪", "诱", "严"), Gear.entries.map { it.label })
    }

    @Test
    fun openingResumesThePreviousCloseAndNextStep() {
        val text = Opening.build(plan, "  限定词改变结论范围  ", "  对照下一段  ")
        assertTrue(text.contains("上次你说：限定词改变结论范围，下一步是对照下一段，先问我做了没有"))
        assertFalse(text.contains("null"))
    }

    @Test
    fun openingDoesNotInventMissingHistory() {
        val noHistory = Opening.build(plan, " \n", "对照下一段")
        assertFalse(noHistory.contains("上次你说"))
        assertFalse(noHistory.contains("对照下一段"))
        val closeOnly = Opening.build(plan, "限定词改变结论范围", null)
        assertTrue(closeOnly.contains("上次你说：限定词改变结论范围"))
        assertFalse(closeOnly.contains("下一步是"))
        assertFalse(closeOnly.contains("null"))
    }

    @Test
    fun noTimeIsCountedBeforeTheFirstForegroundObservation() {
        val presence = Presence(allowed)
        presence.tick(1_000)
        assertEquals(0L, presence.presentMs)
        assertEquals(0, presence.leaves)
        assertNull(presence.awaySince)
        presence.onForeground("reader", 10_000)
        presence.finish(15_000)
        assertEquals(5_000L, presence.presentMs)
    }

    @Test
    fun switchingBetweenAllowedAppsCountsAsOneContinuousPresence() {
        val presence = Presence(allowed)
        presence.onForeground("reader", 1_000)
        presence.onForeground("companion", 11_000)
        presence.onForeground("self", 21_000)
        presence.finish(31_000)
        assertEquals(30_000L, presence.presentMs)
        assertEquals(0, presence.leaves)
        assertNull(presence.awaySince)
    }

    @Test
    fun briefAbsenceDoesNotCountAsALeaveButDoesNotCountAsPresenceEither() {
        val presence = Presence(allowed)
        presence.onForeground("reader", 0)
        presence.onForeground("other", 10_000)
        assertEquals(10_000L, presence.awaySince)
        presence.onForeground("reader", 30_000)
        presence.finish(40_000)
        assertEquals(20_000L, presence.presentMs)
        assertEquals(0, presence.leaves)
        assertNull(presence.awaySince)
    }

    @Test
    fun longAbsenceIsDetectedByATickWithoutAnotherAppSwitch() {
        val presence = Presence(allowed)
        presence.onForeground("reader", 0)
        presence.onForeground("other", 10_000)
        presence.tick(130_000)
        assertEquals(0, presence.leaves)
        presence.tick(130_001)
        assertEquals(1, presence.leaves)
        presence.tick(250_000)
        assertEquals(1, presence.leaves)
        assertEquals(10_000L, presence.presentMs)
        assertEquals(10_000L, presence.awaySince)
    }

    @Test
    fun switchingBetweenOutsideAppsDoesNotRestartTheAbsenceOrCountTwice() {
        val presence = Presence(allowed)
        presence.onForeground("reader", 0)
        presence.onForeground("outside-a", 10_000)
        presence.onForeground("outside-b", 80_000)
        assertEquals(10_000L, presence.awaySince)
        presence.onForeground("outside-c", 140_000)
        presence.onForeground("reader", 150_000)
        presence.finish(160_000)
        assertEquals(1, presence.leaves)
        assertEquals(20_000L, presence.presentMs)
        assertNull(presence.awaySince)
    }

    @Test
    fun returningFromALongAbsenceSettlesItEvenWithoutTicks() {
        val presence = Presence(allowed)
        presence.onForeground("reader", 0)
        presence.onForeground("other", 5_000)
        presence.onForeground("reader", 130_000)
        assertEquals(1, presence.leaves)
        assertNull(presence.awaySince)
        presence.finish(140_000)
        assertEquals(15_000L, presence.presentMs)
    }

    @Test
    fun separateLongAbsencesCountSeparately() {
        val presence = Presence(allowed, awayLimitMs = 100)
        presence.onForeground("reader", 0)
        presence.onForeground("other", 10)
        presence.onForeground("reader", 111)
        presence.onForeground("other", 121)
        presence.finish(222)
        assertEquals(2, presence.leaves)
        assertEquals(20L, presence.presentMs)
    }

    @Test
    fun endingDuringAnAbsenceSettlesItOnceAndFreezesTheCounters() {
        val presence = Presence(allowed, awayLimitMs = 100)
        presence.onForeground("reader", 10)
        presence.onForeground("other", 50)
        presence.finish(151)
        assertEquals(1, presence.leaves)
        assertEquals(40L, presence.presentMs)
        assertNull(presence.awaySince)
        presence.finish(500)
        presence.onForeground("reader", 600)
        presence.tick(700)
        assertEquals(1, presence.leaves)
        assertEquals(40L, presence.presentMs)
        assertNull(presence.awaySince)
    }

    @Test
    fun anInitiallyOutsideAppStillStartsAnAbsence() {
        val presence = Presence(allowed, awayLimitMs = 100)
        presence.onForeground("other", 0)
        assertEquals(0L, presence.awaySince)
        presence.finish(101)
        assertEquals(0L, presence.presentMs)
        assertEquals(1, presence.leaves)
    }

    @Test
    fun duplicateAndOutOfOrderEventsCannotMoveTheClockBackwards() {
        val presence = Presence(allowed, awayLimitMs = 100)
        presence.onForeground("reader", 100)
        presence.onForeground("reader", 100)
        presence.tick(200)
        presence.onForeground("other", 150)
        presence.tick(120)
        presence.finish(250)
        assertEquals(150L, presence.presentMs)
        assertEquals(0, presence.leaves)
    }

    @Test
    fun finishingBeforeAnyObservationDoesNotCreateStatistics() {
        val presence = Presence(allowed)
        presence.finish(100)
        presence.onForeground("reader", 200)
        presence.tick(300)
        assertEquals(0L, presence.presentMs)
        assertEquals(0, presence.leaves)
    }

    @Test
    fun changingTheCallersAllowedSetDoesNotChangeAnExistingSession() {
        val apps = mutableSetOf("reader")
        val presence = Presence(apps)
        presence.onForeground("reader", 0)
        apps.clear()
        presence.onForeground("reader", 10)
        presence.finish(20)
        assertEquals(20L, presence.presentMs)
        assertNull(presence.awaySince)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeAbsenceLimitIsRejected() {
        Presence(allowed, awayLimitMs = -1)
    }

    @Test
    fun closingUsesSubstanceAndAcceptsTheExactMinimum() {
        assertTrue(Closing.ok("先看条件范围", "对照下段"))
        assertTrue(Closing.ok("嗯，就是，先看条件范围。", "然后，对照下段！"))
        assertFalse(Closing.ok("先看范围", "对照下段"))
        assertFalse(Closing.ok("先看条件范围", "看下段"))
    }

    @Test
    fun fillerAndPunctuationCannotPassTheClosingRule() {
        assertFalse(Closing.ok("嗯，那个，我觉得，就是。", "对照下段"))
        assertFalse(Closing.ok("先看条件范围", "嗯那个然后就是"))
        assertFalse(Closing.ok("？！……", "    "))
        assertFalse(Closing.ok("", ""))
    }
}
