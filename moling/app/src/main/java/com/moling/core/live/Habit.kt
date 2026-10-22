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
    /** 明确说“先不聊了 / 别管我”：立刻进安静期；不算一次“他不理我”。 */
    STOPPED(false),
}

/**
 * 这次出现真的被看到了吗（R3）：没连耳机、被系统拦了浮层、页面根本没显示，都不能算“他不理我”。
 * 旧记录没有这个字段，按 [SHOWN] 读。
 */
@Serializable
enum class Exposure(val label: String) {
    /** 真的显示了。 */
    SHOWN("显示了"),
    /** 有机会看到（在通知栏 / 耳机里），但没确认他看见。 */
    SEEN("有机会看到"),
    /** 没被看到（被系统拦、没连耳机、页面不在），不计他的反应。 */
    UNOBSERVED("没被看到"),
}

@Serializable
data class HabitMark(
    val modality: Modality,
    val at: Long,
    val reaction: Reaction? = null,
    val ms: Long = 0,
    val exposure: Exposure = Exposure.SHOWN,
)

@Serializable
data class HabitBook(
    val marks: List<HabitMark> = emptyList(),
    /** 歇着的方式 → 歇到什么时候。 */
    val cooling: Map<Modality, Long> = emptyMap(),
    /** 他明确说了“先不聊了 / 别管我”→ 安静到什么时候。安静期内她不自发出声（他手动开电台 / 找她不算）。 */
    val quietUntil: Long = 0,
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

    /** 明确停止后的安静期：默认 2 小时；他随时可以恢复。 */
    const val QUIET_MS = 2 * 3_600_000L

    fun cooling(b: HabitBook, m: Modality, now: Long): Boolean = (b.cooling[m] ?: 0L) > now

    /** 安静期内她不自发出声；手动开电台 / 主动找她不算。 */
    fun isQuiet(b: HabitBook, now: Long): Boolean = b.quietUntil > now

    fun quietLine(b: HabitBook, now: Long): String {
        if (!isQuiet(b, now)) return ""
        val min = ((b.quietUntil - now) / 60_000L).coerceAtLeast(1)
        return if (min >= 60) "安静期：还剩约 ${((min / 60.0)).let { "%.1f".format(it) }} 小时" else "安静期：还剩约 $min 分钟"
    }

    /**
     * 他明确说“先不聊了 / 别管我”：只给这一次出现（[modality]）记一笔 STOPPED，并进安静期。
     * 找不到这次出现就不记——不拿 PEEK 之类的形态补一条假记录（F11-4）。明确停止不算冷落。
     */
    fun explicitStop(b: HabitBook, now: Long, modality: Modality? = null): HabitBook {
        val i = if (modality == null) -1 else b.marks.indexOfLast { it.modality == modality && it.reaction == null }
        val marks = if (i >= 0) b.marks.toMutableList().also { it[i] = it[i].copy(reaction = Reaction.STOPPED) } else b.marks
        return b.copy(marks = marks.takeLast(KEEP), quietUntil = now + QUIET_MS)
    }

    /** 他点“恢复”：立刻结束安静期（形态冷却不动，免得马上又被同一种方式烦）。 */
    fun restore(b: HabitBook): HabitBook = b.copy(quietUntil = 0)

    fun shown(b: HabitBook, m: Modality, now: Long, exposure: Exposure = Exposure.SHOWN): HabitBook =
        b.copy(marks = (b.marks + HabitMark(m, now, exposure = exposure)).takeLast(KEEP))

    /** 最近一次出现有没有真的被看到（没看到就别算他冷落）。 */
    fun lastExposure(b: HabitBook, m: Modality): Exposure? =
        b.marks.lastOrNull { it.modality == m }?.exposure

    /** 记下他的反应；同一种方式最近两次都“不好”，就歇 48 小时。没被看到的出现不计入“不好”。 */
    fun react(b: HabitBook, m: Modality, r: Reaction, ms: Long, now: Long): HabitBook {
        val i = b.marks.indexOfLast { it.modality == m && it.reaction == null }
        val marks = if (i >= 0) b.marks.toMutableList().also { it[i] = it[i].copy(reaction = r, ms = ms) } else b.marks + HabitMark(m, now, r, ms)
        val seen = marks.filter { it.modality == m && it.reaction != null && it.exposure != Exposure.UNOBSERVED }
        val tired = seen.size >= 2 && seen.takeLast(2).all { it.reaction!!.bad }
        return b.copy(marks = marks.takeLast(KEEP), cooling = if (tired) b.cooling + (m to now + COOL_MS) else b.cooling)
    }

    fun options(can: Can): List<Modality> = buildList {
        add(Modality.PEEK)
        if (can.overlay) { add(Modality.PET); add(Modality.STARE); add(Modality.COUNCIL) }
        if (can.whisper) add(Modality.WHISPER)
        if (can.quiz) add(Modality.QUIZ)
    }

    /** 管用率：真的被看到、有反应的里面“不坏”的比例；没记录算 0.5。 */
    fun works(b: HabitBook, m: Modality): Double {
        val r = b.marks.filter { it.modality == m && it.reaction != null && it.exposure != Exposure.UNOBSERVED }.takeLast(10)
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

    /** 给调试页 / 了解页看：每种方式用了几次、管用率、歇到几点；安静期单独一行。 */
    fun summary(b: HabitBook, now: Long): List<String> = buildList {
        quietLine(b, now).takeIf { it.isNotBlank() }?.let { add(it) }
        Modality.entries.forEach { m ->
            val n = b.marks.count { it.modality == m }
            if (n > 0) add("${m.label}：$n 次，管用 ${(works(b, m) * 100).toInt()}%" + if (cooling(b, m, now)) "（歇着）" else "")
        }
    }
}
