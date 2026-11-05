package com.yishou.app.summary

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 一天的账：不管在哪练的（入口刷题、练思维、海龟汤、残局、刷着练、伴读、主线、陪练窗口），
 * 都记成一手。首页的时之轮、每晚总结、“来一手”都看这一本账，不再各算各的。
 */
enum class LedgerKind(val label: String) {
    THREAD("线程"),
    CARD("刷题"),
    DRILL("练思维"),
    SOUP("海龟汤"),
    ENDGAME("残局"),
    SCROLL("刷着练"),
    PARTNER("伴读"),
    LINE("主线"),
    ROUND("陪练"),
}

/** 这一手落得怎么样：接住、差一点、没接住；伴读这种没有对错的记 NONE */
enum class LedgerMark { HIT, NEAR, MISS, NONE }

data class LedgerEntry(
    val at: Long,
    val kind: LedgerKind,
    val mark: LedgerMark,
    /** 练的是什么（题目、刷到的那条、伴读目标） */
    val what: String,
    /** 你说的 */
    val said: String = "",
    /** 判语、参考、收线 */
    val note: String = "",
)

object Ledger {

    data class Tally(val count: Int, val hit: Int, val miss: Int)

    fun tally(entries: List<LedgerEntry>): Map<LedgerKind, Tally> =
        entries.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal }).mapValues { (_, xs) ->
            Tally(xs.size, xs.count { it.mark == LedgerMark.HIT }, xs.count { it.mark == LedgerMark.MISS })
        }

    /** 一行账：刷题 12（接住 9）· 刷着练 6（接住 3）· 伴读 1 */
    fun line(entries: List<LedgerEntry>): String = tally(entries).entries.joinToString(" · ") { (k, t) ->
        val judged = entries.any { it.kind == k && it.mark != LedgerMark.NONE }
        "${k.label} ${t.count}" + if (judged) "（接住 ${t.hit}）" else ""
    }

    /**
     * 给每晚总结看的那一段：先一行账，再逐条（没接住、差一点的排前面，因为规则从失手里来），最多 max 条。
     * 原话截短，免得一天练得多就把请求撑爆。
     */
    fun digest(entries: List<LedgerEntry>, zone: ZoneId, max: Int = 30): String {
        if (entries.isEmpty()) return ""
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val order = compareBy<LedgerEntry> {
            when (it.mark) {
                LedgerMark.MISS -> 0
                LedgerMark.NEAR -> 1
                LedgerMark.NONE -> 2
                LedgerMark.HIT -> 3
            }
        }.thenBy { it.at }
        val picked = entries.sortedWith(order).take(max).sortedBy { it.at }
        return buildString {
            appendLine("共 ${entries.size} 手：${line(entries)}")
            if (picked.size < entries.size) appendLine("（下面只列 ${picked.size} 手，失手的优先）")
            picked.forEach { e ->
                append(time.format(Instant.ofEpochMilli(e.at))).append(" ").append(e.kind.label)
                append(" · ").append(markLabel(e.mark)).append("：").append(e.what.take(50))
                if (e.said.isNotBlank()) append("｜我说：").append(e.said.take(60))
                if (e.note.isNotBlank()) append("｜").append(e.note.take(60))
                appendLine()
            }
        }.trimEnd()
    }

    fun markLabel(m: LedgerMark) = when (m) {
        LedgerMark.HIT -> "接住"
        LedgerMark.NEAR -> "差一点"
        LedgerMark.MISS -> "没接住"
        LedgerMark.NONE -> "记了一笔"
    }

    /** 时之轮上算不算“好的一子”：接住算好，伴读这种没有对错的也算好 */
    fun good(m: LedgerMark) = m == LedgerMark.HIT || m == LedgerMark.NONE
}
