package com.zongkong.core

import com.zongkong.core.plan.PlanBook
import com.zongkong.core.plan.Plans
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import java.time.ZoneId

/**
 * 总控 ↔ 一手 的连接约定。一手那边有一份同样的定义（com.yishou.app.link.Contract）。
 * 只传字符串、整数、长整数、布尔值，版本不一致时多出来的字段直接忽略。
 */
object Link {
    const val YISHOU = "com.yishou.app"
    const val YISHOU_AUTHORITY = "com.yishou.app.link"
    const val ZONGKONG_AUTHORITY = "com.zongkong.app.link"
    const val VERSION = 1
    const val STATUS = "status"
    /** 总控 ← 一手：since 之后的每一手（一手 0.25 起的「一本账」），总控存一份。 */
    const val LEDGER = "ledger"
    const val SET_TASK = "setTask"
    /** 总控 → 一手：现在开一局陪练窗口。 */
    const val START_WINDOW = "startWindow"
    const val OK = "ok"
    const val ERROR = "error"

    /**
     * 总控对一手的要求。一手每次弹思考页、发放行时都会问一次（只读，不存，总控卸了就没影响）。
     *  - hold：专注锁开着 → 一手这时不发放行，只让你回去做事
     *  - passCap：收紧时每次最多放行几分钟；0 = 不限
     *  - noOffline：收紧时不能“保存并通过”
     */
    data class Influence(val hold: Boolean = false, val passCap: Int = 0, val noOffline: Boolean = false, val note: String = "")

    fun influence(status: Status, config: Config, work: Work, plans: PlanBook, zone: ZoneId): Influence {
        if (!config.yishouInfluence || status.paused || status.emergencyUntil != null) return Influence()
        val now = status.now
        val lock = status.reasons.filterIsInstance<Reason.Focus>().firstOrNull()
        if (lock != null) return Influence(hold = true, passCap = config.yishouPassCap, noOffline = true, note = "总控专注锁：先做完「${lock.title}」")
        if (status.strict) {
            return Influence(passCap = config.yishouPassCap, noOffline = true, note = "总控严管中：" + (status.reasons.firstOrNull()?.text ?: ""))
        }
        val plan = Plans.active(plans, now, zone) ?: return Influence()
        if (Plans.isRestDay(plan, now, zone)) return Influence()
        val main = Plans.mainAction(plans, work, now, zone)
        val minutesToday = Plans.progress(plan, work, now, zone).todayPlanMinutes
        val hour = java.time.Instant.ofEpochMilli(now).atZone(zone).hour
        val mainStart = main?.plan?.let { TimeParse.startMillis(it, zone) }
        val behind = when {
            main != null && mainStart != null -> now > mainStart + 15 * 60_000 && minutesToday == 0
            main != null -> hour >= 14 && minutesToday == 0
            else -> hour >= 12 && minutesToday == 0
        }
        if (!behind) return Influence()
        val what = main?.let { "今天的主线「${it.title}」还没开始" } ?: "今天计划内的事还一分钟没做"
        return Influence(passCap = config.yishouPassCap, noOffline = true, note = "总控：$what")
    }

    /** 总控此刻的状态，给一手看（一手据此决定要不要再弹自己的思考页、放行给多久）。 */
    fun zongkongStatus(status: Status, config: Config, work: Work, zone: ZoneId, plans: PlanBook = PlanBook()): Map<String, Any> {
        val main = Plans.day(plans, DayClock.dateKey(status.now, zone))?.mainActionKey.orEmpty()
        val cur = Focus.current(work, status.now, zone, main)
        val a = cur.action
        val lock = status.reasons.filterIsInstance<Reason.Focus>().firstOrNull()
        val inf = influence(status, config, work, plans, zone)
        val mainRec = Plans.mainAction(plans, work, status.now, zone)
        return mapOf(
            "v" to VERSION,
            "strict" to status.strict,
            "headline" to (status.reasons.firstOrNull()?.text ?: status.headline),
            "reasons" to status.reasons.joinToString("\n") { it.text },
            "focusUntil" to (lock?.until ?: 0L),
            "actionTitle" to (a?.title ?: ""),
            "step" to (a?.get(F.BREAK)?.ifBlank { cur.thread?.get(F.NEXT).orEmpty() } ?: ""),
            "criteria" to (a?.get(F.CRITERIA) ?: ""),
            "threadCode" to (cur.thread?.get(F.CODE) ?: ""),
            "threadTitle" to (cur.thread?.title ?: ""),
            "gatesDone" to status.doneCount,
            "gatesTotal" to status.gates.size,
            "blocks" to config.blocked.joinToString(","),
            "hold" to inf.hold,
            "passCap" to inf.passCap,
            "noOffline" to inf.noOffline,
            "note" to inf.note,
            "mainTitle" to (mainRec?.title ?: ""),
            "lockdown" to status.reasons.any { it is Reason.Session },
        )
    }

    /** 把一步行动交给一手陪练。ref 用“事项编号/行动键”，一手再交一次时据此更新而不是新建。 */
    fun taskOrder(work: Work, actionKey: String): Map<String, String>? {
        val a = work.rec(actionKey) ?: return null
        val t = work.rec(a.threadKey)
        val goal = a[F.CRITERIA].ifBlank { t?.get(F.CRITERIA).orEmpty() }.ifBlank { "把「${a.title}」推进一步，说清楚结果" }
        val material = listOfNotNull(
            t?.let { "事项：${it.title}" },
            t?.get(F.WHY)?.takeIf { it.isNotBlank() }?.let { "为什么：$it" },
            t?.get(F.JUDGE)?.takeIf { it.isNotBlank() }?.let { "当前判断：$it" },
            a[F.STUCK].takeIf { it.isNotBlank() }?.let { "卡点：$it" },
        ).joinToString("\n")
        return mapOf(
            "title" to a.title.take(60),
            "goal" to goal,
            "material" to material,
            "known" to a[F.RESULT].ifBlank { t?.get(F.JUDGE).orEmpty() },
            "stuck" to a[F.BREAK],
            "next" to "",
            "ref" to "${t?.get(F.CODE).orEmpty()}/${a.key}",
        )
    }
}

/** 一手今天的状态。读不到的字段用默认值。 */
data class YishouStatus(
    val appVersion: String = "",
    val taskTitle: String = "",
    val taskGoal: String = "",
    val known: String = "",
    val stuck: String = "",
    val next: String = "",
    val ref: String = "",
    val roundsToday: Int = 0,
    val effectiveToday: Int = 0,
    val setSize: Int = 3,
    val targetSets: Int = 0,
    val doneSets: Int = 0,
    val streak: Int = 0,
    val windowActive: Boolean = false,
    val windowEnd: Long = 0,
    val gateOn: Boolean = false,
    val summaryDate: String = "",
    val rule: String = "",
    val tomorrowQuestion: String = "",
    val summaryNote: String = "",
    /** 一手 0.25 起：今天的一行账（刷题 12（接住 9）· 刷着练 6……） */
    val ledgerLine: String = "",
) {
    val hitToday: Boolean get() = targetSets > 0 && doneSets >= targetSets

    /** 一手正在练的是总控交过去的哪一步（行动键）。 */
    val actionKey: String get() = ref.substringAfter('/', "")

    fun summary(): String = buildString {
        if (ledgerLine.isNotBlank()) {
            // 新版一手没有「目标组数」：直接报一行账，接住的手数折成组
            append("今天 $roundsToday 手，接住 $effectiveToday（折 $doneSets 组）：$ledgerLine")
            if (taskTitle.isNotBlank()) append("；在练「$taskTitle」")
            return@buildString
        }
        append("今天 $doneSets/$targetSets 组（有效 $effectiveToday 手，每组 $setSize 手）")
        if (streak > 1) append("，连续达标 $streak 天")
        if (taskTitle.isNotBlank()) append("；在练「$taskTitle」")
        if (windowActive) append("；陪练窗口进行中")
    }

    companion object {
        fun fromMap(m: Map<String, Any?>): YishouStatus {
            fun s(k: String) = m[k] as? String ?: ""
            fun i(k: String, d: Int = 0) = (m[k] as? Number)?.toInt() ?: d
            return YishouStatus(
                appVersion = s("appVersion"),
                taskTitle = s("taskTitle"),
                taskGoal = s("taskGoal"),
                known = s("known"),
                stuck = s("stuck"),
                next = s("next"),
                ref = s("ref"),
                roundsToday = i("roundsToday"),
                effectiveToday = i("effectiveToday"),
                setSize = i("setSize", 3),
                targetSets = i("targetSets"),
                doneSets = i("doneSets"),
                streak = i("streak"),
                windowActive = m["windowActive"] as? Boolean ?: false,
                windowEnd = (m["windowEnd"] as? Number)?.toLong() ?: 0,
                gateOn = m["gateOn"] as? Boolean ?: false,
                summaryDate = s("summaryDate"),
                rule = s("rule"),
                tomorrowQuestion = s("tomorrowQuestion"),
                summaryNote = s("summaryNote"),
                ledgerLine = s("ledgerLine"),
            )
        }
    }
}
