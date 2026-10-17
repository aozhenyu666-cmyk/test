package com.yishou.app.soup

import com.yishou.app.card.Card
import com.yishou.app.card.Skill
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.SoupPrompts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeHost : SoupJudge {
    val asked = mutableListOf<String>()
    var replies = ArrayDeque<Reply>()
    var truthSeen = ""
    override suspend fun ask(surface: String, truth: String, log: List<QA>, q: String): LlmResult<Reply> {
        asked += q
        truthSeen = truth
        return LlmResult.Ok(replies.removeFirstOrNull() ?: Reply.NO)
    }
    override suspend fun guess(surface: String, truth: String, g: String): LlmResult<Reply> =
        LlmResult.Ok(if ("返乡" in g) Reply.SOLVED else Reply.WRONG)
}

class SoupTest {

    private val card = Card(
        Skill.GAP, 2,
        listOf("县城新开的咖啡店，开在新区的大多活过了一年。", "开在老城区的，半年内关了一半。"),
        "以下哪项最能解释上述现象？",
        listOf("新区租金更低", "新区居民多是返乡青年，有喝咖啡的习惯", "老城区交通拥堵", "咖啡豆涨价"),
        1, setOf(1), emptyMap(), "客群决定存活。",
    )

    @Test
    fun surfaceHidesOptionsAndTruthHasAnswer() {
        val s = Soups.surface(card)
        assertTrue(s.contains("最能解释"))
        assertFalse(s.contains("返乡青年"))
        assertTrue(Soups.truth(card).startsWith("正确答案：B"))
        assertEquals(2, Soups.score(card))
        assertEquals(1, Soups.score(card.copy(question = "这段话主要说的是？")))
        assertEquals(0, Soups.score(card.copy(sentences = emptyList())))
    }

    @Test
    fun mustAskBeforeOptions() = runTest {
        val host = FakeHost()
        val s = SoupSession(host)
        s.start(1, card)
        s.open()
        assertEquals(SoupPhase.ASK, s.state.value.phase)
        host.replies += listOf(Reply.YES, Reply.NOT_YN, Reply.NO, Reply.IRRELEVANT)
        s.ask("是因为租金吗")
        s.ask("为什么")
        s.ask("是因为客人不一样吗")
        // “这不是是非题”不算一问
        assertEquals(2, s.state.value.asks)
        s.open()
        assertEquals(SoupPhase.ASK, s.state.value.phase)
        s.ask("和交通有关吗")
        s.open()
        assertEquals(SoupPhase.OPTIONS, s.state.value.phase)
        s.pick(1)
        assertEquals(true, s.state.value.correct)
        assertTrue(host.truthSeen.contains("返乡青年"))
    }

    @Test
    fun repeatedQuestionIsFree() = runTest {
        val host = FakeHost()
        val s = SoupSession(host)
        s.start(1, card)
        s.ask("是因为租金吗")
        s.ask("是因为租金吗？")
        assertEquals(1, host.asked.size)
        assertTrue(s.state.value.error!!.startsWith("问过了"))
    }

    @Test
    fun guessingTheBottomOpensOptions() = runTest {
        val s = SoupSession(FakeHost())
        s.start(1, card)
        s.guess("新区住的人不一样")
        assertEquals(SoupPhase.ASK, s.state.value.phase)
        s.guess("新区多是返乡青年，本来就喝咖啡")
        assertTrue(s.state.value.solved)
        assertEquals(SoupPhase.OPTIONS, s.state.value.phase)
        assertEquals(2, s.state.value.guesses)
    }

    @Test
    fun budgetRunsOut() = runTest {
        val s = SoupSession(FakeHost())
        s.start(1, card)
        repeat(Soups.BUDGET) { s.ask("第 $it 个问题是不是") }
        assertEquals(SoupPhase.OPTIONS, s.state.value.phase)
        assertEquals(0, s.state.value.left)
    }

    @Test
    fun noHostSaysSo() = runTest {
        val s = SoupSession(null)
        s.start(1, card)
        s.ask("是因为租金吗")
        assertTrue(s.state.value.error!!.contains("ChatGPT"))
        s.backFromOutside()
        assertEquals(SoupPhase.OPTIONS, s.state.value.phase)
    }

    @Test
    fun errorsKeepTheQuestion() = runTest {
        val host = object : SoupJudge {
            override suspend fun ask(surface: String, truth: String, log: List<QA>, q: String): LlmResult<Reply> = LlmResult.Err(LlmError.Local("没网"))
            override suspend fun guess(surface: String, truth: String, g: String): LlmResult<Reply> = LlmResult.Err(LlmError.Local("没网"))
        }
        val s = SoupSession(host)
        s.start(1, card)
        s.ask("是因为租金吗")
        assertEquals(0, s.state.value.asks)
        assertEquals("没网", s.state.value.error)
    }

    @Test
    fun hostRepliesAreOnlyFixedWords() {
        assertEquals(Reply.YES, Soups.parseAsk("是"))
        assertEquals(Reply.NO, Soups.parseAsk("不是。"))
        assertEquals(Reply.PARTLY, Soups.parseAsk("部分是"))
        assertEquals(Reply.IRRELEVANT, Soups.parseAsk("无关"))
        assertEquals(Reply.NOT_YN, Soups.parseAsk("这不是是非题"))
        assertNull(Soups.parseAsk("汤底是返乡青年喜欢喝咖啡"))
        assertEquals(Reply.SOLVED, Soups.parseGuess("猜中"))
        assertEquals(Reply.WRONG, Soups.parseGuess("不对"))
        assertTrue(SoupPrompts.askUser("面", "底", listOf(QA("租金吗", Reply.NO)), "客人吗").contains("租金吗→不是"))
    }

    @Test
    fun handoffHasNoAnswer() {
        val p = Soups.handoff(Soups.surface(card))
        assertTrue(p.contains("海龟汤"))
        assertFalse(p.contains("返乡青年"))
    }

    @Test
    fun statsLine() {
        fun s(asks: Int, solved: Boolean, correct: Boolean?) = com.yishou.app.data.Soup(cardId = 1, asks = asks, guesses = 0, solved = solved, correct = correct, createdAt = 0)
        val line = Soups.line(Soups.stats(listOf(s(4, true, true), s(8, false, false), s(-1, false, true))))
        assertTrue(line.contains("煮过 3 锅"))
        assertTrue(line.contains("平均 6 问"))
        assertTrue(line.contains("选对 66%"))
    }
}
