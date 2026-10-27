package com.yishou.app.card

import com.yishou.app.FakeCoachDao
import com.yishou.app.FakeDao
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardDao
import com.yishou.app.data.CardMode
import com.yishou.app.data.CardRow
import com.yishou.app.data.CardSource
import com.yishou.app.data.SetStatus
import com.yishou.app.llm.CardGen
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sample(skill: Skill = Skill.MAIN, answer: Int = 1) = Card(
    skill = skill,
    difficulty = 1,
    sentences = listOf("行道树能遮阴。", "有人认为行道树只是装饰。", "但行道树还能降噪、吸尘。", "因此城市应多种行道树。"),
    question = "这段文字意在说明：",
    options = listOf("行道树能遮阴", "城市应多种行道树", "行道树只是装饰", "所有树都能降噪"),
    answer = answer,
    evidence = setOf(4),
    flaws = (0..3).filter { it != answer }.associateWith { listOf("范围扩大", "与原文相反", "绝对化")[it % 3] },
    explain = "第④句是结论。",
)

class FakeCardDao : CardDao() {
    val cards = mutableListOf<CardRow>()
    val attempts = mutableListOf<CardAttempt>()
    override suspend fun insertCard(card: CardRow): Long {
        val id = cards.size + 1L
        cards += card.copy(id = id)
        return id
    }
    override suspend fun updateCard(card: CardRow) {
        cards.replaceAll { if (it.id == card.id) card else it }
    }
    override suspend fun getCard(id: Long) = cards.firstOrNull { it.id == id }
    override suspend fun unused() = cards.filter { it.usedAt == null && it.verified }
    override suspend fun recent(n: Int) = cards.takeLast(n).reversed()
    override suspend fun unusedBank() = cards.filter { it.usedAt == null && it.source == CardSource.BANK }
    override fun observeBank(): Flow<List<CardRow>> = emptyFlow()
    override suspend fun insertAttempt(attempt: CardAttempt): Long {
        val id = attempts.size + 1L
        attempts += attempt.copy(id = id)
        return id
    }
    override suspend fun updateAttempt(attempt: CardAttempt) {
        attempts.replaceAll { if (it.id == attempt.id) attempt else it }
    }
    override suspend fun attemptsSince(since: Long) = attempts.filter { it.createdAt >= since }
    override fun observeAttemptsSince(since: Long): Flow<List<CardAttempt>> = emptyFlow()
    override suspend fun attemptsBetween(from: Long, to: Long) = attempts.filter { it.createdAt in from until to }
}

class FakeCardGen : CardGen {
    /** 每次出题的答案；核对时返回的答案 */
    val keys = ArrayDeque<Int>()
    val solves = ArrayDeque<Int>()
    var generated = 0
    var fail = false
    override suspend fun generate(skill: Skill, difficulty: Int, avoid: List<String>): LlmResult<Card> {
        if (fail) return LlmResult.Err(LlmError.Timeout)
        generated++
        return LlmResult.Ok(sample(skill, keys.removeFirstOrNull() ?: 1).copy(difficulty = difficulty))
    }
    override suspend fun solve(card: Card): LlmResult<Int> = LlmResult.Ok(solves.removeFirstOrNull() ?: card.answer)
    override suspend fun structure(text: String): LlmResult<Card> = LlmResult.Ok(sample(Skill.DETAIL, 2))
}

class CardTest {
    private var t = 10_000_000_000L
    private val day = 86_400_000L

    @Test
    fun gateDealsOnlyBankCardsWithAnswerAndEvidence() = runTest {
        val dao = FakeCardDao()
        val gen = FakeCardGen()
        val engine = CardEngine(dao, gen, clock = { t })
        val c = sample()
        dao.insertCard(CardRow(skill = c.skill.name, difficulty = 1, json = c.toJson(), verified = true, source = CardSource.AI, createdAt = t))
        dao.insertCard(CardRow(skill = c.skill.name, difficulty = 1, json = c.copy(evidence = emptySet()).toJson(), verified = true, source = CardSource.BANK, createdAt = t))
        dao.insertCard(CardRow(skill = c.skill.name, difficulty = 1, json = c.toJson(), verified = false, source = CardSource.BANK, createdAt = t))
        assertTrue(engine.dealGate() is LlmResult.Err)
        assertEquals(0, gen.generated)
        dao.insertCard(CardRow(skill = c.skill.name, difficulty = 1, json = c.toJson(), verified = true, source = CardSource.BANK, createdAt = t))
        val dealt = (engine.dealGate() as LlmResult.Ok).value
        assertEquals(CardSource.BANK, dealt.row.source)
        assertTrue(dealt.card.hasEvidence)
    }

    @Test
    fun gateHelpNeverRevealsAnswer() = runTest {
        val dao = FakeCardDao()
        val c = sample()
        dao.insertCard(CardRow(skill = c.skill.name, difficulty = 1, json = c.toJson(), verified = true, source = CardSource.BANK, createdAt = t))
        val session = CardSession(CardEngine(dao, FakeCardGen()), FakeDao(), FakeCoachDao())
        session.start(CardMode.GATE, null, 1)
        repeat(4) { session.hint() }
        assertEquals(2, session.state.value.round?.help)
        assertEquals(Step.BET, session.state.value.round?.step)
        assertFalse(session.state.value.done)
    }

    @Test
    fun parseValidatesAndRoundTrips() {
        val c = sample()
        assertEquals(c, Card.fromJson(c.toJson()))
        val bad = JSONObject(c.toJson()).put("answer", "E")
        assertThrows { Card.parse(bad) }
        // 宽容：错因不在表里记成“答非所问”，依据句超范围就当没有依据，都不再让整张题作废
        val badFlaw = JSONObject(c.toJson()).put("flaws", JSONObject().put("A", "不知道").put("C", "绝对化").put("D", "绝对化"))
        assertEquals("答非所问", Card.parse(badFlaw).flaws[0])
        val badEvidence = JSONObject(c.toJson()).put("evidence", org.json.JSONArray(listOf(9)))
        assertFalse(Card.parse(badEvidence).hasEvidence)
        assertThrows { Card.parse(JSONObject(c.toJson()).put("options", JSONObject().put("A", "x"))) }
        val numbered = JSONObject(c.toJson()).put("passage", org.json.JSONArray(listOf("①第一句", "②第二句")))
            .put("evidence", org.json.JSONArray(listOf(1)))
        assertEquals("第一句", Card.parse(numbered).sentences.first())
    }

    @Test
    fun fullRoundScoring() {
        var r = Round(sample(), RoundConfig(checks = 2, allowChange = true))
        r = r.evidence(setOf(4)) // 还没下注，不动
        assertEquals(Step.BET, r.step)
        r = r.bet(0, Conf.HIGH)
        assertEquals(Step.EVIDENCE, r.step)
        r = r.evidence(setOf(4))
        assertEquals(Step.CHECK, r.step)
        assertEquals(listOf(1, 2, 3), r.checkable)
        r = r.check(1, Flaws.NONE) // 查了正确项，说没毛病：对
        r = r.check(2, "与原文相反") // C 的错因是“绝对化”：错
        assertEquals(Step.CHANGE, r.step)
        assertEquals(1, r.checksOk)
        r = r.change(1)
        assertEquals(Step.REVEAL, r.step)
        assertFalse(r.firstCorrect)
        assertTrue(r.finalCorrect)
        assertTrue(r.changedRight)
        assertEquals("首答 ✗｜把握高 ✗｜依据 ✓｜多查 1/2｜改对", r.scoreLine())
    }

    @Test
    fun gateRoundAndHints() {
        var r = Round(sample(), RoundConfig(checks = 0, allowChange = false))
        r = r.hint()
        assertEquals("看第 ④ 句。", r.hintText())
        r = r.hint()
        assertTrue(r.hintText()!!.startsWith("先排除 A"))
        r = r.bet(1, Conf.LOW).evidence(setOf(2))
        assertEquals(Step.REVEAL, r.step)
        assertFalse(r.evidenceOk)
        assertFalse(r.independent)
        assertEquals("首答 ✓｜把握低 ✗｜依据 ✗｜帮助 2", r.scoreLine())
        val shown = Round(sample(), RoundConfig(0, false)).hint().hint().hint()
        assertEquals(Step.REVEAL, shown.step)
        assertEquals(3, shown.help)
    }

    @Test
    fun voiceParsing() {
        val a = VoiceAnswer.parse("我选B，挺有把握的，依据第三句")
        assertEquals(1, a.option)
        assertEquals(Conf.HIGH, a.conf)
        assertEquals(setOf(3), a.sentences)
        assertEquals(Conf.LOW, VoiceAnswer.parse("C吧，不太确定").conf)
        assertEquals(Conf.MID, VoiceAnswer.parse("A，中").conf)
        assertEquals(Conf.HIGH, VoiceAnswer.parse("d高").conf)
        assertEquals(3, VoiceAnswer.parse("d高").option)
        assertEquals(setOf(3, 4), VoiceAnswer.parse("三和四两句").sentences)
        assertEquals(setOf(2), VoiceAnswer.parse("②").sentences)
        assertEquals(setOf(10), VoiceAnswer.parse("第10句").sentences)
        assertEquals("绝对化", VoiceAnswer.parse("D太绝对了").flaw)
        assertEquals(3, VoiceAnswer.parse("D太绝对了").option)
        assertEquals(Flaws.NONE, VoiceAnswer.parse("B是对的").flaw)
        assertEquals(1, VoiceAnswer.parse("第二个选项").option)
        assertTrue(VoiceAnswer.parse("不改").keep)
        assertNull(VoiceAnswer.parse("我觉得是这样").option)
    }

    private fun att(skill: Skill, ok: Boolean, at: Long, card: Long = 1, level: Int = 1, help: Int = 0, checks: Int = 0, checksOk: Int = 0, difficulty: Int = 1, mode: String = CardMode.HOME) =
        CardAttempt(
            cardId = card, skill = skill.name, difficulty = difficulty, mode = mode, level = level, bet = 0, conf = "高",
            evidenceOk = true, checks = checks, checksOk = checksOk, final = 0, firstCorrect = ok, finalCorrect = ok,
            help = help, cause = "", durationMs = 1, verified = true, createdAt = at,
        )

    @Test
    fun progressRules() {
        val xs = listOf(att(Skill.DETAIL, true, 1), att(Skill.SPLIT, false, 2))
        assertEquals(Skill.MAIN, Progress.nextSkill(xs))
        val all = Skill.entries.mapIndexed { i, s -> att(s, i != 3, i.toLong()) }
        assertEquals(Skill.entries[3], Progress.nextSkill(all))

        val good = List(6) { att(Skill.MAIN, true, it.toLong(), difficulty = 1) }
        assertEquals(2, Progress.difficulty(good, Skill.MAIN))
        val bad = List(6) { att(Skill.MAIN, it == 0, it.toLong(), difficulty = 2) }
        assertEquals(1, Progress.difficulty(bad, Skill.MAIN))
        assertEquals(1, Progress.difficulty(emptyList(), Skill.MAIN))

        val checks = List(5) { att(Skill.MAIN, true, it.toLong(), checks = 1, checksOk = 1) }
        assertEquals(2, Progress.checks(checks))

        val climbing = List(8) { att(Skill.MAIN, it != 0, it.toLong(), level = 1) }
        assertEquals(2, Progress.level(climbing))
        val gateOnly = List(8) { att(Skill.MAIN, true, it.toLong(), level = 1, mode = CardMode.GATE) }
        assertEquals(1, Progress.level(gateOnly))
        val falling = List(6) { att(Skill.MAIN, it == 0, it.toLong(), level = 2) }
        assertEquals(1, Progress.level(falling))
    }

    @Test
    fun redoSchedule() {
        val miss = att(Skill.MAIN, false, 0, card = 7)
        assertEquals(emptyList<Long>(), Progress.dueRedo(listOf(miss), day - 1))
        assertEquals(listOf(7L), Progress.dueRedo(listOf(miss), day))
        val redo1 = att(Skill.MAIN, true, day, card = 7)
        assertEquals(emptyList<Long>(), Progress.dueRedo(listOf(miss, redo1), 3 * day))
        assertEquals(listOf(7L), Progress.dueRedo(listOf(miss, redo1), 4 * day))
        val redo2 = att(Skill.MAIN, true, 4 * day, card = 7)
        val redo3 = att(Skill.MAIN, true, 11 * day, card = 7)
        assertEquals(emptyList<Long>(), Progress.dueRedo(listOf(miss, redo1, redo2, redo3), 100 * day))
        // 靠帮助做对也算没掌握
        assertEquals(listOf(8L), Progress.dueRedo(listOf(att(Skill.MAIN, true, 0, card = 8, help = 1)), day))
        // 从没错过的不重做
        assertEquals(emptyList<Long>(), Progress.dueRedo(listOf(att(Skill.MAIN, true, 0, card = 9)), 9 * day))
    }

    @Test
    fun engineVerifiesAndPrefersRedo() = runTest {
        val dao = FakeCardDao()
        val gen = FakeCardGen()
        val e = CardEngine(dao, gen, clock = { t })
        // 第一张核对没对上，重出一张对上了
        gen.solves += listOf(2, 1)
        assertEquals(1, e.prefetch(1))
        assertEquals(2, gen.generated)
        assertEquals(1, dao.unused().size)

        val d = (e.deal(null) as LlmResult.Ok).value
        assertFalse(d.redo)
        val r = Round(d.card, RoundConfig(0, false)).bet(0, Conf.HIGH).evidence(setOf(4))
        e.record(d, r, CardMode.HOME, 1, 1000)
        assertTrue(dao.cards.single().usedAt != null)

        t += day
        val again = (e.deal(null) as LlmResult.Ok).value
        assertTrue(again.redo)
        assertEquals(d.row.id, again.row.id)

        gen.fail = true
        dao.attempts.clear()
        assertTrue(e.deal(Skill.GAP) is LlmResult.Err)
    }

    @Test
    fun importNeedsOfficialKey() = runTest {
        val dao = FakeCardDao()
        val e = CardEngine(dao, FakeCardGen(), clock = { t })
        val d = (e.import("题目文字") as LlmResult.Ok).value
        assertFalse(d.row.verified)
        assertTrue(dao.unused().isEmpty())
        val same = e.confirmKey(d, 2)
        assertTrue(same.row.verified)
        assertEquals(d.card.flaws, same.card.flaws)
        val diff = e.confirmKey(d, 0)
        assertEquals(0, diff.card.answer)
        assertTrue(diff.card.flaws.isEmpty())
    }

    @Test
    fun sessionFlowRecordsAttemptsRoundsAndSet() = runTest {
        val dao = FakeCardDao()
        val gen = FakeCardGen()
        val app = FakeDao()
        val coach = FakeCoachDao()
        val s = CardSession(CardEngine(dao, gen, clock = { t }), app, coach, clock = { t })
        s.start(CardMode.HOME, Skill.MAIN, size = 2)
        assertEquals(Step.BET, s.state.value.round?.step)
        assertEquals(1, s.state.value.round?.cfg?.checks)
        s.bet(1, Conf.HIGH)
        s.evidence(setOf(4))
        s.check(0, "范围扩大")
        s.change(null)
        assertEquals(1, s.state.value.results.size)
        assertTrue(s.state.value.results.single().firstCorrect)
        s.cause("粗心")
        assertEquals("粗心", dao.attempts.single().cause)
        assertFalse(s.state.value.done)
        s.next()
        s.bet(2, Conf.LOW)
        s.evidence(setOf(1))
        s.check(0, Flaws.NONE)
        s.change(null)
        assertTrue(s.state.value.done)
        assertEquals(2, app.rounds.size)
        assertEquals(listOf(true, false), app.rounds.map { it.effective })
        assertEquals(SetStatus.DONE, coach.sets.single().status)
        assertEquals("1/2 首答对", coach.sets.single().standard)

        // 入口思考页：一张、不多查、不改答案、不记组
        dao.insertCard(CardRow(skill = Skill.MAIN.name, difficulty = 1, json = sample().toJson(), verified = true, source = CardSource.BANK, createdAt = t))
        val g = CardSession(CardEngine(dao, gen, clock = { t }), app, coach, clock = { t })
        g.start(CardMode.GATE, null, size = 5)
        assertEquals(1, g.state.value.size)
        g.bet(1, Conf.MID)
        g.evidence(setOf(4))
        assertEquals(Step.REVEAL, g.state.value.round?.step)
        assertTrue(g.state.value.done)
        assertEquals(1, coach.sets.size)
    }

    @Test
    fun sessionPhotoCardWaitsForKey() = runTest {
        val dao = FakeCardDao()
        val e = CardEngine(dao, FakeCardGen(), clock = { t })
        val s = CardSession(e, FakeDao(), FakeCoachDao(), clock = { t })
        s.start(CardMode.HOME, null, 3)
        val d = (e.import("题") as LlmResult.Ok).value
        s.useDealt(d, checks = 0)
        s.bet(2, Conf.HIGH)
        s.evidence(setOf(4))
        assertTrue(s.state.value.needsKey)
        assertTrue(s.state.value.results.isEmpty())
        s.officialKey(2)
        assertTrue(s.state.value.results.single().verified)
        assertTrue(s.state.value.results.single().firstCorrect)
    }

    @Test
    fun homeMakesYouSayItFirstAndChasesTemplates() = runTest {
        fun session() = CardSession(CardEngine(FakeCardDao(), FakeCardGen(), clock = { t }), FakeDao(), FakeCoachDao(), clock = { t }, predictFirst = true)
        val g = session()
        g.start(CardMode.HOME, null, size = 1)
        assertTrue(g.state.value.mustPredict)
        // 说套话：先被追问，选项不亮
        g.predict("不知道")
        assertTrue(g.state.value.drillAsk != null)
        assertEquals(null, g.state.value.predicted)
        g.predict("文章很好")
        assertTrue(g.state.value.drillHint!!.contains("原文里找不到"))
        g.predict("意在说明")
        assertEquals(1, g.state.value.drillStep)
        g.predict("要说出作者最后主张城市该怎么做")
        assertEquals(null, g.state.value.drillAsk)
        assertTrue(g.state.value.predicted != null)
        // 一句话说中：靶心对了
        val h = session()
        h.start(CardMode.HOME, null, size = 1)
        h.predict("城市应该多种行道树")
        assertTrue(h.state.value.bullseye)
        assertEquals(null, h.state.value.drillAsk)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: BadCard) {
            return
        }
        throw AssertionError("应该抛出 BadCard")
    }
}
