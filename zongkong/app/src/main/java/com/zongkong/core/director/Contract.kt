package com.zongkong.core.director

import kotlinx.serialization.Serializable

/*
 * 墨灵 · 两队之间的约定（接口契约）。
 *
 * 施工队 A（小窗、接力、感知、保活，Android 侧）和施工队 B（导演、记忆，纯逻辑）只通过这个文件里的类型对接：
 *   A 把手机上发生的事包装成 [Sense] 交给 [Director]；
 *   B 决定此刻该说什么，返回 [Card]；A 用小窗 / 通知 / 朗读把它呈现出来（[WindowPort]）；
 *   用户点按钮或说一句话，A 包装成 [Reply] 交回 B；
 *   去 Gemini / ChatGPT 聊之前，A 向 B 要一份 [Opening]（开场白）；聊完把 [SessionLog] 交回 B。
 *
 * 改这个文件要在下面的变更记录里写一行，并通知另一队。
 *
 * 变更记录：
 *   v1 2026-10-19 初版。
 */
const val CONTRACT_VERSION = 1

/** 会话 / 交互的模式。不同模式的成功标准不同，见蓝图 README 第 7 节。 */
@Serializable
enum class Mode(val label: String) {
    WORK("工作"),
    TRAIN("训练"),
    START("启动"),
    REST("休息"),
    COMPANY("陪伴"),
    DEGRADED("降级"),
}

/** 演员：用户在哪个官方 App 里和谁说话。 */
@Serializable
enum class Actor(val label: String, val pkg: String) {
    GEMINI_LIVE("Gemini Live", "com.google.android.apps.bard"),
    GEMINI_TEXT("Gemini", "com.google.android.apps.bard"),
    CHATGPT("ChatGPT", "com.openai.chatgpt"),
    NONE("墨灵", ""),
}

/**
 * 接力棒：一个没想完的念头。一手的“断点”升级成全系统共用的东西。
 * 手机换电脑、白天换晚上、GPT 换 Gemini，都从这里接上。
 */
@Serializable
data class Baton(
    val id: String,
    /** 正在回答的问题 / 正在推进的事。 */
    val question: String,
    /** 已经知道 / 已经做到的。 */
    val known: String = "",
    /** 卡在哪。 */
    val stuck: String = "",
    /** 下一问（一句话就能接着想）。 */
    val nextAsk: String = "",
    /** 挂着的总控行动 / 事项 key（可空）。 */
    val workKey: String = "",
    val updatedAt: Long = 0,
    /** 每次更新 +1，用来识别过期的副本。 */
    val version: Int = 1,
    /** 谁最后更新的：用户 / 导演 / gemini / chatgpt / 一手。 */
    val source: String = "导演",
    val closed: Boolean = false,
)

/** 小窗上的一个按钮选项（点选题用）。 */
@Serializable
data class Choice(val key: String, val label: String)

/**
 * 小窗里的一张卡：此刻要说的一句话。
 * 一次只突出一个 [ask]；[sayLines] 是可以直接念给演员的话；[choices] 不为空时是点选题。
 */
@Serializable
data class Card(
    val id: String,
    val mode: Mode,
    /** 一个短标题，例如“拆事 · 10 分钟”。 */
    val title: String,
    /** 现在这一问。 */
    val ask: String,
    /** 可以直接念给 Gemini / GPT 的 0–2 句话。 */
    val sayLines: List<String> = emptyList(),
    /** 点选题的选项；为空时用默认四个按钮（继续 / 卡住了 / 有结论 / 休息）。 */
    val choices: List<Choice> = emptyList(),
    /** 建议时长（分钟），0 = 不计时。 */
    val minutes: Int = 0,
    /** 建议去哪个演员那里聊；NONE = 就在小窗 / 通知里回答。 */
    val actor: Actor = Actor.NONE,
    val batonId: String = "",
    /** 议程里还排着几件事（小窗显示“还有 N 个”）。 */
    val queued: Int = 0,
    /** 导演的把握：高 / 中 / 低（猜测类的卡才填）。 */
    val confidence: String = "",
    /** 朗读用的一句话；空 = 不朗读。 */
    val speak: String = "",
    val createdAt: Long = 0,
)

/** 开场白：交给演员的那段话，加上这一场准备好的几张卡。 */
@Serializable
data class Opening(
    val sessionId: String,
    val actor: Actor,
    /** 100–300 字，念出来或粘贴进去都行。 */
    val text: String,
    val cards: List<Card>,
    val minutes: Int,
    val mode: Mode,
)

/** 用户对小窗的回应。A 负责采集（按钮、语音听写、点选），B 负责理解。 */
@Serializable
sealed interface Reply {
    val cardId: String
    val at: Long

    /** 继续这一问。 */
    @Serializable data class Continue(override val cardId: String, override val at: Long) : Reply

    /** 卡住了。 */
    @Serializable data class Stuck(override val cardId: String, override val at: Long) : Reply

    /** 有结论：用户说的一句话（听写原文），可能含下一步和时间。 */
    @Serializable data class Conclusion(override val cardId: String, override val at: Long, val text: String) : Reply

    /** 要休息，[minutes] 为 0 时由导演决定。 */
    @Serializable data class Rest(override val cardId: String, override val at: Long, val minutes: Int = 0) : Reply

    /** 点了点选题的某个选项。 */
    @Serializable data class Pick(override val cardId: String, override val at: Long, val key: String) : Reply

    /** 用户说“你猜错了”，可附一句。 */
    @Serializable data class Wrong(override val cardId: String, override val at: Long, val note: String = "") : Reply

    /** 卡片被划掉 / 小窗被关掉 / 超时没回应。 */
    @Serializable data class Dismissed(override val cardId: String, override val at: Long, val timeout: Boolean = false) : Reply
}

/** 感知事件的种类。A 负责产生，B 负责解释。 */
@Serializable
enum class SenseKind {
    UNLOCK,
    SCREEN_OFF,
    /** 前台应用切换，payload: pkg、label、category（ENTERTAINMENT/SHOPPING/WORK/ACTOR/OTHER）。 */
    FOREGROUND,
    /** 同一个娱乐应用连续使用达到阈值，payload: pkg、minutes。 */
    LONG_ENTERTAINMENT,
    /** 计划的行动到点 / 过点没开始，payload: key、title、lateMin。 */
    PLAN_DUE,
    /** 行动开始 / 做完 / 卡住，payload: key、title、state。 */
    PLAN_PROGRESS,
    /** 没有进行中、也没有 2 小时内排着的行动。 */
    IDLE_START,
    /** 付款通知，payload: cents、merchant。 */
    PAYMENT,
    /** 打开了演员（Gemini / ChatGPT），payload: actor。 */
    ACTOR_OPEN,
    /** 健康状态变化，payload 见 [Health]。 */
    HEALTH,
    /** 收件箱里来了外部内容（Notion / Tasks / 剪贴板），payload: source、text。 */
    INBOX,
    /** 定时兜底（每 30 分钟）。 */
    TICK,
    /** 夜间整理时间到。 */
    NIGHT,
}

/** 一条感知事件。quality：看得清 / 推测 / 看不见（例如无障碍掉线期间）。 */
@Serializable
data class Sense(
    val kind: SenseKind,
    val at: Long,
    val source: String,
    val payload: Map<String, String> = emptyMap(),
    val quality: String = "看得清",
)

/** 无障碍健康四层（只看开关是开着，不能说明它在工作）。 */
@Serializable
data class Health(
    val permission: Boolean,
    val connected: Boolean,
    /** 最近一次收到事件的时间。 */
    val lastEventAt: Long,
    /** 目标页面读得到（例如小窗 C 档要读 Gemini 字幕），未知为 null。 */
    val readable: Boolean? = null,
    /** 是用户自己关掉的（要尊重），还是意外掉线（要修）。 */
    val userDisabled: Boolean = false,
)

/** 一次去演员那里聊的记录。 */
@Serializable
data class SessionLog(
    val sessionId: String,
    val actor: Actor,
    val startedAt: Long,
    val endedAt: Long,
    /** 在演员 App 前台的总时长。 */
    val presentMs: Long,
    /** 离开超过 2 分钟的次数。 */
    val leaves: Int,
    /** 开场白交接是否成功（复制 / 分享 / 念），以及用的哪种方式。 */
    val handoff: String = "",
    val handoffOk: Boolean = true,
    /** 收线原文（如果有）。 */
    val conclusion: String = "",
)

/**
 * 导演（施工队 B 实现）。A 只调用这几个方法。
 * 带 suspend 的可能会调用文字模型，A 要在协程里调，并能接受它返回本地兜底结果。
 */
interface Director {
    /** 喂一条感知事件。返回需要立刻呈现的卡（null = 不打扰）。 */
    suspend fun onSense(sense: Sense): Card?

    /** 此刻议程最上面的卡（小窗展开时显示），不调用模型。 */
    fun currentCard(now: Long): Card?

    /** 用户回应了一张卡，返回下一张（null = 收起）。 */
    suspend fun onReply(reply: Reply): Card?

    /** 去演员那里聊之前要开场白。[cardId] 是触发这次会话的卡，可空。 */
    suspend fun opening(actor: Actor, cardId: String?, now: Long): Opening

    /** 会话结束（收线或离开）。 */
    suspend fun onSession(log: SessionLog): Card?

    /** “此刻”的纯文本（给 Notion 页面、Google 文档、调试页用）。 */
    fun nowText(now: Long): String
}

/**
 * 此刻的事实快照（施工队 A 从总控的 Store 里取）。导演只读它，不碰 Android。
 * 拿不到的字段留默认值，并在 [unknown] 里写上，导演会知道自己“看不见”这一块。
 */
@Serializable
data class Facts(
    val now: Long,
    /** 北京时间一天中的分钟（0..1439）。 */
    val minute: Int,
    /** 睡觉 / 静默时段。 */
    val quiet: Boolean = false,
    /** 专注锁或行动计时中。 */
    val inFocus: Boolean = false,
    /** 总控时间进行中。 */
    val hqActive: Boolean = false,
    /** 今天的主线（没定为空）。 */
    val mainTitle: String = "",
    val mainKey: String = "",
    /** 主线计划开始时间（毫秒，0 = 没排时间）。 */
    val mainPlannedAt: Long = 0,
    val mainStarted: Boolean = false,
    val mainDone: Boolean = false,
    /** 主线最近一次有进展（开始 / 记断点 / 做完）的时间。 */
    val lastProgressAt: Long = 0,
    val foregroundPkg: String = "",
    val foregroundLabel: String = "",
    /** ENTERTAINMENT / SHOPPING / WORK / ACTOR / OTHER。 */
    val foregroundCategory: String = "OTHER",
    /** 当前前台应用连续用了多少分钟。 */
    val foregroundMinutes: Int = 0,
    val playMinutesToday: Int = 0,
    val workMinutesToday: Int = 0,
    /** 今天还能玩多少分钟（挣来的娱乐）；-1 = 未知。 */
    val playLeftMin: Int = -1,
    val spendTodayCents: Long = 0,
    /** 信用 0–100；-1 = 未知。 */
    val credit: Int = -1,
    /** 态度：信任 / 正常 / 审慎 / 严格。 */
    val stance: String = "",
    /** 最近一次和用户交流（任何回应）的时间。 */
    val lastContactAt: Long = 0,
    val health: Health? = null,
    /** 长期目标（最多 4 个）。 */
    val aims: List<String> = emptyList(),
    /** 本周要交出的东西。 */
    val weekDeliverables: List<String> = emptyList(),
    /** 接下来 3 小时内排着的行动标题。 */
    val upcoming: List<String> = emptyList(),
    val inboxCount: Int = 0,
    /** 这次拿不到的信息，例如“前台应用（无障碍掉线）”。 */
    val unknown: List<String> = emptyList(),
)

/** 施工队 A 实现：从总控 Store 生成 [Facts]。 */
fun interface FactsSource {
    fun facts(now: Long): Facts
}

/** 施工队 A 实现：调用总控已配置的文字模型（转接 `LlmApi`）。失败、超时、超预算都返回 null，导演会用本地兜底。 */
fun interface Ask {
    suspend fun ask(system: String, user: String): String?
}

/** 施工队 A 实现：把导演的记忆（一段 JSON）存在手机本地，原子写入。 */
interface MindStore {
    fun load(): String?
    fun save(json: String)
}

/** 小窗（施工队 A 实现）。B 不直接调用 Android，只通过它说话。 */
interface WindowPort {
    fun show(card: Card)
    fun hide()
    fun speak(text: String)
}
