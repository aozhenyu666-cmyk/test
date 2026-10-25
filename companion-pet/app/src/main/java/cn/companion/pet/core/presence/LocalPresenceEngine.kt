package cn.companion.pet.core.presence

import kotlin.random.Random

/** 阈值都写在这里，测试按它们核对。 */
data class PresenceThresholds(
    /** 一分钟内切到这么多不同 App：本机提示（再乘以兴趣系数）。 */
    val rapidSwitchApps: Int = 5,
    /** 同时至少切换这么多次，避免“打开一个 App 就算一次”。 */
    val rapidSwitchCount: Int = 6,
    /** 一分钟内切到这么多不同 App：只做微反应。 */
    val lookApps: Int = 4,
    /** 在信息流 App 连续停留：先打哈欠，再提示。 */
    val feedYawnMillis: Long = 10 * 60_000L,
    val feedNudgeMillis: Long = 20 * 60_000L,
    /** 学习/专注连续这么久：开心一下。 */
    val longFocusMillis: Long = 25 * 60_000L,
    /** 半小时内从学习切回同一信息流的次数达到这个数，算重复模式。 */
    val patternReturns: Int = 3,
    val patternWindowMillis: Long = 30 * 60_000L,
    /** 到点时，距离上次真实交流超过这么久也算“值得问候一句”。 */
    val checkInAfterMillis: Long = 2 * 60 * 60_000L,
    /** 微反应冷却范围（随机抖动，避免机械节奏）。 */
    val microMinMillis: Long = 30_000L,
    val microMaxMillis: Long = 90_000L,
    /** 精力低于这个值时只做低强度表现。 */
    val lowEnergy: Double = 0.33,
)

/** 脱敏统计：只有计数和原因，不含任何屏幕内容。 */
data class PresenceStats(
    val evaluations: Long = 0,
    val micro: Long = 0,
    val nudges: Long = 0,
    val modelCalls: Long = 0,
    val silentReasons: Map<String, Long> = emptyMap(),
    val lastDecision: String = "",
)

/**
 * 本机主动性协调器：纯 Kotlin、无 I/O、无网络。每几秒由 PetBrain 用内存里的信号调用一次，
 * 输出“微反应 / 本机提示 / 值得调模型 / 安静”，并附一行原因。
 *
 * 只看可核验的事实（切换次数、停留时长、卡壳探头、到点），不推断心理；证据不够就安静。
 * 克制（上限、间隔、冷却、拒绝）交给 [PresencePolicy]；这里只负责“有没有值得说的理由”。
 * 不处理 NOTE/MATERIAL 事件：那些由 AgentLoop + 事件账本负责，避免同一事件被调两次模型。
 */
class LocalPresenceEngine(
    private val policy: PresencePolicy,
    var thresholds: PresenceThresholds = PresenceThresholds(),
    private val random: Random = Random.Default,
) {
    private var lastForeground: String? = null
    private var lastWasFeed = false
    private var lastWasLearning = false
    private var lastFeedPackage: String? = null
    /** 从学习切回信息流的时间点。 */
    private val feedReturns = ArrayDeque<Long>()
    private var stintStart = 0L
    private var stintYawned = false
    private var stintNudged = false
    private var stintCheered = false
    private var nextMicroAt = 0L
    private var lastRapidNudgeAt = 0L
    private val handledKeys = LinkedHashMap<String, Long>()

    @Volatile var stats = PresenceStats()
        private set

    /** [hasTopic] 只在到点时才会被调用（可能读文件），平时不碰。 */
    fun evaluate(s: PresenceSignals, hasTopic: () -> Boolean = { false }): PresenceDecision {
        track(s)
        val decision = decide(s, hasTopic)
        record(decision)
        return decision
    }

    /** 调用方确认这次决定已执行（显示了气泡/发了请求/做了动作）。 */
    fun commit(decision: PresenceDecision, now: Long) {
        countExecuted(decision)
        when (decision.kind) {
            PresenceKind.MICRO_REACTION -> nextMicroAt = now + jitter()
            PresenceKind.LOCAL_NUDGE, PresenceKind.MODEL_REASONING -> {
                decision.key?.let { handledKeys[it] = now; trimKeys(now) }
                if (decision.key?.startsWith("rapid:") == true) lastRapidNudgeAt = now
                if (decision.key?.startsWith("feed:") == true) stintNudged = true
            }
            PresenceKind.SILENT -> Unit
        }
        if (decision.micro == MicroReaction.YAWN) stintYawned = true
        if (decision.micro == MicroReaction.CHEER) stintCheered = true
    }

    private fun decide(s: PresenceSignals, hasTopic: () -> Boolean): PresenceDecision {
        // 1) 硬性安静：暂停、熄屏/锁屏、排除 App、正在忙别的。
        if (s.paused) return PresenceDecision.silent("已暂停")
        if (!s.userActive) return PresenceDecision.silent("用户不活跃")
        if (s.excluded) return PresenceDecision.silent("排除 App")
        s.busy?.let { return PresenceDecision.silent("忙：$it") }

        val intense = s.energy >= thresholds.lowEnergy
        // 兴趣低（常被忽略）时提示门槛抬高；兴趣高时略降。范围 0.8–1.3。
        val factor = (1.3 - s.interest * 0.5).coerceIn(0.8, 1.3)
        val gate = policy.check(s.now, s.paused)

        // 2) 学习页卡壳：有额度就交给模型针对具体内容问一句，没额度用本机一句话。
        if (s.stuck && s.inLearning && !s.quiet) {
            val key = "stuck:" + s.foreground + ":" + (s.foregroundSince ?: 0)
            if (key !in handledKeys) {
                if (!gate.allowed) return PresenceDecision.silent("卡壳但" + gate.reason)
                return if (s.canCallModel) PresenceDecision(PresenceKind.MODEL_REASONING, "学习页卡壳", reasoning = ReasoningReason.STUCK, key = key, intense = intense)
                else PresenceDecision(PresenceKind.LOCAL_NUDGE, "学习页卡壳（无模型额度）", nudgeText = "这一页停了一会儿了，卡在哪儿了吗？", key = key, intense = intense)
            }
        }

        // 3) 重复模式：半小时内好几次从学习切回同一个信息流。
        val returns = feedReturns.count { it >= s.now - thresholds.patternWindowMillis }
        if (returns >= thresholds.patternReturns && !s.quiet) {
            val key = "pattern:" + lastFeedPackage + ":" + (feedReturns.firstOrNull { it >= s.now - thresholds.patternWindowMillis } ?: 0)
            if (key !in handledKeys) {
                if (gate.allowed) {
                    return if (s.canCallModel) PresenceDecision(PresenceKind.MODEL_REASONING, "半小时内第 $returns 次切回信息流", reasoning = ReasoningReason.PATTERN, key = key, intense = intense)
                    else PresenceDecision(PresenceKind.LOCAL_NUDGE, "重复切回信息流（无模型额度）",
                        nudgeText = "这半小时第 $returns 次从学习切到${s.foregroundLabel}了，要我帮你记着时间吗？", key = key, intense = intense)
                }
            }
        }

        // 4) 一分钟内切了很多 App：证据足够才问，问法只陈述事实。
        val rapidApps = Math.round(thresholds.rapidSwitchApps * factor).toInt().coerceAtLeast(thresholds.lookApps + 1)
        if (s.distinctApps60s >= rapidApps && s.switches60s >= thresholds.rapidSwitchCount && !s.quiet &&
            s.now - lastRapidNudgeAt >= thresholds.patternWindowMillis / 3
        ) {
            if (gate.allowed) {
                return PresenceDecision(PresenceKind.LOCAL_NUDGE, "一分钟切了 ${s.distinctApps60s} 个应用",
                    nudgeText = pick(listOf(
                        "你刚才一分钟内切了好几个应用，是在找东西吗？",
                        "一分钟里换了 ${s.distinctApps60s} 个应用，要找什么我可以帮你想想？",
                    )), key = "rapid:" + (s.now / 60_000), intense = intense)
            }
        }

        // 5) 信息流停留很久：先打哈欠（微反应），再一句提示（每段只提示一次）。
        val dwell = s.foregroundSince?.let { s.now - it } ?: 0L
        if (s.inFeed && dwell >= (thresholds.feedNudgeMillis * factor).toLong() && !stintNudged && !s.quiet && gate.allowed) {
            val minutes = dwell / 60_000
            return PresenceDecision(PresenceKind.LOCAL_NUDGE, "信息流停留 $minutes 分钟",
                nudgeText = pick(listOf(
                    "在${s.foregroundLabel}已经 $minutes 分钟了，要不要起来活动一下眼睛？",
                    "${s.foregroundLabel}刷了 $minutes 分钟了，歇一下再接着看？",
                )), key = "feed:" + s.foreground + ":" + stintStart, intense = intense)
        }

        // 6) 到点：只有值得说才动用模型；不值得就顺延，不再一分钟后反复试。
        if (s.scheduledDue && !s.quiet) {
            val longSilence = s.sinceInteractionMillis == null || s.sinceInteractionMillis >= thresholds.checkInAfterMillis
            val worth = s.inLearning || longSilence || hasTopic()
            if (!worth) return PresenceDecision.silent("到点但没有值得说的", consumeSchedule = true)
            if (!s.canCallModel) return PresenceDecision.silent("到点但没有模型额度", consumeSchedule = true)
            if (!gate.allowed) return PresenceDecision.silent("到点但" + gate.reason, consumeSchedule = true)
            return PresenceDecision(PresenceKind.MODEL_REASONING, "到点且有话题/场景", reasoning = ReasoningReason.SCHEDULED,
                key = "scheduled:" + s.now / 60_000, intense = intense)
        }

        // 7) 微反应：只有出现有意义的状态变化才做，且有冷却。
        micro(s, dwell, intense)?.let { return it }
        return PresenceDecision.silent("没有足够理由")
    }

    private fun micro(s: PresenceSignals, dwell: Long, intense: Boolean): PresenceDecision? {
        if (s.now < nextMicroAt) return null
        val reaction = when {
            justReturnedToFocus -> MicroReaction.NOD
            (s.inLearning || s.inFocus) && dwell >= thresholds.longFocusMillis && !stintCheered -> MicroReaction.CHEER
            s.distinctApps60s >= thresholds.lookApps -> MicroReaction.LOOK
            s.inFeed && dwell >= thresholds.feedYawnMillis && !stintYawned -> MicroReaction.YAWN
            else -> null
        } ?: return null
        // 精力低时把“看一眼/开心”这种高强度动作换成打哈欠一类的低强度表现。
        val shown = if (!intense && (reaction == MicroReaction.LOOK || reaction == MicroReaction.CHEER)) MicroReaction.YAWN else reaction
        return PresenceDecision(PresenceKind.MICRO_REACTION, reaction.label, micro = shown, intense = intense)
    }

    private var justReturnedToFocus = false

    /** 记录前台变化：段落起止、从信息流回到学习、从学习切回信息流。 */
    private fun track(s: PresenceSignals) {
        justReturnedToFocus = false
        if (s.foreground != lastForeground) {
            if (lastWasFeed && (s.inLearning || s.inFocus)) justReturnedToFocus = true
            if (lastWasLearning && s.inFeed) {
                if (lastFeedPackage != null && lastFeedPackage != s.foreground) feedReturns.clear()
                feedReturns.addLast(s.now)
                lastFeedPackage = s.foreground
            }
            lastForeground = s.foreground
            lastWasFeed = s.inFeed
            lastWasLearning = s.inLearning || s.inFocus
            stintStart = s.foregroundSince ?: s.now
            stintYawned = false
            stintNudged = false
            stintCheered = false
        }
        while (feedReturns.isNotEmpty() && feedReturns.first() < s.now - thresholds.patternWindowMillis) feedReturns.removeFirst()
    }

    /** 评估次数和静默原因在评估时记；微反应/提示/模型只在 [commit]（真的执行了）时记。 */
    private fun record(d: PresenceDecision) {
        val st = stats
        stats = if (d.kind != PresenceKind.SILENT) st.copy(evaluations = st.evaluations + 1) else {
            val reasons = st.silentReasons.toMutableMap()
            val key = d.reason.substringBefore("：").take(24)
            reasons[key] = (reasons[key] ?: 0) + 1
            st.copy(evaluations = st.evaluations + 1,
                silentReasons = reasons.entries.sortedByDescending { it.value }.take(8).associate { it.key to it.value })
        }
    }

    private fun countExecuted(d: PresenceDecision) {
        val st = stats
        stats = when (d.kind) {
            PresenceKind.MICRO_REACTION -> st.copy(micro = st.micro + 1, lastDecision = "微反应：" + d.reason)
            PresenceKind.LOCAL_NUDGE -> st.copy(nudges = st.nudges + 1, lastDecision = "本机提示：" + d.reason)
            PresenceKind.MODEL_REASONING -> st.copy(modelCalls = st.modelCalls + 1, lastDecision = "模型：" + d.reason)
            PresenceKind.SILENT -> st
        }
    }

    private fun jitter(): Long = if (thresholds.microMaxMillis <= thresholds.microMinMillis) thresholds.microMinMillis
    else random.nextLong(thresholds.microMinMillis, thresholds.microMaxMillis + 1)

    private fun pick(options: List<String>): String = options[random.nextInt(options.size)]

    private fun trimKeys(now: Long) {
        val it = handledKeys.entries.iterator()
        while (it.hasNext()) { if (now - it.next().value > 6 * 60 * 60_000L) it.remove() }
        while (handledKeys.size > 200) handledKeys.remove(handledKeys.keys.first())
    }
}
