package com.moling.core.flow

import com.moling.core.link.ZkCommand
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Tags
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/*
 * 水道：墨灵决定一天的水怎么流。
 *
 * 总控是闸门（拦住不该去的地方）；墨灵是河道的设计者：先挖好主河道，旁边留支流、源头（去和 GPT / Gemini 想事情）、
 * 水潭（休息）和一片自由水域（娱乐），再预判哪里会决堤、提前备好对策。
 * 水被堵住时不是硬冲，而是改道：卡住了 → 先去源头聊 10 分钟；累了 → 进水潭；晚了 → 整体顺延；
 * 情况变了 → 走 B 方案。每次改道都留一句为什么。
 *
 * 水道定稿后，墨灵把主河道和支流交给总控（定主线、排行动），由总控的闸门守着。
 */

@Serializable
enum class ChannelKind(val label: String, val short: String) {
    MAIN("主河道", "主"),
    SIDE("支流", "支"),
    SPRING("源头（去聊 / 想清楚）", "源"),
    POOL("水潭（休息）", "潭"),
    FREE("自由水域（娱乐）", "自"),
}

@Serializable
enum class ChannelState(val label: String) { WAIT("没开始"), ON("在流"), DONE("流过了"), PLAN_B("改走 B"), SKIPPED("干了") }

@Serializable
data class Channel(
    val id: String,
    val kind: ChannelKind,
    val title: String,
    /** 一天中的分钟（北京时间）；-1 = 不定时，条件到了再流。 */
    val startMin: Int,
    val minutes: Int,
    val doneWhen: String = "",
    val planB: String = "",
    val why: String = "",
    val state: ChannelState = ChannelState.WAIT,
) {
    val endMin: Int get() = if (startMin < 0) -1 else startMin + minutes
    val open: Boolean get() = state == ChannelState.WAIT || state == ChannelState.ON
}

/** 预判会决堤的地方：什么时候、会发生什么、提前怎么办。 */
@Serializable
data class Risk(val atMin: Int, val what: String, val response: String)

@Serializable
data class FlowPlan(
    val day: String,
    /** 今天的意图：一句话。 */
    val intent: String,
    val channels: List<Channel>,
    val risks: List<Risk> = emptyList(),
    val confirmed: Boolean = false,
    val createdAt: Long = 0,
    /** 改道记录：每次一句为什么。 */
    val revisions: List<String> = emptyList(),
    val byModel: Boolean = false,
)

@Serializable
enum class Cause(val label: String) { STUCK("卡住了"), TIRED("累了"), LATE("晚了"), CONFLICT("情况变了") }

/** 日历上的固定安排（课、会、约），排水道时绕开。 */
@Serializable
data class Busy(val start: Long, val end: Long, val title: String)

/** 偏离：该在某条水道里，却在别处。 */
data class Drift(val channel: Channel, val lateMin: Int)

object Flow {
    private const val DAY_END = 23 * 60

    fun minuteOf(at: Long): Int = Instant.ofEpochMilli(at).atZone(BEIJING).let { it.hour * 60 + it.minute }

    /** [minute] 这一刻水该在哪：正在窗口里的那条；没有就是下一条。 */
    fun current(plan: FlowPlan, minute: Int): Channel? {
        val timed = plan.channels.filter { it.open && it.startMin >= 0 }.sortedBy { it.startMin }
        return timed.firstOrNull { minute in it.startMin until it.endMin } ?: timed.firstOrNull { it.startMin > minute }
    }

    /** 该在主河道 / 支流 / 源头的时候在娱乐，而且过了开头 [graceMin] 分钟 → 偏离。 */
    fun drift(plan: FlowPlan, minute: Int, playing: Boolean, working: Boolean, graceMin: Int = 10): Drift? {
        if (!playing || working) return null
        val c = current(plan, minute) ?: return null
        if (minute < c.startMin + graceMin || minute >= c.endMin) return null
        if (c.kind == ChannelKind.FREE || c.kind == ChannelKind.POOL) return null
        return Drift(c, minute - c.startMin)
    }

    /** 改道。返回新的水道和一句说明。 */
    fun reroute(plan: FlowPlan, channelId: String, cause: Cause, minute: Int, note: String = ""): Pair<FlowPlan, String> {
        val c = plan.channels.firstOrNull { it.id == channelId } ?: return plan to ""
        val (channels, why) = when (cause) {
            Cause.TIRED -> insertBefore(plan.channels, c, Channel("pool-$minute", ChannelKind.POOL, "歇一会儿", minute, 20, "到点回来", why = "累了"), minute)
                .let { it to "「${c.title}」前先进水潭 20 分钟，后面整体顺延" }
            Cause.STUCK -> insertBefore(
                plan.channels, c,
                Channel("spring-$minute", ChannelKind.SPRING, "去聊：${c.title}卡在${note.ifBlank { "哪一步" }}", minute, 10, "说出卡点和第一步", why = "卡住了"),
                minute,
            ).let { it to "「${c.title}」卡住了，先去源头聊 10 分钟再回来" }
            Cause.LATE -> {
                val delay = (minute - c.startMin).coerceAtLeast(0)
                shift(plan.channels, c.id, c.startMin, delay).map { if (it.id == c.id) it.copy(startMin = minute) else it } to
                    "「${c.title}」晚了 $delay 分钟，从现在开始，后面顺延"
            }
            Cause.CONFLICT -> plan.channels.map { if (it.id == c.id) it.copy(state = ChannelState.PLAN_B) else it } to
                "「${c.title}」改走 B：${c.planB.ifBlank { "先做最小的一步" }}"
        }
        val (fitted, dropped) = fitDay(channels)
        val msg = why + if (dropped.isNotEmpty()) "；放不下了，先去掉：${dropped.joinToString("、") { it.title }}" else ""
        return plan.copy(channels = fitted, revisions = plan.revisions + "${"%02d:%02d".format(minute / 60, minute % 60)} $msg") to msg
    }

    fun mark(plan: FlowPlan, channelId: String, state: ChannelState): FlowPlan =
        plan.copy(channels = plan.channels.map { if (it.id == channelId) it.copy(state = state) else it })

    /** 在 [target] 前面插一段 [add]（从 [minute] 开始），target 和它后面还没流过的水道整体顺延。 */
    private fun insertBefore(list: List<Channel>, target: Channel, add: Channel, minute: Int): List<Channel> {
        val newStart = minute + add.minutes
        val by = (newStart - target.startMin).coerceAtLeast(0)
        return shift(list, target.id, target.startMin, by).map { if (it.id == target.id) it.copy(startMin = newStart) else it } + add
    }

    /** 除了 [exceptId]，原定在 [fromMin] 之后开始、还没流过的水道都往后挪 [by] 分钟。 */
    private fun shift(list: List<Channel>, exceptId: String, fromMin: Int, by: Int): List<Channel> {
        if (by <= 0) return list
        return list.map { if (it.id != exceptId && it.open && it.startMin > fromMin) it.copy(startMin = it.startMin + by) else it }
    }

    /** 固定安排换成当天的分钟区间（跨出当天的截掉）。 */
    private fun busyMinutes(i: Inputs): List<IntRange> {
        val start = dayStart(i.day)
        return i.busy.mapNotNull { b ->
            val s = ((b.start - start) / 60_000L).toInt().coerceIn(0, 24 * 60)
            val e = ((b.end - start) / 60_000L).toInt().coerceIn(0, 24 * 60)
            if (e > s) s until e else null
        }
    }

    /**
     * 主河道、支流、源头和固定安排重叠的，往后挪到安排结束；挪了之后也不和前面已排好的水道重叠。
     * 水潭和自由水域跟着前一条走（同样往后挪）。
     */
    fun avoid(list: List<Channel>, busy: List<IntRange>): List<Channel> {
        if (busy.isEmpty()) return list
        var prevEnd = -1
        return list.sortedBy { it.startMin }.map { c ->
            if (c.startMin < 0) return@map c
            var start = maxOf(c.startMin, prevEnd)
            var moved = true
            while (moved) {
                moved = false
                busy.firstOrNull { r -> start < r.last + 1 && start + c.minutes > r.first }?.let { r -> start = r.last + 1; moved = true }
            }
            prevEnd = start + c.minutes
            c.copy(startMin = start)
        }
    }

    /** 超出 23:00 的：先去掉支流和自由水域，主河道和源头保留（截短也不删）。 */
    private fun fitDay(list: List<Channel>): Pair<List<Channel>, List<Channel>> {
        val over = list.filter { it.open && it.startMin >= 0 && it.endMin > DAY_END }
        val dropped = over.filter { it.kind == ChannelKind.SIDE || it.kind == ChannelKind.FREE }
        val kept = list.filter { it !in dropped }.map {
            if (it.open && it.startMin >= 0 && it.endMin > DAY_END && it.startMin < DAY_END) it.copy(minutes = DAY_END - it.startMin) else it
        }
        return kept to dropped
    }

    // ------------------------------------------------------------------ 生成

    /** 早上的输入：从总控和记忆里来。 */
    data class Inputs(
        val day: String,
        val now: Long,
        val mainTitle: String,
        val mainPlannedAt: Long,
        val baton: String,
        val aims: List<String>,
        val deliverables: List<String>,
        /** 记忆里的模式，例如“下午容易卡住”。 */
        val patterns: List<String>,
        /** 精力最好的几个钟头（0..23），没有就空。 */
        val bestHours: List<Int>,
        val freeMinutes: Int,
        /** 日历上的固定安排；主河道、支流、源头不和它们重叠。 */
        val busy: List<Busy> = emptyList(),
    )

    /** 不调模型的兜底水道。 */
    fun local(i: Inputs): FlowPlan {
        val now = minuteOf(i.now)
        val mainStart = when {
            i.mainPlannedAt > 0 -> minuteOf(i.mainPlannedAt)
            i.bestHours.isNotEmpty() -> (i.bestHours.firstOrNull { it * 60 >= now } ?: i.bestHours.first()) * 60
            else -> ((now / 30) + 1) * 30
        }.coerceAtLeast(now)
        val chans = mutableListOf<Channel>()
        val main = i.mainTitle.ifBlank { i.deliverables.firstOrNull() ?: i.aims.firstOrNull() ?: "" }
        if (i.baton.isNotBlank()) chans += Channel("spring-0", ChannelKind.SPRING, "接着想：${i.baton}", mainStart - 15, 10, "说出下一步", why = "先把昨天断的地方接上")
        if (main.isNotBlank()) chans += Channel("main-0", ChannelKind.MAIN, main, mainStart, 60, "交出一样能检查的东西", "只做最小的一步（15 分钟）", "今天只推这一样")
        chans += Channel("pool-0", ChannelKind.POOL, "歇一会儿", mainStart + 60, 20, "到点回来", why = "主河道之后留口气")
        chans += Channel("free-0", ChannelKind.FREE, "自由时间", mainStart + 80, i.freeMinutes.coerceIn(0, 120).takeIf { it > 0 } ?: 30, why = "挣来的")
        val risks = i.patterns.take(2).map { Risk(mainStart + 60, it, "到点前 10 分钟我先提醒，必要时开专注锁") }
        val intent = if (main.isNotBlank()) "今天只推「$main」" else "今天先把要推的那件事找出来"
        return FlowPlan(i.day, intent, fitDay(avoid(chans.filter { it.startMin >= 0 }, busyMinutes(i))).first, risks, createdAt = i.now)
    }

    fun morningPrompt(i: Inputs) = buildString {
        appendLine("【现在】${"%02d:%02d".format(minuteOf(i.now) / 60, minuteOf(i.now) % 60)}（北京）")
        appendLine("【总控的主线】${i.mainTitle.ifBlank { "没定" }}" + if (i.mainPlannedAt > 0) "，计划 ${fmt(minuteOf(i.mainPlannedAt))}" else "")
        if (i.baton.isNotBlank()) appendLine("【昨天断在】${i.baton}")
        if (i.aims.isNotEmpty()) appendLine("【长期目标】${i.aims.joinToString("、")}")
        if (i.deliverables.isNotEmpty()) appendLine("【本周要交】${i.deliverables.joinToString("、")}")
        if (i.patterns.isNotEmpty()) appendLine("【我注意到的模式】${i.patterns.joinToString("；")}")
        if (i.bestHours.isNotEmpty()) appendLine("【精力好的钟头】${i.bestHours.joinToString("、") { "$it 点" }}")
        if (i.busy.isNotEmpty()) appendLine("【固定安排（不能占）】" + i.busy.joinToString("；") { "${fmt(minuteOf(it.start))}–${fmt(minuteOf(it.end))} ${it.title}" })
        appendLine("【今天能挣的娱乐】约 ${i.freeMinutes} 分钟")
        appendLine()
        appendLine("你是他的规划者。设计今天的水道：只有一条主河道；可以有一两条支流；至少一个源头（去和 GPT / Gemini 想清楚一件事，10–20 分钟）；")
        appendLine("主河道之后要有水潭（休息）；自由水域放在做完之后。再预判两处最可能决堤的地方和提前的对策。每行一条，只输出：")
        appendLine("【意图】一句话")
        appendLine("【水道】09:00｜60｜主｜做什么｜做完=怎样算完｜B=情况变了做什么｜为什么")
        append("【风险】14:00｜会发生什么｜提前怎么办")
    }

    /** 解析模型给的水道；解析不出主河道就返回 null（用本地兜底）。 */
    fun parse(reply: String, i: Inputs): FlowPlan? {
        val intent = Tags.parse(reply)["意图"].orEmpty()
        val chans = Regex("""【水道】([^\n]+)""").findAll(reply).mapIndexedNotNull { idx, m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            val start = parseHm(f.getOrNull(0) ?: return@mapIndexedNotNull null) ?: return@mapIndexedNotNull null
            val minutes = f.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(5, 240) ?: return@mapIndexedNotNull null
            val kind = when (f.getOrNull(2)?.take(1)) { "主" -> ChannelKind.MAIN; "支" -> ChannelKind.SIDE; "源" -> ChannelKind.SPRING; "潭" -> ChannelKind.POOL; "自" -> ChannelKind.FREE; else -> return@mapIndexedNotNull null }
            val title = f.getOrNull(3).orEmpty().ifBlank { return@mapIndexedNotNull null }
            Channel("${kind.name.lowercase()}-$idx", kind, title.take(40), start, minutes,
                f.getOrNull(4).orEmpty().removePrefix("做完=").take(60), f.getOrNull(5).orEmpty().removePrefix("B=").take(60), f.getOrNull(6).orEmpty().take(60))
        }.toList()
        if (chans.count { it.kind == ChannelKind.MAIN } != 1) return null
        val risks = Regex("""【风险】([^\n]+)""").findAll(reply).mapNotNull { m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            val at = parseHm(f.getOrNull(0) ?: "") ?: return@mapNotNull null
            Risk(at, f.getOrNull(1).orEmpty().take(60), f.getOrNull(2).orEmpty().take(60))
        }.take(3).toList()
        return FlowPlan(i.day, intent.ifBlank { "今天只推「${chans.first { it.kind == ChannelKind.MAIN }.title}」" }, fitDay(avoid(chans, busyMinutes(i))).first, risks, createdAt = i.now, byModel = true)
    }

    /** 定稿后交给总控的指令：主河道定为主线，主河道和支流排进总控的行动。 */
    fun toCommands(plan: FlowPlan, dayStart: Long): List<ZkCommand> {
        fun at(min: Int) = dayStart + min * 60_000L
        val main = plan.channels.firstOrNull { it.kind == ChannelKind.MAIN } ?: return emptyList()
        val out = mutableListOf<ZkCommand>(ZkCommand.SetMain(main.title, at(main.startMin), main.doneWhen, "墨灵的水道：${plan.intent}"))
        plan.channels.filter { (it.kind == ChannelKind.MAIN || it.kind == ChannelKind.SIDE) && it.open && it.startMin >= 0 }.forEach {
            out += ZkCommand.AddAction(it.title, at(it.startMin), at(it.endMin), it.doneWhen, it.why.ifBlank { it.kind.label })
        }
        return out
    }

    /** 当天 0 点（北京时间）的毫秒。 */
    fun dayStart(day: String): Long = LocalDate.parse(day).atStartOfDay(BEIJING).toInstant().toEpochMilli()

    fun render(plan: FlowPlan): String = buildString {
        appendLine("今天的水道：${plan.intent}")
        plan.channels.filter { it.startMin >= 0 }.sortedBy { it.startMin }.forEach {
            append("${fmt(it.startMin)}–${fmt(it.endMin)} ${it.kind.short} ${it.title}")
            if (it.state != ChannelState.WAIT) append("（${it.state.label}）")
            if (it.planB.isNotBlank()) append("｜B：${it.planB}")
            appendLine()
        }
        plan.risks.forEach { appendLine("⚠ ${fmt(it.atMin)} ${it.what} → ${it.response}") }
        plan.revisions.takeLast(3).forEach { appendLine("↺ $it") }
    }.trim()

    fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun parseHm(s: String): Int? {
        val m = Regex("""(\d{1,2})\s*[:：点]\s*(\d{1,2})?""").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].ifBlank { "0" }.toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }
}
