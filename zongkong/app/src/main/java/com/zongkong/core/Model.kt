package com.zongkong.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 四个部门，外加总控自己（日终验收这类跨部门的关卡）。 */
@Serializable
enum class Dept(val label: String, val seal: String) {
    INFO("信息收集部", "收"),
    THINK("谋划思考部", "谋"),
    PLAN("统筹规划部", "筹"),
    ACT("行为管理部", "行"),
    HQ("总控", "控"),
}

/** 怎么验收一份汇报。强度从低到高：字数 < AI 或 Notion < Notion + AI。 */
@Serializable
enum class VerifyMode(val label: String, val strength: Int) {
    TEXT("字数验收", 0),
    AI("AI 验收", 1),
    NOTION("Notion 查账", 1),
    NOTION_AI("Notion 查账 + AI 验收", 2),
}

/** 关卡什么时候开始拦截娱乐应用。 */
@Serializable
enum class BlockMode(val label: String) {
    /** 关卡一开放就拦截，交了才放行。适合“先部署再娱乐”的晨间关卡。 */
    FROM_OPEN("开放即拦截"),
    /** 截止前随便用，过了截止还没交才拦截。 */
    AFTER_DEADLINE("超时才拦截"),
}

/**
 * 一道关卡：某个部门每天（或每周某几天）必须交的一份东西。
 * 时间都是“几点几分”换算成的分钟数（0..1439）。早于日界（凌晨 4 点）的时间算作当天深夜。
 */
@Serializable
data class Gate(
    val id: String,
    val dept: Dept,
    val title: String,
    /** 做什么、交什么，显示在提交页顶部。 */
    val instruction: String,
    /** 一键填入输入框的格式。 */
    val template: String = "",
    val openAt: Int,
    val deadline: Int,
    /** 1 = 周一 … 7 = 周日。 */
    val days: Set<Int> = ALL_DAYS,
    val block: BlockMode = BlockMode.AFTER_DEADLINE,
    val verify: VerifyMode = VerifyMode.TEXT,
    /** 不计空白的最少字数。 */
    val minChars: Int = 30,
    /** AI 验收标准，一行一条。 */
    val rubric: String = "",
    /** Notion 查账用的数据库。 */
    val notionDb: String = "",
    /** 当天这个库里至少要新建几条。 */
    val notionMinPages: Int = 1,
    /** “去做”按钮：应用包名，或者 http(s) 链接。 */
    val launch: String = "",
) {
    companion object {
        val ALL_DAYS = setOf(1, 2, 3, 4, 5, 6, 7)
    }
}

@Serializable
data class AiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val jsonMode: Boolean = true,
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

@Serializable
data class NotionConfig(
    val token: String = "",
    /** 一键搭建时用的父页面。 */
    val parentPage: String = "",
    /** 随手记写入的信息收集库。 */
    val inboxDb: String = "",
    /** 谋划库。 */
    val thinkDb: String = "",
    /** 每次验收的结果写进这里，GPT 读 Notion 时就能看到你每天交了什么。 */
    val logDb: String = "",
) {
    val ready: Boolean get() = token.isNotBlank()
}

@Serializable
data class Config(
    val gates: List<Gate> = Defaults.gates(),
    /** 严管时拦截的应用包名。 */
    val blocked: Set<String> = emptySet(),
    /** 沉默多少小时就要求报到；0 表示不检查。 */
    val silenceHours: Int = 3,
    /** 沉默检查只在这个时段内生效（分钟数）。 */
    val activeStart: Int = 8 * 60,
    val activeEnd: Int = 23 * 60,
    /** 每天娱乐应用总时长上限（分钟）；0 表示不限。 */
    val dailyQuotaMin: Int = 120,
    val emergencyPerDay: Int = 1,
    val emergencyMinutes: Int = 10,
    /** AI 或 Notion 连不上时，每天可以用加倍字数降级验收几次。 */
    val degradedPerDay: Int = 2,
    /** 到这个时间之前总控休假，不拦截（毫秒时间戳）。休假也要等 24 小时才生效。 */
    val pausedUntil: Long = 0,
    val ai: AiConfig = AiConfig(),
    val notion: NotionConfig = NotionConfig(),
    /** 等待生效的放宽改动。 */
    val pending: List<PendingChange> = emptyList(),
    /** 首次设置是否完成。 */
    val onboarded: Boolean = false,
)

/** 一次验收的结论。 */
@Serializable
data class Verdict(
    val pass: Boolean,
    val by: String,
    val feedback: String,
    val score: Int? = null,
    val missing: List<String> = emptyList(),
    /** 降级验收（AI/Notion 不可用时）。 */
    val degraded: Boolean = false,
)

/** 交给某道关卡的一份汇报，不论通过与否都记下来。 */
@Serializable
data class Submission(
    val gateId: String,
    val at: Long,
    val text: String,
    val verdict: Verdict,
    val late: Boolean = false,
)

@Serializable
enum class ReportKind(val label: String) {
    CHECKIN("报到"),
    CAPTURE("随手记"),
}

/** 随时汇报：报到或随手记。都会重置沉默计时。 */
@Serializable
data class Report(
    val at: Long,
    val kind: ReportKind,
    val text: String,
    /** 随手记的类型：信息 / 问题 / 灵感 / 待办。 */
    val tag: String = "",
    /** 已写入 Notion。 */
    val synced: Boolean = false,
)

@Serializable
data class EmergencyPass(val at: Long, val until: Long, val reason: String)

/** 总控失联的一段时间（无障碍服务没有在运行）。 */
@Serializable
data class OfflineGap(val from: Long, val to: Long)

/** 一天的全部记录。date 是逻辑日期（凌晨 4 点换日），格式 yyyy-MM-dd。 */
@Serializable
data class DayLog(
    val date: String,
    val submissions: List<Submission> = emptyList(),
    val reports: List<Report> = emptyList(),
    val emergencies: List<EmergencyPass> = emptyList(),
    val offline: List<OfflineGap> = emptyList(),
    /** 娱乐应用的前台秒数，按包名。 */
    val playSeconds: Map<String, Long> = emptyMap(),
    /** 被弹回总控的次数。 */
    val bounces: Int = 0,
    /** 等待写入 Notion 日志的条目。 */
    val outbox: List<LogEntry> = emptyList(),
) {
    val playMinutes: Int get() = (playSeconds.values.sum() / 60).toInt()

    /** 某道关卡通过的那份汇报。 */
    fun passed(gateId: String): Submission? = submissions.firstOrNull { it.gateId == gateId && it.verdict.pass }
}

/** 写入 Notion 总控日志的一条。 */
@Serializable
data class LogEntry(
    val at: Long,
    val title: String,
    val dept: Dept,
    val result: String,
    val text: String,
    val score: Int? = null,
    /** 还要写到哪些库："log" 总控日志、"think" 谋划库。写成功一个去掉一个。 */
    val targets: Set<String> = setOf(TARGET_LOG),
) {
    companion object {
        const val TARGET_LOG = "log"
        const val TARGET_THINK = "think"
    }
}

/**
 * 一条放宽改动：提交后 24 小时才生效，期间可以撤回。
 * 收紧的改动立即生效，不走这里。
 */
@Serializable
data class PendingChange(
    val id: String,
    val createdAt: Long,
    val effectiveAt: Long,
    val summary: String,
    val op: ChangeOp,
)

@Serializable
sealed class ChangeOp {
    @Serializable
    @SerialName("put_gate")
    data class PutGate(val gate: Gate) : ChangeOp()

    @Serializable
    @SerialName("remove_gate")
    data class RemoveGate(val gateId: String) : ChangeOp()

    @Serializable
    @SerialName("unblock")
    data class Unblock(val pkg: String) : ChangeOp()

    @Serializable
    @SerialName("rules")
    /** 只记放宽了的字段，其余为 null，生效时不碰。 */
    data class Rules(
        val silenceHours: Int? = null,
        val activeStart: Int? = null,
        val activeEnd: Int? = null,
        val dailyQuotaMin: Int? = null,
        val emergencyPerDay: Int? = null,
        val emergencyMinutes: Int? = null,
        val degradedPerDay: Int? = null,
    ) : ChangeOp()

    @Serializable
    @SerialName("pause")
    data class Pause(val until: Long) : ChangeOp()
}
