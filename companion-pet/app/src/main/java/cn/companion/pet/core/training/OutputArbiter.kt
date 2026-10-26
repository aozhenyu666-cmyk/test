package cn.companion.pet.core.training

/**
 * 说话/显示的优先级。数字越小越优先，高优先级可以打断低优先级。
 *
 * 顺序对应 R2.1 §5.3：紧急与结束 > 故障 > 当前题目 > 保护提示 > 温和搭话。
 */
enum class OutputPriority {
    /** 紧急退出、会话结束：必须立刻显示。 */
    URGENT,
    /** 能力故障、技术降级说明。 */
    FAILURE,
    /** 当前训练题目/核验结果：同一时间只能有一个题目气泡。 */
    TRAINING,
    /** 专注拦截、提醒等保护性提示。 */
    PROTECTIVE,
    /** 温和主动搭话、闲聊。 */
    AMBIENT,
}

/**
 * 统一的输出仲裁：气泡与语音只允许一个当前所有者。
 *
 * 为什么需要它：系统里有多条路径都会调 `overlay.showBubble` 和 `speech.say`
 * （训练、严格回合、Agent 主动话题、技能进度、专注拦截…）。
 * 没有仲裁时会出现：训练题目被一句闲聊盖掉、语音念到一半被另一段顶掉、
 * 或者两个气泡在同一位置重叠。
 *
 * 规则：
 * 1. 同优先级不可抢占（后来的被忽略，返回 false）。
 * 2. 更高优先级可以打断，被抢占者收到 onPreempted 通知（用于保存断点）。
 * 3. 释放后其他人才能继续。
 */
class OutputArbiter {

    /** 被抢占时的回调：调用方应保存自己的进度。 */
    var onPreempted: ((String) -> Unit)? = null

    @Volatile
    private var holder: OutputPriority? = null

    @Volatile
    private var holderId: String = ""

    val current: OutputPriority? get() = holder
    val currentId: String get() = holderId

    /**
     * 申请输出权。成功返回 true；被同/更高优先级占用返回 false。
     */
    @Synchronized
    fun acquire(priority: OutputPriority, ownerId: String): Boolean {
        val h = holder
        if (h == null) {
            holder = priority
            holderId = ownerId
            return true
        }
        // 同一所有者重复申请：幂等成功。
        if (holderId == ownerId) return true
        // 只有更高优先级能抢占。
        if (priority.ordinal < h.ordinal) {
            val preempted = holderId
            holder = priority
            holderId = ownerId
            runCatching { onPreempted?.invoke(preempted) }
            return true
        }
        return false
    }

    /** 释放。只有持有者能释放，避免误放。 */
    @Synchronized
    fun release(ownerId: String, force: Boolean = false): Boolean {
        if (holder == null) return false
        if (!force && holderId != ownerId) return false
        holder = null
        holderId = ""
        return true
    }

    fun heldBy(ownerId: String): Boolean = holderId == ownerId

    fun isIdle(): Boolean = holder == null

    /** 诊断：当前所有者可读描述。 */
    fun describe(): String {
        val h = holder ?: return "没有输出所有者"
        return h.name + "（" + holderId.take(12) + "）"
    }
}
