package huilu.core

/** 怎样判断"现实里真的在做这件事"。 */
enum class EnvMode(val label: String) {
    IN_APPS("在指定 App 里"),
    OFF_PHONE("离开手机"),
    ANY("不限（只防娱乐）"),
}

data class Expectation(val mode: EnvMode, val targetApps: Set<String> = emptySet()) {
    /** 指定了 App 却一个都没选，等同于不限。 */
    val effectiveMode: EnvMode get() = if (mode == EnvMode.IN_APPS && targetApps.isEmpty()) EnvMode.ANY else mode
}

/** 现在准备采取的那个动作：足够具体，几分钟后可以被现实验证。 */
data class Action(
    val id: String,
    val text: String,
    val why: String,
    val plannedMin: Int,
    val startedAt: Long,
    val endAt: Long,
    val expect: Expectation,
    /** 本轮是否允许强干预（送回桌面、屏蔽娱乐 App）。由用户在开始时决定。 */
    val strict: Boolean,
)

enum class Mode { IDLE, ACTING, RESTING }

/** 干预强度，从弱到强。 */
enum class Level(val rank: Int, val label: String) {
    NOTIFY(1, "通知"),
    INTERRUPT(2, "弹出检查页"),
    HOME(3, "送回桌面"),
    BLOCK(4, "本轮屏蔽娱乐 App"),
    /** 通过 Shizuku 以 shell 身份暂停（pm suspend）娱乐 App，直到本轮结束。 */
    DEVICE(5, "暂停娱乐 App"),
}

enum class SensorState { OK, NO_PERMISSION, UNAVAILABLE }

/** 此刻的前台。pkg 为 null 表示屏幕关闭或锁屏。since 是这个前台连续持续的起点。 */
data class Foreground(val pkg: String?, val since: Long, val state: SensorState = SensorState.OK)

/** 一段时间窗口里的客观使用情况。 */
data class Observation(
    val from: Long,
    val to: Long,
    /** 每个 App 在窗口内处于前台的毫秒数。 */
    val apps: Map<String, Long>,
    /** 每个 App 在窗口内第一次进入前台的时间。 */
    val firstSeen: Map<String, Long> = emptyMap(),
    val state: SensorState = SensorState.OK,
) {
    val windowMs: Long get() = (to - from).coerceAtLeast(0)
    /** 没有任何 App 在前台的时间，近似为息屏 / 没在用手机。 */
    val idleMs: Long get() = (windowMs - apps.values.sum()).coerceAtLeast(0)

    companion object {
        fun unknown(from: Long, to: Long, state: SensorState) = Observation(from, to, emptyMap(), emptyMap(), state)
    }
}

enum class DevKind(val label: String) {
    ON_TRACK("符合计划"),
    DRIFT("偏到娱乐"),
    OFF_TARGET("在做别的"),
    AWAY("没在用手机"),
    UNKNOWN("无法观察"),
}

/** 预期与现实之间的差异：一等数据。 */
data class Deviation(
    val kind: DevKind,
    val targetMs: Long = 0,
    val distractorMs: Long = 0,
    val otherMs: Long = 0,
    val idleMs: Long = 0,
    val topDistractor: String? = null,
    val topOther: String? = null,
    /** 偏离开始于行动的第几分钟。 */
    val onsetMin: Int? = null,
    /** 给人看的一句话："计划：… | 实际：…"。 */
    val reality: String = "",
)

enum class Trigger(val label: String) {
    SCHEDULED("定时检查"),
    DRIFT("偏离触发"),
    END("时间到"),
    REST_OVER("休息结束"),
    USER("主动检查"),
}

enum class AnswerKind(val label: String) {
    ON_TRACK("在做"),
    RECOMMIT("偏了，现在回去"),
    DONE("完成了"),
    EXTEND("还没完，再 5 分钟"),
    SHRINK("太难，改小一点"),
    REST("休息 5 分钟"),
    SWITCH("改做别的"),
    ABANDON("没做成，先停下"),
    MUTE("这次别管我"),
}

enum class Via { NOTIFICATION, SCREEN, LLM, APP }

enum class CloseReason { ANSWERED, UNANSWERED, SUPERSEDED }

/** 一次独立的小型交互：当时的局面 → 问题 → 回答 → 判断 → 后续动作。 */
data class CheckIn(
    val id: String,
    val actionId: String,
    val actionText: String,
    val trigger: Trigger,
    val openedAt: Long,
    val minuteOfAction: Int,
    val observation: Observation,
    val deviation: Deviation,
    val question: String,
    val level: Level,
    val choices: List<AnswerKind>,
    // 下面的字段由后续事件填充
    val prompts: Int = 0,
    val lastPromptAt: Long = openedAt,
    val answeredAt: Long? = null,
    val answer: AnswerKind? = null,
    val answerText: String? = null,
    val via: Via? = null,
    val notes: List<String> = emptyList(),
    val closedAt: Long? = null,
    val closeReason: CloseReason? = null,
    val decision: String? = null,
)

enum class Outcome(val label: String) {
    DONE("完成"),
    ABANDONED("放弃"),
    REPLACED("换成别的"),
    UNCONFIRMED("未确认"),
}

enum class ActResult(val label: String) {
    VERIFIED("已确认生效"),
    NO_EFFECT("执行了但没生效"),
    FAILED("执行失败"),
    UNAVAILABLE("能力不可用"),
}

enum class Aftermath(val label: String) {
    RETURNED("回到了目标"),
    STAYED("还在原处"),
    SHIFTED("转移到别的娱乐"),
    LEFT_PHONE("离开了手机"),
    OTHER("去做了别的"),
}

data class Intervention(
    val id: String,
    val at: Long,
    val level: Level,
    val checkInId: String?,
    /** 被干预的娱乐 App；null 表示不针对具体 App（例如定时检查的通知）。 */
    val target: String?,
    val reason: String,
    /** 何时评估这次干预的行为效果；null 表示不评估。 */
    val probeAt: Long?,
)

data class Settings(
    val distractors: Set<String> = DEFAULT_DISTRACTORS,
    /** 娱乐 App 连续在前台多久算偏离。 */
    val driftThresholdSec: Int = 120,
    /** 允许的最高干预强度；非严格轮次最高只到 INTERRUPT。 */
    val maxLevel: Level = Level.DEVICE,
    /** 第一次检查在计划时长的多少比例处。 */
    val firstCheckRatio: Double = 0.5,
    val renotifyMin: Int = 3,
    val giveUpMin: Int = 10,
    val effectProbeSec: Int = 120,
    val recheckAfterDriftMin: Int = 3,
    val restMin: Int = 5,
    val extendMin: Int = 5,
) {
    companion object {
        val DEFAULT_DISTRACTORS = setOf(
            "tv.danmaku.bili", "com.bilibili.app.in", "tv.danmaku.bilibilihd",
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite",
            "com.smile.gifmaker", "com.kuaishou.nebula",
            "com.xingin.xhs", "com.sina.weibo", "com.zhihu.android",
            "com.ss.android.article.news", "com.ss.android.article.lite",
            "com.qiyi.video", "com.youku.phone", "com.tencent.qqlive",
            "com.dragon.read", "com.tencent.tmgp.sgame",
            "com.google.android.youtube", "com.zhiliaoapp.musically",
            "com.instagram.android", "com.twitter.android", "com.reddit.frontpage",
        )
    }
}
