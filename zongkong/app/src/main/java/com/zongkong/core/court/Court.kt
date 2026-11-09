package com.zongkong.core.court

import kotlinx.serialization.Serializable
import java.time.LocalDate

/*
 * 公审：把四个部门焊成一条不能跳步的流水线。总控时间每一环都要在这里留下东西，下一环才放行。
 *
 *   信息收集部：每条念头必须分进三个槽位之一——① 战略危机（交谋划部）② 日常 / 常识漏洞（交行为部）③ 逃避型杂念（封存）。
 *   谋划思考部：一张《全局战略底线表》（前线死令 + 退路底牌）。每个临时起意都要拿它比一比：
 *              “这事能让行测多拿分，还是能帮我找到下个月的工作？”答不上来就枪毙。
 *   统筹规划部：熔断——行测五步训练连续两天挂零，严管打开（娱乐应用打不开），直到当天补够。
 *   行为管理部：三层——微习惯盲区表（点名确认）、防错规避库（“遇到 A 就做 B”）、五步思维 × 行测。
 *   封：签一条《强制执行令》，下次总控时间一开场先查账。
 *
 * 这里全是纯函数，日期用逻辑日（yyyy-MM-dd，和 DayClock.dateKey 一致）。
 */

/** 信息收集部的三个槽位。 */
@Serializable
enum class Slot(val label: String, val to: String) {
    CRISIS("① 战略危机", "交谋划部"),
    SLIP("② 日常 / 常识漏洞", "交行为部"),
    ESCAPE("③ 逃避型杂念", "封存"),
}

/** 一条念头被分到了哪个槽位。 */
@Serializable
data class Sorted(val noteKey: String, val slot: Slot, val at: Long, val line: String = "")

/** 《全局战略底线表》。 */
@Serializable
data class Baseline(
    /** 在备考的是什么（比如“省考”）。 */
    val exam: String = "",
    /** 笔试日期 yyyy-MM-dd。 */
    val examDate: String = "",
    /** 前线死令：剩下这些天一定要做到的。 */
    val frontline: String = "",
    /** 退路底牌：没进面，立刻去投的岗位（至少一类）。 */
    val fallback: List<String> = emptyList(),
    /** 退路什么时候启动（比如“考完第 2 天”）。 */
    val fallbackStart: String = "",
    val updatedAt: Long = 0,
) {
    val ready: Boolean get() = frontline.isNotBlank() && fallback.any { it.isNotBlank() }
}

/** 临时起意的判决。 */
@Serializable
enum class Verdict(val label: String) {
    EXAM("能让行测多拿分"),
    JOB("能帮我找到下个月的工作"),
    KILL("都不能 → 枪毙"),
}

@Serializable
data class Impulse(val id: String, val at: Long, val text: String, val verdict: Verdict)

/** 微习惯盲区表的一条（最多 3 条）。 */
@Serializable
data class Habit(val id: String, val label: String)

/** 防错规避库的一条死规则：如果遇到场景 A，立刻执行动作 B。 */
@Serializable
data class GuardRule(
    val id: String,
    val at: Long,
    /** 场景 A（预判：什么信号出现时该警惕）。 */
    val scene: String,
    /** 动作 B。 */
    val action: String,
    /** 回想：上次哪个瞬间走神了。 */
    val slip: String = "",
    /** 从收集箱哪一条来的（② 槽位）。 */
    val source: String = "",
)

/** 行测五步：每道题都要跑完这五个槽位才算一题。 */
@Serializable
enum class DrillStep(val label: String, val ask: String) {
    CONDENSE("精简", "题干压成哪几个核心词？（30 字以内）"),
    FORESEE("预判", "看提问方式，它考什么、陷阱在哪？"),
    ASSOCIATE("联想", "联想到哪个秒杀公式、规律或常识？"),
    QUESTION("提问", "对拿不准的选项，向自己提一个问题来排错"),
    RECALL("回想", "对完答案先别看解析：卡点在哪？"),
}

@Serializable
data class Drill(
    val id: String,
    val at: Long,
    /** 逻辑日。 */
    val date: String,
    /** 哪一题（模块 + 题号，或者一句题干）。 */
    val topic: String,
    val steps: Map<DrillStep, String> = emptyMap(),
    /** 对 / 错 / 蒙的。 */
    val result: String = "",
) {
    val complete: Boolean get() = topic.isNotBlank() && DrillStep.entries.all { steps[it].orEmpty().isNotBlank() }
}

/** 《强制执行令》：一条、具体、有时限，不讨价还价。 */
@Serializable
data class Order(
    val id: String,
    val issuedAt: Long,
    val text: String,
    val dueAt: Long,
    val checkedAt: Long = 0,
    val done: Boolean = false,
    val note: String = "",
) {
    val open: Boolean get() = checkedAt == 0L
}

@Serializable
data class CourtBook(
    /** 第一次用公审的时间（熔断只看这之后的日子）。 */
    val since: Long = 0,
    val baseline: Baseline = Baseline(),
    val habits: List<Habit> = Court.DEFAULT_HABITS,
    /** 逻辑日 → 当天确认过的微习惯。 */
    val habitChecks: Map<String, List<String>> = emptyMap(),
    val sorted: List<Sorted> = emptyList(),
    val rules: List<GuardRule> = emptyList(),
    val drills: List<Drill> = emptyList(),
    val impulses: List<Impulse> = emptyList(),
    val orders: List<Order> = emptyList(),
)

object Court {
    /** 每天至少几道行测五步。 */
    const val DAILY_DRILLS = 5
    /** 精简：一句话最多几个字。 */
    const val CONCISE_MAX = 30
    /** 微习惯要连续确认几天。 */
    const val HABIT_DAYS = 14
    const val MAX_HABITS = 3

    val DEFAULT_HABITS = listOf(
        Habit("window", "窗户关了吗"),
        Habit("cap", "牙膏盖合上了吗"),
        Habit("putback", "用完的东西归位了吗"),
    )

    // ---------- 收 ----------

    /** 精简过了没有：一句话，1–30 字，不换行。 */
    fun concise(text: String): Boolean = text.trim().let { it.isNotEmpty() && it.length <= CONCISE_MAX && '\n' !in it }

    fun sortedKeys(b: CourtBook): Set<String> = b.sorted.map { it.noteKey }.toSet()

    fun sort(b: CourtBook, noteKey: String, slot: Slot, now: Long, line: String = ""): CourtBook =
        b.copy(sorted = (b.sorted.filterNot { it.noteKey == noteKey } + Sorted(noteKey, slot, now, line.trim())).takeLast(500))

    /** 分到“② 日常 / 常识漏洞”、还没写成规则的。 */
    fun pendingSlips(b: CourtBook): List<Sorted> {
        val done = b.rules.map { it.source }.toSet()
        return b.sorted.filter { it.slot == Slot.SLIP && it.noteKey !in done }
    }

    // ---------- 谋 ----------

    fun setBaseline(b: CourtBook, base: Baseline, now: Long): CourtBook =
        b.copy(baseline = base.copy(fallback = base.fallback.map { it.trim() }.filter { it.isNotEmpty() }.take(3), updatedAt = now))

    /** 离笔试还有几天；没填日期或日期不对返回 null。 */
    fun daysLeft(b: CourtBook, today: String): Int? {
        val exam = date(b.baseline.examDate) ?: return null
        val d = date(today) ?: return null
        return (exam.toEpochDay() - d.toEpochDay()).toInt()
    }

    fun judge(b: CourtBook, id: String, text: String, verdict: Verdict, now: Long): CourtBook =
        b.copy(impulses = (b.impulses + Impulse(id, now, text.trim(), verdict)).takeLast(200))

    // ---------- 行 · 微习惯 ----------

    fun setHabits(b: CourtBook, labels: List<String>): CourtBook {
        val clean = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_HABITS)
        if (clean.isEmpty()) return b
        return b.copy(habits = clean.mapIndexed { i, l -> b.habits.firstOrNull { it.label == l } ?: Habit("h$i-" + l.hashCode().toString(36), l) })
    }

    fun confirmHabit(b: CourtBook, date: String, habitId: String): CourtBook {
        val day = b.habitChecks[date].orEmpty()
        if (habitId in day) return b
        // 只留最近 60 天
        val kept = (b.habitChecks + (date to day + habitId)).entries.sortedBy { it.key }.takeLast(60).associate { it.key to it.value }
        return b.copy(habitChecks = kept)
    }

    fun habitsDone(b: CourtBook, date: String): Boolean {
        val day = b.habitChecks[date].orEmpty()
        return b.habits.isNotEmpty() && b.habits.all { it.id in day }
    }

    /** 连续几天全部确认（今天还没确认完就从昨天往回数）。 */
    fun habitStreak(b: CourtBook, today: String): Int {
        var d = date(today) ?: return 0
        if (!habitsDone(b, d.toString())) d = d.minusDays(1)
        var n = 0
        while (n < 400 && habitsDone(b, d.toString())) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    // ---------- 行 · 防错规避库 ----------

    /** 写一条规则：场景和动作都要有，不然返回 null。 */
    fun addRule(b: CourtBook, id: String, scene: String, action: String, slip: String, source: String, now: Long): CourtBook? {
        if (scene.isBlank() || action.isBlank()) return null
        return b.copy(rules = (b.rules + GuardRule(id, now, scene.trim(), action.trim(), slip.trim(), source)).takeLast(300))
    }

    // ---------- 行 · 五步思维 × 行测 ----------

    fun addDrill(b: CourtBook, d: Drill): CourtBook? {
        if (!d.complete || !concise(d.steps[DrillStep.CONDENSE].orEmpty())) return null
        return b.copy(drills = (b.drills + d.copy(topic = d.topic.trim(), steps = d.steps.mapValues { it.value.trim() })).takeLast(2000))
    }

    fun drillsOn(b: CourtBook, date: String): Int = b.drills.count { it.date == date && it.complete }

    /** 熔断：行测五步连续两天挂零。 */
    data class Fuse(val zeroDays: Int, val doneToday: Int) {
        val needToday: Int get() = (DAILY_DRILLS - doneToday).coerceAtLeast(0)
        val text: String get() = "行测五步训练已经连续 $zeroDays 天挂零：熔断。今天做够 $DAILY_DRILLS 道（还差 $needToday 道）才解开"
    }

    /**
     * 熔断开着没有：填了笔试日期、还没考完；昨天和前天都是 0 道（只看开始用公审之后的日子）；今天还没做够。
     * 今天做够 [DAILY_DRILLS] 道马上解开。
     */
    fun fuse(b: CourtBook, today: String, sinceDate: String?): Fuse? {
        val exam = date(b.baseline.examDate) ?: return null
        val d = date(today) ?: return null
        if (d.isAfter(exam)) return null
        val start = sinceDate?.let { date(it) } ?: return null
        val doneToday = drillsOn(b, today)
        if (doneToday >= DAILY_DRILLS) return null
        // 前天得在开始用公审之后（含当天），不然装上第一天就熔断
        if (start.isAfter(d.minusDays(2))) return null
        if (drillsOn(b, d.minusDays(1).toString()) > 0 || drillsOn(b, d.minusDays(2).toString()) > 0) return null
        var zero = 0
        var x = d.minusDays(1)
        while (zero < 60 && !x.isBefore(start) && drillsOn(b, x.toString()) == 0) {
            zero++
            x = x.minusDays(1)
        }
        return Fuse(zero, doneToday)
    }

    // ---------- 封 · 执行令 ----------

    fun openOrder(b: CourtBook): Order? = b.orders.lastOrNull { it.open }

    /** 签一条执行令。还开着的旧令没查账，按“没做到”收掉。 */
    fun issue(b: CourtBook, id: String, text: String, dueAt: Long, now: Long): CourtBook {
        val closed = b.orders.map { if (it.open) it.copy(checkedAt = now, done = false, note = "没查账就签了新令") else it }
        return b.copy(orders = (closed + Order(id, now, text.trim(), dueAt)).takeLast(200))
    }

    fun check(b: CourtBook, id: String, done: Boolean, note: String, now: Long): CourtBook =
        b.copy(orders = b.orders.map { if (it.id == id && it.open) it.copy(checkedAt = now, done = done, note = note.trim()) else it })

    // ---------- 给总控（AI）看的底牌 ----------

    fun brief(b: CourtBook, today: String, sinceDate: String?, hhmm: (Long) -> String): String = buildString {
        val base = b.baseline
        append("《全局战略底线表》")
        if (!base.ready) append("（还没填完：前线死令、退路底牌缺一不可）")
        append('\n')
        val left = daysLeft(b, today)
        append("- 备考：${base.exam.ifBlank { "（没写）" }}，笔试 ${base.examDate.ifBlank { "（没填日期）" }}")
        left?.let { append(if (it >= 0) "，还剩 $it 天" else "，已经考完") }
        append('\n')
        append("- 前线死令：${base.frontline.ifBlank { "（空）" }}\n")
        append("- 退路底牌：${base.fallback.joinToString("；").ifBlank { "（空）" }}${base.fallbackStart.takeIf { it.isNotBlank() }?.let { "（$it 启动）" } ?: ""}\n")
        val done = drillsOn(b, today)
        append("- 行测五步：今天 $done/$DAILY_DRILLS 道，最近 7 天 ${last7(b, today)} 道\n")
        fuse(b, today, sinceDate)?.let { append("- 熔断中：${it.text}。只批行测训练，别的折腾一律不予立案\n") }
        openOrder(b)?.let { append("- 未查账的《强制执行令》：「${it.text}」，时限 ${hhmm(it.dueAt)}。一开场先查这条\n") }
        val slips = pendingSlips(b)
        if (slips.isNotEmpty()) append("- 防错规避库：${slips.size} 条踩坑还没写成“遇到 A 就做 B”\n")
        append("- 微习惯盲区：${b.habits.joinToString("、") { it.label }}；连续确认 ${habitStreak(b, today)}/$HABIT_DAYS 天\n")
        b.impulses.takeLast(3).takeIf { it.isNotEmpty() }?.let { l -> append("- 最近的临时起意：").append(l.joinToString("；") { "${it.text.take(20)}→${it.verdict.label}" }).append('\n') }
    }

    private fun last7(b: CourtBook, today: String): Int {
        val d = date(today) ?: return 0
        return (0L..6L).sumOf { drillsOn(b, d.minusDays(it).toString()) }
    }

    // ---------- 导出到 Obsidian ----------

    /** 整本公审档案写成一篇 Markdown（Obsidian 直接能读）。 */
    fun markdown(b: CourtBook, today: String, sinceDate: String?, hhmm: (Long) -> String): String = buildString {
        val base = b.baseline
        append("---\ntype: zongkong-court\nexported: $today\n---\n\n")
        append("# 公审档案 $today\n\n")
        append("## 全局战略底线表\n\n")
        append("| 项 | 内容 |\n|---|---|\n")
        append("| 备考 | ${cell(base.exam)} |\n")
        append("| 笔试日期 | ${cell(base.examDate)}${daysLeft(b, today)?.let { " （还剩 $it 天）" } ?: ""} |\n")
        append("| 前线死令 | ${cell(base.frontline)} |\n")
        append("| 退路底牌 | ${cell(base.fallback.joinToString("；"))} |\n")
        append("| 退路启动 | ${cell(base.fallbackStart)} |\n\n")
        fuse(b, today, sinceDate)?.let { append("> [!warning] 熔断\n> ${it.text}\n\n") }
        append("## 强制执行令\n\n| 签发 | 执行令 | 时限 | 结果 |\n|---|---|---|---|\n")
        b.orders.takeLast(20).reversed().forEach { o ->
            append("| ${hhmm(o.issuedAt)} | ${cell(o.text)} | ${hhmm(o.dueAt)} | ${if (o.open) "待查" else if (o.done) "做到" else "没做到" + o.note.takeIf { it.isNotBlank() }?.let { "：" + cell(it) }.orEmpty()} |\n")
        }
        append("\n## 微习惯盲区表\n\n连续确认 ${habitStreak(b, today)}/$HABIT_DAYS 天\n\n| 习惯 | 今天 |\n|---|---|\n")
        b.habits.forEach { h -> append("| ${cell(h.label)} | ${if (h.id in b.habitChecks[today].orEmpty()) "✓" else "—"} |\n") }
        append("\n## 防错规避库\n\n| 如果遇到（场景 A） | 立刻执行（动作 B） | 上次走神的瞬间 |\n|---|---|---|\n")
        b.rules.reversed().forEach { r -> append("| ${cell(r.scene)} | ${cell(r.action)} | ${cell(r.slip)} |\n") }
        append("\n## 五步思维 × 行测\n\n| 日期 | 题 | 精简 | 预判 | 联想 | 提问 | 回想 | 结果 |\n|---|---|---|---|---|---|---|---|\n")
        b.drills.takeLast(100).reversed().forEach { d ->
            append("| ${d.date} | ${cell(d.topic)} | " + DrillStep.entries.joinToString(" | ") { cell(d.steps[it].orEmpty()) } + " | ${cell(d.result)} |\n")
        }
        append("\n## 临时起意审查\n\n| 时间 | 念头 | 判决 |\n|---|---|---|\n")
        b.impulses.takeLast(50).reversed().forEach { i -> append("| ${hhmm(i.at)} | ${cell(i.text)} | ${i.verdict.label} |\n") }
        append("\n## 收集箱分流\n\n| 时间 | 槽位 | 一句话 |\n|---|---|---|\n")
        b.sorted.takeLast(100).reversed().forEach { s -> append("| ${hhmm(s.at)} | ${s.slot.label} | ${cell(s.line)} |\n") }
    }

    private fun cell(s: String) = s.replace("|", "／").replace("\n", " ").trim()

    private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim()) }.getOrNull()
}
