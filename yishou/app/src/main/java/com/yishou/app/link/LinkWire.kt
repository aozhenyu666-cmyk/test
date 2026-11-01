package com.yishou.app.link

import com.yishou.app.summary.Ledger
import com.yishou.app.summary.LedgerEntry
import com.yishou.app.summary.LedgerMark

/**
 * 一手 → 总控 的连接约定（纯逻辑，单元测试直接跑）。总控那边有一份对应的解码（com.zongkong.core.YishouLedger）。
 *
 * 只传字符串、整数、长整数、布尔值和它们的数组：版本不一致时多出来的字段直接忽略。
 *  - status：今天的一行账、接住几手（给总控的「一手组数」关卡和参谋看）
 *  - ledger：since 之后的每一手（时间、哪种练法、接住没有、练的什么、你说的、判语），总控存一份
 */
object LinkWire {
    const val AUTHORITY = "com.yishou.app.link"
    const val VERSION = 2
    const val STATUS = "status"
    const val LEDGER = "ledger"
    const val SET_TASK = "setTask"
    const val START_WINDOW = "startWindow"
    const val OK = "ok"
    const val ERROR = "error"

    /** 一组几手（总控按「组」验收：接住 3 手算 1 组）。 */
    const val SET_SIZE = 3

    /** 一次最多给多少手、每段文字最长多少字：跨进程的包不能太大。 */
    const val MAX_ENTRIES = 300
    const val MAX_TEXT = 160

    /** 今天的状态。字段名沿用旧版连接（总控的 YishouStatus 认这些名字）。 */
    fun status(today: List<LedgerEntry>, appVersion: String, gateOn: Boolean): Map<String, Any> {
        val hit = today.count { it.mark == LedgerMark.HIT }
        return mapOf(
            "v" to VERSION,
            "appVersion" to appVersion,
            "roundsToday" to today.size,
            "effectiveToday" to hit,
            "setSize" to SET_SIZE,
            "doneSets" to hit / SET_SIZE,
            "targetSets" to 0,
            "gateOn" to gateOn,
            "ledgerLine" to Ledger.line(today).ifBlank { "今天还没练" },
            "missToday" to today.count { it.mark == LedgerMark.MISS },
            "lastAt" to (today.maxOfOrNull { it.at } ?: 0L),
        )
    }

    /** 一串账 → 几个平行数组（按时间从旧到新，最多 [MAX_ENTRIES] 手，多了留最新的）。 */
    fun ledger(entries: List<LedgerEntry>): Map<String, Any> {
        val list = entries.sortedBy { it.at }.takeLast(MAX_ENTRIES)
        fun cut(s: String) = if (s.length > MAX_TEXT) s.take(MAX_TEXT) + "…" else s
        return mapOf(
            "v" to VERSION,
            "at" to LongArray(list.size) { list[it].at },
            "kind" to Array(list.size) { list[it].kind.name },
            "kindLabel" to Array(list.size) { list[it].kind.label },
            "mark" to Array(list.size) { list[it].mark.name },
            "what" to Array(list.size) { cut(list[it].what) },
            "said" to Array(list.size) { cut(list[it].said) },
            "note" to Array(list.size) { cut(list[it].note) },
            "truncated" to (entries.size > list.size),
        )
    }
}
