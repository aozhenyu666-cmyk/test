package cn.companion.pet.core.proactive

/**
 * 自主话题的**类型**。目的：不要每次都是同一句"在干嘛"。
 *
 * 用户原话："允许创建新话题，不能反复只读取第一条记忆""记录最近已经说过的话题，降低机械重复"。
 * 所以这里做两件事：
 * 1. 在多种话题形式之间轮换（下面这些枚举）；
 * 2. 记录最近用过的形式与锚点，主动回避。
 */
enum class UtteranceShape(val label: String, val hint: String) {
    /** 简短问候。 */
    GREETING("打招呼", "一句话，轻松，不要客套"),
    /** 延续旧话题。 */
    CONTINUE_TOPIC("接着聊", "提一句之前没聊完的事，说清是哪件"),
    /** 主动追问。 */
    FOLLOW_UP_QUESTION("问一句", "问一个具体、能直接回答的问题"),
    /** 分享一个想法。 */
    SHARE_THOUGHT("说个想法", "讲一个自己的看法，不要总结对方"),
    /** 提醒此前的约定。 */
    REMIND_APPOINTMENT("提个约定", "提到之前约好的事，说清时间或内容"),
    /** 就当前情境发起讨论。 */
    DISCUSS_CONTEXT("聊眼前的", "围绕他正在做的事说一句"),
    ;
}

/**
 * 话题选择：类型轮换 + 锚点去重。
 *
 * 纯逻辑、可注入随机源。不使用内存里的"第一条记忆"——调用方必须把**候选清单**传进来，
 * 没有候选时退回 [UtteranceShape.GREETING]/[UtteranceShape.SHARE_THOUGHT]（通用问候），
 * 保证"没有任何历史记忆时也能产生合适的通用闲聊"。
 */
class AutonomousConversationEngine(
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
    /** 最近多少个已用过的锚点算"重复"。 */
    private val recentWindow: Int = 12,
) {
    private val recentShapes = ArrayDeque<UtteranceShape>()
    private val recentAnchors = ArrayDeque<String>()

    data class Selection(
        val shape: UtteranceShape,
        val anchor: String,
        /** 给提示词用的具体指令。 */
        val instruction: String,
    )

    /**
     * 选一个本次要用的形式与锚点。
     *
     * @param trigger 触发理由（来自 [ContextTriggerEngine]）
     * @param anchors 可用的具体锚点（旧话题标题、约定内容、屏幕引用等）；可为空
     */
    fun select(trigger: ProactiveTrigger, anchors: List<String>): Selection {
        val shape = pickShape(trigger)
        val usable = anchors.filter { it.isNotBlank() && it !in recentAnchors }
        val anchor = if (usable.isEmpty()) "" else usable[random.nextInt(usable.size)]
        if (anchor.isNotEmpty()) {
            recentAnchors.addLast(anchor)
            while (recentAnchors.size > recentWindow) recentAnchors.removeFirst()
        }
        recentShapes.addLast(shape)
        while (recentShapes.size > recentWindow) recentShapes.removeFirst()
        return Selection(shape, anchor, buildInstruction(shape, anchor, trigger))
    }

    fun snapshot(): State = State(recentShapes.toList(), recentAnchors.toList())

    data class State(
        val shapes: List<UtteranceShape> = emptyList(),
        val anchors: List<String> = emptyList(),
    )

    fun restore(state: State) {
        recentShapes.clear()
        state.shapes.takeLast(recentWindow).forEach { recentShapes.addLast(it) }
        recentAnchors.clear()
        state.anchors.takeLast(recentWindow).forEach { recentAnchors.addLast(it) }
    }

    /**
     * 形式选择：先按触发理由给一个优先集合，再排除最近两次用过的，最后随机挑一个。
     *
     * 为什么"排除最近两次"而不是"只按顺序轮"：机械轮换会形成可预测的周期，
     * 用户能感觉出规律；随机 + 排除最近用过，才像真人。
     */
    private fun pickShape(trigger: ProactiveTrigger): UtteranceShape {
        val preferred = when (trigger) {
            ProactiveTrigger.TASK_COMPLETED -> listOf(UtteranceShape.GREETING, UtteranceShape.FOLLOW_UP_QUESTION, UtteranceShape.SHARE_THOUGHT)
            ProactiveTrigger.LONG_SILENCE -> listOf(UtteranceShape.GREETING, UtteranceShape.SHARE_THOUGHT, UtteranceShape.CONTINUE_TOPIC)
            ProactiveTrigger.UNRESOLVED_QUESTION -> listOf(UtteranceShape.CONTINUE_TOPIC, UtteranceShape.FOLLOW_UP_QUESTION)
            ProactiveTrigger.APPOINTMENT_DUE -> listOf(UtteranceShape.REMIND_APPOINTMENT)
            ProactiveTrigger.OLD_TOPIC_REVIVAL -> listOf(UtteranceShape.CONTINUE_TOPIC, UtteranceShape.SHARE_THOUGHT)
            ProactiveTrigger.FOCUS_ENDED -> listOf(UtteranceShape.GREETING, UtteranceShape.DISCUSS_CONTEXT, UtteranceShape.SHARE_THOUGHT)
            ProactiveTrigger.LIGHT_SMALL_TALK -> UtteranceShape.entries.toList()
        }
        val lastTwo = recentShapes.takeLast(2).toSet()
        val filtered = preferred.filter { it !in lastTwo }
        val pool = filtered.ifEmpty { preferred }
        return pool[random.nextInt(pool.size)]
    }

    private fun buildInstruction(shape: UtteranceShape, anchor: String, trigger: ProactiveTrigger): String = buildString {
        append("本次开口形式：").append(shape.label).append("。")
        append(shape.hint).append("。")
        if (anchor.isNotBlank()) append("具体围绕：").append(anchor).append("。")
        if (trigger == ProactiveTrigger.LIGHT_SMALL_TALK && anchor.isBlank()) {
            append("没有任何素材，就说一句关于你自己的、具体的、能接话的话，不要问「在干嘛」。")
        }
        append("不要重复最近说过的话。")
    }
}
