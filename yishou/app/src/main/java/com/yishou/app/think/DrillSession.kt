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

/** SELF：判卷请求没成功（没网、没配置、格式不对），改成你自己对着锁住的答案逐条打勾，不卡在这里 */
enum class DrillStep { LOADING, ANSWER, JUDGING, COUNTER, SELF, REVEAL }

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
    /** 原文收起来了（背下来：看完之后；回想：一直收着，揭晓才给看） */
    val hidden: Boolean = false,
    /** 背下来：看到什么时候自动收起 */
    val readUntil: Long = 0,
    /** 背下来：收起后离开过一手几次 */
    val leaves: Int = 0,
    /** 背下来：复述时给不给骨架提示 */
    val skeleton: Boolean = false,
    /** 背下来：逐字对照的结果 */
    val recite: Recite.Result? = null,
    /** 找相似：三项回答 */
    val linkParts: List<String> = emptyList(),
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

    /** 题库里的题：答案导入时就锁住了，直接作答 */
    suspend fun startLocked(material: Material?, key: DrillKey) {
        _state.value = DrillState(active = true, material = material, lens = key.lens, key = key, step = DrillStep.ANSWER, startedAt = clock())
        material?.let { dao.updateMaterial(it.copy(usedAt = clock(), uses = it.uses + 1)) }
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

    private fun labels(lens: Lens, key: DrillKey): List<String> = when (lens) {
        Lens.GIST, Lens.ECHO -> listOf("主干") + key.points.drop(1).indices.map { "要点 ${it + 1}" }
        Lens.FRAME -> key.points.indices.map { "关系 ${it + 1}" }
        Lens.LINK -> LinkItem.entries.map { it.label }
        Lens.ESSAY -> key.points.indices.map { "要点 ${it + 1}" }
        Lens.STANCE -> StanceItem.entries.map { it.label }
        else -> key.points.indices.map { "前提 ${it + 1}" }
    }

    private fun pointScore(lens: Lens, key: DrillKey, checks: List<Check>, answer: String): Pair<List<Check>, Int> = when (lens) {
        Lens.GIST, Lens.ECHO -> checks to Judge.gistScore(checks)
        Lens.FRAME, Lens.LINK -> checks to Judge.coverScore(checks)
        Lens.ESSAY -> {
            val limit = key.value?.toInt()
            val over = Judge.overLimit(answer, limit)
            val all = if (limit != null) checks + Check("字数 ≤ $limit", !over, note = if (over) "写了 ${Words.norm(answer).length} 字，超了" else "") else checks
            all to Judge.essayScore(checks, over)
        }
        Lens.STANCE -> checks to Judge.stanceScore(checks)
        else -> checks to Judge.assumeScore(checks)
    }

    /** 抓主干、挖前提、申论 */
    suspend fun answer(text: String) {
        val s = _state.value
        val key = s.key ?: return
        if (s.step != DrillStep.ANSWER || s.lens !in setOf(Lens.GIST, Lens.ASSUME, Lens.ESSAY, Lens.FRAME, Lens.ECHO)) return
        Judge.tooThin(s.lens, text)?.let { thin -> _state.update { it.copy(answer = text, thin = thin) }; return }
        _state.update { it.copy(answer = text, thin = null, error = null, step = DrillStep.JUDGING) }
        when (val r = gen.judgePoints(key, text)) {
            is LlmResult.Err -> _state.update {
                it.copy(
                    step = DrillStep.SELF,
                    checks = labels(s.lens, key).map { l -> Check(l, false) },
                    error = "判卷没成功（${r.error.message}）。对着下面锁住的答案，自己逐条打勾。",
                )
            }
            is LlmResult.Ok -> {
                val checks = labels(s.lens, key).mapIndexed { i, l -> Judge.verify(l, r.value.getOrNull(i), text) }
                val (all, score) = pointScore(s.lens, key, checks, text)
                reveal(all, score)
            }
        }
    }

    /** 找相似：三项一起交 */
    suspend fun link(parts: List<String>) {
        val s = _state.value
        val key = s.key ?: return
        if (s.step != DrillStep.ANSWER || s.lens != Lens.LINK) return
        val thin = LinkItem.entries.filterIndexed { i, _ -> Words.substance(parts.getOrNull(i).orEmpty()) < 4 }
        if (thin.isNotEmpty()) {
            _state.update { it.copy(linkParts = parts, thin = "这几项还空着或太短：" + thin.joinToString("、") { t -> t.label }) }
            return
        }
        val joined = LinkItem.entries.mapIndexed { i, it -> "${it.label}：${parts[i]}" }.joinToString("\n")
        _state.update { it.copy(linkParts = parts, answer = joined, thin = null, error = null, step = DrillStep.JUDGING) }
        when (val r = gen.judgeRubric(key.prompt, LinkItem.entries.map { it.label to it.standard }, parts)) {
            is LlmResult.Err -> _state.update {
                it.copy(
                    step = DrillStep.SELF,
                    checks = labels(Lens.LINK, key).map { l -> Check(l, false) },
                    error = "判卷没成功（${r.error.message}）。对着下面的标准，自己逐项打勾。",
                )
            }
            is LlmResult.Ok -> {
                val checks = LinkItem.entries.mapIndexed { i, item -> Judge.verify(item.label, r.value.getOrNull(i), parts[i]) }
                reveal(checks, Judge.coverScore(checks))
            }
        }
    }

    /**
     * 背下来：先看 [Recite.readSeconds] 秒（可以提前点“记住了”），然后原文收起来，复述，本机逐字对照。
     * 不需要模型，没网也能练。
     */
    fun startRecite(material: Material?, title: String, text: String, skeleton: Boolean) {
        val key = DrillKey(Lens.RECITE, title, listOf(text.trim()))
        val now = clock()
        _state.value = DrillState(
            active = true, material = material, lens = Lens.RECITE, key = key, step = DrillStep.ANSWER,
            startedAt = now, readUntil = now + Recite.readSeconds(text) * 1000L, skeleton = skeleton,
        )
    }

    /** 看完了（或者时间到了）：收起原文 */
    fun hide() {
        if (_state.value.lens == Lens.RECITE && _state.value.step == DrillStep.ANSWER) _state.update { it.copy(hidden = true) }
    }

    /** 收起原文之后离开了一手：记一次（揭晓时会写出来） */
    fun left() {
        val s = _state.value
        if (s.hidden && s.step == DrillStep.ANSWER && s.lens == Lens.RECITE) _state.update { it.copy(leaves = it.leaves + 1) }
    }

    suspend fun recite(said: String) {
        val s = _state.value
        val key = s.key ?: return
        if (s.step != DrillStep.ANSWER || s.lens != Lens.RECITE || !s.hidden) return
        if (Words.norm(said).isEmpty()) {
            _state.update { it.copy(thin = Judge.tooThin(Lens.RECITE, said)) }
            return
        }
        val r = Recite.diff(key.points.first(), said)
        val checks = buildList {
            add(Check("逐字对上", r.score >= 90, note = "对上 ${r.matched}/${r.total} 字" + if (r.said > r.total) "，多说了 ${r.said - r.matched} 字" else ""))
            if (s.skeleton) add(Check("看着骨架背的", false, note = "下次试试不看骨架"))
            if (s.leaves > 0) add(Check("收起后没离开", false, note = "离开过 ${s.leaves} 次"))
        }
        _state.update { it.copy(answer = said, recite = r) }
        reveal(checks, r.score)
    }

    /** 回想：拿一段以前练过的素材，原文收着，说出当时的主干；答案用以前锁过的，没有就现锁 */
    suspend fun startEcho(material: Material, previous: DrillKey?) {
        _state.value = DrillState(active = true, material = material, lens = Lens.ECHO, hidden = true, startedAt = clock())
        if (previous != null) {
            _state.update { it.copy(key = previous.copy(lens = Lens.ECHO, prompt = Lens.ECHO.ask), step = DrillStep.ANSWER) }
        } else {
            lock()
        }
        dao.updateMaterial(material.copy(usedAt = clock(), uses = material.uses + 1))
    }

    /** 自己对照：marks[i] 表示第 i 条你说到了 */
    suspend fun selfMark(marks: List<Boolean>) {
        val s = _state.value
        val key = s.key ?: return
        if (s.step != DrillStep.SELF) return
        val checks = labels(s.lens, key).mapIndexed { i, l -> Check(l, marks.getOrElse(i) { false }, note = "自评") }
        val (all, score) = pointScore(s.lens, key, checks, s.answer)
        reveal(all, score)
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
            is LlmResult.Err -> _state.update {
                it.copy(
                    step = DrillStep.SELF,
                    checks = labels(Lens.STANCE, key).map { l -> Check(l, false) },
                    error = "判卷没成功（${r.error.message}）。对着下面的标准，自己逐项打勾。",
                )
            }
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
        _state.update { it.copy(checks = checks, score = score, step = DrillStep.REVEAL, thin = null, error = null, hidden = false) }
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

    /** 六骰子每一面最近的平均分（这一面下所有练法合起来，最近 10 次） */
    fun face(rows: List<DrillRow>, face: Int): Int? {
        val xs = rows.filter { r -> Lens.of(r.lens)?.face == face && !r.disputed && r.score >= 0 }.takeLast(10)
        return if (xs.isEmpty()) null else xs.sumOf { it.score } / xs.size
    }

    data class Line(val lens: Lens, val n: Int, val avg: Int?)

    fun lines(rows: List<DrillRow>): List<Line> = Lens.entries.filter { it != Lens.PREDICT && (it in Lens.MATERIAL_LENSES || rows.any { r -> r.lens == it.name }) }.map { lens ->
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
