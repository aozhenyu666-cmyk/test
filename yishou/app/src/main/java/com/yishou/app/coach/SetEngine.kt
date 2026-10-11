package com.yishou.app.coach

import com.yishou.app.data.AppDao
import com.yishou.app.data.ChainRecall
import com.yishou.app.data.CoachDao
import com.yishou.app.data.MoveKind
import com.yishou.app.data.MoveVerdict
import com.yishou.app.data.Round
import com.yishou.app.data.SetMove
import com.yishou.app.data.SetStatus
import com.yishou.app.data.Task
import com.yishou.app.data.TrainSet
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.Prompts
import com.yishou.app.llm.SetCoach
import com.yishou.app.llm.SetContext
import com.yishou.app.settings.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 刚判完的那一手，界面上显示一会儿（过了、再改、没说清） */
data class Feedback(
    val verdict: String,
    val gap: String,
    val conclusion: String = "",
    val correctStep: String = "",
)

data class CoachState(
    val loaded: Boolean = false,
    val set: TrainSet? = null,
    val moves: List<SetMove> = emptyList(),
    /** 正在做的事，界面显示“教练在……” */
    val busy: Busy? = null,
    val error: String? = null,
    val feedback: Feedback? = null,
    /** 刚结束的一组（显示组末小结，等用户写“练会了什么”） */
    val ended: TrainSet? = null,
    val endedMoves: List<SetMove> = emptyList(),
) {
    /** 等着回答的那一手 */
    val current: SetMove? get() = moves.lastOrNull()?.takeIf { it.verdict == MoveVerdict.PENDING }
    val chain: List<String> get() = CoachPlan.chain(moves)
}

enum class Busy { OPENING, JUDGING, HELPING }

/**
 * 教练组：开组 → 首次尝试 → 判定 →（重做 | 接力）→ … → 独立验证 → 收组。
 * 每一手判完都写进数据库，也照旧记一条 Round，分组训练、回响、每晚总结接着用。
 * 主页和陪练窗口共用这一个。
 */
class SetEngine(
    private val coachDao: CoachDao,
    private val dao: AppDao,
    private val coach: SetCoach,
    private val prefs: () -> AppPrefs,
    private val savePrefs: ((AppPrefs) -> AppPrefs) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(CoachState())
    val state: StateFlow<CoachState> = _state.asStateFlow()
    private val lock = Mutex()

    sealed interface Outcome {
        data object Ok : Outcome
        data object Busy : Outcome
        data class Failed(val message: String) : Outcome
        data class Judged(val verdict: String, val feedback: Feedback, val next: SetMove?, val ended: TrainSet?) : Outcome
    }

    /** 启动时把没练完的一组接上 */
    suspend fun load() {
        val set = coachDao.activeSet()
        _state.update { it.copy(loaded = true, set = set, moves = set?.let { s -> coachDao.moves(s.id) }.orEmpty()) }
    }

    /** 下一组建议主练哪一面 */
    suspend fun suggestFace(): Int = CoachPlan.suggestFace(prefs().faceLevels, coachDao.recentSets(10))

    suspend fun open(task: Task, face: Int, userMaterial: String?, warmup: String = ""): Outcome {
        if (!lock.tryLock()) return Outcome.Busy
        try {
            val f = face.coerceIn(CoachPlan.FACES)
            val level = prefs().faceLevels[f] ?: 0
            val lessons = coachDao.recentSets(5).map { it.lesson }.filter { it.isNotBlank() }
            _state.update { it.copy(busy = Busy.OPENING, error = null, ended = null, feedback = null) }
            val ctx = SetContext(task, f, level, prefs().coachSetMoves, userMaterial?.takeIf { it.isNotBlank() }, lessons)
            return when (val r = coach.open(ctx)) {
                is LlmResult.Err -> fail(r.error.message)
                is LlmResult.Ok -> {
                    // 还有没练完的旧组就收起来
                    coachDao.activeSet()?.let { coachDao.updateSet(it.copy(status = SetStatus.DROPPED, endedAt = clock())) }
                    val now = clock()
                    val set0 = TrainSet(
                        taskId = task.id, face = f, level = level,
                        goal = r.value.goal, material = r.value.material, standard = r.value.standard,
                        startedAt = now, status = SetStatus.ACTIVE, warmup = warmup,
                    )
                    val set = set0.copy(id = coachDao.insertSet(set0))
                    val m0 = SetMove(setId = set.id, idx = 1, kind = MoveKind.TRY, attempt = 1, question = r.value.question, say = r.value.say, createdAt = now)
                    val first = m0.copy(id = coachDao.insertMove(m0))
                    _state.update { it.copy(set = set, moves = listOf(first), busy = null) }
                    Outcome.Ok
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** 回答当前这一手。source 记进 Round（主页 / 陪练窗口）。 */
    suspend fun answer(text: String, source: String): Outcome {
        val answer = text.trim()
        if (answer.isEmpty()) return Outcome.Failed("先说一句")
        if (!lock.tryLock()) return Outcome.Busy
        try {
            val s = _state.value
            val set = s.set ?: return Outcome.Failed("还没开组")
            val move = s.current ?: return Outcome.Failed("这一手已经答过了")
            val nextKind = CoachPlan.nextKind(move, prefs().coachSetMoves)
            val give = CoachPlan.giveAnswer(move)
            _state.update { it.copy(busy = Busy.JUDGING, error = null) }
            val j = when (val r = coach.judge(set, s.chain, move, answer, nextKind, give)) {
                is LlmResult.Err -> return fail(r.error.message)
                is LlmResult.Ok -> r.value
            }
            val now = clock()
            val shown = give && j.verdict == MoveVerdict.REDO
            val verdict = if (shown) MoveVerdict.SHOWN else j.verdict
            val judged = move.copy(
                answer = answer,
                verdict = verdict,
                gap = j.gap,
                conclusion = if (verdict == MoveVerdict.PASS || verdict == MoveVerdict.SHOWN) j.conclusion else "",
                correctStep = if (shown) j.correctStep else "",
                answeredAt = now,
            )
            coachDao.updateMove(judged)
            if (verdict != MoveVerdict.UNCLEAR) recordRound(set, judged, source, now)

            val feedback = Feedback(verdict, j.gap, judged.conclusion, judged.correctStep)
            val moves = s.moves.dropLast(1) + judged
            val advance = verdict == MoveVerdict.PASS || verdict == MoveVerdict.SHOWN
            if (advance && nextKind == CoachPlan.END) {
                val ended = finish(set, moves, now)
                _state.update { it.copy(set = null, moves = emptyList(), busy = null, feedback = feedback, ended = ended, endedMoves = moves) }
                return Outcome.Judged(verdict, feedback, null, ended)
            }
            val next0 = when {
                advance -> SetMove(setId = set.id, idx = move.idx + 1, kind = nextKind, attempt = 1, question = j.nextQuestion, say = j.nextSay, createdAt = now)
                verdict == MoveVerdict.REDO -> move.copy(id = 0, attempt = move.attempt + 1, question = j.nextQuestion, say = j.nextSay,
                    answer = "", verdict = MoveVerdict.PENDING, gap = "", help = 0, helpText = "", createdAt = now, answeredAt = null)
                // 没说清（题目或识别的问题）：同一次尝试重新问，不算次数
                else -> move.copy(id = 0, question = j.nextQuestion, say = j.nextSay, answer = "", verdict = MoveVerdict.PENDING,
                    gap = "", createdAt = now, answeredAt = null)
            }
            val next = next0.copy(id = coachDao.insertMove(next0))
            _state.update { it.copy(moves = moves + next, busy = null, feedback = feedback) }
            return Outcome.Judged(verdict, feedback, next, null)
        } finally {
            lock.unlock()
        }
    }

    /** 卡住了：explain 是“没看懂材料”，否则在这一手已有帮助上再加一级（线索 → 半步 → 示范）。 */
    suspend fun help(explain: Boolean, demo: Boolean = false): Outcome {
        if (!lock.tryLock()) return Outcome.Busy
        try {
            val s = _state.value
            val set = s.set ?: return Outcome.Failed("还没开组")
            val move = s.current ?: return Outcome.Failed("没有等着回答的一手")
            val level = when {
                demo -> CoachPlan.MAX_LEVEL
                explain -> maxOf(move.help, 1)
                else -> (move.help + 1).coerceAtMost(CoachPlan.MAX_LEVEL)
            }
            _state.update { it.copy(busy = Busy.HELPING, error = null) }
            return when (val r = coach.help(set, s.chain, move, level, explain && !demo)) {
                is LlmResult.Err -> fail(r.error.message)
                is LlmResult.Ok -> {
                    val text = listOf(move.helpText, r.value.help).filter { it.isNotBlank() }.joinToString("\n")
                    val updated = move.copy(help = maxOf(move.help, level), helpText = text)
                    coachDao.updateMove(updated)
                    _state.update { it.copy(moves = it.moves.dropLast(1) + updated, busy = null) }
                    Outcome.Ok
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** 不练这组了（换主练、换材料） */
    suspend fun drop() {
        lock.withLock {
            val set = _state.value.set ?: return
            coachDao.updateSet(set.copy(status = SetStatus.DROPPED, endedAt = clock()))
            _state.update { it.copy(set = null, moves = emptyList(), feedback = null) }
        }
    }

    /** 组末：用一句话说这组练会了什么 */
    suspend fun saveLesson(text: String) {
        val ended = _state.value.ended ?: return
        val updated = ended.copy(lesson = text.trim())
        coachDao.updateSet(updated)
        _state.update { it.copy(ended = null, endedMoves = emptyList(), feedback = null) }
    }

    fun dismissEnded() = _state.update { it.copy(ended = null, endedMoves = emptyList(), feedback = null) }

    fun clearFeedback() = _state.update { it.copy(feedback = null) }

    /** 回来后凭记忆说结论链：一共几节，说对几节 */
    suspend fun recordRecall(total: Int, recalled: Int, awaySeconds: Long) {
        val set = _state.value.set ?: return
        coachDao.insertRecall(ChainRecall(setId = set.id, at = clock(), total = total, recalled = recalled.coerceIn(0, total), awaySeconds = awaySeconds))
    }

    private suspend fun finish(set: TrainSet, moves: List<SetMove>, now: Long): TrainSet {
        val verifies = moves.filter { it.kind == MoveKind.VERIFY && it.verdict != MoveVerdict.UNCLEAR }
        val independent = verifies.firstOrNull()?.let(CoachPlan::independent) ?: false
        val done = set.copy(status = SetStatus.DONE, endedAt = now, verified = if (verifies.isEmpty()) -1 else if (independent) 1 else 0)
        coachDao.updateSet(done)
        // 帮助等级：验证手第一次就独立做对就撤一级；最后是教练给的答案就加一级
        val probe = if (independent) verifies.first() else verifies.lastOrNull()
        val next = CoachPlan.nextLevel(set.level, probe)
        if (next != set.level) savePrefs { p -> p.copy(faceLevels = p.faceLevels + (set.face to next)) }
        return done
    }

    private suspend fun recordRound(set: TrainSet, m: SetMove, source: String, now: Long) {
        val pass = m.verdict == MoveVerdict.PASS
        dao.insertRound(
            Round(
                taskId = set.taskId,
                source = source,
                triggerPackage = null,
                coachMove = m.question,
                userAnswer = m.answer,
                effective = pass,
                moveType = if (pass) "中间结论" else Prompts.MOVE_NONE,
                reason = m.gap,
                feedback = if (pass) m.conclusion else m.gap,
                judged = true,
                createdAt = now,
                face = set.face,
            ),
        )
    }

    private fun fail(message: String): Outcome.Failed {
        _state.update { it.copy(busy = null, error = message) }
        return Outcome.Failed(message)
    }
}
