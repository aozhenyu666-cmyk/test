package cn.companion.pet.core.partner

/**
 * 主动开口的调度：什么时候说、说哪件事、没回应怎么办。纯逻辑，所有信号由调用方给。
 *
 * 用户的要求（2026-10-28）：
 * - 频率要高：正常用手机一小时 15–30 次，越重要越积极（默认 20 次/小时，可调 5–40）；
 * - 骑车、走路、刷手机、玩游戏都要能聊；骑车时是语音连续对话；
 * - 他没回应常常是在想事情，不能因此就不再开口（否则“越不说越不问”）；
 * - 讨厌泛泛而谈：每次开口都要有具体的锚点（屏幕上的一句话、画面里的东西、一件没聊完的事）。
 *
 * 不可绕过的安静规则（优先级从高到低）：
 * 1. 他明确点了“安静”→ 到期前一律不说；
 * 2. 正在通话、正在录音/语音识别、正在打字；
 * 3. 睡眠时段（用户自己设的）；
 * 4. 别的会话占着（严格回合、训练、守护拦截）——由调用方通过 [busyReason] 传入；
 * 5. 它刚说完、还在等他回应的窗口内，不抢话。
 */
enum class UsageContext {
    /** 用户显式开启的骑行/走路语音模式：只用语音，不弹需要看的东西。 */
    RIDING,
    /** 正在读长文字（阅读类应用、网页长文）。 */
    READING,
    /** 正在看视频。 */
    WATCHING_VIDEO,
    /** 正在玩游戏。 */
    GAMING,
    /** 刷信息流。 */
    BROWSING_FEED,
    /** 在桌宠自己的聊天页里。 */
    IN_CHAT,
    /** 亮屏但没在用什么（桌面、发呆）。 */
    IDLE,
    UNKNOWN,
}

enum class TopicSource {
    /** 当前屏幕上的文字（有原句可引用）。 */
    SCREEN_TEXT,
    /** 当前画面（截图经视觉模型，有具体可见元素）。 */
    SCREEN_IMAGE,
    /** 到期的话题线（没聊完的事、烦心事的进展）。 */
    THREAD_FOLLOW_UP,
    /** 长期目标的检查。 */
    GOAL_CHECK,
    /** 它自己读到的东西（R3 自己的生活）。 */
    OWN_READING,
    /** 普通闲聊。 */
    SMALL_TALK,
}

data class TopicCandidate(
    val id: String,
    val source: TopicSource,
    /** 具体锚点：原句、画面元素、话题线标题。闲聊以外不能为空。 */
    val anchor: String,
    /** 1–3，同 [PartnerThread.importance]。 */
    val importance: Int = 1,
    /** 观察到的时刻（屏幕类来源会过期）。 */
    val observedAt: Long,
    /** 调用方用 SayingLedger 判定“和最近说过的太像”。 */
    val tooSimilarToRecent: Boolean = false,
    val threadId: String = "",
)

data class GovernorSignals(
    val now: Long,
    val context: UsageContext,
    val screenOn: Boolean,
    val typing: Boolean = false,
    val capturingVoice: Boolean = false,
    val inCall: Boolean = false,
    val sleepHours: Boolean = false,
    val dismissedUntil: Long = 0,
    /** 别的会话占着通道时的原因；null 表示没有。 */
    val busyReason: String? = null,
)

sealed interface GovernorDecision {
    data class Speak(val candidate: TopicCandidate, val voiceOnly: Boolean, val why: String) : GovernorDecision
    data class Silent(val reason: String, val retryAt: Long) : GovernorDecision
}

class ProactiveGovernor(
    /** 每小时目标开口次数（用户可调，5–40）。 */
    targetPerHour: Int = 20,
) {
    var targetPerHour: Int = targetPerHour.coerceIn(5, 40)
        private set

    fun configureTarget(value: Int) { targetPerHour = value.coerceIn(5, 40) }

    private var lastShownAt: Long? = null

    /** 最近一次判定要说的需求，用于 onShown 时满足对应动机。 */
    private var lastNeed: cn.companion.pet.core.proactive.Need? = null

    /** 最近一次说的话题锚点（前 40 字），用于同话题上限。 */
    private var lastSpokenTopic: String = ""

    /**
     * 随机触发引擎：把"固定间隔"换成"真随机、非均匀、有突发"。
     *
     * 原来的实现是 baseGap × 各种倍率，完全是确定性的——20 次/小时就是死板的三分钟一次，
     * 用户能一眼看出是定时器。现在间隔由对数正态采样，形态上更像真人。
     */
    private val randomEngine = cn.companion.pet.core.proactive.RandomTriggerEngine()

    /**
     * 动机引擎：回答「它此刻想不想说」。
     *
     * 与 [randomEngine] 的分工：随机引擎管「什么时候有机会」，动机引擎管「这个机会值不值」。
     * 两者相乘，才既不像定时器、也不会无脑开口。
     */
    private val driveEngine = cn.companion.pet.core.proactive.DriveEngine()

    /** 动机低到这个程度就先缓一缓。 */
    private val lowAppetiteFloor = 0.45
    private var awaitingSince: Long? = null
    private var consecutiveIgnored: Int = 0
    private val shownTimes = ArrayDeque<Long>()

    /** 基础间隔：60 分钟 / 目标次数。20 次/小时 = 3 分钟一次。 */
    private val baseGapMillis: Long get() = 3_600_000L / targetPerHour

    fun gapMultiplier(ctx: UsageContext): Double = when (ctx) {
        UsageContext.RIDING -> 0.6        // 骑车时就是在陪他聊，节奏更密
        UsageContext.IN_CHAT -> 0.5
        UsageContext.IDLE -> 0.8
        UsageContext.READING -> 1.0
        UsageContext.BROWSING_FEED -> 1.0
        UsageContext.WATCHING_VIDEO -> 1.5 // 看视频、玩游戏也聊，但别太打断
        UsageContext.GAMING -> 1.5
        UsageContext.UNKNOWN -> 1.2
    }

    /** 等他回应的窗口：骑车时短（说话就是即时的），看屏幕时长一些。 */
    fun answerWindowMillis(ctx: UsageContext): Long = when (ctx) {
        UsageContext.RIDING -> 25_000
        UsageContext.IN_CHAT -> 120_000
        else -> 90_000
    }

    /** 没回应的退避：每多一次没回应间隔 +50%，最多 3 倍，**不会变成不说话**。 */
    private fun silencePenalty(): Double = (1.0 + 0.5 * consecutiveIgnored).coerceAtMost(3.0)

    /** 屏幕类锚点过期快：两分钟前的屏幕内容已经不是“现在在看的”。 */
    private fun fresh(c: TopicCandidate, now: Long): Boolean = when (c.source) {
        TopicSource.SCREEN_TEXT, TopicSource.SCREEN_IMAGE -> now - c.observedAt <= 120_000
        else -> true
    }

    private fun sourceWeight(src: TopicSource, ctx: UsageContext): Int = when (ctx) {
        UsageContext.RIDING -> when (src) {           // 骑车看不了屏幕：只聊事，不聊画面
            TopicSource.THREAD_FOLLOW_UP -> 5; TopicSource.GOAL_CHECK -> 4
            TopicSource.OWN_READING -> 3; TopicSource.SMALL_TALK -> 3
            TopicSource.SCREEN_TEXT, TopicSource.SCREEN_IMAGE -> 0
        }
        UsageContext.READING, UsageContext.BROWSING_FEED -> when (src) {
            TopicSource.SCREEN_TEXT -> 6; TopicSource.SCREEN_IMAGE -> 4; TopicSource.THREAD_FOLLOW_UP -> 4
            TopicSource.GOAL_CHECK -> 3; TopicSource.OWN_READING -> 2; TopicSource.SMALL_TALK -> 1
        }
        UsageContext.WATCHING_VIDEO, UsageContext.GAMING -> when (src) {
            TopicSource.SCREEN_IMAGE -> 5; TopicSource.SCREEN_TEXT -> 4; TopicSource.THREAD_FOLLOW_UP -> 3
            TopicSource.SMALL_TALK -> 3; TopicSource.OWN_READING -> 2; TopicSource.GOAL_CHECK -> 1
        }
        else -> when (src) {
            TopicSource.THREAD_FOLLOW_UP -> 5; TopicSource.GOAL_CHECK -> 4; TopicSource.OWN_READING -> 3
            TopicSource.SCREEN_TEXT -> 3; TopicSource.SCREEN_IMAGE -> 3; TopicSource.SMALL_TALK -> 2
        }
    }

    fun score(c: TopicCandidate, ctx: UsageContext): Int =
        sourceWeight(c.source, ctx) * 2 + c.importance.coerceIn(1, 3) * 2 - if (c.tooSimilarToRecent) 100 else 0

    /** 最近一小时实际说出来的次数（只算确认显示/说出的）。 */
    fun shownLastHour(now: Long): Int {
        while (shownTimes.isNotEmpty() && now - shownTimes.first() > 3_600_000L) shownTimes.removeFirst()
        return shownTimes.size
    }

    fun decide(s: GovernorSignals, candidates: List<TopicCandidate>, requested: Boolean = false): GovernorDecision {
        val now = s.now
        fun silent(r: String, retry: Long = now + 15_000) = GovernorDecision.Silent(r, retry)
        if (now < s.dismissedUntil) return silent("你让我安静一会儿", s.dismissedUntil)
        if (s.inCall) return silent("在通话")
        if (s.capturingVoice) return silent("正在听你说")
        if (s.typing) return silent("你在打字")
        if (s.sleepHours) return silent("睡眠时段", now + 30 * 60_000)
        if (s.busyReason != null) return silent("别的会话进行中：" + s.busyReason)
        if (!s.screenOn && s.context != UsageContext.RIDING) return silent("屏幕关着")

        // 刚说完还在等他接：窗口内不抢话；窗口过了还没回应，记一次“没回应”。
        awaitingSince?.let { since ->
            val window = answerWindowMillis(s.context)
            if (!requested && now - since < window) return silent("在等你接话", since + window)
            if (requested) return@let
            awaitingSince = null
            consecutiveIgnored++
        }

        val usable = candidates.filter { c ->
            fresh(c, now) && (c.source == TopicSource.SMALL_TALK || c.anchor.isNotBlank()) &&
                score(c, s.context) > 0 && sourceWeight(c.source, s.context) > 0
        }
        if (usable.isEmpty()) return silent("手里没有具体可聊的")
        val best = usable.maxWith(compareBy<TopicCandidate> { score(it, s.context) }.thenBy { it.observedAt })

        // 动机层：把「它此刻想不想说」折进候选选择。
        //
        // 关键：动机只影响**选哪个/要不要缓一缓**，不绕过任何硬规则
        // （专注禁言、免打扰、频率上限、每日上限都已经在上面判过）。
        val need = needForSource(best.source)
        lastNeed = need
        if (need != null) {
            val appetite = driveEngine.appetiteFor(need)
            // 动机过低时这次先缓一缓，但**不消耗**随机机会——想说的劲儿还在，下次再说。
            if (appetite < lowAppetiteFloor) {
                return silent("此刻不太想说（" + need.label + " " + appetite + "）", now + 2 * 60_000L)
            }
        }

        // 重要的事（3）间隔减半；没回应按退避拉长但不归零。
        lastSpokenTopic = best.anchor.take(40)
        val importanceFactor = if (best.importance >= 3) 0.5 else 1.0
        // 间隔已交给 RandomTriggerEngine（对数正态采样）。
        // 这里只把"重要的事"反映到引擎上：重要度高的临时收紧间隔。
        randomEngine.configure(
            cn.companion.pet.core.proactive.RandomTriggerConfig(
                mode = modeForTarget(),
                minGapMillis = minGapFor(importanceFactor),
            ),
        )
        // 间隔判定交给随机引擎：它自己维护"上次真的开口的时刻"，
        // 并按对数正态采样下一次，叠加突发与沉默恢复。
        // requested=true 表示用户主动要求"现在聊一句"，跳过随机等待。
        if (!requested) {
            val verdict = randomEngine.evaluate(
                nowMillis = now,
                gateAllows = true, // 闸门已在上面逐条判过
                lastSpeakAtMillis = lastShownAt ?: -1L,
            )
            if (verdict is cn.companion.pet.core.proactive.TriggerDecision.Hold) {
                return silent("随机等待中（" + verdict.reason + "）", verdict.nextAtMillis)
            }
        }
        // 每小时硬上限：目标的 1.5 倍，防止重要事件连发。
        if (shownLastHour(now) >= (targetPerHour * 3) / 2) return silent("这一小时说得够多了", now + 5 * 60_000)

        return GovernorDecision.Speak(best, voiceOnly = s.context == UsageContext.RIDING,
            why = best.source.name + "：" + best.anchor.take(40))
    }

    /** 调用方**确认真的显示了 / 念出来了**之后才调用；显示失败不调用，这句就不算说过。 */
    /** 目标次数/小时 → 对应档位。用户设置的值优先。 */
    private fun modeForTarget(): cn.companion.pet.core.proactive.ProactiveMode =
        when {
            targetPerHour >= 16 -> cn.companion.pet.core.proactive.ProactiveMode.EXPERIMENT
            targetPerHour >= 10 -> cn.companion.pet.core.proactive.ProactiveMode.HIGH
            else -> cn.companion.pet.core.proactive.ProactiveMode.DAILY
        }

    /** 重要的事项允许更密的开口（但仍有 45 秒硬下限）。 */
    private fun minGapFor(importanceFactor: Double): Long =
        (45_000L * importanceFactor.coerceIn(0.5, 1.0)).toLong()

    /** 供测试验证"重要的事硬下限更短"这条规则本身（不依赖随机结果）。 */
    internal fun minGapForForTest(importanceFactor: Double): Long = minGapFor(importanceFactor)

    /** 候选来源 → 它对应的动机需求；对不上时 null（不强行编需求）。 */
    private fun needForSource(src: TopicSource): cn.companion.pet.core.proactive.Need? = when (src) {
        TopicSource.THREAD_FOLLOW_UP -> cn.companion.pet.core.proactive.Need.CONNECT
        TopicSource.GOAL_CHECK -> cn.companion.pet.core.proactive.Need.CARE
        TopicSource.SCREEN_TEXT, TopicSource.SCREEN_IMAGE -> cn.companion.pet.core.proactive.Need.SHARE
        TopicSource.OWN_READING -> cn.companion.pet.core.proactive.Need.LEARN
        TopicSource.SMALL_TALK -> cn.companion.pet.core.proactive.Need.CONNECT
    }

    /**
     * 每秒推进一次动机（衰减 + 连接累积）。
     *
     * 放在这里而不是 decide 里：decide 可能因为闸门提前 return，
     * 而动机必须持续随时间变化。
     *
     * @param lastUserTalkAtMillis 用户最后一次说话；0 表示没有记录
     */
    /** 应用用户设置：黏度与同话题上限。 */
    fun configureDrives(clinginess: Int, sameTopicLimit: Int) {
        driveEngine.clinginess = clinginess.coerceIn(0, 100)
        driveEngine.sameTopicLimit = sameTopicLimit.coerceIn(1, 10)
    }

    /** 这个话题还能不能继续（同话题上限保护）。 */
    fun canContinueTopic(topicKey: String): Boolean = driveEngine.canContinueTopic(topicKey)

    fun tickDrives(now: Long, lastUserTalkAtMillis: Long) {
        driveEngine.tick(now, lastUserTalkAtMillis)
    }

    /** 当前动机快照（诊断页读它）。 */
    fun drives(): cn.companion.pet.core.proactive.Drives = driveEngine.current()

    fun onShown(now: Long) {
        lastShownAt = now
        // 只有真的说出去了才推进随机节奏；生成失败不消耗机会。
        randomEngine.onSpoken(now, lastShownAt ?: -1L, consecutiveIgnored)
        // 真的说出去了：满足对应动机（说完就不那么想说了）。
        lastNeed?.let { driveEngine.onSpoken(now, it) }
        // 记录这个话题说过一次（用于同话题上限）。
        driveEngine.noteTopic(lastSpokenTopic)
        awaitingSince = now
        shownTimes.addLast(now)
    }

    /** 他回应了（任何一句）：清掉等待和退避。 */
    fun onUserReplied() {
        awaitingSince = null
        consecutiveIgnored = 0
    }

    val ignoredStreak: Int get() = consecutiveIgnored

    fun snapshot(): ProactiveState = ProactiveState(shownTimes.toList(), awaitingSince, consecutiveIgnored)
    fun restore(state: ProactiveState, now: Long) {
        shownTimes.clear()
        state.shownAt.filter { it <= now && now - it <= 3_600_000L }.sorted().takeLast(60).forEach { shownTimes.addLast(it) }
        lastShownAt = shownTimes.lastOrNull()
        awaitingSince = state.awaitingSince?.takeIf { it <= now && now - it <= 3_600_000L }
        consecutiveIgnored = state.ignored.coerceIn(0, 100)
    }
}
