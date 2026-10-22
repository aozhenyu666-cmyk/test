package com.moling.core.live

import com.moling.core.flow.Flow
import kotlinx.serialization.Serializable

/*
 * 认知画像（v13 W10 T8，L3 基础）：只从她真实的出现记录（[HabitBook.marks]）里数——
 * 哪种方式、哪个时段，他回应 / 敷衍 / 跳过 / 明确拒绝 / 点急事各几次；没被看到的出现单列，不算他冷落。
 *
 * 规矩：
 *  - 样本不足 [MIN_SAMPLES] 次就写“样本不足”，不给比例，不提规律；
 *  - 记录里没有的情境（他在哪、在用什么 App、戴没戴耳机……）一律写“未知”，不编；
 *  - “有用”目前没有记录来源，写“未记录”，不拿“回了”冒充“有用”；
 *  - 样本够了才“提出”一条规律（PROPOSED）；他点“对”才算 ACCEPTED，点“停用”就 DISABLED；
 *    这一批的规律只给他看，不改她的出现策略、不写进关于他的长期判断（下一批再接表现策略）。
 */

@Serializable
enum class PatternState { PROPOSED, ACCEPTED, DISABLED }

/** 一组出现记录的计数（只数有反应、真的被看到的那些做样本）。 */
data class Tally(
    val shown: Int = 0,
    val seen: Int = 0,
    val unobserved: Int = 0,
    val replied: Int = 0,
    val perfunctory: Int = 0,
    val skipped: Int = 0,
    val refused: Int = 0,
    val escaped: Int = 0,
    /** 还没有反应（刚出现 / 记录没跟上）。 */
    val open: Int = 0,
) {
    /** 样本 = 被看到了、也有反应的次数。 */
    val samples: Int get() = replied + perfunctory + skipped + refused + escaped
    val enough: Boolean get() = samples >= CognitiveProfile.MIN_SAMPLES
    /** 回应率；样本不足返回 null（不给数字）。 */
    val replyRate: Double? get() = if (enough) replied / samples.toDouble() else null
}

data class ProfileRow(val label: String, val tally: Tally) {
    fun line(): String {
        val t = tally
        val head = "$label：出现 ${t.shown + t.seen + t.unobserved} 次（显示 ${t.shown} · 有机会看到 ${t.seen} · 没被看到 ${t.unobserved}）"
        val counts = "回应 ${t.replied} · 敷衍 ${t.perfunctory} · 跳过 ${t.skipped} · 明确拒绝 ${t.refused} · 急事 ${t.escaped}"
        val rate = t.replyRate?.let { "回应率 ${(it * 100).toInt()}%" } ?: "样本不足（${t.samples}/${CognitiveProfile.MIN_SAMPLES}），先不下结论"
        return "$head\n$counts\n$rate"
    }
}

data class Pattern(val id: String, val text: String, val samples: Int, val state: PatternState)

data class Profile(
    val total: Tally,
    val byModality: List<ProfileRow>,
    val byTime: List<ProfileRow>,
    /** 记录里没有的情境：一律未知。 */
    val unknown: List<String>,
    val patterns: List<Pattern>,
)

object CognitiveProfile {
    const val MIN_SAMPLES = 10
    const val HIGH = 0.6
    const val LOW = 0.2

    /** 记录里没有、所以只能写“未知”的情境。 */
    val UNKNOWN_CONTEXTS = listOf("他当时在哪：未知", "在用什么 App：未知", "戴没戴耳机：未知（只记了她选的方式）", "有用：未记录（还没有“有用”按钮的记录）")

    /** 时段：只用出现时刻（北京时间），这是记录里真有的。 */
    fun slot(at: Long): String {
        val m = Flow.minuteOf(at)
        return when {
            m < 7 * 60 -> "夜里（0–7 点）"
            m < 12 * 60 -> "上午（7–12 点）"
            m < 18 * 60 -> "下午（12–18 点）"
            m < 23 * 60 -> "晚上（18–23 点）"
            else -> "夜里（23–24 点）"
        }
    }

    private val SLOTS = listOf("上午（7–12 点）", "下午（12–18 点）", "晚上（18–23 点）", "夜里（0–7 点）", "夜里（23–24 点）")

    fun tally(marks: List<HabitMark>): Tally = marks.fold(Tally()) { t, m ->
        val e = when (m.exposure) {
            Exposure.SHOWN -> t.copy(shown = t.shown + 1)
            Exposure.SEEN -> t.copy(seen = t.seen + 1)
            Exposure.UNOBSERVED -> t.copy(unobserved = t.unobserved + 1)
        }
        if (m.exposure == Exposure.UNOBSERVED) return@fold e
        when (m.reaction) {
            null -> e.copy(open = e.open + 1)
            Reaction.REPLIED -> e.copy(replied = e.replied + 1)
            Reaction.PERFUNCTORY -> e.copy(perfunctory = e.perfunctory + 1)
            Reaction.DISMISSED, Reaction.DISMISSED_FAST, Reaction.IGNORED -> e.copy(skipped = e.skipped + 1)
            Reaction.STOPPED -> e.copy(refused = e.refused + 1)
            Reaction.ESCAPED -> e.copy(escaped = e.escaped + 1)
        }
    }

    /** [decisions] = 他对规律的选择（id → ACCEPTED / DISABLED）；没选过的、样本够的规律是 PROPOSED。 */
    fun build(book: HabitBook, decisions: Map<String, PatternState> = emptyMap()): Profile {
        val marks = book.marks
        val byModality = Modality.entries.mapNotNull { m ->
            marks.filter { it.modality == m }.takeIf { it.isNotEmpty() }?.let { ProfileRow(m.label, tally(it)) }
        }
        val byTime = SLOTS.mapNotNull { s ->
            marks.filter { slot(it.at) == s }.takeIf { it.isNotEmpty() }?.let { ProfileRow(s, tally(it)) }
        }
        val patterns = buildList {
            Modality.entries.forEach { m ->
                val t = tally(marks.filter { it.modality == m })
                propose("m:${m.name}", "用「${m.label}」找他", t, decisions)?.let { add(it) }
            }
            SLOTS.forEach { s ->
                val t = tally(marks.filter { slot(it.at) == s })
                propose("t:$s", "${s}找他", t, decisions)?.let { add(it) }
            }
        }
        return Profile(tally(marks), byModality, byTime, UNKNOWN_CONTEXTS, patterns)
    }

    private fun propose(id: String, what: String, t: Tally, decisions: Map<String, PatternState>): Pattern? {
        val rate = t.replyRate ?: return null
        val hiLo = when {
            rate >= HIGH -> "$what，他多半会回（${t.samples} 次里回了 ${t.replied} 次）"
            rate <= LOW -> "$what，他多半不理（${t.samples} 次里只回了 ${t.replied} 次）"
            else -> return null
        }
        val id2 = id + if (rate >= HIGH) ":hi" else ":lo"
        return Pattern(id2, hiLo, t.samples, decisions[id2] ?: PatternState.PROPOSED)
    }
}
