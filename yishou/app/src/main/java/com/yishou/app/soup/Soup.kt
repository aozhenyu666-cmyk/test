package com.yishou.app.soup

import com.yishou.app.card.Card
import com.yishou.app.llm.LlmResult
import com.yishou.app.think.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 海龟汤：拿题库里的真题当汤（材料 + 题干，先不给选项）。汤底是锁死的正确答案和解析，只有主持人知道。
 * 你只能问是非问题，主持人只回“是 / 不是 / 部分是 / 无关”——它没法替你想，只能逼你自己一层层逼近。
 * 问到能说出汤底，或者问够几问，再亮 ABCD：这一下是拿锁死的答案核对的，蒙不过去。
 *
 * 为什么这样练：做题时看着选项挑，练的是“认”；海龟汤练的是“生”——自己生出假设、自己设计能切掉一半可能的问题。
 */
enum class Reply(val label: String) {
    YES("是"), NO("不是"), PARTLY("部分是"), IRRELEVANT("无关"), NOT_YN("这不是是非题，换个能答是或不是的问法"),
    SOLVED("猜中了"), CLOSE("接近了，还差关键一环"), WRONG("不对"),
}

data class QA(val q: String, val reply: Reply, val guess: Boolean = false)

/** 主持人。只回固定的几个字，不解释、不提示。 */
interface SoupJudge {
    suspend fun ask(surface: String, truth: String, log: List<QA>, q: String): LlmResult<Reply>
    suspend fun guess(surface: String, truth: String, g: String): LlmResult<Reply>
}

enum class SoupPhase { ASK, OPTIONS, DONE }

data class SoupState(
    val cardId: Long = 0,
    val card: Card? = null,
    val surface: String = "",
    val log: List<QA> = emptyList(),
    val phase: SoupPhase = SoupPhase.ASK,
    /** 亮选项之前就说中了汤底 */
    val solved: Boolean = false,
    val picked: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val asks: Int get() = log.count { !it.guess && it.reply != Reply.NOT_YN }
    val guesses: Int get() = log.count { it.guess }
    val left: Int get() = (Soups.BUDGET - asks).coerceAtLeast(0)
    val canOpen: Boolean get() = solved || asks >= Soups.MIN_ASKS || left == 0
    val correct: Boolean? get() = picked?.let { p -> card?.let { it.answer == p } }
}

object Soups {

    /** 适合煮汤的题：要你解释、找原因、加强削弱、找前提的 */
    val KINDS = listOf("解释", "原因", "加强", "削弱", "支持", "质疑", "前提", "假设", "反驳", "论证", "评价", "推出", "结论")

    /** 问几问才能亮选项（太早亮就退回成看选项挑了） */
    const val MIN_ASKS = 3
    /** 最多问几问 */
    const val BUDGET = 15

    fun eligible(card: Card): Boolean = card.sentences.isNotEmpty() && card.options.size == 4

    /** 越像“为什么会这样”的题越适合 */
    fun score(card: Card): Int = when {
        !eligible(card) -> 0
        KINDS.any { it in card.question || it in card.type } -> 2
        else -> 1
    }

    fun surface(card: Card): String = card.sentences.joinToString("") + "\n\n" + card.question

    fun truth(card: Card): String = "正确答案：${Card.letter(card.answer)}. ${card.options[card.answer]}" +
        card.explain.trim().takeIf { it.isNotEmpty() }?.let { "\n解析：$it" }.orEmpty()

    /** 主持人回的话 → 固定的几种。只认这几个字，多说的一概不显示（防止它漏汤底）。 */
    fun parseAsk(text: String): Reply? {
        val t = text.trim().take(20)
        return when {
            t.isEmpty() -> null
            "是非" in t || "这不是" in t -> Reply.NOT_YN
            "部分" in t -> Reply.PARTLY
            "无关" in t -> Reply.IRRELEVANT
            t.startsWith("不是") || t.startsWith("否") || t.startsWith("不") -> Reply.NO
            t.startsWith("是") || t.startsWith("对") -> Reply.YES
            else -> null
        }
    }

    fun parseGuess(text: String): Reply? {
        val t = text.trim().take(20)
        return when {
            "猜中" in t -> Reply.SOLVED
            "接近" in t -> Reply.CLOSE
            "不对" in t || "没" in t || "错" in t -> Reply.WRONG
            else -> null
        }
    }

    /** 带去 ChatGPT（语音模式）玩的开场白。不含选项和答案：答案锁在手机里，回来再核对。 */
    fun handoff(surface: String) = """
        我们玩海龟汤。下面是汤面：一道题去掉了选项。你先自己读懂，想清楚“汤底”——最关键的那个机制或原因——但不要告诉我。
        规则：我每次问一个是非问题，你只回答“是 / 不是 / 部分是 / 无关”，一个字都不要多说。
        我说“我猜……”时，你只回答“猜中 / 接近 / 不对”。不要提示，不要解释，不要鼓励。我说“揭晓”你才说出汤底。

        汤面：
        $surface
    """.trimIndent()

    data class Stats(val pots: Int, val solved: Int, val avgAsks: Int?, val right: Int, val picked: Int)

    fun stats(xs: List<com.yishou.app.data.Soup>): Stats {
        val played = xs.filter { it.asks >= 0 }
        return Stats(
            pots = xs.size,
            solved = xs.count { it.solved },
            avgAsks = if (played.isEmpty()) null else played.sumOf { it.asks } / played.size,
            right = xs.count { it.correct == true },
            picked = xs.count { it.correct != null },
        )
    }

    fun line(s: Stats): String {
        if (s.pots == 0) return "还没煮过。拿题库里的真题当汤：只能问是非问题，问穿了再亮选项。"
        return buildList {
            add("煮过 ${s.pots} 锅")
            add("亮选项前猜中 ${s.solved}")
            s.avgAsks?.let { add("平均 $it 问") }
            if (s.picked > 0) add("选对 ${s.right * 100 / s.picked}%")
        }.joinToString(" · ")
    }
}

class SoupSession(private val judge: SoupJudge?) {
    private val _state = MutableStateFlow(SoupState())
    val state: StateFlow<SoupState> = _state.asStateFlow()

    fun start(cardId: Long, card: Card) {
        _state.value = SoupState(cardId = cardId, card = card, surface = Soups.surface(card))
    }

    /** 问一个是非问题 */
    suspend fun ask(q: String) {
        val s = _state.value
        val card = s.card ?: return
        val text = q.trim()
        if (s.busy || s.phase != SoupPhase.ASK || s.left == 0 || Words.substance(text) < 2) return
        // 问过一模一样的：直接给上次的回答，不算一问
        s.log.firstOrNull { !it.guess && Words.norm(it.q) == Words.norm(text) }?.let { old ->
            _state.update { it.copy(error = "问过了：${old.reply.label}") }
            return
        }
        val j = judge ?: return noJudge()
        _state.update { it.copy(busy = true, error = null) }
        when (val r = j.ask(s.surface, Soups.truth(card), s.log, text)) {
            is LlmResult.Err -> _state.update { it.copy(busy = false, error = r.error.message) }
            is LlmResult.Ok -> _state.update {
                val log = it.log + QA(text, r.value)
                val next = it.copy(busy = false, log = log)
                if (next.left == 0) next.copy(phase = SoupPhase.OPTIONS) else next
            }
        }
    }

    /** 说汤底：猜中就亮选项 */
    suspend fun guess(g: String) {
        val s = _state.value
        val card = s.card ?: return
        val text = g.trim()
        if (s.busy || s.phase != SoupPhase.ASK || Words.substance(text) < 4) return
        val j = judge ?: return noJudge()
        _state.update { it.copy(busy = true, error = null) }
        when (val r = j.guess(s.surface, Soups.truth(card), text)) {
            is LlmResult.Err -> _state.update { it.copy(busy = false, error = r.error.message) }
            is LlmResult.Ok -> _state.update {
                val solved = r.value == Reply.SOLVED
                it.copy(busy = false, log = it.log + QA(text, r.value, guess = true), solved = it.solved || solved, phase = if (solved) SoupPhase.OPTIONS else it.phase)
            }
        }
    }

    /** 不问了，亮选项（问够几问才行） */
    fun open() {
        if (_state.value.canOpen) _state.update { it.copy(phase = SoupPhase.OPTIONS, error = null) }
    }

    /** 选一个：拿锁死的答案核对 */
    fun pick(option: Int) {
        val s = _state.value
        if (s.phase != SoupPhase.OPTIONS || s.card == null || option !in 0..3) return
        _state.update { it.copy(picked = option, phase = SoupPhase.DONE) }
    }

    /** 去 ChatGPT 玩完回来：直接亮选项核对 */
    fun backFromOutside() = _state.update { it.copy(phase = SoupPhase.OPTIONS, error = null) }

    private fun noJudge() = _state.update { it.copy(error = "海龟汤要用大模型当主持人。还没填接口的话，点“去 ChatGPT 用语音玩”。") }
}
