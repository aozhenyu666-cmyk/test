package com.zongkong.core.plan

import com.zongkong.core.DayClock
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.Plan
import com.zongkong.core.work.Rec
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkOps
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/*
 * 今天的安排（0.12）：总控的计划就是“今天几点到几点做什么”。
 *
 *   - 说一句就排上：“下午2点到4点刷题”“明天上午9点改简历一小时”“现在开始背单词半小时”
 *   - 做不了就延期：一小时后 / 下午 / 今晚 / 明天同一时间——延期不是放手，到新的时间照样提醒、照样记欠账
 *   - 没排时间的（GPT、Notion 同步来的待办）放在“待安排”，一点就排进上午 / 下午 / 晚上，
 *     不再被拿来在首页反复追问
 * 这些零散的事挂在一个固定的事项「日常安排」下面。
 */
object QuickPlan {
    const val DAILY = "日常安排"
    const val DEFAULT_MINUTES = 60L

    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    data class Parsed(val title: String, val plan: Plan?)

    private val CN = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    /** “十一”“两”“二十”这种（只管时间里用到的 0–59）。 */
    private fun cnNumber(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.all { it in CN }) return if (s.length == 1) CN[s[0]] else null
        val i = s.indexOf('十')
        if (i < 0) return null
        val tens = if (i == 0) 1 else CN[s[0]] ?: return null
        val ones = if (i == s.lastIndex) 0 else CN[s[i + 1]] ?: return null
        return tens * 10 + ones
    }

    /** 时间里的中文数字换成阿拉伯数字：“两点半”→“2点半”，“一小时”→“1小时”，“半小时”→“30分钟”。 */
    fun normalize(text: String): String = text
        .replace("半小时", "30分钟").replace("半个小时", "30分钟")
        .replace("一个半小时", "90分钟")
        .replace(Regex("([零一二两三四五六七八九十]{1,3})(?=点|个?小时|分钟)")) { m -> cnNumber(m.value)?.toString() ?: m.value }
        .replace(Regex("(\\d+)个小时"), "$1小时")

    private val DROP = listOf(
        Regex("(大后天|后天|明天|今天|今晚|明晚|今早|明早)"),
        Regex("(下下|下)?(周|星期|礼拜)[一二三四五六日天]"),
        Regex("(凌晨|早上|上午|中午|下午|傍晚|晚上)"),
        Regex("\\d{1,2}(:\\d{2}|点(半|\\d{1,2}分?)?)"),
        Regex("\\d+(\\.\\d+)?\\s*(分钟|小时|h|min)"),
        Regex("(现在|马上|立刻|等会儿?|待会儿?)"),
    )

    /**
     * 一句话拆成“做什么”和“几点”。没说时间的 plan = null（放进待安排）。
     * 只说了开始，按 [DEFAULT_MINUTES]（或者说了“一小时 / 半小时”就按说的）。
     */
    fun parse(text: String, now: Long, zone: ZoneId): Parsed {
        val t = normalize(text.trim().replace('：', ':'))
        var plan: Plan? = null
        if (Regex("现在|马上|立刻").containsMatchIn(t)) {
            val minutes = Regex("(\\d+(?:\\.\\d+)?)\\s*(分钟|小时|h|min)").find(t)?.let { m ->
                val n = m.groupValues[1].toDouble()
                if (m.groupValues[2] in setOf("小时", "h")) (n * 60).toLong() else n.toLong()
            } ?: DEFAULT_MINUTES
            val s = roundUp(now, zone)
            plan = Plan(s.format(ISO), s.plusMinutes(minutes).format(ISO))
        } else {
            TimeParse.parse(t, now, zone)?.let { p ->
                plan = if (p.hasTime && p.end.isBlank()) {
                    val s = ZonedDateTime.parse(p.start)
                    Plan(p.start, s.plusMinutes(DEFAULT_MINUTES).format(ISO))
                } else if (p.hasTime) p else null
            }
        }
        var title = t
        DROP.forEach { title = title.replace(it, " ") }
        title = title.replace(Regex("[到至~\\-—]"), " ")
            .replace(Regex("^\\s*(我要|我想|要|去|先|开始)+"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '，', ',', '。', '：', ':', '、', '；', ';')
            .replace(Regex("^(开始|去)"), "")
            .trim()
        return Parsed(title.ifBlank { text.trim() }, plan)
    }

    private fun roundUp(now: Long, zone: ZoneId): ZonedDateTime {
        val z = Instant.ofEpochMilli(now).atZone(zone).withSecond(0).withNano(0)
        val extra = (5 - z.minute % 5) % 5
        return z.plusMinutes(extra.toLong())
    }

    /** 延期 / 排进的去处。 */
    enum class Slot(val label: String) {
        LATER("一小时后"), MORNING("上午"), AFTERNOON("下午"), EVENING("今晚"), TOMORROW("明天"),
    }

    /**
     * 把一段时间挪到 [slot]，时长不变（没有时长按 [DEFAULT_MINUTES]）。
     * 今天这个时段已经过了，就挪到一小时后。明天 = 明天同一时间（原来没时间的明天 9 点）。
     */
    fun moveTo(old: Plan?, slot: Slot, now: Long, zone: ZoneId): Plan {
        val oldStart = old?.takeIf { it.hasTime }?.let { TimeParse.startMillis(it, zone) }
        val oldEnd = old?.let { TimeParse.endMillis(it, zone) }
        val minutes = if (oldStart != null && oldEnd != null && oldEnd > oldStart) (oldEnd - oldStart) / 60_000 else DEFAULT_MINUTES
        val today = DayClock.date(now, zone)
        fun at(hour: Int) = ZonedDateTime.of(today.atTime(hour, 0), zone)
        val soon = roundUp(now + 60 * 60_000L, zone)
        val start: ZonedDateTime = when (slot) {
            Slot.LATER -> soon
            Slot.MORNING -> at(9).takeIf { it.toInstant().toEpochMilli() > now } ?: soon
            Slot.AFTERNOON -> at(14).takeIf { it.toInstant().toEpochMilli() > now } ?: soon
            Slot.EVENING -> at(20).takeIf { it.toInstant().toEpochMilli() > now } ?: soon
            Slot.TOMORROW -> oldStart?.let { Instant.ofEpochMilli(it).atZone(zone).plusDays(1) }
                ?.let { if (DayClock.date(it.toInstant().toEpochMilli(), zone) == today) it.plusDays(1) else it }
                ?: ZonedDateTime.of(today.plusDays(1).atTime(9, 0), zone)
        }
        return Plan(start.format(ISO), start.plusMinutes(minutes).format(ISO))
    }

    /** 「日常安排」这件事（没有就建一个）：零散的、不属于哪件大事的安排都挂在它下面。 */
    fun daily(work: Work, now: Long, rnd: Random): Pair<Work, Rec> {
        work.threads().firstOrNull { it.title == DAILY }?.let { return work to it }
        return WorkOps.create(work, Kind.THREAD, mapOf(F.TITLE to DAILY, F.WHY to "零散的、当天要做的事（刷题、跑步、办事……）", F.STATUS to "推进中"), now, rnd)
    }

    /** 今天排了时间的行动（含做完的），按开始时间。 */
    fun today(work: Work, now: Long, zone: ZoneId): List<Pair<Rec, Pair<Long, Long>>> {
        val d = DayClock.date(now, zone)
        val from = DayClock.startOf(d, zone)
        val to = DayClock.startOf(d.plusDays(1), zone)
        return work.actions().mapNotNull { a ->
            val p = a.plan?.takeIf { it.hasTime } ?: return@mapNotNull null
            val s = TimeParse.startMillis(p, zone) ?: return@mapNotNull null
            if (s !in from until to || a.actionStatus == com.zongkong.core.work.ActionStatus.CANCELLED) return@mapNotNull null
            val e = TimeParse.endMillis(p, zone)?.takeIf { it > s } ?: (s + DEFAULT_MINUTES * 60_000)
            a to (s to e)
        }.sortedBy { it.second.first }
    }

    /** 没排时间的待办（GPT、Notion 同步来的、随手加的）。 */
    fun unscheduled(work: Work): List<Rec> = work.actions()
        .filter { it.actionStatus.open && it.plan?.hasTime != true }
        .sortedByDescending { it.updatedAt }

    /** 给 GPT 的“帮我排今天”：手上的事 + 今天已经排的，要它按交接块的格式交回时段。 */
    fun gptPrompt(work: Work, now: Long, zone: ZoneId): String = buildString {
        val d = Instant.ofEpochMilli(now).atZone(zone)
        append("帮我把今天（${d.monthValue}月${d.dayOfMonth}日，现在 %02d:%02d）排成具体的时段。\n\n".format(d.hour, d.minute))
        val done = today(work, now, zone)
        if (done.isNotEmpty()) {
            append("已经排了的：\n")
            done.forEach { (a, t) -> append("- ${hm(t.first, zone)}-${hm(t.second, zone)} ${a.title}（${a[F.STATUS]}）\n") }
            append('\n')
        }
        val loose = unscheduled(work).take(12)
        if (loose.isNotEmpty()) {
            append("还没排时间的待办：\n")
            loose.forEach { a -> append("- ${a.title}" + (work.rec(a.threadKey)?.let { "（${it.title} ${it[F.CODE]}）" } ?: "") + "\n") }
            append('\n')
        }
        val threads = work.threads().filter { it.threadStatus.open && it.title != DAILY }.sortedByDescending { it.updatedAt }.take(8)
        if (threads.isNotEmpty()) {
            append("手上的事（下一步）：\n")
            threads.forEach { t -> append("- ${t.title} ${t[F.CODE]}：${t[F.NEXT].ifBlank { "下一步没写" }}\n") }
            append('\n')
        }
        append(
            """
            要求：
            1. 先问我今天有哪些固定安排（上课、吃饭、出门），再排；排得下就行，留出空当，别排满。
            2. 每一段写具体做什么、做到什么算完；第一段要能马上开始。
            3. 排好后，按事项分别输出交接块（一件事一个块；零散的事放在“日常安排”下），时间写“今天 14:00-15:30”：

            【总控交接】
            事项：日常安排
            行动：
            - 刷 20 道资料分析 | 今天 14:00-15:00 | 完成依据：做完并对答案
            【交接结束】

            总控会把这些排进今天：到点提醒、过点没开始就关掉娱乐，直到我开始或者改时间。
            """.trimIndent(),
        )
    }

    private fun hm(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
}
