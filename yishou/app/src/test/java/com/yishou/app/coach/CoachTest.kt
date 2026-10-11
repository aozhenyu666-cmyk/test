package com.yishou.app.coach

import com.yishou.app.FakeDao
import com.yishou.app.data.ChainRecall
import com.yishou.app.data.CoachDao
import com.yishou.app.data.MoveKind
import com.yishou.app.data.MoveVerdict
import com.yishou.app.data.SetMove
import com.yishou.app.data.SetStatus
import com.yishou.app.data.Task
import com.yishou.app.data.TrainSet
import com.yishou.app.llm.BadJsonException
import com.yishou.app.llm.HelpReply
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.MoveJudgement
import com.yishou.app.llm.SetCoach
import com.yishou.app.llm.SetContext
import com.yishou.app.llm.SetOpening
import com.yishou.app.llm.SetParser
import com.yishou.app.settings.AppPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FakeCoachDao : CoachDao() {
    val sets = mutableListOf<TrainSet>()
    val moveList = mutableListOf<SetMove>()
    val recalls = mutableListOf<ChainRecall>()

    override suspend fun insertSet(set: TrainSet): Long {
        val id = sets.size + 1L
        sets += set.copy(id = id)
        return id
    }
    override suspend fun updateSet(set: TrainSet) {
        sets.replaceAll { if (it.id == set.id) set else it }
    }
    override suspend fun activeSet() = sets.lastOrNull { it.status == SetStatus.ACTIVE }
    override suspend fun getSet(id: Long) = sets.firstOrNull { it.id == id }
    override suspend fun recentSets(n: Int) = sets.sortedByDescending { it.startedAt }.take(n)
    override fun observeSetsSince(since: Long): Flow<List<TrainSet>> = emptyFlow()
    override suspend fun insertMove(move: SetMove): Long {
        val id = moveList.size + 1L
        moveList += move.copy(id = id)
        return id
    }
    override suspend fun updateMove(move: SetMove) {
        moveList.replaceAll { if (it.id == move.id) move else it }
    }
    override suspend fun moves(setId: Long) = moveList.filter { it.setId == setId }
    override suspend fun movesSince(since: Long) = moveList.filter { it.createdAt >= since }
    override suspend fun insertRecall(recall: ChainRecall): Long {
        recalls += recall
        return recalls.size.toLong()
    }
    override suspend fun recallsSince(since: Long) = recalls.filter { it.at >= since }
}

class FakeSetCoach : SetCoach {
    val verdicts = ArrayDeque<String>()
    var lastNextKind: String? = null
    var lastGive = false
    var lastHelpLevel = -1
    var lastExplain = false
    var lastCtx: SetContext? = null

    override suspend fun open(ctx: SetContext): LlmResult<SetOpening> {
        lastCtx = ctx
        return LlmResult.Ok(SetOpening("今天练：接上一步", "面试15:00，车程30分钟", "算出安排并独立做一题", "最晚几点出门？", "几点出门"))
    }

    override suspend fun judge(set: TrainSet, chain: List<String>, move: SetMove, answer: String, nextKind: String, giveAnswer: Boolean): LlmResult<MoveJudgement> {
        lastNextKind = nextKind
        lastGive = giveAnswer
        val v = verdicts.removeFirst()
        return LlmResult.Ok(
            MoveJudgement(
                verdict = v,
                gap = if (v == MoveVerdict.PASS) "用上了到场要求" else "漏了提前15分钟",
                conclusion = if (v == MoveVerdict.PASS) "结论${move.idx}" else "",
                reason = "",
                correctStep = if (giveAnswer) "应为14:15" else "",
                nextQuestion = if (v == MoveVerdict.PASS && nextKind == CoachPlan.END) "" else "下一问${move.idx}",
                nextSay = "",
            ),
        )
    }

    override suspend fun help(set: TrainSet, chain: List<String>, move: SetMove, level: Int, explain: Boolean): LlmResult<HelpReply> {
        lastHelpLevel = level
        lastExplain = explain
        return LlmResult.Ok(HelpReply("看“提前15分钟”", ""))
    }
}

class CoachTest {
    private val task = Task(id = 1, title = "生活安排", goal = "", material = null, isCurrent = true, createdAt = 0)
    private var prefs = AppPrefs(coachSetMoves = 3)
    private var t = 1_000L

    private fun engine(cd: FakeCoachDao, d: FakeDao, c: FakeSetCoach) =
        SetEngine(cd, d, c, prefs = { prefs }, savePrefs = { f -> prefs = f(prefs) }, clock = { ++t })

    @Test
    fun fullSetWithRedoRelayVerifyAndLevelUp() = runTest {
        val cd = FakeCoachDao()
        val d = FakeDao()
        val c = FakeSetCoach()
        val e = engine(cd, d, c)
        e.open(task, face = 2, userMaterial = null)
        assertEquals(MoveKind.TRY, e.state.value.current?.kind)
        assertEquals(0, c.lastCtx?.level)

        // 第 1 手：先要求改，改过以后过了
        c.verdicts += listOf(MoveVerdict.REDO, MoveVerdict.PASS)
        e.answer("14:30", "home")
        val redo = e.state.value.current!!
        assertEquals(1, redo.idx)
        assertEquals(2, redo.attempt)
        assertEquals("下一问1", redo.question)
        assertEquals(MoveVerdict.REDO, e.state.value.feedback?.verdict)
        e.answer("14:15", "home")
        assertEquals(MoveKind.RELAY, c.lastNextKind)
        assertEquals(listOf("结论1"), e.state.value.chain)
        assertEquals(MoveKind.RELAY, e.state.value.current?.kind)

        // 第 2 手接力过了，下一手是独立验证
        c.verdicts += MoveVerdict.PASS
        e.answer("14:05", "home")
        assertEquals(MoveKind.VERIFY, e.state.value.current?.kind)

        // 第 3 手独立验证第一次就过：组结束，等级 +1
        c.verdicts += MoveVerdict.PASS
        e.answer("7:40 出门，留10分钟余量", "window")
        assertEquals(CoachPlan.END, c.lastNextKind)
        val s = e.state.value
        assertNull(s.set)
        assertEquals(SetStatus.DONE, s.ended?.status)
        assertEquals(1, s.ended?.verified)
        assertEquals(1, prefs.faceLevels[2])
        // 每一次判定都记一条 Round（重做那次算无效）
        assertEquals(listOf(false, true, true, true), d.rounds.map { it.effective })
        assertEquals("window", d.rounds.last().source)

        e.saveLesson("先列条件再倒推")
        assertEquals("先列条件再倒推", cd.sets.single().lesson)
        assertNull(e.state.value.ended)
    }

    @Test
    fun unclearDoesNotCountAndThirdAttemptShowsAnswer() = runTest {
        val cd = FakeCoachDao()
        val d = FakeDao()
        val c = FakeSetCoach()
        val e = engine(cd, d, c)
        e.open(task, 3, "我自己的材料")
        assertEquals("我自己的材料", c.lastCtx?.userMaterial)

        c.verdicts += MoveVerdict.UNCLEAR
        e.answer("听不清", "home")
        assertEquals(1, e.state.value.current?.attempt)
        assertTrue(d.rounds.isEmpty())

        c.verdicts += listOf(MoveVerdict.REDO, MoveVerdict.REDO, MoveVerdict.REDO)
        e.answer("a", "home")
        e.answer("b", "home")
        assertEquals(3, e.state.value.current?.attempt)
        e.answer("c", "home")
        assertTrue(c.lastGive)
        val shown = cd.moveList.first { it.verdict == MoveVerdict.SHOWN }
        assertEquals("应为14:15", shown.correctStep)
        assertEquals(2, e.state.value.current?.idx)
        assertEquals(MoveVerdict.SHOWN, e.state.value.feedback?.verdict)
    }

    @Test
    fun helpLadderAndDrop() = runTest {
        val cd = FakeCoachDao()
        val c = FakeSetCoach()
        val e = engine(cd, FakeDao(), c)
        e.open(task, 1, null)
        e.help(explain = true)
        assertTrue(c.lastExplain)
        assertEquals(1, c.lastHelpLevel)
        e.help(explain = false)
        assertEquals(2, c.lastHelpLevel)
        e.help(explain = false)
        e.help(explain = false)
        assertEquals(3, c.lastHelpLevel)
        assertEquals(3, e.state.value.current?.help)
        assertTrue(e.state.value.current!!.helpText.contains("提前15分钟"))
        e.drop()
        assertNull(e.state.value.set)
        assertEquals(SetStatus.DROPPED, cd.sets.single().status)
    }

    @Test
    fun reopenDropsOldActiveSetAndLoadRestores() = runTest {
        val cd = FakeCoachDao()
        val e = engine(cd, FakeDao(), FakeSetCoach())
        e.open(task, 1, null)
        e.open(task, 2, null)
        assertEquals(listOf(SetStatus.DROPPED, SetStatus.ACTIVE), cd.sets.map { it.status })
        val again = engine(cd, FakeDao(), FakeSetCoach())
        again.load()
        assertEquals(2, again.state.value.set?.face)
        assertEquals(1, again.state.value.moves.size)
    }

    @Test
    fun planRules() {
        val m = SetMove(setId = 1, idx = 1, kind = MoveKind.TRY, attempt = 1, question = "", say = "", createdAt = 0)
        assertEquals(MoveKind.RELAY, CoachPlan.nextKind(m, 5))
        assertEquals(MoveKind.VERIFY, CoachPlan.nextKind(m.copy(idx = 4, kind = MoveKind.RELAY), 5))
        assertEquals(CoachPlan.END, CoachPlan.nextKind(m.copy(kind = MoveKind.VERIFY), 5))
        assertFalse(CoachPlan.giveAnswer(m.copy(attempt = 2)))
        assertTrue(CoachPlan.giveAnswer(m.copy(attempt = 3)))

        val pass = m.copy(kind = MoveKind.VERIFY, verdict = MoveVerdict.PASS)
        assertEquals(2, CoachPlan.nextLevel(1, pass))
        assertEquals(3, CoachPlan.nextLevel(3, pass))
        assertEquals(1, CoachPlan.nextLevel(1, pass.copy(help = 1)))
        assertEquals(0, CoachPlan.nextLevel(1, pass.copy(verdict = MoveVerdict.SHOWN)))
        assertEquals(1, CoachPlan.nextLevel(1, null))

        val set = TrainSet(taskId = 1, face = 4, level = 0, goal = "", material = "", standard = "", startedAt = 5, status = SetStatus.DONE, verified = 0)
        assertEquals(4, CoachPlan.suggestFace(emptyMap(), listOf(set)))
        assertEquals(1, CoachPlan.suggestFace(mapOf(2 to 1), listOf(set.copy(verified = 1))))
        assertEquals(2, CoachPlan.suggestFace(mapOf(1 to 1, 3 to 1, 4 to 2, 5 to 1), listOf(set.copy(verified = 1))))
    }

    @Test
    fun stats() {
        fun mv(set: Long, idx: Int, kind: String, attempt: Int, verdict: String, help: Int = 0) =
            SetMove(setId = set, idx = idx, kind = kind, attempt = attempt, question = "", say = "", verdict = verdict, help = help, createdAt = 0)
        val moves = listOf(
            mv(1, 1, MoveKind.TRY, 1, MoveVerdict.REDO),
            mv(1, 1, MoveKind.TRY, 2, MoveVerdict.PASS),
            mv(1, 2, MoveKind.RELAY, 1, MoveVerdict.PASS),
            mv(1, 3, MoveKind.RELAY, 1, MoveVerdict.REDO, help = 1),
            mv(1, 3, MoveKind.RELAY, 2, MoveVerdict.PASS),
            mv(1, 4, MoveKind.VERIFY, 1, MoveVerdict.PASS),
            mv(2, 1, MoveKind.TRY, 1, MoveVerdict.PENDING),
        )
        val sets = listOf(TrainSet(id = 1, taskId = 1, face = 2, level = 0, goal = "", material = "", standard = "", startedAt = 0, status = SetStatus.DONE))
        val s = CoachStats.of(sets, moves, listOf(ChainRecall(setId = 1, at = 0, total = 3, recalled = 2, awaySeconds = 90)))
        assertEquals(1, s.sets)
        assertEquals("1/1", CoachStats.ratio(s.independent, s.verified))
        assertEquals("1/2", CoachStats.ratio(s.relayFirst, s.relays))
        assertEquals("2/2", CoachStats.ratio(s.fixed, s.redone))
        assertEquals("2/3", CoachStats.ratio(s.recalled, s.recallTotal))
        assertEquals(1, s.helped)
        assertEquals(4, s.moves)
        assertEquals("-", CoachStats.ratio(0, 0))
    }

    @Test
    fun warmupChainStaysInRange() {
        repeat(200) { seed ->
            val c = Warmup.chain(Random(seed))
            var v = c.start
            c.steps.forEach { st ->
                val n = st.op.drop(1).toInt()
                v = when (st.op[0]) {
                    '+' -> v + n
                    '−' -> v - n
                    '×' -> v * n
                    else -> v / n
                }
                assertEquals(v, st.result)
                assertTrue(v in 1..99)
            }
        }
    }

    @Test
    fun parser() {
        val j = SetParser.parseJudgement("""{"verdict":"Redo","gap":"漏了条件","next_question":"只改出门时间"}""", MoveKind.RELAY)
        assertEquals(MoveVerdict.REDO, j.verdict)
        val end = SetParser.parseJudgement("""{"verdict":"pass","conclusion":"最晚14:15出门","next_question":""}""", CoachPlan.END)
        assertEquals("最晚14:15出门", end.conclusion)
        assertThrows { SetParser.parseJudgement("""{"verdict":"pass","next_question":""}""", MoveKind.RELAY) }
        assertThrows { SetParser.parseJudgement("""{"verdict":"good","next_question":"x"}""", MoveKind.RELAY) }
        val o = SetParser.parseOpening("""```json
            {"goal":"今天练：预判","material":"材料","standard":"","question":"第一问","say":"问"}
            ```""")
        assertEquals("第一问", o.question)
        assertThrows { SetParser.parseOpening("""{"goal":"g","material":"m"}""") }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: BadJsonException) {
            return
        }
        throw AssertionError("应该抛出 BadJsonException")
    }
}
