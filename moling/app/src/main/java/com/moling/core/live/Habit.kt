package com.moling.core.live

import kotlinx.serialization.Serializable

/*
 * 反免疫（v9）：任何一种打断用久了都会被大脑当成背景噪音。
 * 她记下每次用哪种方式出现、他怎么反应；同一种方式连着两次被秒划掉 / 敷衍 / 不理，就让它歇 48 小时，换别的。
 * 挑方式时偏向“好久没用过的”和“以前管用的”，再加一点随机——他摸不清她下次会怎么来。
 * 拦人（GATE）不在这里随机：拦不拦由她判断 + 拦人的硬规矩决定，这里只在拦人歇着时换一种不挡路的方式。
 */

/** 她出现的方式。 */
@Serializable
enum class Modality(val label: String) {
    PEEK("底部卡片"),
    GATE("拦在面前"),
    WHISPER("耳机里说一句"),
    PET("桌宠跳出来"),
    STARE("盯着你看"),
    COUNCIL("参谋弹幕"),
    QUIZ("捉虫题"),
}

/** 他怎么回应的。 */
@Serializable
enum class Reaction(val bad: Boolean) {
    REPLIED(false),
    PERFUNCTORY(true),
    /** 3 秒内划掉。 */
    DISMISSED_FAST(true),
    DISMISSED(false),
    /** 一直没理（到时间自己消失）。 */
    IGNORED(true),
    /** 点了“急事”。 */
    ESCAPED(false),
}

@Serializable
data class HabitMark(val modality: Modality, val at: Long, val reaction: Reaction? = null, val ms: Long = 0)

@Serializable
data class HabitBook(
    val marks: List<HabitMark> = emptyList(),
    /** 歇着的方式 → 歇到什么时候。 */
    val cooling: Map<Modality, Long> = emptyMap(),
)

/** 此刻能用哪些方式（App 按权限和设备情况填）。 */
data class Can(
    /** 有“显示在其他应用上层”：桌宠、盯着、参谋弹幕。 */
    val overlay: Boolean = false,
    /** 戴着耳机（或他允许外放）、不在安静时段。 */
    val whisper: Boolean = false,
    /** 有能出题的材料（体系图论断、她的判断）。 */
    val quiz: Boolean = false,
)

object Habits {
    const val COOL_MS = 48 * 3_600_000L
    const val KEEP = 120

    fun cooling(b: HabitBook, m: Modality, now: Long): Boolean = (b.cooling[m] ?: 0L) > now

    fun shown(b: HabitBook, m: Modality, now: Long): HabitBook = b.copy(marks = (b.marks + HabitMark(m, now)).takeLast(KEEP))

    /** 记下他的反应；同一种方式最近两次都“不好”，就歇 48 小时。 */
    fun react(b: HabitBook, m: Modality, r: Reaction, ms: Long, now: Long): HabitBook {
        val i = b.marks.indexOfLast { it.modality == m && it.reaction == null }
        val marks = if (i >= 0) b.marks.toMutableList().also { it[i] = it[i].copy(reaction = r, ms = ms) } else b.marks + HabitMark(m, now, r, ms)
        val lastTwo = marks.filter { it.modality == m && it.reaction != null }.takeLast(2)
        val tired = lastTwo.size == 2 && lastTwo.all { it.reaction!!.bad }
        return b.copy(marks = marks.takeLast(KEEP), cooling = if (tired) b.cooling + (m to now + COOL_MS) else b.cooling)
    }

    fun options(can: Can): List<Modality> = buildList {
        add(Modality.PEEK)
        if (can.overlay) { add(Modality.PET); add(Modality.STARE); add(Modality.COUNCIL) }
        if (can.whisper) add(Modality.WHISPER)
        if (can.quiz) add(Modality.QUIZ)
    }

    /** 管用率：回应过的里面“不坏”的比例；没记录算 0.5。 */
    fun works(b: HabitBook, m: Modality): Double {
        val r = b.marks.filter { it.modality == m && it.reaction != null }.takeLast(10)
        return if (r.isEmpty()) 0.5 else r.count { !it.reaction!!.bad } / r.size.toDouble()
    }

    /**
     * 挑一种不挡路的方式。[roll] 0..1（测试里固定）。
     * 权重 = 1 + 新鲜度（多久没用，按天算，最多 +2） + 管用率 × 2；歇着的不选；都歇着就用底部卡片。
     */
    fun pick(b: HabitBook, can: Can, now: Long, roll: Double): Modality {
        val opts = options(can).filterNot { cooling(b, it, now) }
        if (opts.isEmpty()) return Modality.PEEK
        val w = opts.map { m ->
            val last = b.marks.lastOrNull { it.modality == m }?.at ?: 0L
            val fresh = if (last == 0L) 2.0 else ((now - last) / 86_400_000.0).coerceAtMost(2.0)
            m to 1.0 + fresh + works(b, m) * 2
        }
        var x = roll.coerceIn(0.0, 0.999999) * w.sumOf { it.second }
        for ((m, wt) in w) {
            if (x < wt) return m
            x -= wt
        }
        return w.last().first
    }

    /** 给调试页 / 了解页看：每种方式用了几次、管用率、歇到几点。 */
    fun summary(b: HabitBook, now: Long): List<String> = Modality.entries.mapNotNull { m ->
        val n = b.marks.count { it.modality == m }
        if (n == 0) null
        else "${m.label}：$n 次，管用 ${(works(b, m) * 100).toInt()}%" + if (cooling(b, m, now)) "（歇着）" else ""
    }
}
