package com.yishou.app.think

import com.yishou.app.data.Bet
import com.yishou.app.data.BetKind
import com.yishou.app.data.DrillRow
import com.yishou.app.data.Material
import com.yishou.app.data.PracticeDao
import com.yishou.app.data.ThinkDao
import com.yishou.app.llm.DrillGen
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DrillStep { LOADING, ANSWER, JUDGING, COUNTER, REVEAL }

data class DrillState(
    val active: Boolean = false,
    val material: Material? = null,
    val lens: Lens = Lens.GIST,
    val key: DrillKey? = null,
    val step: DrillStep = DrillStep.LOADING,
    val answer: String = "",
    val parts: Map<StanceItem, String> = emptyMap(),
    val low: Double? = null,
    val high: Double? = null,
    val confidence: Int = 0,
    val checks: List<Check> = emptyList(),
    /** 站立场：追问的那条反驳 */
    val counter: String = "",
    val reply: String = "",
    /** 0–100；押后果是 -1（等现实） */
    val score: Int = 0,
    /** 回答太单薄时的提示（不判） */
    val thin: String? = null,
    val error: String? = null,
    val rowId: Long = 0,
    val disputed: Boolean = false,
    val takeaway: String = "",
    val startedAt: Long = 0,
)

/**
 * 一次练习：锁答案 → 你作答 → 本机核对原话、算分 →（站立场多一轮追问）→ 揭晓 → 一句话收口。
 * 不会接着聊下去：揭晓之后要么换练法，要么换素材。
 */
class DrillSession(
    private val gen: DrillGen,
    private val dao: ThinkDao,
    private val practice: PracticeDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(DrillState())
    val state: StateFlow<DrillState> = _state.asStateFlow()

    /** 开始练。估个数可以没有素材（出常识量），其余练法要有素材。 */
    suspend fun start(material: Material?, lens: Lens) {
        _state.value = DrillState(active = true, material = material, lens = lens, startedAt = clock())
        if (material == null && lens != Lens.ESTIMATE) {
            _state.update { it.copy(error = "这个练法要先有一段素材") }
            return
        }
        material?.let { dao.updateMaterial(it.copy(usedAt = clock(), uses = it.uses + 1)) }
        lock()
    }

    /** 锁答案失败后重试 */
    suspend fun retry() {
        val s = _state.value
        if (!s.active || s.key != null) return
        _state.update { it.copy(error = null) }
        lock()
    }

    private suspend fun lock() {
        val s = _state.value
        val text = s.material?.text
        repeat(if (s.lens == Lens.ESTIMATE) 2 else 1) {
            when (val k = gen.key(s.lens, text)) {
                is LlmResult.Err -> {
                    _state.update { it.copy(error = k.error.message) }
                    return
                }
                is LlmResult.Ok -> {
                    val key = k.value
                    if (s.lens == Lens.ESTIMATE) {
                        // 参考值要另一次独立估得差不多，才用这道题
                        val again = gen.reestimate(key.prompt, text)
                        if (again !is LlmResult.Ok || !Estimate.agree(key.value ?: 0.0, again.value)) return@repeat
                    }
                    _state.update { it.copy(key = key, step = DrillStep.ANSWER, error = null, startedAt = clock()) }
                    return
                }
            }
        }
        _state.update { it.copy(error = "两次估的参考值对不上，这道不出了。点重试换一道。") }
    }

    /** 抓主干、挖前提 */
    suspend fun answer(text: String) {
        val s = _state.value
        val key = s.key ?: return
        if (s.step != DrillStep.ANSWER || (s.lens != Lens.GIST && s.lens != Lens.ASSUME)) return
        Judge.tooThin(s.lens, text)?.let { thin -> _state.update { it.copy(answer = text, thin = thin) }; return }
        _state.update { it.copy(answer = text, thin = null, error = null, step = DrillStep.JUDGING) }
        when (val r = gen.judgePoints(key, text)) {
            is LlmResult.Err -> _state.update { it.copy(step = DrillStep.ANSWER, error = r.error.message) }
            is LlmResult.Ok -> {
                val labels = if (s.lens == Lens.GIST) listOf("主干") + key.points.drop(1).indices.map { "要点 ${it + 1}" }
                else key.points.indices.map { "前提 ${it + 1}" }
                val checks = labels.mapIndexed { i, l -> Judge.verify(l, r.value.getOrNull(i), text) }
                val score = if (s.lens == Lens.GIST) Judge.gistScore(checks) else Judge.assumeScore(checks)
                reveal(checks, score)
            }
        }
    }

    /** 站立场：四项一起交 */
    suspend fun stance(parts: Map<StanceItem, String>) {
        val s = _state.value
        val key = s.key ?: return
        if (s.step != DrillStep.ANSWER || s.lens != Lens.STANCE) return
        Judge.stanceThin(parts)?.let { thin -> _state.update { it.copy(parts = parts, thin = thin) }; return }
        val joined = StanceItem.entries.joinToString("\n") { "${it.label}：${parts[it].orEmpty()}" }
        _state.update { it.copy(parts = parts, answer = joined, thin = null, error = null, step = DrillStep.JUDGING) }
        when (val r = gen.judgeStance(key.prompt, parts)) {
            is LlmResult.Err -> _state.update { it.copy(step = DrillStep.ANSWER, error = r.error.message) }
            is LlmResult.Ok -> {
                val (raw, counter) = r.value
                val checks = StanceItem.entries.mapIndexed { i, item -> Judge.verify(item.label, raw.getOrNull(i), parts[item].orEmpty()) }
                val score = Judge.stanceScore(checks)
                _state.update { it.copy(checks = checks, counter = counter, score = score, step = DrillStep.COUNTER) }
                save()
            }
        }
    }

    /** 站立场：回应追问（只有这一轮） */
    suspend fun reply(text: String) {
        val s = _state.value
        val key = s.key ?: return
        if (s.step != DrillStep.COUNTER) return
        if (Words.substance(text) < 4) {
            _state.update { it.copy(reply = text, thin = "说一句完整的回应，或者点“先不回应”。") }
            return
        }
        _state.update { it.copy(reply = text, thin = null, error = null, step = DrillStep.JUDGING) }
        when (val r = gen.judgeReply(key.prompt, s.counter, text)) {
            is LlmResult.Err -> _state.update { it.copy(step = DrillStep.COUNTER, error = r.error.message) }
            is LlmResult.Ok -> {
                val checks = s.checks + Judge.verify("接住反驳", r.value, text)
                reveal(checks, Judge.stanceScore(checks))
            }
        }
    }

    suspend fun skipReply() {
        val s = _state.value
        if (s.step != DrillStep.COUNTER) return
        reveal(s.checks, s.score)
    }

    /** 估个数：九成把握的区间 */
    suspend fun range(low: Double, high: Double) {
        val s = _state.value
        val key = s.key ?: return
        val truth = key.value ?: return
        if (s.step != DrillStep.ANSWER || s.lens != Lens.ESTIMATE) return
        if (low <= 0 || high <= 0) {
            _state.update { it.copy(thin = "填两个正数：最低和最高。") }
            return
        }
        val lo = minOf(low, high)
        val hi = maxOf(low, high)
        val hit = Estimate.hit(lo, hi, truth)
        val check = Check(
            "九成区间",
            hit,
            note = if (hit) "" else "参考值 ${Estimate.show(truth)}${key.unit}，在区间${if (truth < lo) "下面" else "上面"}",
        )
        _state.update { it.copy(low = lo, high = hi, answer = "${Estimate.show(lo)}–${Estimate.show(hi)}${key.unit}", thin = null) }
        reveal(listOf(check), Estimate.score(lo, hi, truth))
    }

    /** 押后果：押一个判断和把握，存进预判本，交给现实 */
    suspend fun predict(claim: String, confidence: Int) {
        val s = _state.value
        val key = s.key ?: return
        if (s.step != DrillStep.ANSWER || s.lens != Lens.PREDICT) return
        Judge.tooThin(Lens.PREDICT, claim)?.let { thin -> _state.update { it.copy(answer = claim, thin = thin) }; return }
        if (confidence !in 50..100) {
            _state.update { it.copy(answer = claim, thin = "选一个把握（50–100）。") }
            return
        }
        practice.insertBet(
            Bet(
                kind = BetKind.BET,
                title = s.material?.title.orEmpty(),
                claim = claim.trim(),
                basis = key.prompt,
                confidence = confidence,
                createdAt = clock(),
            ),
        )
        _state.update { it.copy(answer = claim, confidence = confidence, thin = null) }
        reveal(emptyList(), -1)
    }

    private suspend fun reveal(checks: List<Check>, score: Int) {
        _state.update { it.copy(checks = checks, score = score, step = DrillStep.REVEAL, thin = null, error = null) }
        save()
    }

    /** 第一次存一条，之后（追问、收口、有异议）都更新同一条 */
    private suspend fun save() {
        val s = _state.value
        val key = s.key ?: return
        val row = DrillRow(
            id = s.rowId,
            materialId = s.material?.id,
            lens = s.lens.name,
            key = key.toJson(),
            answer = s.answer,
            checks = Check.listToJson(s.checks),
            score = s.score,
            reply = s.reply,
            takeaway = s.takeaway,
            disputed = s.disputed,
            durationMs = clock() - s.startedAt,
            createdAt = s.startedAt,
        )
        if (s.rowId == 0L) {
            val id = dao.insertDrill(row)
            _state.update { it.copy(rowId = id) }
        } else {
            dao.updateDrill(row)
        }
    }

    /** 觉得锁住的答案本身有问题：这一次不计入统计 */
    suspend fun dispute() {
        if (_state.value.step != DrillStep.REVEAL) return
        _state.update { it.copy(disputed = !it.disputed) }
        save()
    }

    /** 收口：一句话，你自己的 */
    suspend fun takeaway(text: String) {
        if (_state.value.step != DrillStep.REVEAL) return
        _state.update { it.copy(takeaway = text.trim()) }
        save()
    }

    fun stop() {
        _state.value = DrillState()
    }
}

/** 各练法最近的成绩（不算有异议的、押后果的） */
object DrillStats {

    data class Line(val lens: Lens, val n: Int, val avg: Int?)

    fun lines(rows: List<DrillRow>): List<Line> = Lens.entries.filter { it != Lens.PREDICT }.map { lens ->
        val xs = rows.filter { it.lens == lens.name && !it.disputed && it.score >= 0 }.takeLast(10)
        Line(lens, rows.count { it.lens == lens.name }, if (xs.isEmpty()) null else xs.sumOf { it.score } / xs.size)
    }

    /** 估个数的区间命中情况 */
    fun estimateHits(rows: List<DrillRow>): List<Boolean> = rows
        .filter { it.lens == Lens.ESTIMATE.name && !it.disputed }
        .map { r -> Check.listFromJson(r.checks).firstOrNull()?.ok == true }

    /** 今天算“自己做到了”的一次：分数 ≥ 60（押后果算押下就是） */
    fun good(r: DrillRow): Boolean = r.score < 0 || r.score >= 60
}
