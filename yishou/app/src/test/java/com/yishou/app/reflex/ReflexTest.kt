package com.yishou.app.reflex

import com.yishou.app.judge.LockedQ
import com.yishou.app.judge.Pause
import com.yishou.app.judge.Point
import com.yishou.app.judge.ProbeTrace
import com.yishou.app.judge.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexTest {

    private val question = "研究发现，喝咖啡的人平均寿命更长。以下哪项最能削弱上述结论？"

    @Test
    fun reflexMustBeConcrete() {
        assertNull(Reflexes.check("最能削弱", "先找有没有同时导致两件事的第三个原因", question))
        assertNotNull(Reflexes.check("削弱", "认真读题", question))
        assertNotNull(Reflexes.check("看到因果", "先找他因", question)) // 信号不在题目里
        assertNotNull(Reflexes.check("最能削弱", "最能削弱", question))
    }

    @Test
    fun recallAndSchedule() {
        val r = Reflexes.create("最能削弱", "先找有没有同时导致两件事的第三个原因", "q", 0)
        assertTrue(Reflexes.recalled(r.action, "先找有没有同时导致两件事的第三原因"))
        assertFalse(Reflexes.recalled(r.action, "找原因"))
        assertFalse(Reflexes.recalled(r.action, "认真读题仔细想"))
        val ok = Reflexes.afterRecall(r, true, 100)
        assertEquals(1, ok.reps)
        assertEquals(100 + Reflexes.INTERVALS[1], ok.dueAt)
        val bad = Reflexes.afterRecall(ok, false, 200)
        assertEquals(0, bad.reps)
        assertEquals(200 + Reflexes.INTERVALS[0], bad.dueAt)
    }

    @Test
    fun pickPrefersMostOverdue() {
        val a = Reflexes.create("a信号", "动作甲乙丙丁", "q", 0).copy(id = 1, dueAt = 50)
        val b = Reflexes.create("b信号", "动作甲乙丙丁", "q", 0).copy(id = 2, dueAt = 10)
        assertEquals(2L, Reflexes.pick(listOf(a, b), 100)!!.id)
        assertEquals(1L, Reflexes.pick(listOf(a, b), 100, exclude = setOf(2))!!.id)
        assertNull(Reflexes.pick(emptyList(), 100))
    }

    private val q = LockedQ(
        id = "q", prompt = question, options = listOf("喝咖啡的人收入更高", "咖啡有抗氧化物"), answer = 0,
        points = listOf(Point("P1", "找出同时导致喝咖啡和长寿的第三个原因", "两件事背后有没有共同的东西"), Point("P2", "说明这个原因怎样让结论站不住")),
        need = 2, reference = "A",
    )

    @Test
    fun diffCardShowsWhereYouStalled() {
        val answer = "可能是有钱人才喝得起咖啡也看得起病"
        val t = ProbeTrace(shownAt = 0, firstInputAt = 45_000, submitAt = 120_000, deletes = 3, pauses = listOf(Pause(5, 50_000)))
        val v = Verdict(mapOf("P1" to "有钱人才喝得起咖啡也看得起病", "P2" to null), false, false, "还差 1 个")
        val c = DiffCards.build(q, answer, t, v)
        assertTrue(c.yours.first().contains("45 秒"))
        assertTrue(c.yours.any { it.contains("停了 50 秒") })
        assertTrue(c.yours.any { it.contains("删改了 3 次") })
        assertTrue(c.shortest[0].startsWith("✓"))
        assertTrue(c.shortest[1].startsWith("✗"))
    }

    @Test
    fun fadeLevels() {
        var f = FadeState()
        assertEquals(FadeLevel.FEED, f.level)
        repeat(3) { f = f.after(true) }
        assertEquals(FadeLevel.ASK_FIRST, f.level)
        repeat(3) { f = f.after(true) }
        assertEquals(FadeLevel.BARE, f.level)
        f = f.after(false)
        assertEquals(FadeLevel.BARE, f.level)
        f = f.after(false)
        assertEquals(FadeLevel.ASK_FIRST, f.level)
        val ask = Fade.askFirstQ(q)
        assertEquals(1, ask.need)
        assertTrue(ask.prompt.contains("先别答"))
        assertEquals(listOf("两件事背后有没有共同的东西"), Fade.feedHints(q))
    }

    @Test
    fun icebreakerUsesRightAndWrong() {
        val a = Icebreaker.from(q, 0)!!
        val b = Icebreaker.from(q, 1)!!
        assertEquals("咖啡有抗氧化物", if (a.nonsense == 0) a.left else a.right)
        assertEquals("咖啡有抗氧化物", if (b.nonsense == 0) b.left else b.right)
        assertNull(Icebreaker.from(q.copy(options = emptyList(), answer = -1), 0))
    }

    @Test
    fun gateOrder() {
        val base = GateInput(strandReady = true, bankReady = true, judgeOk = true, hasReflex = true, wrongInRow = 0)
        assertEquals(Main.STRAND, GatePlan.pick(base))
        assertEquals(Main.BANK, GatePlan.pick(base.copy(strandReady = false)))
        assertEquals(Main.LOW_ENERGY, GatePlan.pick(base.copy(judgeOk = false)))
        assertEquals(Main.LOW_ENERGY, GatePlan.pick(base.copy(wrongInRow = 2)))
        assertEquals(Main.WAIT, GatePlan.pick(base.copy(judgeOk = false, hasReflex = false)))
    }

    @Test
    fun morningOncePerDayInWindow() {
        val c = MorningConfig()
        assertTrue(Morning.shouldWake(c, 7, 100, 99))
        assertFalse(Morning.shouldWake(c, 7, 100, 100))
        assertFalse(Morning.shouldWake(c, 11, 100, 99))
        assertFalse(Morning.shouldWake(c.copy(enabled = false), 7, 100, null))
    }
}
