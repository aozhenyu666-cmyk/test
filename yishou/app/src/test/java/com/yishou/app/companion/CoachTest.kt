package com.yishou.app.companion

import com.yishou.app.companion.core.AskPlan
import com.yishou.app.companion.core.AttemptMemory
import com.yishou.app.companion.core.CoachAction
import com.yishou.app.companion.core.CoachInput
import com.yishou.app.companion.core.CoachParse
import com.yishou.app.companion.core.CoachReply
import com.yishou.app.companion.core.ContextSlice
import com.yishou.app.companion.core.InterruptBudget
import com.yishou.app.companion.core.JudgeParse
import com.yishou.app.companion.core.JudgeReply
import com.yishou.app.companion.core.Segment
import com.yishou.app.companion.core.ThinkMove
import com.yishou.app.companion.core.Verdict
import com.yishou.app.companion.core.VideoCoach
import com.yishou.app.companion.core.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 介入时机、动作挑选、问题有没有出处、会不会泄题、判定失败算不算错 */
class CoachTest {

    private var now = 0L
    private fun coach(budget: InterruptBudget = InterruptBudget()) = VideoCoach(budget) { now }

    private fun slice(
        text: String = "他从样本 A 推出全部人群一定会这样",
        from: Long = 0,
        to: Long = 150_000,
        ids: List<Long> = listOf(1L, 2L, 3L),
    ): ContextSlice = ContextSlice(
        epoch = 1,
        evidenceIds = ids,
        fromMs = from,
        toMs = to,
        text = text,
        segments = listOf(Segment(1, Channel.VIDEO_AUDIO, from, to, text, ids)),
        gapsMs = 0,
        expiresAtMs = now + 20_000,
    )

    @Test
    fun `材料没铺开就继续听`() {
        val c = coach()
        val plan = c.consider(CoachInput(now, "弄懂抽样", slice(to = 30_000), elapsedMs = 30_000))
        assertTrue(plan is AskPlan.Wait)
        assertTrue((plan as AskPlan.Wait).reason.contains("再听"))
    }

    @Test
    fun `什么都没听到就继续听`() {
        val c = coach()
        val plan = c.consider(CoachInput(now, "弄懂抽样", slice(text = "", ids = emptyList())))
        assertTrue(plan is AskPlan.Wait)
    }

    @Test
    fun `刚问过就闭嘴`() {
        val c = coach()
        val first = c.consider(CoachInput(now, "目标", slice(), elapsedMs = 150_000))
        assertTrue(first is AskPlan.Ask)
        c.onAsked(now, (first as AskPlan.Ask).move)
        now += 30_000
        val second = c.consider(CoachInput(now, "目标", slice(to = 180_000), elapsedMs = 180_000))
        assertTrue(second is AskPlan.Wait)
        assertTrue((second as AskPlan.Wait).reason.contains("刚问过"))
    }

    @Test
    fun `问够次数以后不再打扰`() {
        val c = coach(InterruptBudget(maxPerSession = 1))
        val first = c.consider(CoachInput(now, "目标", slice(), elapsedMs = 150_000))
        c.onAsked(now, (first as AskPlan.Ask).move)
        now += 200_000
        val second = c.consider(CoachInput(now, "目标", slice(to = 350_000), elapsedMs = 350_000))
        assertTrue(second is AskPlan.Wait)
        assertTrue((second as AskPlan.Wait).reason.contains("问了 1 次"))
    }

    @Test
    fun `有把话说过头的苗头就让他补条件`() {
        val c = coach()
        val plan = c.consider(CoachInput(now, "目标", slice("所以这个方法对所有情况都一定成立"), elapsedMs = 150_000))
        assertEquals(ThinkMove.FIND_GAP, (plan as AskPlan.Ask).move)
    }

    @Test
    fun `没有破绽时不硬说有错，改用预测或复述`() {
        val c = coach()
        val plan = c.consider(CoachInput(now, "目标", slice("先取样本，再做统计，最后画图"), elapsedMs = 150_000))
        val move = (plan as AskPlan.Ask).move
        assertTrue(move == ThinkMove.PREDICT || move == ThinkMove.RECALL || move == ThinkMove.TRANSFER)
    }

    @Test
    fun `上次没到的动作先复练`() {
        val c = coach()
        val recent = listOf(AttemptMemory(ThinkMove.COUNTER, 0, Verdict.MISS, "只举了一个不相关的例子"))
        val plan = c.consider(CoachInput(now, "目标", slice(), recent, elapsedMs = 150_000))
        assertEquals(ThinkMove.COUNTER, (plan as AskPlan.Ask).move)
    }

    @Test
    fun `问题里带上证据编号和材料`() {
        val c = coach()
        val plan = c.consider(CoachInput(now, "弄懂抽样", slice(), elapsedMs = 150_000)) as AskPlan.Ask
        assertEquals(listOf(1L, 2L, 3L), plan.evidenceIds)
        assertTrue(plan.system.contains("只能就【材料】里真实出现的字提问"))
        assertTrue(plan.user.contains("1,2,3"))
        assertTrue(plan.expiresAtMs > now)
    }

    @Test
    fun `问题过期时间按预算给`() {
        val c = coach(InterruptBudget(questionTtlMs = 5_000))
        val plan = c.consider(CoachInput(now, "目标", slice(), elapsedMs = 150_000)) as AskPlan.Ask
        assertEquals(now + 5_000, plan.expiresAtMs)
    }

    @Test
    fun `有出处的四行能解析成决策`() {
        val raw = """
            【问题】他从样本 A 推到全部人，中间还缺哪个条件？
            【判据】缺样本代表性
            【依据】1,2
            【动作】找条件
        """.trimIndent()
        val parsed = CoachReply.parse(raw, allowedIds = setOf(1L, 2L), epoch = 1, expiresAtMs = 99_000)
        assertTrue(parsed is CoachParse.Ok)
        val d = (parsed as CoachParse.Ok).decision
        assertEquals("他从样本 A 推到全部人，中间还缺哪个条件？", d.question)
        assertEquals(listOf(1L, 2L), d.evidenceIds)
        assertEquals(CoachAction.ASK, d.action)
    }

    @Test
    fun `依据对不上材料就不接受`() {
        val raw = """
            【问题】这里是不是有矛盾？
            【判据】前后不一致
            【依据】99
        """.trimIndent()
        val parsed = CoachReply.parse(raw, allowedIds = setOf(1L), epoch = 1, expiresAtMs = 1L)
        assertTrue(parsed is CoachParse.Reject)
        assertTrue((parsed as CoachParse.Reject).reason.contains("依据"))
    }

    @Test
    fun `判据混进问题就是泄题，不接受`() {
        val raw = """
            【问题】你说他是不是样本代表性不足？
            【判据】样本代表性不足
            【依据】1
        """.trimIndent()
        val parsed = CoachReply.parse(raw, allowedIds = setOf(1L), epoch = 1, expiresAtMs = 1L)
        assertTrue(parsed is CoachParse.Reject)
        assertTrue((parsed as CoachParse.Reject).reason.contains("先给答案"))
    }

    @Test
    fun `不是问句不接受`() {
        val raw = """
            【问题】这里有个条件没说
            【判据】缺条件
            【依据】1
        """.trimIndent()
        assertTrue(CoachReply.parse(raw, setOf(1L), 1, 1L) is CoachParse.Reject)
    }

    @Test
    fun `完全不成格式也转成继续听`() {
        val parsed = CoachReply.parse("好的，我问你一个问题", setOf(1L), 1, 2L)
        val d = parsed.asDecision(epoch = 1, expiresAtMs = 2L)
        assertEquals(CoachAction.WAIT, d.action)
        assertEquals(1, d.epoch)
    }

    @Test
    fun `判定解析出三种结果`() {
        assertEquals(Verdict.HIT, (JudgeReply.parse("【判定】到了\n【差值】—") as JudgeParse.Ok).verdict)
        assertEquals(Verdict.NEAR, (JudgeReply.parse("【判定】差一点\n【差值】少说了条件") as JudgeParse.Ok).verdict)
        assertEquals(Verdict.MISS, (JudgeReply.parse("【判定】没到\n【差值】只复述") as JudgeParse.Ok).verdict)
    }

    @Test
    fun `判定解析不出来就是没判成，不算答错`() {
        assertEquals(JudgeParse.Failed, JudgeReply.parse("他说得不错"))
        assertEquals(JudgeParse.Failed, JudgeReply.parse(""))
        assertFalse(Verdict.NONE.counted)
    }

    @Test
    fun `判据短到六个字以下不算泄题`() {
        assertFalse(CoachReply.leaked("样本代表谁？", "抽样"))
        assertTrue(CoachReply.leaked("这个抽样方式没有代表性吗？", "抽样方式没有代表性"))
    }
}
