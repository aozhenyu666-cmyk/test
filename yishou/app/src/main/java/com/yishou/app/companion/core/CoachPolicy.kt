package com.yishou.app.companion.core

/** 能练的五种思考动作。同一个材料可以练不同的动作，不总是找错。 */
enum class ThinkMove(val label: String) {
    RECALL("复述"),
    PREDICT("预测"),
    TRANSFER("迁移"),
    COUNTER("反例"),
    FIND_GAP("找条件"),
}

/** 一次作答的判定。NONE 专门留给「没判成」——识别失败、网络失败、用户跳过，都不算答错。 */
enum class Verdict(val label: String) {
    HIT("到了"),
    NEAR("差一点"),
    MISS("没到"),
    NONE("没判成");

    val counted: Boolean get() = this == HIT || this == NEAR || this == MISS
}

/** 以前练过的动作和当时的差值：下一次提问要能接上，不然每次都从零开始 */
data class AttemptMemory(
    val move: ThinkMove,
    val atMs: Long,
    val verdict: Verdict,
    val delta: String,
)

/**
 * 打扰预算。视频时长只是节奏参数，不是「每几分钟必须打断」。
 * 谈不拢就继续听，比硬问一句更有用。
 */
data class InterruptBudget(
    /** 材料至少铺这么长才考虑介入 */
    val minMaterialMs: Long = 120_000,
    /** 两次提问之间至少隔这么久 */
    val minGapMs: Long = 150_000,
    /** 一场最多主动问几次 */
    val maxPerSession: Int = 6,
    /** 问题准备好以后多久内必须出声，过期就丢掉 */
    val questionTtlMs: Long = 20_000,
)

data class CoachInput(
    val nowMs: Long,
    val goal: String,
    val slice: ContextSlice,
    val recent: List<AttemptMemory> = emptyList(),
    val elapsedMs: Long = 0,
)

/** 这次要么问，要么继续听；不打扰时连理由都写出来（界面上能看，也不至于让人以为坏了） */
sealed interface AskPlan {
    data class Ask(
        val system: String,
        val user: String,
        val evidenceIds: List<Long>,
        val move: ThinkMove,
        val epoch: Int,
        val expiresAtMs: Long,
        /** 为什么现在问：内部记录用，不念出来 */
        val why: String,
    ) : AskPlan

    data class Wait(val reason: String) : AskPlan
}

/**
 * 文本里的「可能有破绽」信号。
 *
 * 只是**候选信号**，不是结论：看到「所以 / 都 / 一定」不代表材料真的错了。
 * 它唯一的作用是挑一个思考动作（找条件/反例），真正是不是破绽由模型按证据判断，
 * 界面上也永远不说「这段有错」。
 */
object FlawSignals {

    private val GENERALIZE = listOf("所以", "因此", "可见", "这说明")
    private val ABSOLUTE = listOf("都", "所有", "全部", "任何", "一定", "必然", "肯定", "从来")
    private val CAUSAL = listOf("导致", "造成", "因为", "取决于")

    fun detect(text: String): Boolean {
        val generalize = GENERALIZE.any { it in text } && ABSOLUTE.any { it in text }
        val causal = CAUSAL.any { it in text } && ABSOLUTE.any { it in text }
        return generalize || causal
    }
}

/**
 * 视频陪练的介入策略（纯逻辑）。
 *
 * 顺序永远是：先看该不该打扰（预算/间隔/材料够不够），再挑练什么动作，最后才让模型出问题。
 * 「没破绽」的时候不许硬说有错——没有破绽就走复述、预测、迁移。
 */
class VideoCoach(
    private val budget: InterruptBudget = InterruptBudget(),
    private val clock: () -> Long,
) {

    private var asked = 0
    private var lastAskMs: Long? = null
    private var lastMove: ThinkMove? = null

    fun askedCount(): Int = asked

    fun consider(input: CoachInput): AskPlan {
        val slice = input.slice
        if (slice.isEmpty) return AskPlan.Wait("还没有听到内容")
        if (asked >= budget.maxPerSession) return AskPlan.Wait("这场已经问了 $asked 次，先听")
        val span = slice.toMs - slice.fromMs
        if (span < budget.minMaterialMs && input.elapsedMs < budget.minMaterialMs) {
            return AskPlan.Wait("才铺开 ${span / 1_000} 秒，再听一会")
        }
        val last = lastAskMs
        if (last != null && input.nowMs - last < budget.minGapMs) {
            return AskPlan.Wait("刚问过 ${(input.nowMs - last) / 1_000} 秒，先让它讲完这段")
        }
        val move = pickMove(slice, input.recent)
        return AskPlan.Ask(
            system = systemPrompt(input.goal, move),
            user = userPrompt(input, slice, move),
            evidenceIds = slice.evidenceIds,
            move = move,
            epoch = slice.epoch,
            expiresAtMs = input.nowMs + budget.questionTtlMs,
            why = why(slice, input.recent, move),
        )
    }

    /** 记一次真的问出去了（计预算、记动作） */
    fun onAsked(atMs: Long, move: ThinkMove) {
        asked++
        lastAskMs = atMs
        lastMove = move
    }

    /**
     * 挑动作：
     * 1. 上次没到 / 差一点的那个动作先复练一次（同一个能力不往前跳过）；
     * 2. 材料里有归纳或因果的苗头 → 找条件（或反例）；
     * 3. 都没有 → 预测 → 复述 → 迁移 轮着来，不重复同一个。
     */
    fun pickMove(slice: ContextSlice, recent: List<AttemptMemory>): ThinkMove {
        val weak = recent.lastOrNull { it.verdict == Verdict.MISS || it.verdict == Verdict.NEAR }
        if (weak != null) return weak.move
        val text = slice.text
        val flaw = FlawSignals.detect(text)
        val hasQuestion = slice.segments.any { it.text.contains("?") || it.text.contains("？") }
        return when {
            flaw -> if (lastMove == ThinkMove.FIND_GAP) ThinkMove.COUNTER else ThinkMove.FIND_GAP
            hasQuestion -> ThinkMove.PREDICT
            lastMove == null || lastMove == ThinkMove.RECALL -> ThinkMove.PREDICT
            lastMove == ThinkMove.PREDICT -> ThinkMove.RECALL
            else -> ThinkMove.TRANSFER
        }
    }

    private fun why(slice: ContextSlice, recent: List<AttemptMemory>, move: ThinkMove): String {
        val weak = recent.lastOrNull { it.verdict == Verdict.MISS || it.verdict == Verdict.NEAR }
        return when {
            weak != null -> "上次「${weak.move.label}」差在：${weak.delta.take(20)}"
            FlawSignals.detect(slice.text) -> "这段有一处把话说过头的苗头，让他补条件"
            else -> "没有明显破绽，换个动作（${move.label}）练"
        }
    }

    private fun systemPrompt(goal: String, move: ThinkMove): String = buildString {
        appendLine("你是坐在用户旁边一起看视频的同学，不是助手，也不是老师。说话口语，一次只问一句。")
        appendLine("这次要弄懂的是：${goal.ifBlank { "（没说，就跟着内容走）" }}")
        appendLine("只能就【材料】里真实出现的字提问，不许编材料里没有的东西，不许拿常识当材料说的。")
        appendLine("没有明显破绽时不要硬说人家错了：用复述、预测、迁移。")
        appendLine("这一问的思考动作：${move.label}。")
        appendLine("只输出四行，不要别的：")
        appendLine("【问题】一句开放的问法，不要二选一，不要给答案")
        appendLine("【判据】你心里认定什么样算答到（这一行不会念给用户听）")
        appendLine("【依据】材料里支持这一问的证据编号，用逗号分开")
        appendLine("【动作】${move.label}")
    }

    private fun userPrompt(input: CoachInput, slice: ContextSlice, move: ThinkMove): String {
        val mem = input.recent.takeLast(3).joinToString("；") { "${it.move.label}:${it.verdict.label}${if (it.delta.isBlank()) "" else "（${it.delta.take(20)}）"}" }
        return buildString {
            appendLine("【材料】")
            appendLine(slice.text.take(1_500))
            appendLine()
            appendLine("【证据编号】${slice.evidenceIds.joinToString(",")}")
            appendLine("【段落】${slice.segments.size} 段，从第 ${slice.fromMs / 1_000} 秒到第 ${slice.toMs / 1_000} 秒")
            if (slice.gapsMs > 0) appendLine("【缺口】有 ${slice.gapsMs / 1_000} 秒没有声音，别把断掉的当连续的")
            if (mem.isNotEmpty()) appendLine("【以前】$mem")
            appendLine("【动作】${move.label}")
        }
    }
}

/** 模型回复的解析结果：成了给决策，没成给一条明确的拒绝理由（上层照 WAIT 处理） */
sealed interface CoachParse {
    data class Ok(val decision: CoachDecision) : CoachParse
    data class Reject(val reason: String) : CoachParse

    /** 任何时候都能转成一条 WAIT 决策，方便上层统一处理 */
    fun asDecision(epoch: Int, expiresAtMs: Long): CoachDecision = when (this) {
        is Ok -> decision
        is Reject -> CoachDecision(CoachAction.WAIT, "", reason, emptyList(), reason, epoch, expiresAtMs)
    }
}

/**
 * 解析模型给的那四行。拦三件事：
 * 1. 没有依据、或依据编号不在材料里 → 不接受（问题必须有出处）；
 * 2. 判据原样混进了问题里 → 不接受（没答就泄题）；
 * 3. 不是问句 → 不接受（那不是在问）。
 */
object CoachReply {

    private val PROBLEM = Regex("""【问题】\s*(.+)""")
    private val CRITERIA = Regex("""【判据】\s*(.+)""")
    private val EVIDENCE = Regex("""【依据】\s*([0-9,\s、]*)""")
    private val MOVE = Regex("""【动作】\s*(\S+)""")

    fun parse(raw: String, allowedIds: Set<Long>, epoch: Int, expiresAtMs: Long): CoachParse {
        val question = PROBLEM.find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        if (question.isEmpty()) return CoachParse.Reject("没给问题")
        val criteria = CRITERIA.find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val ids = EVIDENCE.find(raw)?.groupValues?.get(1).orEmpty()
            .split(',', '、')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it in allowedIds }
        if (ids.isEmpty()) return CoachParse.Reject("没有能在材料里对上的依据")
        if (!isQuestion(question)) return CoachParse.Reject("这不像一句问话")
        if (criteria.isNotEmpty() && leaked(question, criteria)) return CoachParse.Reject("判据混进了问题，等于先给答案")
        val moveLabel = MOVE.find(raw)?.groupValues?.get(1).orEmpty().trim()
        val move = ThinkMove.entries.firstOrNull { it.label == moveLabel }
        val action = if (move == null) CoachAction.FOLLOW_UP else CoachAction.ASK
        return CoachParse.Ok(
            CoachDecision(
                action = action,
                question = question,
                criteria = criteria,
                evidenceIds = ids,
                reason = if (move == null) "动作没认出来，按追问处理" else "动作：${move.label}",
                epoch = epoch,
                expiresAtMs = expiresAtMs,
            ),
        )
    }

    fun isQuestion(text: String): Boolean =
        text.endsWith("？") || text.endsWith("?") || text.endsWith("吗") || text.endsWith("呢") || text.contains("？")

    /** 判据里有 6 个字以上原样出现在问题里，就算泄题 */
    fun leaked(question: String, criteria: String): Boolean {
        val c = criteria.trim()
        if (c.length < 6) return false
        for (i in 0..(c.length - 6)) {
            if (question.contains(c.substring(i, i + 6))) return true
        }
        return false
    }
}

/** 判一次作答的结果 */
sealed interface JudgeParse {
    data class Ok(val verdict: Verdict, val delta: String) : JudgeParse
    data object Failed : JudgeParse
}

/**
 * 解析判定。**解析不出来就返回 Failed**，上层据此记 NONE（没判成），绝不记答错：
 * 把识别故障算成能力问题是这套东西最要命的错误。
 */
object JudgeReply {

    private val VERDICT = Regex("""【判定】\s*(到了|差一点|没到|HIT|NEAR|MISS)""")
    private val DELTA = Regex("""【差值】\s*(.+)""")

    fun parse(raw: String): JudgeParse {
        val v = VERDICT.find(raw)?.groupValues?.get(1) ?: return JudgeParse.Failed
        val verdict = when (v) {
            "到了", "HIT" -> Verdict.HIT
            "差一点", "NEAR" -> Verdict.NEAR
            else -> Verdict.MISS
        }
        val delta = DELTA.find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        return JudgeParse.Ok(verdict, delta)
    }

    fun systemPrompt(): String = buildString {
        appendLine("你在看学生刚说的一段话，判断他有没有真的想到。不评价性格，只判这一次。")
        appendLine("只输出两行：")
        appendLine("【判定】到了／差一点／没到")
        appendLine("【差值】具体差在哪个动作或哪个条件上，一句话，不客套")
        appendLine("他说的和材料不符、或者只是复述原话没自己推，都算没到。")
    }

    fun userPrompt(question: String, criteria: String, answer: String, material: String): String = buildString {
        appendLine("【材料】")
        appendLine(material.take(800))
        appendLine("【问的是】$question")
        appendLine("【心里想要的】$criteria")
        appendLine("【他说的】$answer")
    }
}
