package cn.companion.pet.core.proactive

/**
 * 情境触发：什么情况下「值得说一句」。
 *
 * 与随机触发的分工：
 * - [RandomTriggerEngine] 决定「**什么时候**有机会说」；
 * - 这里决定「**为什么**要说」——没有理由就不该凭空开口。
 *
 * 用户明确要求：情境观察必须遵守授权，不能偷偷读私信、持续录屏或采集无关信息。
 * 所以这里的输入全部是**调用方已经合法拿到的、粗粒度的事实**，不包含任何消息正文。
 */
enum class ProactiveTrigger(val label: String) {
    /** 用户刚完成一个任务/一题。 */
    TASK_COMPLETED("刚做完一件事"),

    /** 用户很久没回应了——但也可能是在专注，所以要配合退避。 */
    LONG_SILENCE("很久没说话了"),

    /** 上一次对话有没解决的问题。 */
    UNRESOLVED_QUESTION("上次有个问题没聊完"),

    /** 之前约定的事即将到期。 */
    APPOINTMENT_DUE("约好的时间快到了"),

    /** 发现值得继续聊的旧话题。 */
    OLD_TOPIC_REVIVAL("想起一件旧事"),

    /** 用户刚退出专注状态。 */
    FOCUS_ENDED("刚结束专注"),

    /** 纯随机轻闲聊——**永远可用的兜底**，保证任何情况下都有话说。 */
    LIGHT_SMALL_TALK("随便聊两句"),
    ;
}

/**
 * 一次情境判断的输入。全部是粗粒度事实，不含正文。
 *
 * @param minutesSinceUserReply 距上次用户回话多少分钟；null 表示今天还没说过话
 * @param hasUnresolvedQuestion 是否有一句悬着的问题没被回答
 * @param minutesUntilAppointment 距最近的约定还有多少分钟；null 表示没有约定
 * @param justLeftFocus 刚退出专注
 * @param idleTopicCount 可复用的旧话题数量
 */
data class ContextSignals(
    val nowMillis: Long,
    val minutesSinceUserReply: Long? = null,
    val hasUnresolvedQuestion: Boolean = false,
    val minutesUntilAppointment: Long? = null,
    val justLeftFocus: Boolean = false,
    val justCompletedTask: Boolean = false,
    val idleTopicCount: Int = 0,
)

/** 一个候选理由 + 优先级。 */
data class ContextCandidate(
    val trigger: ProactiveTrigger,
    /** 1~3，越高越该说。 */
    val priority: Int,
    /** 具体锚点：让模型知道"为什么现在说"。可以为空（闲聊）。 */
    val anchor: String = "",
)

/**
 * 情境触发器：纯函数，把 [ContextSignals] 变成候选列表。
 *
 * 关键设计：**LIGHT_SMALL_TALK 永远在列表里**。
 * 这是对"没有待办就彻底不说话"（focusmate 的 WakePlanner 缺陷）的正面对抗——
 * 任何情况下都必须至少有一个可以说的话题，否则引擎会在无事可做时静默到像死了。
 */
object ContextTriggerEngine {

    /** 约定提前多久开始提醒。 */
    const val APPOINTMENT_LEAD_MINUTES = 10L

    /** 多久没回应算「很久」。 */
    const val LONG_SILENCE_MINUTES = 40L

    fun candidates(s: ContextSignals): List<ContextCandidate> {
        val out = mutableListOf<ContextCandidate>()

        if (s.justCompletedTask) {
            out += ContextCandidate(ProactiveTrigger.TASK_COMPLETED, priority = 3)
        }
        if (s.hasUnresolvedQuestion) {
            out += ContextCandidate(ProactiveTrigger.UNRESOLVED_QUESTION, priority = 3)
        }
        s.minutesUntilAppointment?.let { left ->
            if (left <= APPOINTMENT_LEAD_MINUTES) {
                out += ContextCandidate(
                    ProactiveTrigger.APPOINTMENT_DUE,
                    priority = 3,
                    anchor = "还有 " + left.coerceAtLeast(0) + " 分钟到约定的时间",
                )
            }
        }
        if (s.justLeftFocus) {
            out += ContextCandidate(ProactiveTrigger.FOCUS_ENDED, priority = 2)
        }
        if (s.idleTopicCount > 0) {
            out += ContextCandidate(ProactiveTrigger.OLD_TOPIC_REVIVAL, priority = 2)
        }
        s.minutesSinceUserReply?.let { idle ->
            if (idle >= LONG_SILENCE_MINUTES) {
                out += ContextCandidate(ProactiveTrigger.LONG_SILENCE, priority = 1)
            }
        }

        // 兜底：永远有得聊。
        out += ContextCandidate(ProactiveTrigger.LIGHT_SMALL_TALK, priority = 1)

        // 高优先级在前；同优先级保持插入顺序（稳定排序）。
        return out.sortedByDescending { it.priority }
    }
}
