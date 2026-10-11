package com.zongkong.core

import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
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
    const val SET_TASK = "setTask"
    const val OK = "ok"
    const val ERROR = "error"

    /** 总控此刻的状态，给一手看（一手据此决定要不要再弹自己的思考页）。 */
    fun zongkongStatus(status: Status, config: Config, work: Work, zone: ZoneId): Map<String, Any> {
        val cur = Focus.current(work, status.now, zone)
        val a = cur.action
        val lock = status.reasons.filterIsInstance<Reason.Focus>().firstOrNull()
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
) {
    val hitToday: Boolean get() = targetSets > 0 && doneSets >= targetSets

    /** 一手正在练的是总控交过去的哪一步（行动键）。 */
    val actionKey: String get() = ref.substringAfter('/', "")

    fun summary(): String = buildString {
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
            )
        }
    }
}
