package com.zongkong.core.ledger

import com.zongkong.core.DayClock
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 账本（试验）：一天花了多少、花在哪。
 *   - 导入微信 / 支付宝导出的账单（CSV），只收“支出”，按交易单号去重
 *   - 跟总控说一句（“午饭 18”），或者把付款截图发给它，它记一笔
 *   - 今天 / 本周的合计和分类，日终清单里带一行
 * 金额一律存“分”，免得小数出错。
 */

@Serializable
data class Expense(
    val id: String,
    val at: Long,
    /** 分。 */
    val cents: Long,
    val what: String,
    val cat: String = "其他",
    /** 微信 / 支付宝 / 手记 / 截图。 */
    val source: String = "手记",
    /** 交易单号（导入的才有，用来去重）。 */
    val ref: String = "",
)

@Serializable
data class LedgerBook(val items: List<Expense> = emptyList())

object Ledger {
    val CATS = listOf("吃饭", "交通", "购物", "娱乐", "学习", "日用", "人情", "其他")

    private val HINTS: List<Pair<String, List<String>>> = listOf(
        "吃饭" to listOf("餐", "饭", "食", "面", "奶茶", "咖啡", "美团外卖", "饿了么", "麦当劳", "肯德基", "瑞幸", "蜜雪", "喜茶", "早点", "午饭", "晚饭", "外卖", "水果", "超市便利"),
        "交通" to listOf("地铁", "公交", "滴滴", "打车", "出行", "高铁", "火车", "12306", "机票", "加油", "停车", "单车", "哈啰", "共享"),
        "娱乐" to listOf("游戏", "腾讯视频", "爱奇艺", "优酷", "B站", "哔哩", "会员", "网易云", "QQ音乐", "电影", "KTV", "Steam", "充值", "抖音", "快手"),
        "学习" to listOf("书", "课程", "教育", "考试", "报名", "粉笔", "华图", "中公", "打印", "文具", "知网"),
        "购物" to listOf("淘宝", "天猫", "京东", "拼多多", "唯品会", "服饰", "鞋"),
        "日用" to listOf("话费", "电费", "水费", "燃气", "房租", "物业", "宽带", "药", "医院", "理发", "洗衣"),
        "人情" to listOf("红包", "转账", "礼"),
    )

    fun guessCat(text: String): String = HINTS.firstOrNull { (_, ws) -> ws.any { text.contains(it, ignoreCase = true) } }?.first ?: "其他"

    /** "¥12.50" / "12.5" / "-12.50" → 1250；认不出 = null。 */
    fun cents(text: String): Long? {
        val t = text.replace("¥", "").replace("￥", "").replace(",", "").replace("元", "").trim().removePrefix("-").removePrefix("+")
        val d = t.toBigDecimalOrNull() ?: return null
        return d.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong().takeIf { it > 0 }
    }

    fun yuan(cents: Long): String = if (cents % 100 == 0L) "${cents / 100}" else "%d.%02d".format(cents / 100, cents % 100)

    // ---------- 导入账单 ----------

    /** 一行 CSV（双引号里可以有逗号）。 */
    fun csvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }

    private val TIME_FORMATS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/M/d H:mm:ss", "yyyy/M/d H:mm")
        .map { DateTimeFormatter.ofPattern(it) }

    fun time(text: String, zone: ZoneId): Long? {
        val t = text.trim()
        for (f in TIME_FORMATS) {
            val dt = runCatching { LocalDateTime.parse(t, f) }.getOrNull() ?: continue
            return dt.atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    data class Import(val items: List<Expense>, val skipped: Int, val source: String, val error: String? = null)

    /**
     * 微信、支付宝导出的账单 CSV（前面几行说明、后面表头 + 明细）：找到含“交易时间”和“收/支”的表头，按列名取。
     * 只收“支出”；退款、关闭的跳过。
     */
    fun parseBill(text: String, zone: ZoneId): Import {
        val lines = text.replace("﻿", "").lines()
        val hi = lines.indexOfFirst { it.contains("交易时间") && it.contains("收/支") }
        if (hi < 0) return Import(emptyList(), 0, "", "没找到账单的表头（要有“交易时间”“收/支”两列）。微信导出的是 xlsx 的话，先用 WPS / Excel 另存为 CSV。")
        val head = csvLine(lines[hi]).map { it.trim() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> head.indexOfFirst { it.startsWith(n) }.takeIf { it >= 0 } } ?: -1
        val cTime = col("交易时间")
        val cWho = col("交易对方")
        val cItem = col("商品说明", "商品")
        val cIo = col("收/支")
        val cAmt = col("金额")
        val cStatus = col("当前状态", "交易状态")
        val cRef = col("交易单号", "交易订单号")
        val cCat = col("交易分类")
        val source = if (text.contains("支付宝") || head.contains("交易分类")) "支付宝" else "微信"
        val out = mutableListOf<Expense>()
        var skipped = 0
        for (line in lines.drop(hi + 1)) {
            if (line.isBlank()) continue
            val r = csvLine(line)
            fun at(i: Int) = r.getOrNull(i)?.trim().orEmpty()
            if (at(cIo) != "支出") { skipped++; continue }
            if (at(cStatus).let { it.contains("退款") || it.contains("关闭") || it.contains("失败") }) { skipped++; continue }
            val c = cents(at(cAmt))
            val t = time(at(cTime), zone)
            if (c == null || t == null) { skipped++; continue }
            val who = at(cWho).trim('/', ' ')
            val item = at(cItem).trim('/', ' ')
            val what = listOf(who, item).filter { it.isNotBlank() && it != "/" }.distinct().joinToString(" · ").take(60).ifBlank { "（没写）" }
            val ref = at(cRef).trim()
            out += Expense("e" + (ref.ifBlank { "$t$c" }).hashCode().toUInt().toString(36), t, c, what, guessCat("$what ${at(cCat)}"), source, ref)
        }
        return Import(out, skipped, source)
    }

    /** 放进账本：同一交易单号（或者同一时刻、同一金额、同一说明）只记一次。返回 (新账本, 新加了几笔)。 */
    fun add(book: LedgerBook, items: List<Expense>): Pair<LedgerBook, Int> {
        val refs = book.items.mapNotNull { it.ref.takeIf { r -> r.isNotBlank() } }.toMutableSet()
        val keys = book.items.map { Triple(it.at, it.cents, it.what) }.toMutableSet()
        val added = items.filter { e ->
            val fresh = if (e.ref.isNotBlank()) refs.add(e.ref) else true
            fresh && keys.add(Triple(e.at, e.cents, e.what))
        }
        return book.copy(items = (book.items + added).sortedBy { it.at }.takeLast(5000)) to added.size
    }

    fun remove(book: LedgerBook, id: String): LedgerBook = book.copy(items = book.items.filterNot { it.id == id })

    // ---------- 统计 ----------

    fun between(book: LedgerBook, from: Long, to: Long): List<Expense> = book.items.filter { it.at in from until to }

    fun day(book: LedgerBook, date: LocalDate, zone: ZoneId): List<Expense> =
        between(book, DayClock.startOf(date, zone), DayClock.startOf(date.plusDays(1), zone))

    fun week(book: LedgerBook, now: Long, zone: ZoneId): List<Expense> {
        val ws = com.zongkong.core.plan.Plans.weekStart(now, zone)
        return between(book, DayClock.startOf(ws, zone), DayClock.startOf(ws.plusWeeks(1), zone))
    }

    fun total(items: List<Expense>): Long = items.sumOf { it.cents }

    fun byCat(items: List<Expense>): List<Pair<String, Long>> =
        items.groupBy { it.cat }.map { (k, v) -> k to total(v) }.sortedByDescending { it.second }

    fun line(items: List<Expense>): String =
        if (items.isEmpty()) "没记" else "${yuan(total(items))} 元（" + byCat(items).take(4).joinToString("、") { "${it.first} ${yuan(it.second)}" } + "）"

    /** 给参谋看的一段。 */
    fun brief(book: LedgerBook, now: Long, zone: ZoneId): String = buildString {
        val today = day(book, DayClock.date(now, zone), zone)
        val week = week(book, now, zone)
        append("今天支出：").append(line(today)).append('\n')
        append("本周支出：").append(line(week)).append('\n')
        today.maxByOrNull { it.cents }?.let { append("今天最大一笔：${it.what} ${yuan(it.cents)} 元\n") }
        append("最近几笔：").append(book.items.takeLast(5).reversed().joinToString("；") { "${it.what} ${yuan(it.cents)}（${it.cat}）id=${it.id}" }.ifBlank { "没有" })
    }
}
