package com.yishou.app.card

import com.yishou.app.data.AppDao
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardMode
import com.yishou.app.data.CoachDao
import com.yishou.app.data.Round as RoundRow
import com.yishou.app.data.SetStatus
import com.yishou.app.data.TrainSet
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.Prompts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SessionState(
    val active: Boolean = false,
    val mode: String = CardMode.HOME,
    /** 这一组主练的小技能；null 表示按掌握度挑 */
    val focus: Skill? = null,
    /** 一组几张 */
    val size: Int = 5,
    val dealt: Dealt? = null,
    val round: Round? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** 关屏阶梯 1–3 */
    val level: Int = 1,
    /** 材料已经收起（阶梯 2、3） */
    val hidden: Boolean = false,
    /** 阶梯 2 时，看材料期间记下的几个字 */
    val notes: String = "",
    /** 这一组做完的 */
    val results: List<CardAttempt> = emptyList(),
    /** 拍照导入的题，揭晓前要先点官方答案 */
    val needsKey: Boolean = false,
    /** 这一组做完了 */
    val done: Boolean = false,
    val cardStartedAt: Long = 0,
    val startedAt: Long = 0,
) {
    val index: Int get() = results.size
}

/**
 * 一组题卡（入口思考页是一张）：发牌 → 四步 → 揭晓时记下结果 → 下一张。
 * 主页、陪练窗口、入口思考页都用它，各自一个实例。
 * 每张也照旧记一条 Round（每晚总结、盘点接着用）；一组做完记一条练完的组（分组训练按组算）。
 */
class CardSession(
    private val engine: CardEngine,
    private val dao: AppDao,
    private val coachDao: CoachDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private var recorded: CardAttempt? = null

    /** 开一组；deal 为 false 时只开组不发牌（拍下的题随后用 [useDealt] 放进来） */
    suspend fun start(mode: String, focus: Skill?, size: Int, deal: Boolean = true) {
        val hist = engine.history()
        val gate = mode == CardMode.GATE
        _state.value = SessionState(
            active = true,
            mode = mode,
            focus = focus,
            size = if (gate) 1 else size.coerceIn(1, 10),
            level = if (gate) 1 else Progress.level(hist),
            startedAt = clock(),
        )
        if (deal) deal()
    }

    /** 再发一张（第一张、下一张、出题失败后重试） */
    suspend fun deal() {
        val s = _state.value
        if (!s.active || s.done) return
        _state.update { it.copy(loading = true, error = null) }
        val hist = engine.history()
        val gate = s.mode == CardMode.GATE
        when (val r = engine.deal(s.focus, allowRedo = true, needKey = !gate)) {
            is LlmResult.Err -> _state.update { it.copy(loading = false, error = r.error.message) }
            is LlmResult.Ok -> {
                recorded = null
                val cfg = RoundConfig(checks = if (gate) 0 else Progress.checks(hist), allowChange = !gate)
                _state.update {
                    it.copy(
                        loading = false, dealt = r.value, round = Round(r.value.card, cfg),
                        hidden = false, notes = "", needsKey = false, cardStartedAt = clock(),
                    )
                }
            }
        }
    }

    /** 拍下的真题直接作为下一张 */
    fun useDealt(d: Dealt, checks: Int) {
        recorded = null
        _state.update {
            it.copy(dealt = d, round = Round(d.card, RoundConfig(checks, allowChange = true)), hidden = false, notes = "", needsKey = false, cardStartedAt = clock(), error = null)
        }
    }

    fun hide() = _state.update { if (it.level >= 2) it.copy(hidden = true) else it }

    fun note(text: String) = _state.update { it.copy(notes = text) }

    suspend fun bet(option: Int, conf: Conf) = advance { it.bet(option, conf) }
    suspend fun evidence(sentences: Set<Int>) = advance { it.evidence(sentences) }
    suspend fun check(option: Int, flaw: String) = advance { it.check(option, flaw) }
    suspend fun change(option: Int?) = advance { it.change(option) }
    suspend fun hint() = advance { it.hint() }

    /** 揭晓后点的错因：补进已记的结果里 */
    suspend fun cause(c: String) {
        _state.update { it.copy(round = it.round?.cause(c)) }
        val a = recorded ?: return
        val updated = a.copy(cause = c)
        engine.updateAttempt(updated)
        recorded = updated
        _state.update { s -> s.copy(results = s.results.map { if (it.id == updated.id) updated else it }) }
    }

    /** 拍照题：点了官方答案（null 表示没有官方答案，这一张不计分） */
    suspend fun officialKey(option: Int?) {
        val s = _state.value
        val d = s.dealt ?: return
        val r = s.round ?: return
        if (option != null) {
            val confirmed = engine.confirmKey(d, option)
            _state.update { it.copy(dealt = confirmed, round = r.copy(card = confirmed.card), needsKey = false) }
        } else {
            _state.update { it.copy(needsKey = false) }
        }
        record()
    }

    private suspend fun advance(f: (Round) -> Round) {
        val r = _state.value.round ?: return
        val next = f(r)
        if (next == r) return
        _state.update { it.copy(round = next) }
        if (next.step == Step.REVEAL && recorded == null) {
            val d = _state.value.dealt ?: return
            // 拍的、导入时没写答案的：先点官方答案；模型两次没核对上的：直接揭晓，不计分
            if (!d.row.verified && d.row.source != com.yishou.app.data.CardSource.AI) _state.update { it.copy(needsKey = true) } else record()
        }
    }

    private suspend fun record() {
        val s = _state.value
        val d = s.dealt ?: return
        val r = s.round ?: return
        if (recorded != null) return
        val a = engine.record(d, r, s.mode, s.level, clock() - s.cardStartedAt)
        recorded = a
        val results = s.results + a
        val done = results.size >= s.size
        _state.update { it.copy(results = results, done = done) }
        writeRound(d, r, s.mode)
        if (done && s.mode != CardMode.GATE) writeSet(results, s)
    }

    /** 照旧记一条 Round：每晚总结、盘点页都读它 */
    private suspend fun writeRound(d: Dealt, r: Round, mode: String) {
        val taskId = dao.getCurrentTask()?.id ?: 0
        dao.insertRound(
            RoundRow(
                taskId = taskId,
                source = when (mode) {
                    CardMode.GATE -> com.yishou.app.data.RoundSource.GATE
                    CardMode.WINDOW -> com.yishou.app.data.RoundSource.WINDOW
                    else -> com.yishou.app.data.RoundSource.HOME
                },
                triggerPackage = null,
                coachMove = d.card.question,
                userAnswer = buildString {
                    append("选 ${r.bet?.let(Card::letter) ?: "-"}，把握${r.conf?.label ?: "-"}，依据 ")
                    append(r.evidence.sorted().joinToString("、") { Card.mark(it) })
                    r.final?.takeIf { it != r.bet }?.let { append("，改成 ${Card.letter(it)}") }
                },
                effective = r.firstCorrect && r.help == 0,
                moveType = if (r.firstCorrect) "中间结论" else Prompts.MOVE_NONE,
                reason = d.card.explain,
                feedback = r.scoreLine(),
                judged = d.row.verified,
                createdAt = clock(),
                face = d.card.skill.face,
            ),
        )
    }

    /** 一组做完：记一条练完的组，分组训练按它算 */
    private suspend fun writeSet(results: List<CardAttempt>, s: SessionState) {
        val taskId = dao.getCurrentTask()?.id ?: 0
        val skill = s.focus ?: Skill.of(results.groupingBy { it.skill }.eachCount().maxByOrNull { it.value }?.key ?: "") ?: Skill.DETAIL
        coachDao.insertSet(
            TrainSet(
                taskId = taskId,
                face = skill.face,
                level = s.level,
                goal = "练：${s.focus?.label ?: "按掌握度挑"}",
                material = "",
                standard = "${results.count { it.firstCorrect }}/${results.size} 首答对",
                startedAt = s.startedAt,
                endedAt = clock(),
                status = SetStatus.DONE,
                verified = if (results.all { it.firstCorrect && it.help == 0 }) 1 else 0,
            ),
        )
    }

    suspend fun next() {
        if (_state.value.done) return
        deal()
    }

    fun stop() {
        recorded = null
        _state.value = SessionState()
    }
}
