package com.yishou.app.line

import com.yishou.app.bank.BankImport
import com.yishou.app.bank.BankParser
import com.yishou.app.bank.DocText
import com.yishou.app.card.AntiRandom
import com.yishou.app.card.CardEngine
import com.yishou.app.card.FakeCardDao
import com.yishou.app.card.FakeCardGen
import com.yishou.app.data.Line
import com.yishou.app.data.LineDao
import com.yishou.app.data.Move
import com.yishou.app.data.LineVerdict
import com.yishou.app.llm.CoachReply
import com.yishou.app.llm.LineCoach
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.think.FakeThinkDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkTest {

    @Test
    fun fillerIsRejected() {
        val c = Talk.check("嗯，就是那个，我觉得吧，然后就是那个，可能大概就是要多读书吧", TalkRules.of(Level.MID))
        assertFalse(c.ok)
        assertTrue(c.violations.any { it.code == "filler" })
    }

    @Test
    fun conclusionFirst() {
        val bad = Talk.check("嗯那个，因为最近几次考试资料分析都超时，所以我觉得应该先练速算", TalkRules.of(Level.MID))
        assertTrue(bad.violations.any { it.code == "lead" })
        val good = Talk.check("先练速算，因为资料分析每次都超时，速算能省一半时间", TalkRules.of(Level.MID))
        assertTrue(good.ok)
    }

    @Test
    fun longerNeedsStructure() {
        val long = "先练速算" + "资料分析每次都超时题目很多做不完时间不够用".repeat(5)
        val c = Talk.check(long, TalkRules.of(Level.HARD))
        assertTrue(c.violations.any { it.code == "long" || it.code == "structure" })
        assertTrue(Talk.check("先练速算，因为每次都超时", TalkRules.of(Level.LIGHT)).ok)
    }

    @Test
    fun refusalsAndLadder() {
        assertTrue(Resist.refused("不知道"))
        assertTrue(Resist.refused("嗯……"))
        assertTrue(Resist.refused("随便吧"))
        assertFalse(Resist.refused("我不知道具体数字，但应该是先涨后跌，因为补贴退坡"))
        assertEquals(Ask.SMALLER, Resist.rung(1))
        assertEquals(Ask.CHOOSE, Resist.rung(2))
        assertEquals(Ask.GUESS, Resist.rung(3))
        assertNull(Resist.rung(4))
        assertTrue(Resist.prompt(Ask.SMALLER, "资料分析提速", 2).contains("资料分析提速"))
    }
}

class FakeLineDao : LineDao() {
    val lines = mutableListOf<Line>()
    val moves = mutableListOf<Move>()
    override suspend fun insertLine(l: Line): Long {
        lines += l.copy(id = lines.size + 1L)
        return lines.size.toLong()
    }
    override suspend fun updateLine(l: Line) {
        lines.replaceAll { if (it.id == l.id) l else it }
    }
    override suspend fun getLine(id: Long) = lines.firstOrNull { it.id == id }
    override suspend fun latestOpen() = lines.filter { it.closedAt == null }.maxByOrNull { it.updatedAt }
    override fun observeLines(): Flow<List<Line>> = emptyFlow()
    override suspend fun insertMove(m: Move): Long {
        moves += m.copy(id = moves.size + 1L)
        return moves.size.toLong()
    }
    override suspend fun recentMoves(lineId: Long, n: Int) = moves.filter { it.lineId == lineId }.takeLast(n).reversed()
    override fun observeMoves(lineId: Long): Flow<List<Move>> = emptyFlow()
    override fun observeMovesSince(since: Long): Flow<List<Move>> = emptyFlow()
    override suspend fun movesBetween(from: Long, to: Long) = moves.filter { it.createdAt in from until to }
}

class FakeLineCoach : LineCoach {
    var replies = ArrayDeque<LlmResult<CoachReply>>()
    var calls = 0
    override suspend fun reply(anchor: String, conclusions: List<String>, doubts: List<String>, recent: List<Pair<String, String>>, question: String, answer: String, face: Int): LlmResult<CoachReply> {
        calls++
        return replies.removeFirstOrNull() ?: LlmResult.Ok(CoachReply(true, answer.take(6), "收下", "结论", "下一问是什么", (face % 6) + 1, ""))
    }
}

class LineSessionTest {

    @Test
    fun goodMoveAdvancesAndSavesBreakpoint() = runTest {
        val dao = FakeLineDao()
        val coach = FakeLineCoach()
        val s = LineSession(dao, coach, clock = { 1000L })
        s.create("资料分析", "资料分析怎么提速", Level.MID)
        coach.replies += LlmResult.Ok(CoachReply(true, "先练速算", "先练速算", "结论", "速算练哪一类？", 2, ""))
        s.answer("先练速算，因为每次都超时在计算上")
        val st = s.state.value
        assertEquals("速算练哪一类？", st.question)
        assertEquals(1, st.caught)
        assertEquals("速算练哪一类？", dao.lines.single().nextQuestion)
        assertTrue(dao.lines.single().conclusions.contains("先练速算"))
        assertEquals(LineVerdict.OK, dao.moves.single().verdict)
    }

    @Test
    fun fillerIsSentBackWithoutCallingCoach() = runTest {
        val dao = FakeLineDao()
        val coach = FakeLineCoach()
        val s = LineSession(dao, coach, clock = { 1000L })
        s.create("t", "资料分析怎么提速", Level.MID)
        val q = s.state.value.question
        s.answer("嗯就是那个，我觉得吧，然后就是那个那个，可能大概要练吧")
        assertEquals(0, coach.calls)
        assertEquals(q, s.state.value.question)
        assertTrue(s.state.value.say.contains(q))
        assertEquals(LineVerdict.THIN, dao.moves.last().verdict)
    }

    @Test
    fun refusalLadderThenPark() = runTest {
        val dao = FakeLineDao()
        val s = LineSession(dao, FakeLineCoach(), clock = { 1000L })
        s.create("t", "资料分析怎么提速", Level.MID)
        s.answer("不知道")
        assertEquals(Ask.SMALLER, s.state.value.ask)
        s.answer("随便")
        assertEquals(Ask.CHOOSE, s.state.value.ask)
        s.answer("")
        assertEquals(Ask.GUESS, s.state.value.ask)
        s.answer("不想说")
        assertTrue(s.state.value.parked)
        assertEquals(Level.LIGHT, s.state.value.level)
        assertEquals(4, dao.moves.count { it.verdict == LineVerdict.REFUSED })
        s.resume()
        assertFalse(s.state.value.parked)
    }

    @Test
    fun smallerQuestionAcceptsOneWord() = runTest {
        val coach = FakeLineCoach()
        val s = LineSession(FakeLineDao(), coach, clock = { 1000L })
        s.create("t", "资料分析怎么提速", Level.HARD)
        s.answer("不知道")
        s.answer("速算")
        assertEquals(Ask.NORMAL, s.state.value.ask)
        assertEquals(1, coach.calls)
    }

    @Test
    fun driftIsSentBackOnce() = runTest {
        val coach = FakeLineCoach()
        val s = LineSession(FakeLineDao(), coach, clock = { 1000L })
        s.create("t", "资料分析怎么提速", Level.MID)
        val q = s.state.value.question
        coach.replies += LlmResult.Ok(CoachReply(false, "", "", "结论", "x", 2, "你在说申论，不是资料分析"))
        s.answer("申论要多背范文，因为阅卷看规范表述")
        assertEquals(q, s.state.value.question)
        assertTrue(s.state.value.say.contains("申论"))
    }

    @Test
    fun anchorEveryFewMovesAndOfflineFallback() = runTest {
        val s = LineSession(FakeLineDao(), null, clock = { 1000L })
        s.create("t", "资料分析怎么提速", Level.HARD)
        repeat(3) { s.answer("先练速算，因为每次都超时在计算上") }
        assertEquals(Ask.ANCHOR, s.state.value.ask)
        assertTrue(s.state.value.question.startsWith("回到主线"))
    }

    @Test
    fun quoteMustBeInAnswer() = runTest {
        val dao = FakeLineDao()
        val coach = FakeLineCoach()
        val s = LineSession(dao, coach, clock = { 1000L })
        s.create("t", "资料分析怎么提速", Level.MID)
        coach.replies += LlmResult.Ok(CoachReply(true, "编出来的话", "编出来的提炼", "结论", "下一问", 2, ""))
        s.answer("先练速算，因为每次都超时在计算上")
        assertFalse(dao.lines.single().conclusions.contains("编出来的提炼"))
        assertTrue(dao.lines.single().conclusions.contains("先练速算"))
    }

    @Test
    fun coachDownStillMoves() = runTest {
        val coach = FakeLineCoach()
        coach.replies += LlmResult.Err(LlmError.Timeout)
        val s = LineSession(FakeLineDao(), coach, clock = { 1000L })
        s.create("t", "资料分析怎么提速", Level.MID)
        s.answer("先练速算，因为每次都超时在计算上")
        assertEquals(1, s.state.value.caught)
        assertTrue(s.state.value.question.contains("资料分析怎么提速"))
    }

    @Test
    fun todayLine() {
        fun m(v: String) = Move(lineId = 1, question = "", answer = "", ask = "NORMAL", verdict = v, face = 1, createdAt = 0)
        val t = Lines.today(listOf(m(LineVerdict.OK), m(LineVerdict.THIN), m(LineVerdict.REFUSED)))
        assertEquals(1, t.caught)
        assertTrue(Lines.line(t).contains("退回 1 次"))
    }
}

class ImportFixTest {

    @Test
    fun sameStemDifferentQuestionsAreKept() = runTest {
        val text = (1..5).joinToString("\n---\n") { i ->
            "【材料】第${i}段材料说的是第${i}件事。\n【题目】这段文字意在说明：\nA. 甲$i\nB. 乙$i\nC. 丙$i\nD. 丁$i\n【答案】A"
        }
        val engine = CardEngine(FakeCardDao(), FakeCardGen(), clock = { 1L })
        val r = BankImport(engine, FakeThinkDao(), clock = { 1L }).import(text)
        assertEquals(5, r.choices)
        assertEquals(5, BankParser.estimate(text))
    }

    @Test
    fun wordAutoNumbering() {
        val numbering = """
            <w:numbering>
            <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
            <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="upperLetter"/><w:lvlText w:val="%2."/></w:lvl></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            </w:numbering>
        """.trimIndent()
        fun p(text: String, ilvl: Int) = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"$ilvl\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"
        val doc = "<w:body>" + p("第一题问什么", 0) + listOf("甲", "乙", "丙", "丁").joinToString("") { p(it, 1) } +
            "<w:p><w:r><w:t>答案：B</w:t></w:r></w:p>" + p("第二题问什么", 0) + listOf("一", "二", "三", "四").joinToString("") { p(it, 1) } + "</w:body>"
        val text = DocText.fromDocx(doc, numbering)
        assertTrue(text.contains("1. 第一题问什么"))
        assertTrue(text.contains("B. 乙"))
        assertTrue(text.contains("2. 第二题问什么"))
        assertTrue(text.contains("A. 一"))
        val items = BankParser.parse(text)
        assertEquals(2, items.size)
        assertEquals("C", DocText.format(3, "upperLetter"))
        assertEquals("三", DocText.format(3, "chineseCounting"))
    }
}

class AntiRandomTest {

    @Test
    fun closestAndRushing() {
        val card = com.yishou.app.card.Card(
            skill = com.yishou.app.card.Skill.MAIN, difficulty = 1, sentences = listOf("行道树能遮阴。"), question = "意在说明：",
            options = listOf("行道树能遮阴", "城市应多种行道树", "行道树只是装饰", "所有树都能降噪"),
            answer = 1, evidence = setOf(1), flaws = emptyMap(), explain = "",
        )
        assertEquals(1, AntiRandom.closest("城市要多种树", card))
        assertNull(AntiRandom.closest("完全无关的话", card))
        assertTrue(AntiRandom.minReadMs(card) >= 4_000)
        fun a(ok: Boolean, ms: Long) = com.yishou.app.data.CardAttempt(
            cardId = 1, skill = "MAIN", difficulty = 1, mode = "home", level = 1, bet = 0, conf = "高", evidenceOk = true, checks = 0, checksOk = 0,
            final = 0, firstCorrect = ok, finalCorrect = ok, help = 0, cause = "", durationMs = ms, verified = true, createdAt = 0,
        )
        assertTrue(AntiRandom.rushing(listOf(a(false, 2000), a(false, 3000), a(false, 1500))))
        assertFalse(AntiRandom.rushing(listOf(a(false, 2000), a(true, 3000), a(false, 1500))))
        assertNotNull(card)
    }
}
