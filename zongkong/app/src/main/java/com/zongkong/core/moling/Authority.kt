package com.zongkong.core.moling

import com.zongkong.core.DayClock
import com.zongkong.core.Policy
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/*
 * 授权书和临时开关（v4 的 C1、C2）。
 *
 * 授权书：用户事先写好“墨灵可以不问我直接让总控做的几类事”和边界。墨灵在授权内交来的指令，
 * 总控不再等用户点“执行”，而是先发通知“60 秒后执行 · 取消”，60 秒内没取消就做。
 *   - 只能授权收紧和排计划；放宽、改拦截名单永远不能授权。
 *   - 授权给得更多 = 墨灵能管得更紧 = 收紧，立即生效；
 *     授权收回或缩小 = 放宽，走总控的 24 小时冷静期（和别的规则一样，冲动时撤不掉）。紧急退出不受影响。
 *
 * 临时开关：墨灵（或授权内的指令）打开、到点自己关的两样东西。到期不算放宽，因为是事先约好的。
 *   - 临时严管：到某个时刻之前严管（拦截名单里的应用一打开就弹回）。
 *   - 只减今天的娱乐：今天能玩的分钟数少 N，明天自动恢复。
 */

/** 能授权的几类事。 */
@Serializable
enum class GrantKind(val label: String) {
    FOCUS("开专注锁"),
    STRICT("临时严管"),
    CUT_PLAY("减今天的娱乐"),
    HQ("进总控时间"),
    PLAN("排今天的计划（主线、行动）"),
    COMMIT("记约定、收谋划单"),
}

/**
 * 授权书的一行。
 * [maxMinutes]：每次最多多少分钟（专注锁、严管、减娱乐、总控时间用；排计划和约定不看）。
 * [perDay]：每天最多几次。[fromMin] / [toMin]：一天中哪段时间可以（北京时间分钟，to ≤ from 表示跨夜）。
 */
@Serializable
data class Grant(
    val kind: GrantKind,
    val maxMinutes: Int = 30,
    val perDay: Int = 2,
    val fromMin: Int = 8 * 60,
    val toMin: Int = 23 * 60,
)

/** 等冷静期的收回：到 [effectiveAt] 才把授权换成 [next]（null = 整行删掉）。 */
@Serializable
data class GrantChange(val kind: GrantKind, val next: Grant?, val createdAt: Long, val effectiveAt: Long)

@Serializable
data class GrantBook(val grants: List<Grant> = emptyList(), val pending: List<GrantChange> = emptyList())

@Serializable
enum class HoldKind { STRICT, CUT_PLAY }

/** 一个临时开关。[from, until) 之间有效。 */
@Serializable
data class Hold(
    val id: String,
    val kind: HoldKind,
    val minutes: Int,
    val from: Long,
    val until: Long,
    val why: String = "",
)

object Grants {
    /** 用户在总控里改一行授权（[next] = null 是删掉）。返回新的授权书和一句给人看的话。 */
    fun set(book: GrantBook, kind: GrantKind, next: Grant?, now: Long): Pair<GrantBook, String> {
        val b = applyDue(book, now)
        val old = b.grants.firstOrNull { it.kind == kind }
        val clean = next?.let { sane(it) }
        // 这一行原来就有等着生效的收回：用户又改了，先撤掉那条（新的改动重新判断）
        val base = b.copy(pending = b.pending.filter { it.kind != kind })
        return when {
            clean == old -> base to "没有变化"
            old == null || (clean != null && covers(clean, old)) ->
                base.copy(grants = base.grants.filter { it.kind != kind } + clean!!) to "授权「${kind.label}」立即生效"
            else -> base.copy(pending = base.pending + GrantChange(kind, clean, now, now + Policy.DELAY_MILLIS)) to
                "授权「${kind.label}」" + (if (clean == null) "收回" else "缩小") + "，24 小时后生效（冷静期）"
        }
    }

    /** 冷静期到了的收回生效。 */
    fun applyDue(book: GrantBook, now: Long): GrantBook {
        val due = book.pending.filter { it.effectiveAt <= now }
        if (due.isEmpty()) return book
        var grants = book.grants
        due.sortedBy { it.effectiveAt }.forEach { c -> grants = grants.filter { it.kind != c.kind } + listOfNotNull(c.next) }
        return GrantBook(grants, book.pending - due.toSet())
    }

    /** [a] 给墨灵的权柄至少和 [b] 一样大（时间段包含、次数和分钟数不少）。 */
    fun covers(a: Grant, b: Grant): Boolean =
        a.kind == b.kind && a.maxMinutes >= b.maxMinutes && a.perDay >= b.perDay && windowContains(a, b)

    private fun windowContains(a: Grant, b: Grant): Boolean {
        val aw = minutes(a); val bw = minutes(b)
        return bw.all { it in aw }
    }

    private fun minutes(g: Grant): Set<Int> =
        if (g.fromMin < g.toMin) (g.fromMin until g.toMin).toSet() else ((g.fromMin until 24 * 60) + (0 until g.toMin)).toSet()

    private fun sane(g: Grant) = g.copy(
        maxMinutes = g.maxMinutes.coerceIn(5, 240),
        perDay = g.perDay.coerceIn(1, 10),
        fromMin = g.fromMin.mod(24 * 60),
        toMin = g.toMin.mod(24 * 60),
    )

    /** 指令属于哪一类授权；不能授权的（拦截名单、放宽）返回 null。 */
    fun kindOf(f: Map<String, String>): GrantKind? = when (f["cmd"]) {
        "start_focus" -> GrantKind.FOCUS
        "enter_hq" -> GrantKind.HQ
        "set_main", "add_action" -> GrantKind.PLAN
        "commit", "directive" -> GrantKind.COMMIT
        "tighten" -> when (f["kind"]) {
            "strict" -> GrantKind.STRICT
            "cut_play" -> GrantKind.CUT_PLAY
            else -> null
        }
        else -> null
    }

    /** 这条指令涉及多少分钟（看 [Grant.maxMinutes] 用）；排计划和约定给 0。 */
    fun minutesOf(f: Map<String, String>): Int = when (f["cmd"]) {
        "start_focus", "enter_hq" -> f["minutes"]?.toIntOrNull() ?: 0
        "tighten" -> f["value"]?.toIntOrNull() ?: 0
        else -> 0
    }

    /**
     * 授权书允许这条指令吗？允许返回那一行，不允许返回 null 和原因。
     * [usedToday]：今天这一类已经授权执行过几次（含正在倒计时的）。
     */
    fun check(book: GrantBook, f: Map<String, String>, now: Long, zone: ZoneId, usedToday: Int): Pair<Grant?, String> {
        val kind = kindOf(f) ?: return null to "这一类不能授权"
        val g = applyDue(book, now).grants.firstOrNull { it.kind == kind } ?: return null to "授权书里没有「${kind.label}」"
        val minute = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
        if (minute !in minutes(g)) return null to "不在授权的时间段里"
        if (usedToday >= g.perDay) return null to "「${kind.label}」今天已经用了 $usedToday 次"
        val m = minutesOf(f)
        if (m > g.maxMinutes) return null to "超过授权的 ${g.maxMinutes} 分钟"
        if (kind == GrantKind.PLAN && !today(f, now, zone)) return null to "授权只管今天的计划"
        return g to ""
    }

    /** 排计划类指令的时间都落在今天（逻辑日）。没写时间的也算今天。 */
    private fun today(f: Map<String, String>, now: Long, zone: ZoneId): Boolean {
        val day = DayClock.date(now, zone)
        return listOf("at", "start").mapNotNull { f[it]?.toLongOrNull()?.takeIf { t -> t > 0 } }.all { DayClock.date(it, zone) == day }
    }

    /** 给人看的授权书。 */
    fun describe(book: GrantBook): String = buildString {
        if (book.grants.isEmpty()) append("还没有授权：墨灵的每条建议都要你在总控里点“执行”。")
        book.grants.forEach { g ->
            appendLine("· ${g.kind.label}：" + (if (g.kind in setOf(GrantKind.PLAN, GrantKind.COMMIT)) "" else "每次 ≤ ${g.maxMinutes} 分钟，") +
                "每天 ≤ ${g.perDay} 次，${hm(g.fromMin)}–${hm(g.toMin)}")
        }
        book.pending.forEach { appendLine("（${it.kind.label}的收回 / 缩小在冷静期，${java.time.Instant.ofEpochMilli(it.effectiveAt).atZone(DayClock.BEIJING).let { t -> "%d日 %02d:%02d".format(t.dayOfMonth, t.hour, t.minute) }} 生效）") }
    }.trim()

    private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
}

object Holds {
    fun active(holds: List<Hold>, now: Long): List<Hold> = holds.filter { now >= it.from && now < it.until }

    /** 此刻生效的临时严管里到期最晚的那个。 */
    fun strict(holds: List<Hold>, now: Long): Hold? = active(holds, now).filter { it.kind == HoldKind.STRICT }.maxByOrNull { it.until }

    /** 今天（逻辑日）一共减了多少分钟娱乐。 */
    fun cutToday(holds: List<Hold>, now: Long, zone: ZoneId): Int =
        active(holds, now).filter { it.kind == HoldKind.CUT_PLAY }.sumOf { it.minutes }

    /** 打开一个临时严管：从现在起 [minutes] 分钟。 */
    fun strictFor(id: String, minutes: Int, now: Long, why: String) = Hold(id, HoldKind.STRICT, minutes, now, now + minutes * 60_000L, why)

    /** 今天的娱乐减 [minutes] 分钟，到下一个逻辑日开始（凌晨 4 点）失效。 */
    fun cutPlay(id: String, minutes: Int, now: Long, zone: ZoneId, why: String): Hold {
        val end = DayClock.startOf(DayClock.date(now, zone).plusDays(1), zone)
        return Hold(id, HoldKind.CUT_PLAY, minutes, now, end, why)
    }

    /** 过期一天以上的不再留。 */
    fun prune(holds: List<Hold>, now: Long): List<Hold> = holds.filter { it.until > now - 24 * 3600_000L }
}
