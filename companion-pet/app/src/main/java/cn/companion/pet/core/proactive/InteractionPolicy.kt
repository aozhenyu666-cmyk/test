package cn.companion.pet.core.proactive

/**
 * 互动频率仲裁：根据用户回应情况动态调整机会。
 *
 * 用户要求："当用户频繁回应时，可以增加互动机会；连续不回应时逐渐减少提醒，避免无意义轰炸。"
 *
 * 但要小心一个反模式：**退避不能退成"再也不说"**。
 * focusmate 的缺陷之一就是越不说越不问，最后用户觉得它死了。
 * 所以这里退避有**下限**，最多把期望频率压到基础值的 [MIN_FACTOR]。
 *
 * 另一条硬规则：**随机主动交谈与用户预约提醒分别管理**，
 * 随机消息不许挤掉重要提醒。所以 [avenue] 把两类分开算。
 */
enum class TalkAvenue {
    /** 随机/自主闲聊。 */
    RANDOM,

    /** 用户约定的提醒——优先级更高，不受闲聊退避影响。 */
    APPOINTMENT,
}

/**
 * 互动信号。全部是计数与时长，不含任何消息内容。
 *
 * @param consecutiveIgnored 连续被忽略的次数（用户没回应）
 * @param repliesLastHour 最近一小时用户回应的次数
 * @param dismissedRecently 用户刚点过"安静/别说了"
 */
data class InteractionSignals(
    val consecutiveIgnored: Int = 0,
    val repliesLastHour: Int = 0,
    val dismissedRecently: Boolean = false,
)

/**
 * 仲裁结果：给随机引擎用的频率乘数与是否允许。
 *
 * @param rateFactor 乘到基础频率上；1.0 = 不变，>1 更活跃，<1 更安静
 * @param allowed false 时这一轮随机闲聊直接不许
 */
data class InteractionVerdict(
    val rateFactor: Double,
    val allowed: Boolean,
    val reason: String,
)

object InteractionPolicy {

    /** 退避的极限：最多压到基础值的 1/4，绝不归零。 */
    const val MIN_FACTOR = 0.25

    /** 频繁回应时的加成上限。 */
    const val MAX_FACTOR = 1.6

    /**
     * 计算本次仲裁。
     *
     * 规则从高到低（顺序不可调换）：
     * 1. 用户刚要求安静 → 直接不许（这条最高，任何加成都不许覆盖）
     * 2. 预约提醒 → **永远允许**，不参与闲聊退避（防止随机消息挤掉重要提醒）
     * 3. 连续被忽略 → 逐级退避，但有地板 [MIN_FACTOR]
     * 4. 用户频繁回应 → 加成，有天花板 [MAX_FACTOR]
     */
    fun arbitrate(signals: InteractionSignals, avenue: TalkAvenue): InteractionVerdict {
        if (signals.dismissedRecently) {
            return InteractionVerdict(0.0, allowed = false, reason = "用户要求安静")
        }
        if (avenue == TalkAvenue.APPOINTMENT) {
            // 重要提醒不因闲聊退避而被压掉；但连续忽略很多次时稍微降低密度即可。
            val soft = (1.0 - signals.consecutiveIgnored * 0.1).coerceIn(0.6, 1.0)
            return InteractionVerdict(soft, allowed = true, reason = "约定提醒")
        }

        // 连续忽略到一定程度就完全停一轮（但只停这一轮，不是永久）。
        if (signals.consecutiveIgnored >= HARD_STOP_IGNORED) {
            return InteractionVerdict(0.0, allowed = false, reason = "连续 " + signals.consecutiveIgnored + " 次没回应")
        }

        var factor = 1.0
        // 退避：每忽略一次乘 0.8，最低压到 MIN_FACTOR。
        if (signals.consecutiveIgnored > 0) {
            factor *= 0.8.pow(signals.consecutiveIgnored).coerceAtLeast(MIN_FACTOR)
        }
        // 加成：一小时回应 3 次以上开始变活跃。
        if (signals.repliesLastHour >= 3) {
            factor *= (1.0 + (signals.repliesLastHour - 2) * 0.15).coerceAtMost(MAX_FACTOR)
        }
        return InteractionVerdict(
            rateFactor = factor.coerceIn(MIN_FACTOR, MAX_FACTOR),
            allowed = true,
            reason = when {
                signals.consecutiveIgnored > 0 -> "退避中（忽略 " + signals.consecutiveIgnored + " 次）"
                signals.repliesLastHour >= 3 -> "你最近回应积极"
                else -> "正常"
            },
        )
    }

    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())

    /** 连续忽略到这个次数，本轮随机闲聊直接放弃。 */
    const val HARD_STOP_IGNORED = 6
}
