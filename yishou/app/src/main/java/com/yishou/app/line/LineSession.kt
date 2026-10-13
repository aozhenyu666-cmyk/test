package com.yishou.app.line

import com.yishou.app.data.Line
import com.yishou.app.data.LineDao
import com.yishou.app.data.Move
import com.yishou.app.data.LineVerdict
import com.yishou.app.llm.LineCoach
import com.yishou.app.llm.LlmResult
import com.yishou.app.think.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

data class LineState(
    val line: Line? = null,
    /** 现在问的那一句 */
    val question: String = "",
    val ask: Ask = Ask.NORMAL,
    val face: Int = 1,
    /** 教练刚说的（判语 + 提示），语音模式要念出来 */
    val say: String = "",
    /** 本机规则的检查结果（交了之后） */
    val check: TalkCheck? = null,
    /** 等教练回话 */
    val busy: Boolean = false,
    val level: Level = Level.MID,
    /** 连着拒绝几次 */
    val refusals: Int = 0,
    /** 同一问连着没过规则几次 */
    val thinTries: Int = 0,
    /** 连着接住几手 */
    val streak: Int = 0,
    /** 降阶到底还是不想：这一手先放着 */
    val parked: Boolean = false,
    /** 连着接住好几手，可以加强度了 */
    val canRaise: Boolean = false,
    /** 这次打开以来下了几手、接住几手 */
    val moves: Int = 0,
    val caught: Int = 0,
    val askedAt: Long = 0,
)

/**
 * 主线一局：一问一答往下走。每一手：
 * 1. 不想答（不知道、随便、不说话）→ 降阶阶梯，不放过也不硬逼；
 * 2. 本机规则（口水、先说结论、长度、结构）不过 → 不收，指出哪条，原问题再说一次（同一问最多退两次，第三次照收但记成“没过”）；
 * 3. 规则过了 → 交给教练：判接没接住、提炼一条、问下一问；教练不在（没网、没配）就用本机的六面问题接着走；
 * 4. 每走几手回一次主线：“到现在你的结论是什么？”——防止越聊越远。
 * 断点（下一问）随时存着，下次打开接着下。
 */
class LineSession(
    private val dao: LineDao,
    private val coach: LineCoach?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LineState())
    val state: StateFlow<LineState> = _state.asStateFlow()

    /** 开一局新的主线 */
    suspend fun create(title: String, anchor: String, level: Level, materialId: Long? = null): Line {
        val now = clock()
        val a = anchor.trim().ifEmpty { title.trim() }
        val first = Lines.question(1, a)
        val l = Line(title = title.trim().ifEmpty { a.take(16) }, anchor = a, nextQuestion = first, face = 1, createdAt = now, updatedAt = now, materialId = materialId)
        val saved = l.copy(id = dao.insertLine(l))
        open(saved, level)
        return saved
    }

    /** 接着下：从断点开始 */
    fun open(line: Line, level: Level) {
        val q = line.nextQuestion.ifBlank { Lines.question(line.face, line.anchor) }
        _state.value = LineState(line = line, question = q, face = line.face, level = level, say = q, askedAt = clock())
    }

    fun setLevel(level: Level) = _state.update { it.copy(level = level, canRaise = false) }

    /** 交一手 */
    suspend fun answer(text: String, voice: Boolean = false) {
        val s = _state.value
        val line = s.line ?: return
        if (s.busy) return
        val latency = clock() - s.askedAt

        // 1. 不想答：降阶
        if (Resist.refused(text)) {
            val n = s.refusals + 1
            val rung = Resist.rung(n)
            record(line, s, text, LineVerdict.REFUSED, if (rung == null) "先放着" else "降阶", "", voice, latency)
            if (rung == null) {
                _state.update { it.copy(refusals = n, parked = true, say = Resist.PARK, check = null, streak = 0, moves = it.moves + 1, level = it.level.down()) }
                return
            }
            val q = Resist.prompt(rung, line.anchor, s.face)
            _state.update { it.copy(refusals = n, ask = rung, question = q, say = q, check = null, streak = 0, moves = it.moves + 1, askedAt = clock()) }
            return
        }

        // 2. 本机规则
        val rules = if (s.ask == Ask.NORMAL || s.ask == Ask.ANCHOR) TalkRules.of(s.level) else TalkRules.SMALL
        val check = Talk.check(text, rules)
        val enforce = s.level != Level.LIGHT && (s.ask == Ask.NORMAL || s.ask == Ask.ANCHOR)
        if (!check.ok && enforce && s.thinTries < 2) {
            record(line, s, text, LineVerdict.THIN, check.hint.orEmpty(), "", voice, latency)
            val say = "${check.hint} 再说一次：${s.question}"
            _state.update { it.copy(check = check, thinTries = it.thinTries + 1, say = say, streak = 0, moves = it.moves + 1, askedAt = clock()) }
            return
        }

        // 3. 交给教练（降阶的小问题接住了，直接回到正常的一问）
        _state.update { it.copy(busy = true, check = check, refusals = 0) }
        val conclusions = Lines.list(line.conclusions)
        val doubts = Lines.list(line.doubts)
        val recent = dao.recentMoves(line.id, 6).reversed().filter { it.verdict == LineVerdict.OK }.map { it.question to it.answer }
        val reply = coach?.reply(line.anchor, conclusions, doubts, recent, s.question, text, s.face)
        val nowCount = s.caught + 1
        val anchorDue = s.ask != Ask.ANCHOR && nowCount % Lines.anchorEvery(s.level) == 0

        when {
            reply is LlmResult.Ok && !reply.value.caught && s.ask == Ask.NORMAL && s.thinTries < 2 -> {
                // 没接住问题：说差在哪，原问题再来（也算一次退回）
                val why = reply.value.why.ifEmpty { "没接住这个问题。" }
                record(line, s, text, LineVerdict.DRIFT, why, "", voice, latency)
                _state.update { it.copy(busy = false, thinTries = it.thinTries + 1, say = "$why 再回答：${s.question}", streak = 0, moves = it.moves + 1, askedAt = clock()) }
            }
            else -> {
                val r = (reply as? LlmResult.Ok)?.value
                // 提炼只认原话里有的；对不上就用你第一句
                val gist = r?.gist?.takeIf { r.quote.isNotEmpty() && Words.quoted(text, r.quote) }
                    ?: Talk.lead(text).take(30)
                val isDoubt = r?.kind == "疑问"
                val face = r?.face ?: ((s.face % 6) + 1)
                val next = when {
                    anchorDue -> Lines.anchorQuestion(line.anchor)
                    r != null -> r.next
                    else -> Lines.question(face, line.anchor)
                }
                val newConclusions = when {
                    s.ask == Ask.ANCHOR -> listOf(gist) // 回主线时的结论替换掉零碎的
                    isDoubt -> conclusions
                    else -> (conclusions + gist).takeLast(12)
                }
                val newDoubts = if (isDoubt) (doubts + gist).takeLast(8) else doubts
                val updated = line.copy(
                    conclusions = Lines.json(newConclusions),
                    doubts = Lines.json(newDoubts),
                    nextQuestion = next,
                    face = if (anchorDue) 1 else face,
                    updatedAt = clock(),
                )
                dao.updateLine(updated)
                val note = if (check.ok) "" else "没过：${check.hint}"
                record(updated, s, text, if (check.ok || !enforce) LineVerdict.OK else LineVerdict.THIN, note, gist, voice, latency)
                val streak = s.streak + 1
                val ack = if (r == null) "记下了。" else "接住了：$gist。"
                _state.update {
                    it.copy(
                        line = updated, question = next, ask = if (anchorDue) Ask.ANCHOR else Ask.NORMAL, face = updated.face,
                        say = "$ack 下一问：$next", busy = false, thinTries = 0, streak = streak, parked = false,
                        canRaise = streak >= 6 && it.level != Level.HARD, moves = it.moves + 1, caught = nowCount, askedAt = clock(),
                    )
                }
            }
        }
    }

    /** 不想下了：记成先放着，断点留着 */
    suspend fun park() {
        val s = _state.value
        val line = s.line ?: return
        record(line, s, "", LineVerdict.PARKED, "主动先放着", "", false, clock() - s.askedAt)
        _state.update { it.copy(parked = true, say = Resist.PARK) }
    }

    /** 放着之后回来：从断点接着 */
    fun resume() {
        val s = _state.value
        val line = s.line ?: return
        val q = line.nextQuestion.ifBlank { Lines.question(line.face, line.anchor) }
        _state.update { it.copy(parked = false, refusals = 0, ask = Ask.NORMAL, question = q, say = q, askedAt = clock()) }
    }

    /** 收起这一局（结论都留着） */
    suspend fun close() {
        val line = _state.value.line ?: return
        dao.updateLine(line.copy(closedAt = clock(), updatedAt = clock()))
        _state.value = LineState()
    }

    fun stop() {
        _state.value = LineState()
    }

    private suspend fun record(line: Line, s: LineState, answer: String, verdict: String, note: String, gist: String, voice: Boolean, latency: Long) {
        dao.insertMove(
            Move(
                lineId = line.id, question = s.question, answer = answer.trim(), ask = s.ask.name, verdict = verdict,
                note = note, gist = gist, face = s.face, voice = voice, latencyMs = latency, createdAt = clock(),
            ),
        )
    }
}

/** 主线的几样本机工具：六面的问题、回主线的问题、结论列表的读写、统计 */
object Lines {

    /** 教练不在时（没网、没配接口）用的六面问题，都围着主线 */
    fun question(face: Int, anchor: String): String = when (face) {
        2 -> "接下来会怎样？关于「$anchor」，押一个过几天能核对的判断。"
        3 -> "「$anchor」像你见过的什么？相同在哪，不同在哪？"
        4 -> "关于「$anchor」，你现在最拿不准的是哪一点？要先弄清什么？"
        5 -> "「$anchor」和你已经知道的哪件事有关系？是什么关系？"
        6 -> "回想一下：关于「$anchor」，你上一次的结论是什么？现在还成立吗？"
        else -> "先说结论：关于「$anchor」，你现在的判断是什么？再说一个理由。"
    }

    fun anchorQuestion(anchor: String) = "回到主线：关于「$anchor」，到现在你的结论是什么？一句话。"

    /** 每接住几手回一次主线：越强越勤 */
    fun anchorEvery(level: Level): Int = when (level) {
        Level.LIGHT -> 6
        Level.MID -> 5
        Level.HARD -> 3
    }

    fun list(json: String): List<String> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun json(xs: List<String>): String = JSONArray(xs).toString()

    data class Today(val moves: Int, val caught: Int, val thin: Int, val refused: Int, val parked: Int, val voice: Int, val avgLatencyMs: Long?)

    fun today(moves: List<Move>): Today {
        val answered = moves.filter { it.verdict != LineVerdict.PARKED }
        return Today(
            moves = moves.size,
            caught = moves.count { it.verdict == LineVerdict.OK },
            thin = moves.count { it.verdict == LineVerdict.THIN || it.verdict == LineVerdict.DRIFT },
            refused = moves.count { it.verdict == LineVerdict.REFUSED },
            parked = moves.count { it.verdict == LineVerdict.PARKED },
            voice = moves.count { it.voice },
            avgLatencyMs = if (answered.isEmpty()) null else answered.sumOf { it.latencyMs } / answered.size,
        )
    }

    /** 一行：接住几手、口水退回几次、不想答几次 */
    fun line(t: Today): String {
        if (t.moves == 0) return "今天还没下。"
        return buildList {
            add("接住 ${t.caught} 手")
            if (t.thin > 0) add("退回 ${t.thin} 次")
            if (t.refused > 0) add("不想答 ${t.refused} 次")
            if (t.parked > 0) add("放着 ${t.parked} 手")
        }.joinToString(" · ")
    }
}
