package com.moling.core.link

import com.moling.core.power.Power
import kotlinx.serialization.Serializable

/*
 * 墨灵 ↔ 总控 的约定。
 *
 * 分工：墨灵决定“水怎么流”（规划、对话、判断），总控负责“闸门”（拦截、专注锁、总控时间、信用）。
 * 墨灵通过这份约定读总控的状态、给总控下指令；总控只认墨灵的签名（和总控 ↔ 一手的连接同一种做法）。
 *
 * 铁律在总控那边也要再守一遍：收紧立即生效，放宽只进总控自己的 24 小时冷静期。
 * Android 上用 ContentProvider.call(method, arg, extras) 传递；extras 里放 [toMap] 的键值（都是字符串）。
 */

const val ZK_LINK_VERSION = 1
const val ZK_AUTHORITY = "com.zongkong.app.moling"
const val ZK_PACKAGE = "com.zongkong.app"

/** ContentProvider.call 的方法名（总控那边在 zongkong/.../core/moling/Moling.kt 的 MolingLink）。 */
const val ZK_STATUS = "status"
const val ZK_COMMAND = "command"
const val ZK_RESULT = "result"

/** 总控此刻的状态（墨灵每分钟读一次，也在总控推送变化时读）。 */
@Serializable
data class ZkStatus(
    val version: Int = ZK_LINK_VERSION,
    val at: Long = 0,
    /** 总控的无障碍服务在系统设置里是开着的。 */
    val accessibilityOn: Boolean = false,
    /** 总控无障碍服务最近一次报平安（服务真的在跑）。 */
    val heartbeatAt: Long = 0,
    /** 用户自己在设置里关掉的（总控知道时才填）。 */
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
    /** 0.11 哨兵是否在线。 */
    val sentinelOn: Boolean = false,
)

/** 墨灵对总控下的指令。每条都对应一项权柄，先过 [com.moling.core.power.Charter] 再发。 */
@Serializable
sealed interface ZkCommand {
    val reason: String

    /** 定今天的主线（[at] = 计划开始时间，0 = 不定时间）。 */
    @Serializable data class SetMain(val title: String, val at: Long, val doneWhen: String, override val reason: String) : ZkCommand

    /** 加一步行动并排进时间。 */
    @Serializable data class AddAction(val title: String, val start: Long, val end: Long, val doneWhen: String, override val reason: String) : ZkCommand

    /** 开专注锁（做这一步时娱乐应用弹回）。 */
    @Serializable data class StartFocus(val minutes: Int, val actionTitle: String, override val reason: String) : ZkCommand

    /** 现在进入总控时间（手机只剩白名单）。 */
    @Serializable data class EnterHq(val minutes: Int, override val reason: String) : ZkCommand

    /** 收紧：kind = block_app（value=包名）。总控现在只支持这一种；“只减今天的娱乐”“临时严管”总控还没有开关。 */
    @Serializable data class Tighten(val kind: String, val value: String, override val reason: String) : ZkCommand

    /**
     * 申请放宽：总控照自己的规矩走 24 小时冷静期，墨灵不能直接放宽。
     * kind = unblock_app（value=包名）/ play_quota（value=每天娱乐上限的分钟数）。
     */
    @Serializable data class RequestLoosen(val kind: String, val value: String, override val reason: String) : ZkCommand

    /** 往总控的收集箱放一条（结论、材料、问题）。 */
    @Serializable data class Note(val text: String, override val reason: String) : ZkCommand
}

/** 指令对应的权柄。 */
fun ZkCommand.power(): Power = when (this) {
    is ZkCommand.SetMain, is ZkCommand.AddAction, is ZkCommand.Note -> Power.PLAN
    is ZkCommand.StartFocus, is ZkCommand.EnterHq, is ZkCommand.Tighten -> Power.TIGHTEN
    is ZkCommand.RequestLoosen -> Power.LOOSEN
}

/** 总控执行后的回执。 */
@Serializable
data class ZkReceipt(val ok: Boolean, val message: String, val at: Long)

/** 给用户看的一句话（确认框、日志里用）。 */
fun ZkCommand.describe(): String = when (this) {
    is ZkCommand.SetMain -> "把今天的主线定为「$title」"
    is ZkCommand.AddAction -> "加一步「$title」"
    is ZkCommand.StartFocus -> "开 $minutes 分钟专注锁（$actionTitle）"
    is ZkCommand.EnterHq -> "现在进入 $minutes 分钟总控时间"
    is ZkCommand.Tighten -> when (kind) {
        "block_app" -> "把 $value 加进拦截名单"
        "cut_play" -> "今天的娱乐时间减少 $value 分钟"
        "strict" -> "临时严管 $value 分钟"
        else -> "收紧：$kind $value"
    }
    is ZkCommand.RequestLoosen -> when (kind) {
        "unblock_app" -> "申请把 $value 移出拦截名单（总控 24 小时后生效）"
        "play_quota" -> "申请把每天娱乐上限改成 $value 分钟（放宽要等 24 小时）"
        else -> "申请放宽：$kind $value"
    }
    is ZkCommand.Note -> "往总控收集箱放一条：${text.take(30)}"
} + if (reason.isNotBlank()) "。原因：$reason" else ""

/** Android 传输用：指令 ↔ 键值对（都是字符串，方便塞进 Bundle）。 */
object ZkWire {
    fun toMap(c: ZkCommand): Map<String, String> = when (c) {
        is ZkCommand.SetMain -> mapOf("cmd" to "set_main", "title" to c.title, "at" to c.at.toString(), "done" to c.doneWhen)
        is ZkCommand.AddAction -> mapOf("cmd" to "add_action", "title" to c.title, "start" to c.start.toString(), "end" to c.end.toString(), "done" to c.doneWhen)
        is ZkCommand.StartFocus -> mapOf("cmd" to "start_focus", "minutes" to c.minutes.toString(), "title" to c.actionTitle)
        is ZkCommand.EnterHq -> mapOf("cmd" to "enter_hq", "minutes" to c.minutes.toString())
        is ZkCommand.Tighten -> mapOf("cmd" to "tighten", "kind" to c.kind, "value" to c.value)
        is ZkCommand.RequestLoosen -> mapOf("cmd" to "request_loosen", "kind" to c.kind, "value" to c.value)
        is ZkCommand.Note -> mapOf("cmd" to "note", "text" to c.text)
    } + mapOf("reason" to c.reason, "v" to ZK_LINK_VERSION.toString())

    fun fromMap(m: Map<String, String>): ZkCommand? {
        val r = m["reason"].orEmpty()
        fun l(k: String) = m[k]?.toLongOrNull() ?: 0L
        fun i(k: String) = m[k]?.toIntOrNull() ?: 0
        return when (m["cmd"]) {
            "set_main" -> ZkCommand.SetMain(m["title"].orEmpty(), l("at"), m["done"].orEmpty(), r)
            "add_action" -> ZkCommand.AddAction(m["title"].orEmpty(), l("start"), l("end"), m["done"].orEmpty(), r)
            "start_focus" -> ZkCommand.StartFocus(i("minutes"), m["title"].orEmpty(), r)
            "enter_hq" -> ZkCommand.EnterHq(i("minutes"), r)
            "tighten" -> ZkCommand.Tighten(m["kind"].orEmpty(), m["value"].orEmpty(), r)
            "request_loosen" -> ZkCommand.RequestLoosen(m["kind"].orEmpty(), m["value"].orEmpty(), r)
            "note" -> ZkCommand.Note(m["text"].orEmpty(), r)
            else -> null
        }?.takeIf { valid(it) }
    }

    /** 总控的状态（[ZK_STATUS] 返回的键值）→ [ZkStatus]。读不出的字段用默认值；列表用换行分隔。 */
    fun status(m: Map<String, String>): ZkStatus {
        fun s(k: String) = m[k].orEmpty()
        fun b(k: String) = m[k] == "true"
        fun l(k: String, d: Long = 0) = m[k]?.toLongOrNull() ?: d
        fun i(k: String, d: Int = 0) = m[k]?.toIntOrNull() ?: d
        fun list(k: String) = s(k).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return ZkStatus(
            version = i("v", ZK_LINK_VERSION), at = l("at"),
            accessibilityOn = b("accessibilityOn"), heartbeatAt = l("heartbeatAt"), userDisabled = b("userDisabled"),
            strict = b("strict"), strictReason = s("strictReason"), focus = b("focus"), hqActive = b("hqActive"), quiet = b("quiet"),
            mainTitle = s("mainTitle"), mainKey = s("mainKey"), mainPlannedAt = l("mainPlannedAt"), mainStarted = b("mainStarted"), mainDone = b("mainDone"),
            lastProgressAt = l("lastProgressAt"), playMinutesToday = i("playMinutesToday"), workMinutesToday = i("workMinutesToday"),
            playLeftMin = i("playLeftMin", -1), spendTodayCents = l("spendTodayCents"), credit = i("credit", -1), stance = s("stance"),
            aims = list("aims"), weekDeliverables = list("weekDeliverables"), upcoming = list("upcoming"), blocked = list("blocked"),
            sentinelOn = b("sentinelOn"),
        )
    }

    /** 总控对 [ZK_COMMAND] 的回执 → [ZkReceipt]。总控报错（例如签名不对）时 ok = false。 */
    fun receipt(m: Map<String, String>, now: Long): ZkReceipt =
        m["error"]?.let { ZkReceipt(false, it, now) } ?: ZkReceipt(m["ok"] == "true", m["message"].orEmpty(), now)

    /** 总控那边也要调用：明显不合理的指令直接拒绝。 */
    fun valid(c: ZkCommand): Boolean = when (c) {
        is ZkCommand.SetMain -> c.title.isNotBlank()
        is ZkCommand.AddAction -> c.title.isNotBlank() && (c.start == 0L || c.end == 0L || c.end > c.start)
        is ZkCommand.StartFocus -> c.minutes in 5..180
        is ZkCommand.EnterHq -> c.minutes in 5..60
        is ZkCommand.Tighten -> when (c.kind) {
            "block_app" -> c.value.isNotBlank() && c.value !in com.moling.core.power.Charter.neverTouch
            else -> false
        }
        is ZkCommand.RequestLoosen -> when (c.kind) {
            "unblock_app" -> c.value.isNotBlank()
            "play_quota" -> (c.value.toIntOrNull() ?: -1) in 0..1440
            else -> false
        }
        is ZkCommand.Note -> c.text.isNotBlank()
    }
}
