package com.yishou.app.link

/**
 * 一手 ↔ 总控 的连接约定。两边各有一份同样的定义（总控那边在 com.zongkong.app.link）。
 * 只传字符串、整数、长整数、布尔值，放在 Bundle 里，版本号不一致时多出来的字段直接忽略。
 */
object Link {
    const val YISHOU_AUTHORITY = "com.yishou.app.link"
    const val ZONGKONG_AUTHORITY = "com.zongkong.app.link"
    const val VERSION = 1

    /** 读对方的状态。 */
    const val STATUS = "status"

    /** 总控 → 一手：把一步行动交给一手陪练（新建或更新当前任务）。 */
    const val SET_TASK = "setTask"

    const val OK = "ok"
    const val ERROR = "error"
}

/** 一手今天的状态，给总控的行为管理部看。 */
data class YishouStatus(
    val appVersion: String = "",
    val taskTitle: String = "",
    val taskGoal: String = "",
    val known: String = "",
    val stuck: String = "",
    val next: String = "",
    /** 当前任务是总控派来的：总控的事项编号或行动键。 */
    val ref: String = "",
    val roundsToday: Int = 0,
    val effectiveToday: Int = 0,
    val setSize: Int = 3,
    val targetSets: Int = 0,
    val doneSets: Int = 0,
    val streak: Int = 0,
    val windowActive: Boolean = false,
    val windowEnd: Long = 0,
    /** 入口思考页（无障碍服务）开着没有。 */
    val gateOn: Boolean = false,
    val summaryDate: String = "",
    val rule: String = "",
    val tomorrowQuestion: String = "",
    val summaryNote: String = "",
) {
    fun toMap(): Map<String, Any> = mapOf(
        "v" to Link.VERSION,
        "appVersion" to appVersion,
        "taskTitle" to taskTitle,
        "taskGoal" to taskGoal,
        "known" to known,
        "stuck" to stuck,
        "next" to next,
        "ref" to ref,
        "roundsToday" to roundsToday,
        "effectiveToday" to effectiveToday,
        "setSize" to setSize,
        "targetSets" to targetSets,
        "doneSets" to doneSets,
        "streak" to streak,
        "windowActive" to windowActive,
        "windowEnd" to windowEnd,
        "gateOn" to gateOn,
        "summaryDate" to summaryDate,
        "rule" to rule,
        "tomorrowQuestion" to tomorrowQuestion,
        "summaryNote" to summaryNote,
    )
}

/** 总控此刻的状态：严管没有、正在做哪一步。一手据此决定要不要再弹自己的思考页。 */
data class ZongkongStatus(
    /** 总控正在严管：拦截名单里的应用会被总控弹回。 */
    val strict: Boolean = false,
    val headline: String = "",
    val reasons: List<String> = emptyList(),
    /** 专注锁到什么时候（毫秒）；0 = 没开。 */
    val focusUntil: Long = 0,
    val actionTitle: String = "",
    /** 下一步 / 断点里“回来第一件事”。 */
    val step: String = "",
    val criteria: String = "",
    val threadCode: String = "",
    val threadTitle: String = "",
    val gatesDone: Int = 0,
    val gatesTotal: Int = 0,
    /** 严管时总控会弹回的应用包名。 */
    val blocks: Set<String> = emptySet(),
) {
    /** 这个应用总控现在会弹回：一手就不必再弹思考页了。 */
    fun bounces(pkg: String): Boolean = strict && pkg in blocks

    companion object {
        fun fromMap(m: Map<String, Any?>): ZongkongStatus = ZongkongStatus(
            strict = m["strict"] as? Boolean ?: false,
            headline = m["headline"] as? String ?: "",
            reasons = (m["reasons"] as? String).orEmpty().split('\n').filter { it.isNotBlank() },
            focusUntil = (m["focusUntil"] as? Number)?.toLong() ?: 0,
            actionTitle = m["actionTitle"] as? String ?: "",
            step = m["step"] as? String ?: "",
            criteria = m["criteria"] as? String ?: "",
            threadCode = m["threadCode"] as? String ?: "",
            threadTitle = m["threadTitle"] as? String ?: "",
            gatesDone = (m["gatesDone"] as? Number)?.toInt() ?: 0,
            gatesTotal = (m["gatesTotal"] as? Number)?.toInt() ?: 0,
            blocks = (m["blocks"] as? String).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
        )
    }
}

/** 总控派来的一步行动。 */
data class TaskOrder(
    val title: String,
    val goal: String,
    val material: String = "",
    val known: String = "",
    val stuck: String = "",
    val next: String = "",
    val ref: String = "",
) {
    companion object {
        /** 标题和目标都不能空；太长的截断，免得撑爆界面。 */
        fun fromMap(m: Map<String, Any?>): TaskOrder? {
            fun s(k: String, max: Int) = (m[k] as? String).orEmpty().trim().take(max)
            val title = s("title", 60)
            val goal = s("goal", 300)
            if (title.isBlank() || goal.isBlank()) return null
            return TaskOrder(title, goal, s("material", 2000), s("known", 500), s("stuck", 500), s("next", 300), s("ref", 80))
        }
    }
}
