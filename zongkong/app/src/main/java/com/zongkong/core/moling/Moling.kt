package com.zongkong.core.moling

import com.zongkong.core.Defaults
import com.zongkong.core.agent.ToolResult
import com.zongkong.core.agent.ZkTools
import com.zongkong.core.work.Work
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/*
 * 总控 ↔ 墨灵：总控这一头。
 *
 * 墨灵是规划者，总控是闸门。墨灵只能“建议”，执行永远在总控这边，而且要用户在总控里点一下：
 *   1. 墨灵通过 ContentProvider.call 交来一条指令（键值都是字符串，格式和墨灵的 ZkWire 一致）。
 *   2. 总控先检查（不认识的、不合理的、拦不得的应用、太频繁的，直接拒），合格的放进“墨灵的建议”等确认。
 *   3. 用户在总控里点“执行”，才通过参谋的工具箱（[ZkTools]）去做：规矩和参谋一样——
 *      收紧立即生效，放宽（移出拦截名单、放宽娱乐上限）照样进 24 小时冷静期。
 * 墨灵这边的约定在 moling/app/src/main/java/com/moling/core/link/ZkLink.kt，两边改一边就要改另一边。
 */
object MolingLink {
    const val PACKAGE = "com.moling.app"
    const val AUTHORITY = "com.zongkong.app.moling"
    const val VERSION = 1

    /** 墨灵读总控的状态。返回 [MolingStatus.toMap]。 */
    const val STATUS = "status"

    /** 墨灵交一条指令。返回 ok / message / id。 */
    const val COMMAND = "command"

    /** 墨灵问一条指令后来怎么样了（arg = id）。返回 state / message。 */
    const val RESULT = "result"

    const val OK = "ok"
    const val MESSAGE = "message"
    const val ID = "id"
    const val STATE = "state"
    const val ERROR = "error"

    /** 墨灵排的行动都挂在这件事下面。 */
    const val THREAD = "墨灵的水道"

    /** 墨灵的铁律里永不拦截的包，和总控自己的合在一起。 */
    val neverBlock: Set<String> = Defaults.neverBlock + setOf(
        "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.android.mms",
        "com.google.android.apps.messaging", "com.android.settings", "com.tencent.mm", "com.eg.android.AlipayGphone",
        "com.autonavi.minimap", "com.baidu.BaiduMap", "com.google.android.apps.maps", PACKAGE,
    )
}

@Serializable
enum class OrderState(val label: String) {
    PENDING("等你确认"),
    DONE("执行了"),
    FAILED("没执行成"),
    DECLINED("不用了"),
    EXPIRED("过期了"),
}

/** 墨灵交来的一条指令。[fields] 是原样的键值（cmd、title……、reason、v）。 */
@Serializable
data class MolingOrder(
    val id: String,
    val fields: Map<String, String>,
    val receivedAt: Long,
    val state: OrderState = OrderState.PENDING,
    val result: String = "",
    val decidedAt: Long = 0,
) {
    val cmd: String get() = fields["cmd"].orEmpty()
    val text: String get() = Moling.describe(fields)
}

@Serializable
data class MolingBook(val orders: List<MolingOrder> = emptyList(), val seq: Long = 0)

/** 收下一条指令后给墨灵的回执。 */
data class Receipt(val ok: Boolean, val message: String, val id: String = "")

/** 总控此刻的状态，给墨灵看。字段和墨灵的 ZkStatus 一一对应；列表用换行连起来。 */
data class MolingStatus(
    val at: Long,
    val accessibilityOn: Boolean = false,
    val heartbeatAt: Long = 0,
    val userDisabled: Boolean = false,
    val strict: Boolean = false,
    val strictReason: String = "",
    val focus: Boolean = false,
    val hqActive: Boolean = false,
    val quiet: Boolean = false,
    val mainTitle: String = "",
    val mainKey: String = "",
    val mainPlannedAt: Long = 0,
    val mainStarted: Boolean = false,
    val mainDone: Boolean = false,
    val lastProgressAt: Long = 0,
    val playMinutesToday: Int = 0,
    val workMinutesToday: Int = 0,
    val playLeftMin: Int = -1,
    val spendTodayCents: Long = 0,
    val credit: Int = -1,
    val stance: String = "",
    val aims: List<String> = emptyList(),
    val weekDeliverables: List<String> = emptyList(),
    val upcoming: List<String> = emptyList(),
    val blocked: List<String> = emptyList(),
    val sentinelOn: Boolean = false,
) {
    fun toMap(): Map<String, String> {
        fun list(xs: List<String>) = xs.joinToString("\n") { it.replace('\n', ' ') }
        return mapOf(
            "v" to MolingLink.VERSION.toString(),
            "at" to at.toString(),
            "accessibilityOn" to accessibilityOn.toString(),
            "heartbeatAt" to heartbeatAt.toString(),
            "userDisabled" to userDisabled.toString(),
            "strict" to strict.toString(),
            "strictReason" to strictReason,
            "focus" to focus.toString(),
            "hqActive" to hqActive.toString(),
            "quiet" to quiet.toString(),
            "mainTitle" to mainTitle,
            "mainKey" to mainKey,
            "mainPlannedAt" to mainPlannedAt.toString(),
            "mainStarted" to mainStarted.toString(),
            "mainDone" to mainDone.toString(),
            "lastProgressAt" to lastProgressAt.toString(),
            "playMinutesToday" to playMinutesToday.toString(),
            "workMinutesToday" to workMinutesToday.toString(),
            "playLeftMin" to playLeftMin.toString(),
            "spendTodayCents" to spendTodayCents.toString(),
            "credit" to credit.toString(),
            "stance" to stance,
            "aims" to list(aims),
            "weekDeliverables" to list(weekDeliverables),
            "upcoming" to list(upcoming),
            "blocked" to list(blocked),
            "sentinelOn" to sentinelOn.toString(),
        )
    }
}

object Moling {
    /** 没确认的建议挂多久。 */
    const val TTL_MS = 3 * 3_600_000L
    /** 同时最多挂几条没确认的。 */
    const val MAX_PENDING = 10
    /** 一小时最多收几条（防止墨灵出错时刷屏）。 */
    const val MAX_PER_HOUR = 20
    private const val KEEP = 100

    /** 能放宽的种类（都走总控自己的冷静期）。 */
    val loosenKinds = setOf("unblock_app", "play_quota")

    /** 合格返回 null，否则返回拒绝的原因。 */
    fun check(f: Map<String, String>): String? {
        val v = f["v"]?.toIntOrNull() ?: return "缺少版本号"
        if (v > MolingLink.VERSION) return "墨灵的版本比总控新（v$v），先更新总控"
        fun s(k: String) = f[k].orEmpty().trim()
        fun i(k: String) = f[k]?.toIntOrNull()
        fun l(k: String) = f[k]?.toLongOrNull() ?: 0L
        return when (f["cmd"]) {
            "set_main" -> if (s("title").isBlank()) "主线标题是空的" else null
            "add_action" -> when {
                s("title").isBlank() -> "行动标题是空的"
                l("start") > 0 && l("end") > 0 && l("end") <= l("start") -> "结束时间早于开始时间"
                else -> null
            }
            "start_focus" -> if (i("minutes") !in 5..180) "专注锁只能 5–180 分钟" else null
            "enter_hq" -> if (i("minutes") !in 5..90) "总控时间只能 5–90 分钟" else null
            "tighten" -> when (s("kind")) {
                "block_app" -> when {
                    s("value").isBlank() -> "没写要拦哪个应用"
                    s("value") in MolingLink.neverBlock -> "这个应用永远不拦：${s("value")}"
                    else -> null
                }
                "cut_play", "strict" -> "总控还没有“只减今天的娱乐”“临时严管”这两个开关，先用专注锁或总控时间"
                else -> "不认识的收紧：${s("kind")}"
            }
            "request_loosen" -> when {
                s("kind") !in loosenKinds -> "这种放宽只能你自己在总控里改：${s("kind")}"
                s("value").isBlank() -> "放宽没写具体内容"
                s("kind") == "play_quota" && i("value") == null -> "娱乐上限要写分钟数"
                else -> null
            }
            "note" -> if (s("text").isBlank()) "内容是空的" else null
            null, "" -> "没写要做什么"
            else -> "不认识的指令：${f["cmd"]}"
        }
    }

    /** 给人看的一句话（通知、列表里用）。 */
    fun describe(f: Map<String, String>): String {
        fun s(k: String) = f[k].orEmpty()
        val what = when (f["cmd"]) {
            "set_main" -> "把今天的主线定为「${s("title")}」"
            "add_action" -> "加一步「${s("title")}」" + timeText(f["start"]?.toLongOrNull() ?: 0, f["end"]?.toLongOrNull() ?: 0)
            "start_focus" -> "开 ${s("minutes")} 分钟专注锁" + if (s("title").isNotBlank()) "（${s("title")}）" else ""
            "enter_hq" -> "现在进入 ${s("minutes")} 分钟总控时间"
            "tighten" -> if (s("kind") == "block_app") "把 ${s("value")} 加进拦截名单" else "收紧：${s("kind")} ${s("value")}"
            "request_loosen" -> when (s("kind")) {
                "unblock_app" -> "把 ${s("value")} 移出拦截名单（24 小时后生效）"
                "play_quota" -> "每天娱乐上限改成 ${s("value")} 分钟（放宽要等 24 小时）"
                else -> "放宽：${s("kind")} ${s("value")}"
            }
            "note" -> "往收集箱放一条：${s("text").take(30)}"
            else -> "不认识的指令"
        }
        return what + if (s("reason").isNotBlank()) "。墨灵说：${s("reason")}" else ""
    }

    private fun timeText(start: Long, end: Long, zone: ZoneId = com.zongkong.core.DayClock.BEIJING): String {
        if (start <= 0) return ""
        fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        return "，" + hm(start) + if (end > start) "–" + hm(end) else ""
    }

    // ------------------------------------------------------------------ 收

    /** 收一条指令：过期旧的、检查、限流、去重，合格的挂起来等确认。 */
    fun receive(book: MolingBook, f: Map<String, String>, now: Long): Pair<MolingBook, Receipt> {
        val b = expire(book, now)
        check(f)?.let { return b to Receipt(false, it) }
        val clean = f.mapValues { (_, v) -> v.take(500) }
        val pending = pending(b, now)
        pending.firstOrNull { same(it.fields, clean) }?.let { return b to Receipt(true, "这条已经在等你确认了", it.id) }
        if (pending.size >= MAX_PENDING) return b to Receipt(false, "总控里已经有 $MAX_PENDING 条墨灵的建议没处理，先处理掉再交")
        if (b.orders.count { now - it.receivedAt < 3_600_000L } >= MAX_PER_HOUR) return b to Receipt(false, "这一小时墨灵交得太多了，晚点再交")
        val o = MolingOrder("m${b.seq}-${now.toString(36)}", clean, now)
        return b.copy(orders = (b.orders + o).takeLast(KEEP), seq = b.seq + 1) to Receipt(true, "总控收下了，等你在总控里确认", o.id)
    }

    /** 同一件事（不管理由和版本号写得一不一样）。 */
    private fun same(a: Map<String, String>, b: Map<String, String>) = a - "reason" - "v" == b - "reason" - "v"

    fun pending(book: MolingBook, now: Long): List<MolingOrder> =
        book.orders.filter { it.state == OrderState.PENDING && now - it.receivedAt < TTL_MS }

    fun expire(book: MolingBook, now: Long): MolingBook = book.copy(orders = book.orders.map {
        if (it.state == OrderState.PENDING && now - it.receivedAt >= TTL_MS) it.copy(state = OrderState.EXPIRED, decidedAt = now) else it
    })

    fun decline(book: MolingBook, id: String, now: Long): MolingBook = book.copy(orders = book.orders.map {
        if (it.id == id && it.state == OrderState.PENDING) it.copy(state = OrderState.DECLINED, decidedAt = now) else it
    })

    fun finish(book: MolingBook, id: String, r: ToolResult, now: Long): MolingBook = book.copy(orders = book.orders.map {
        if (it.id == id) it.copy(state = if (r.ok) OrderState.DONE else OrderState.FAILED, result = r.summary.ifBlank { r.text }.take(200), decidedAt = now) else it
    })

    // ------------------------------------------------------------------ 做

    /**
     * 用户点了“执行”：用参谋的工具箱去做，规矩和参谋一样（放宽照样进冷静期）。
     * 执行前再检查一次（存着的指令可能被改坏）。[work] 用来按标题找行动。
     */
    suspend fun run(order: MolingOrder, tools: ZkTools, work: () -> Work, now: Long, zone: ZoneId): ToolResult {
        check(order.fields)?.let { return ToolResult.fail(it) }
        val f = order.fields
        fun s(k: String) = f[k].orEmpty().trim()
        fun l(k: String) = f[k]?.toLongOrNull() ?: 0L
        val why = s("reason")
        return when (order.cmd) {
            "set_main" -> {
                val key = openAction(work(), s("title")) ?: run {
                    val r = addAction(tools, s("title"), timeOf(l("at"), 0, zone), s("done"))
                    if (!r.ok) return r
                    openAction(work(), s("title")) ?: return ToolResult.fail("建好了行动却找不到它")
                }
                tools.run("set_day_main", args("action" to key))
            }
            "add_action" -> {
                val time = timeOf(l("start"), l("end"), zone)
                val exists = work().actions().any { it.title == s("title") && it.actionStatus.open && (time.isBlank() || it.plan != null) }
                if (exists) ToolResult("已经有「${s("title")}」了，没有重复加", "已经有「${s("title")}」") else addAction(tools, s("title"), time, s("done"))
            }
            "start_focus" -> {
                val title = s("title").ifBlank { "专注一段（墨灵建议）" }
                val key = openAction(work(), title) ?: run {
                    val r = addAction(tools, title, "", "")
                    if (!r.ok) return r
                    openAction(work(), title) ?: return ToolResult.fail("建好了行动却找不到它")
                }
                tools.run("start_focus", args("action" to key, "minutes" to s("minutes"), "lock" to "true"))
            }
            "enter_hq" -> tools.run("start_hq_session", args("minutes" to s("minutes").toInt().coerceIn(15, 90).toString()))
            "tighten" -> tools.run("block_apps", args("add" to s("value")))
            "request_loosen" -> when (s("kind")) {
                "unblock_app" -> tools.run("block_apps", args("remove" to s("value")))
                else -> tools.run("set_rules", args("daily_quota_min" to s("value")))
            }
            "note" -> tools.run("add_note", args("type" to "材料", "title" to s("text").lines().first().take(40), "text" to s("text") + if (why.isNotBlank()) "\n（墨灵：$why）" else ""))
            else -> ToolResult.fail("不认识的指令")
        }
    }

    private suspend fun addAction(tools: ZkTools, title: String, time: String, criteria: String): ToolResult {
        tools.run("create_thread", args("title" to MolingLink.THREAD, "why" to "墨灵排的水道交给总控的行动都挂在这里"))
        return tools.run("add_action", args("thread" to MolingLink.THREAD, "title" to title, "time" to time, "criteria" to criteria))
    }

    /** 标题完全一样、还开着的行动（最新的那条）。 */
    private fun openAction(w: Work, title: String): String? =
        w.actions().filter { it.title == title && it.actionStatus.open }.maxByOrNull { it.updatedAt }?.key

    /** 毫秒 → 总控认得的时间写法：2026-10-20 15:00-15:45。 */
    fun timeOf(start: Long, end: Long, zone: ZoneId): String {
        if (start <= 0) return ""
        val a = Instant.ofEpochMilli(start).atZone(zone)
        val head = "%04d-%02d-%02d %02d:%02d".format(a.year, a.monthValue, a.dayOfMonth, a.hour, a.minute)
        if (end <= start) return head
        val b = Instant.ofEpochMilli(end).atZone(zone)
        return head + "-%02d:%02d".format(b.hour, b.minute)
    }

    /** 工具参数（整数参数也用字符串传，工具那边两种都认）。 */
    private fun args(vararg kv: Pair<String, String>): String = buildJsonObject {
        kv.filter { it.second.isNotBlank() }.forEach { (k, v) ->
            when {
                v == "true" || v == "false" -> put(k, v.toBoolean())
                v.toIntOrNull() != null && k in setOf("minutes", "daily_quota_min") -> put(k, v.toInt())
                else -> put(k, v)
            }
        }
    }.toString()
}
