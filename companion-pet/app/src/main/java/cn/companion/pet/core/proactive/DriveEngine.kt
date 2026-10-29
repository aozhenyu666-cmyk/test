package cn.companion.pet.core.proactive

/**
 * 动机引擎：把 [Drives] 接到主动决策上。
 *
 * 职责边界（刻意做小）：
 * - [Drives] 只管"数值怎么变"（纯数据）；
 * - 这里管"什么时候变、变成什么、以及**它怎么影响这次要不要说**"。
 *
 * 关键设计：动机不是"开关"，是**乘数**。
 * 动机高只是让"更可能说"，不会绕过专注禁言、免打扰、频率上限——
 * 那些是硬规则，动机永远不能让它们失效。
 */
class DriveEngine(
    initial: Drives = Drives(),
    /** 人格偏置；从人格卡推导，null 表示不做偏置。 */
    private val personaBias: PersonaBias? = null,
) {
    private var drives: Drives = initial
    /** 上次更新动机的时刻（用于衰减）。 */
    private var updatedAtMillis: Long = 0L
    /** 上次真正开口的时刻，用于连接需求累积。 */
    private var lastSpokenAtMillis: Long = 0L

    /** 黏度（0..100）。由设置注入，默认中性。 */
    var clinginess: Int = 50

    /** 同一话题连续说了几次（达到上限就强制换话题）。 */
    private var sameTopicStreak: Int = 0
    private var lastTopicKey: String = ""

    /** 同一话题最多连续几次。 */
    var sameTopicLimit: Int = 3

    /** 这个话题还能不能继续说。false = 必须换话题。 */
    fun canContinueTopic(topicKey: String): Boolean =
        topicKey.isBlank() || topicKey != lastTopicKey || sameTopicStreak < sameTopicLimit

    /** 记录说了一次某话题。 */
    fun noteTopic(topicKey: String) {
        if (topicKey.isBlank()) return
        if (topicKey == lastTopicKey) sameTopicStreak++ else { lastTopicKey = topicKey; sameTopicStreak = 1 }
    }

    fun current(): Drives = drives

    fun snapshot(): State = State(drives, updatedAtMillis, lastSpokenAtMillis)

    data class State(
        val drives: Drives = Drives(),
        val updatedAtMillis: Long = 0L,
        val lastSpokenAtMillis: Long = 0L,
        val lastTopicKey: String = "",
        val sameTopicStreak: Int = 0,
    )

    fun restore(state: State) {
        drives = state.drives
        updatedAtMillis = state.updatedAtMillis
        lastSpokenAtMillis = state.lastSpokenAtMillis
    }

    /**
     * 每次评估前推进一次动机。幂等：同一毫秒调用多次结果相同。
     *
     * @param nowMillis 当前时刻
     * @param lastUserTalkAtMillis 用户最后一次说话；0/-1 表示没有记录
     */
    fun tick(nowMillis: Long, lastUserTalkAtMillis: Long) {
        if (updatedAtMillis == 0L) { updatedAtMillis = nowMillis; return }
        val hours = (nowMillis - updatedAtMillis).coerceAtLeast(0L) / 3_600_000.0
        if (hours <= 0.0) return
        drives = drives.decay(hours)
        // 连接需求按"距上次和用户说话多久"累积（不是按上次衰减多久）。
        if (lastUserTalkAtMillis > 0L) {
            val hoursSinceUser = (nowMillis - lastUserTalkAtMillis).coerceAtLeast(0L) / 3_600_000.0
            drives = drives.withConnectionUrgency(hoursSinceUser)
        }
        personaBias?.let { drives = drives.biasedBy(it) }
        drives = drives.withClinginess(clinginess)
        updatedAtMillis = nowMillis
    }

    /**
     * 这个候选值不值得说——把动机折成一个乘数。
     *
     * @param need 这个候选所属的需求
     * @return 0.5~1.6 的乘数：动机低也不会到 0（否则永远不说），动机高有上限
     */
    fun appetiteFor(need: Need): Double {
        val strength = drives.of(need)
        // 基线附近 → 1.0；需求高涨 → 最多 1.6；需求被满足过 → 最低 0.5
        return (0.5 + strength).coerceIn(0.5, 1.6)
    }

    /** 真的说出去了：对应需求被满足。 */
    fun onSpoken(nowMillis: Long, need: Need) {
        drives = drives.satisfy(need)
        lastSpokenAtMillis = nowMillis
        updatedAtMillis = nowMillis
    }

    /** 说了但没送到（通知失败/被取消）：只轻微回落。 */
    fun onDeliveryFailed(need: Need) {
        drives = drives.satisfySoftly(need)
    }

    /**
     * 触发理由 → 需求。让"为什么现在说"和"它想什么"对上。
     *
     * 对应不上时返回 null——**不强行编一个需求**，避免动机层变成噪音。
     */
    companion object {
        fun needFor(trigger: ProactiveTrigger): Need? = when (trigger) {
            ProactiveTrigger.APPOINTMENT_DUE -> Need.CARE
            ProactiveTrigger.UNRESOLVED_QUESTION -> Need.CONNECT
            ProactiveTrigger.LONG_SILENCE -> Need.CONNECT
            ProactiveTrigger.TASK_COMPLETED -> Need.SHARE
            ProactiveTrigger.FOCUS_ENDED -> Need.CARE
            ProactiveTrigger.OLD_TOPIC_REVIVAL -> Need.SHARE
            ProactiveTrigger.LIGHT_SMALL_TALK -> Need.CONNECT
        }
    }
}
