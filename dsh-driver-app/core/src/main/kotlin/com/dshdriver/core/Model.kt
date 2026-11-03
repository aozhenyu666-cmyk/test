package com.dshdriver.core

enum class Phase { IDLE, LOCKED, INTERRUPTED_BY_SWITCH, PAUSED, WRAPPING, ENDED }

/** 登记过的一张卡。kind ∈ open | one_word | ab | read_two_words | say_to_live | conclusion。 */
class Card(
    val id: String,
    val kind: String,
    val text: String,
    val options: Options?,
    val expects: String,
    var intent: String,
    val source: String,
    val anchor: String?,
    val missing: String?,
    val resumes: String?,
    val level: Int,
    val generatedAt: Long,
) {
    var answered = false
    val anchorWords: List<String> get() = listOfNotNull(missing)
}

/** 驾驶员发给界面的一条指令。 */
data class Directive(
    val seq: Int,
    val type: String,
    val at: Long,
    val level: Int = 0,
    val card: Card? = null,
    val relaunch: String? = null,
    val reason: String? = null,
    val until: Long? = null,
    val conclusion: String? = null,
    val summary: Telemetry.Summary? = null,
) {
    companion object {
        const val SHOW = "show_card"
        const val PULLBACK = "pullback"
        const val ESCALATE = "escalate"
        const val RELEASE = "release"
        const val END = "end"
    }
}

/** 你自己写的主线目标和惯性死穴。 */
data class Profile(val goals: List<String> = emptyList(), val habits: List<String> = emptyList())

/** 跨会话统计：骰子推进率、切屏分钟分布、回复耗时。 */
class History(
    val dice: MutableMap<String, DieStat> = mutableMapOf(),
    val escapeMinutes: MutableMap<Int, Int> = mutableMapOf(),
    var sessions: Int = 0,
    val latencies: MutableList<Long> = mutableListOf(),
) {
    fun medianLatencyMs(): Long? = latencies.sorted().takeIf { it.size >= 5 }?.let { it[it.size / 2] }

    /** 只说有样本支撑的规律（给模型的隐秘层用）。 */
    fun patterns(): List<String> {
        val out = mutableListOf<String>()
        val total = escapeMinutes.values.sum()
        if (total >= 3) {
            val peak = escapeMinutes.maxBy { it.value }
            out += "过去 $sessions 次锁定里，切走最常发生在第 ${peak.key} 分钟前后（${peak.value}/$total 次）"
        }
        dice.filter { it.value.tries >= 3 }.maxByOrNull { it.value.advanced.toDouble() / it.value.tries }?.let {
            out += "对他最管用的降维骰子是「${it.key}」（${it.value.advanced}/${it.value.tries} 次推进）"
        }
        medianLatencyMs()?.let { out += "他平时回驾驶员的中位耗时约 ${Math.round(it / 1000.0)} 秒" }
        return out
    }
}

/**
 * 交给"大脑"（配对后的 DSH Go 插件）的出题请求：三层上下文的事实部分。
 * 记忆（fish-memory）由插件那边自己补，这里不带。
 */
data class ComposeRequest(
    val intent: String,
    val cog: CogState,
    val die: String?,
    val topic: String,
    val task: String,
    val minute: Int,
    val minutes: Int,
    val breakpoint: String?,
    val lastQuestion: String?,
    val missing: String?,
    val recentReplies: List<String>,
    val asked: List<String>,
    val goals: List<String>,
    val habits: List<String>,
    val patterns: List<String>,
    val unresolved: List<String>,
)

/** 出题的大脑。拿不到、超时、不合规都返回 null，驾驶员就用模板。实现方负责自己的超时。 */
fun interface Brain {
    fun compose(request: ComposeRequest): Draft?
}

object Drafts {
    private val KINDS = setOf("open", "one_word", "ab", "read_two_words", "say_to_live", "conclusion")
    val EXPECTS = mapOf("open" to "evidence", "one_word" to "evidence", "ab" to "choice", "read_two_words" to "words", "say_to_live" to "spoken", "conclusion" to "conclusion")

    /** 校验大脑给的草稿；限速/逃逸时只许 ab 或 read_two_words，ab 必须两个选项都有。 */
    fun validate(d: Draft?, state: Cog): Draft? {
        if (d == null || d.kind !in KINDS || d.text.isBlank()) return null
        if ((state == Cog.THROTTLED_EMPTY || state == Cog.ESCAPED) && d.kind != "ab" && d.kind != "read_two_words") return null
        if (d.kind == "ab" && (d.options == null || d.options.a.isBlank() || d.options.b.isBlank())) return null
        return d.copy(
            text = d.text.trim().take(80),
            expects = EXPECTS.getValue(d.kind),
            options = d.options?.let { Options(it.a.trim().take(16), it.b.trim().take(16)) },
            anchor = d.anchor?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
            missing = d.missing?.trim()?.take(30)?.takeIf { it.isNotEmpty() },
            source = "llm",
        )
    }
}
