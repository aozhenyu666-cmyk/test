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

        // 重要的事（3）间隔减半；没回应按退避拉长但不归零。
        val importanceFactor = if (best.importance >= 3) 0.5 else 1.0
        val gap = (baseGapMillis * gapMultiplier(s.context) * silencePenalty() * importanceFactor).toLong()
        lastShownAt?.let { last -> if (!requested && now - last < gap) return silent("离上次开口不到 " + gap / 1000 + " 秒", last + gap) }
        // 每小时硬上限：目标的 1.5 倍，防止重要事件连发。
        if (shownLastHour(now) >= (targetPerHour * 3) / 2) return silent("这一小时说得够多了", now + 5 * 60_000)

        return GovernorDecision.Speak(best, voiceOnly = s.context == UsageContext.RIDING,
            why = best.source.name + "：" + best.anchor.take(40))
    }

    /** 调用方**确认真的显示了 / 念出来了**之后才调用；显示失败不调用，这句就不算说过。 */
    fun onShown(now: Long) {
        lastShownAt = now
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
