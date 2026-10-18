package com.zongkong.core

import com.zongkong.core.initiative.InitiativeConfig
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
    /** 看事项里的真实记录：排了行动、关联了材料、形成了判断、完成了行动。 */
    EVIDENCE("看事项证据", 1),
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
    /** 看事项证据时看哪一种（Focus.Evidence 的名字）。 */
    val evidence: String = "",
    val evidenceMin: Int = 1,
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
    /** 看图用的模型（可空：主模型能看图就不用填）。 */
    val visionModel: String = "",
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
    /** 双向同步的三个库：事项、行动、记录。 */
    val threadsDb: String = "",
    val actionsDb: String = "",
    val notesDb: String = "",
    /** 手机 ↔ 电脑传话的“总控·设备”库。 */
    val devicesDb: String = "",
    /** 测试时换成本地模拟服务器；平时留空用官方地址。 */
    val baseUrl: String = "",
) {
    val workReady: Boolean get() = token.isNotBlank() && threadsDb.isNotBlank() && actionsDb.isNotBlank() && notesDb.isNotBlank()

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
    /** 开始一步行动时默认开专注锁（分钟；0 = 不开）。没写结束时间的行动用这个时长。 */
    val focusMinutes: Int = 45,
    /** 首页的快捷按钮（参谋可以帮你加）。 */
    val shortcuts: List<Shortcut> = emptyList(),
    /** 总控管一手：落后于计划、专注锁时收紧一手的放行。 */
    val yishouInfluence: Boolean = true,
    /** 收紧时一手每次最多放行几分钟。 */
    val yishouPassCap: Int = 5,
    /** 总控时间、震动、失联阶梯、参谋主动找你。 */
    val hq: com.zongkong.core.hq.HqConfig = com.zongkong.core.hq.HqConfig(),
    /** 总控者：截图抽查、每天抽查、连续娱乐、模型锁、桌面宠物、主动程度。 */
    val boss: com.zongkong.core.boss.BossConfig = com.zongkong.core.boss.BossConfig(),
    /** 语音：说话（TTS）和听写（STT）的接口；不填就用手机自带的。 */
    val voice: VoiceConfig = VoiceConfig(),
    /** 对话：每天最多新开几段（0 = 不限）。 */
    val chat: ChatConfig = ChatConfig(),
    /** 低屏模式：闲鱼、Twitter 这类不全封，改成限时 + 冷却 + 做题。 */
    val watch: WatchConfig = WatchConfig(),
    /** 外接的谋划单（Gemini / GPT / Notion 交进来的），总控只负责监督落实。 */
    val directives: List<com.zongkong.core.plan.Directive> = emptyList(),
    /** 主动性：什么时候该主动来找你（信息收集部的周期拉取、退避、每日上限）。 */
    val initiative: InitiativeConfig = InitiativeConfig(),
    /** 无回应后的强督导：首次使用在界面中明确开启。 */
    val takeover: com.zongkong.core.control.TakeoverConfig = com.zongkong.core.control.TakeoverConfig(),
)

/** 对话的设置。 */
@Serializable
data class ChatConfig(
    /** 每天最多新开几段对话；0 = 不限。默认 1：逼着旧对话压缩成摘要带走，不再无限堆。 */
    val threadsPerDay: Int = 1,
)

/** 低屏模式：不能全封（太狠）也不能放开（浪费时间）的应用。 */
@Serializable
data class WatchConfig(
    val enabled: Boolean = false,
    val rules: List<WatchRule> = emptyList(),
)

/** 一条守门规则：连续用 [maxMinutes] 分钟 → 弹回做题页 → 冷却 [cooldownSeconds] 秒 → 才放行下一段。 */
@Serializable
data class WatchRule(
    val pkg: String,
    val label: String = "",
    val maxMinutes: Int = 2,
    val cooldownSeconds: Int = 10,
)

/**
 * 语音接口（OpenAI 兼容：/audio/speech、/audio/transcriptions）。
 * 地址、密钥不填：说话用手机自带的语音，听写用手机自带的语音输入。
 */
@Serializable
data class VoiceConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "alloy",
    val sttModel: String = "whisper-1",
    /** 主动找你时开口说（睡觉时段不说）。 */
    val speakProactive: Boolean = true,
    /** 对讲里的回复念出来。 */
    val speakReplies: Boolean = true,
    /** 它问完以后自动听你回答（只在对讲页里）。 */
    val listenAfterAsk: Boolean = true,
) {
    val apiReady: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}

/**
 * 首页的一个快捷按钮。
 * kind = ask：点了把 payload 发给参谋；kind = open：打开 payload 指的页面。
 */
@Serializable
data class Shortcut(
    val id: String,
    val label: String,
    val kind: String,
    val payload: String,
    val dept: Dept? = null,
) {
    companion object {
        const val ASK = "ask"
        const val OPEN = "open"
    }
}

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
    @SerialName("takeover")
    data class Takeover(val config: com.zongkong.core.control.TakeoverConfig) : ChangeOp()

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

    @Serializable
    @SerialName("yishou")
    data class Yishou(val on: Boolean, val cap: Int) : ChangeOp()

    @Serializable
    @SerialName("hq")
    data class Hq(val hq: com.zongkong.core.hq.HqConfig) : ChangeOp()

    @Serializable
    @SerialName("boss")
    data class Boss(val boss: com.zongkong.core.boss.BossConfig) : ChangeOp()

    @Serializable
    @SerialName("ai")
    data class Ai(val ai: AiConfig) : ChangeOp()
}
