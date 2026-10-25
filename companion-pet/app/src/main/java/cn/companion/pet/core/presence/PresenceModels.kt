package cn.companion.pet.core.presence

/** 本机主动性的四种输出。 */
enum class PresenceKind {
    /** 本机轻量动作/表情：不调模型、不花额度、不算主动开口。 */
    MICRO_REACTION,
    /** 本机规则提示：一句基于事实的短话，走现有气泡/对话；不调模型。 */
    LOCAL_NUDGE,
    /** 值得动用现有模型流程的情境。 */
    MODEL_REASONING,
    /** 没有足够理由介入。 */
    SILENT,
}

/** 微反应：映射到已有的 Mood / 动画，不新增动画系统。 */
enum class MicroReaction(val label: String) {
    /** 从信息流回到学习/专注：点头。 */
    NOD("点头"),
    /** 连续专注很久：开心一下。 */
    CHEER("开心"),
    /** 一分钟内切了好几个 App：好奇地看一眼。 */
    LOOK("看一眼"),
    /** 在信息流里待了挺久：打个哈欠。 */
    YAWN("打哈欠"),
}

/** 为什么要动用模型。 */
enum class ReasoningReason(val prompt: String) {
    STUCK("他在学习页面停了一会儿没动，可能卡住了"),
    PATTERN("这半小时他好几次从学习切回同一个信息流 App"),
    SCHEDULED("到了平常聊一句的时候，而且有值得接的话题或场景"),
}

/**
 * 一次评估的结论。[reason] 是一行可读的短原因（进诊断统计）；[key] 用来去重，同一件事不会触发两次。
 * [intense] 为 false 时只做低强度表现（精力低）。
 */
data class PresenceDecision(
    val kind: PresenceKind,
    val reason: String,
    val micro: MicroReaction? = null,
    val nudgeText: String? = null,
    val reasoning: ReasoningReason? = null,
    val key: String? = null,
    val intense: Boolean = true,
    /** 到点的计划这次不值得开口：调用方按正常节奏顺延，而不是一分钟后再试。 */
    val consumeSchedule: Boolean = false,
) {
    companion object {
        fun silent(reason: String, consumeSchedule: Boolean = false) =
            PresenceDecision(PresenceKind.SILENT, reason, consumeSchedule = consumeSchedule)
    }
}

/** 用户对一次主动开口的反馈。明确拒绝/暂停优先级最高。 */
enum class PresenceFeedback {
    /** 回了话、点了去 AI 等。 */
    INTERACTED,
    /** 没理，走完被忽略的升级。 */
    IGNORED,
    /** 明确点 × 或点桌宠收起。 */
    DISMISSED,
    /** 用户暂停/停止桌宠。 */
    PAUSED,
}

/**
 * 每次评估的输入快照：全部来自内存里的轨迹和设置，评估本身不读写文件。
 */
data class PresenceSignals(
    val now: Long,
    val foreground: String?,
    val foregroundLabel: String,
    val foregroundSince: Long?,
    /** 最近 60 秒切到过的不同 App 数。 */
    val distinctApps60s: Int,
    /** 最近 60 秒的切换次数。 */
    val switches60s: Int,
    val inFeed: Boolean,
    val inLearning: Boolean,
    val inFocus: Boolean,
    val excluded: Boolean,
    val inLauncher: Boolean,
    /** 学习页卡壳探头这一次触发了。 */
    val stuck: Boolean,
    /** 原有开口节奏到点了。 */
    val scheduledDue: Boolean,
    val quiet: Boolean,
    val userActive: Boolean,
    val paused: Boolean,
    /** 正在深聊/想一想/记念头/技能/看一眼/模型请求中等，非空就不插嘴。 */
    val busy: String?,
    /** 有 Key 且今天还有额度。 */
    val canCallModel: Boolean,
    /** 距离上一次真实交流（用户回话或主动找它）多久；没有记录为 null。 */
    val sinceInteractionMillis: Long?,
    /** 角色表现参数（来自 PetStateStore），会改变行为阈值和动画强度。 */
    val energy: Double = 0.6,
    val interest: Double = 0.5,
)
