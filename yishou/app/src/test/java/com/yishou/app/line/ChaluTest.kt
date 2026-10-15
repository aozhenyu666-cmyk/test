package com.yishou.app.line

import com.yishou.app.data.Echo
import com.yishou.app.data.EchoKind
import com.yishou.app.data.LineVerdict
import com.yishou.app.llm.CoachReply
import com.yishou.app.llm.LineCoach
import com.yishou.app.llm.LlmResult
import com.yishou.app.think.Logic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoesTest {

    private val h = 3_600_000L
    private fun e(id: Long, scene: String, at: Long, shownAt: Long? = null, confirmed: Int = 0) =
        Echo(id = id, text = "下次见到资料分析先估算再精算", kind = EchoKind.DELTA, scene = scene, createdAt = at, shownAt = shownAt, confirmed = confirmed)

    @Test
    fun sameSceneFirstThenLeastConfirmed() {
        val now = 1000 * h
        val all = listOf(e(1, "", 10), e(2, "com.a", 5), e(3, "com.b", 20, confirmed = 2), e(4, "", 30, confirmed = 1))
        assertEquals(2L, Echoes.pick(all, "com.a", now)?.id)
        // 没有这个场景的：确认最少里最新的
        assertEquals(1L, Echoes.pick(all, "com.zzz", now)?.id)
        assertEquals(1L, Echoes.pick(all, "", now)?.id)
    }

    @Test
    fun confirmedOnesRestLonger() {
        val now = 1000 * h
        assertTrue(Echoes.due(e(1, "", 0), now))
        assertFalse(Echoes.due(e(1, "", 0, shownAt = now - 2 * h), now))
        assertTrue(Echoes.due(e(1, "", 0, shownAt = now - 13 * h), now))
        assertFalse(Echoes.due(e(1, "", 0, shownAt = now - 13 * h, confirmed = 1), now))
        assertTrue(Echoes.due(e(1, "", 0, shownAt = now - 200 * h, confirmed = 9), now))
        // 全在歇着：不推
        assertNull(Echoes.pick(listOf(e(1, "com.a", 0, shownAt = now - h)), "com.a", now))
    }

    @Test
    fun readBackAndUsable() {
        val t = "下次见到资料分析先估算再精算"
        assertTrue(Echoes.readBack(t, "下次见到资料分析，先估算再精算"))
        assertFalse(Echoes.readBack(t, "嗯"))
        assertFalse(Echoes.readBack(t, ""))
        assertFalse(Echoes.usable("嗯嗯就是"))
        assertTrue(Echoes.usable("漏看了单位，下次先圈单位"))
    }
}

class LogicTest {

    @Test
    fun marksLongestFirstWithoutOverlap() {
        val t = "只有政策不变，才能涨；但是补贴并非长期的。"
        val ms = Logic.marks(t)
        val words = ms.map { t.substring(it.start, it.end) }
        assertTrue(words.contains("只有"))
        assertTrue(words.contains("但是"))
        assertTrue(words.contains("并非"))
        assertFalse(words.contains("但")) // 被“但是”吃掉
        assertEquals(Logic.Kind.TURN, ms.first { t.substring(it.start, it.end) == "但是" }.kind)
        ms.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
    }

    @Test
    fun clauseAndConditional() {
        val t = "房价会涨，只有当利率下降时才成立。"
        assertEquals("只有当利率下降时才成立", Logic.clauseAt(t, t.indexOf("利率")))
        assertEquals("房价会涨", Logic.clauseAt(t, 0))
        assertTrue(Logic.conditional("只有当利率下降，房价才会涨"))
        assertTrue(Logic.conditional("如果补贴退坡，销量就会跌"))
        assertFalse(Logic.conditional("房价会涨"))
        assertFalse(Logic.conditional("才怪，如果"))
    }

    @Test
    fun cutRulesWantACondition() {
        assertTrue(Talk.check("房价一定会涨，大家都这么说", TalkRules.CUT).violations.any { it.code == "cond" })
        assertTrue(Talk.check("只有当利率下降，房价才会涨", TalkRules.CUT).ok)
    }
}

/** 岔路的教练：每次回一句带坑的判断（或变条件的追问），记下收到的拍子 */
class TrapCoach : LineCoach {
    val beats = mutableListOf<Pair<String, String>>()
    val recalls = mutableListOf<String>()
    var caught = true
    override suspend fun reply(anchor: String, conclusions: List<String>, doubts: List<String>, recent: List<Pair<String, String>>, question: String, answer: String, face: Int): LlmResult<CoachReply> =
        error("岔路不该走六面问")

    override suspend fun trap(anchor: String, conclusions: List<String>, question: String, answer: String, beat: String, flaw: String, next: String, recall: String): LlmResult<CoachReply> {
        beats += beat to next
        recalls += recall
        return LlmResult.Ok(
            if (next == "TRAP") CoachReply(caught, answer.take(4), answer.take(10), "结论", "只要多刷题，资料分析就会提速", 1, "差一点", flaw = "只靠刷题不够，要先改算法")
            else CoachReply(caught, answer.take(4), answer.take(10), "结论", "如果考试时间缩短一半呢？", 6, ""),
        )
    }
}

class ChaluSessionTest {

    private val cond = "只有当速算先过关，资料分析才会提速"

    @Test
    fun beatsAlternateAndFlawIsKept() = runTest {
        val dao = FakeLineDao()
        val coach = TrapCoach()
        val s = LineSession(dao, coach, clock = { 1000L }, style = CoachStyle.TRAP, lenient = false, recall = { "上次的结论" })
        s.create("资料分析", "资料分析怎么提速", Level.LIGHT)
        assertEquals(Beat.OPEN, s.state.value.beat)
        assertTrue(s.state.value.question.contains("只有当"))

        s.answer(cond)
        assertEquals(Beat.TRAP, s.state.value.beat)
        assertTrue(s.state.value.question.startsWith(Lines.TRAP_PREFIX))
        assertEquals("只靠刷题不够，要先改算法", s.state.value.flaw)
        assertTrue(dao.lines.single().doubts.contains(Lines.FLAW))

        // 断点重开：拍子和漏洞都回来
        s.open(dao.lines.single(), Level.LIGHT)
        assertEquals(Beat.TRAP, s.state.value.beat)
        assertEquals("只靠刷题不够，要先改算法", s.state.value.flaw)

        s.answer("不对，只有当算法先改了，刷题才会提速")
        assertEquals(Beat.FLIP, s.state.value.beat)
        assertTrue(s.state.value.question.startsWith(Lines.FLIP_PREFIX))
        assertEquals("", s.state.value.flaw)
        assertEquals(listOf("OPEN" to "TRAP", "TRAP" to "FLIP"), coach.beats)
        assertEquals("上次的结论", coach.recalls.last())
    }

    @Test
    fun noLenienceAndCutKeepsRaw() = runTest {
        val dao = FakeLineDao()
        val coach = TrapCoach()
        val s = LineSession(dao, coach, clock = { 1000L }, style = CoachStyle.TRAP, lenient = false)
        s.create("t", "资料分析怎么提速", Level.LIGHT)
        // 不是条件句：退回三次也不放（主线第三次就放了）
        repeat(3) { s.answer("要多练速算，每天练半小时") }
        assertEquals(0, coach.beats.size)
        assertEquals(3, dao.moves.count { it.verdict == LineVerdict.THIN })

        s.answerCut("嗯我觉得吧就是速算很重要然后每天都要练练练，不然就会超时超时", cond)
        assertEquals(1, coach.beats.size)
        val m = dao.moves.last()
        assertEquals(cond, m.answer)
        assertTrue(m.note.contains("喷："))
        // 之后的手不再带着喷的原话
        s.answer("不对，只有当算法先改了，刷题才会提速")
        assertFalse(dao.moves.last().note.contains("喷："))
    }

    @Test
    fun anchorConclusionGoesToEcho() = runTest {
        val dao = FakeLineDao()
        val echoed = mutableListOf<String>()
        val s = LineSession(dao, TrapCoach(), clock = { 1000L }, style = CoachStyle.TRAP, lenient = false, onConclusion = { echoed += it })
        s.create("t", "资料分析怎么提速", Level.HARD)
        repeat(Lines.anchorEvery(Level.HARD)) { s.answer(cond) }
        assertEquals(Ask.ANCHOR, s.state.value.ask)
        s.answer(cond)
        assertEquals(1, echoed.size)
        assertEquals(Beat.TRAP, s.state.value.beat)
    }

    @Test
    fun offlineFlipsLocally() = runTest {
        val dao = FakeLineDao()
        val s = LineSession(dao, null, clock = { 1000L }, style = CoachStyle.TRAP, lenient = false)
        s.create("t", "资料分析怎么提速", Level.LIGHT)
        s.answer(cond)
        assertEquals(Beat.FLIP, s.state.value.beat)
        assertTrue(s.state.value.question.startsWith(Lines.FLIP_PREFIX))
        assertTrue(s.state.value.question.contains("反过来"))
    }
}
