package com.zongkong.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 一手的刷题记录，导入总控后存的那一份（一手 0.25 起的「一本账」：刷题、刷着练、线程、海龟汤……每一手一条）。
 *
 * 一手那边的编码见 com.yishou.app.link.LinkWire：几个平行数组。这里解码、去重合并、给参谋和界面出一句话。
 * 纯逻辑，单元测试直接跑。
 */
@Serializable
data class YishouEntry(
    val at: Long,
    /** 一手的练法（CARD / SCROLL / THREAD …），原样存 */
    val kind: String,
    /** 中文名（刷题、刷着练……） */
    val label: String = "",
    /** HIT 接住 / NEAR 差一点 / MISS 没接住 / NONE 只记一笔 */
    val mark: String,
    val what: String = "",
    val said: String = "",
    val note: String = "",
) {
    val hit: Boolean get() = mark == HIT
    val miss: Boolean get() = mark == MISS
    val name: String get() = label.ifBlank { kind }

    companion object {
        const val HIT = "HIT"
        const val NEAR = "NEAR"
        const val MISS = "MISS"
    }
}

@Serializable
data class YishouBook(val entries: List<YishouEntry> = emptyList(), val pulledAt: Long = 0L)

object YishouLedger {
    /** 总控只留最近这么多天的。 */
    const val KEEP_DAYS = 30L

    /** 平行数组 → 记录。长度对不上的按最短的来；缺的文字当空。 */
    fun decode(
        at: LongArray?, kind: Array<String?>?, label: Array<String?>?, mark: Array<String?>?,
        what: Array<String?>?, said: Array<String?>?, note: Array<String?>?,
    ): List<YishouEntry> {
        if (at == null || kind == null || mark == null) return emptyList()
        val n = minOf(at.size, kind.size, mark.size)
        return (0 until n).mapNotNull { i ->
            val k = kind[i] ?: return@mapNotNull null
            YishouEntry(
                at = at[i], kind = k, label = label?.getOrNull(i).orEmpty(), mark = mark[i].orEmpty(),
                what = what?.getOrNull(i).orEmpty(), said = said?.getOrNull(i).orEmpty(), note = note?.getOrNull(i).orEmpty(),
            )
        }
    }

    /** 合并：同一时刻、同一练法、同一题只留一条（新拉的覆盖旧的：一手可能事后改判）；只留最近 [KEEP_DAYS] 天。 */
    fun merge(book: YishouBook, incoming: List<YishouEntry>, now: Long): YishouBook {
        val keyed = LinkedHashMap<Triple<Long, String, String>, YishouEntry>()
        for (e in book.entries + incoming) keyed[Triple(e.at, e.kind, e.what)] = e
        val cutoff = now - KEEP_DAYS * 86_400_000L
        return YishouBook(keyed.values.filter { it.at >= cutoff }.sortedBy { it.at }, pulledAt = now)
    }

    /** 下次从哪一刻开始拉：最后一条往前倒 1 小时（一手可能补记、改判），没有记录就让一手给最近一周。 */
    fun since(book: YishouBook): Long = book.entries.maxOfOrNull { it.at }?.let { (it - 3_600_000L).coerceAtLeast(0L) } ?: 0L

    fun between(book: YishouBook, from: Long, to: Long): List<YishouEntry> = book.entries.filter { it.at in from until to }

    /** 一行账：刷题 12（接住 9）· 刷着练 6（接住 3） */
    fun line(entries: List<YishouEntry>): String {
        if (entries.isEmpty()) return "还没有记录"
        return entries.groupBy { it.name }.entries.joinToString(" · ") { (name, xs) ->
            val judged = xs.any { it.mark != "NONE" && it.mark.isNotBlank() }
            "$name ${xs.size}" + if (judged) "（接住 ${xs.count { it.hit }}）" else ""
        }
    }

    /** 接住几手、按「组」算（一手那边一组 3 手）。 */
    fun sets(entries: List<YishouEntry>, setSize: Int = 3): Int = entries.count { it.hit } / setSize.coerceAtLeast(1)

    /** 给参谋看的一段：一行账，再列没接住、差一点的（规则从失手里来），最多 [max] 条。 */
    fun digest(entries: List<YishouEntry>, zone: ZoneId, max: Int = 8): String {
        if (entries.isEmpty()) return "一手今天还没有刷题记录。"
        val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val misses = entries.filter { it.mark == YishouEntry.MISS || it.mark == YishouEntry.NEAR }.takeLast(max)
        return buildString {
            append("一手今天 ${entries.size} 手：").append(line(entries))
            if (misses.isNotEmpty()) {
                append("\n没接住 / 差一点的：")
                misses.forEach { e ->
                    append("\n- ").append(hm.format(Instant.ofEpochMilli(e.at))).append(' ').append(e.name)
                    append(if (e.miss) " 没接住：" else " 差一点：").append(e.what.take(50))
                    if (e.said.isNotBlank()) append("｜说的：").append(e.said.take(40))
                    if (e.note.isNotBlank()) append("｜判：").append(e.note.take(40))
                }
            }
        }
    }
}
