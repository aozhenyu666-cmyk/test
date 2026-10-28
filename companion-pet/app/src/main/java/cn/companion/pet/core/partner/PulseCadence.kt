package cn.companion.pet.core.partner

/**
 * 高频陪伴（用户 2026-10-29 要求：“一分钟一次”）的唯一节奏判定。纯逻辑。
 *
 * 和 [ProactiveGovernor] 的区别：Governor 适合“挑时机、宁缺毋滥”；用户明确说那样会让它几乎不开口。
 * 这里改成**到点就说**，只保留真正不能说话的硬条件：
 * 打字、通话、正在录音、点了“安静”、睡眠时段、别的会话（训练题/严格回合/守护拦截）正在显示。
 *
 * 他不回应不等于不想聊（他说过“我在想的时候不回，你就不问，会恶性循环”），所以：
 * - 前 [patience] 次没回应照常每 [intervalMillis] 说一次；
 * - 之后每多 [patience] 次没回应间隔 +1 倍，最多 [maxStretch] 倍，**不会停**；
 * - 他回任何一句，立刻恢复原节奏。
 *
 * 只有界面确认显示（或语音确认播出）后才调用 [onShown]；没显示出来的这一次不算说过，下次 tick 立即重试，
 * 但重试之间至少隔 [retryMillis]，避免显示失败时每秒狂试。
 */
data class PulseSignals(
    val now: Long,
    val screenOn: Boolean,
    val typing: Boolean = false,
    val capturingVoice: Boolean = false,
    val inCall: Boolean = false,
    val sleepHours: Boolean = false,
    val quietUntil: Long = 0,
    val paused: Boolean = false,
    /** 别的会话正在占用气泡时的原因（训练题、严格回合、守护拦截、正在生成回复）。 */
    val busyReason: String? = null,
)

sealed interface PulseDecision {
    data object Speak : PulseDecision
    data class Wait(val reason: String, val nextAt: Long) : PulseDecision
}

class PulseCadence(
    intervalMillis: Long = 60_000,
    private val patience: Int = 5,
    private val maxStretch: Int = 3,
    private val retryMillis: Long = 15_000,
) {
    var intervalMillis: Long = intervalMillis.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        private set

    private var lastShownAt: Long? = null
    private var lastAttemptAt: Long? = null
    var unanswered: Int = 0
        private set
    var lastReason: String = "还没开始"
        private set

    fun configure(seconds: Int) { intervalMillis = (seconds * 1000L).coerceIn(MIN_INTERVAL, MAX_INTERVAL) }

    /** 当前实际间隔：没回应太多次才拉长，封顶 [maxStretch] 倍。 */
    fun currentInterval(): Long {
        val stretch = (1 + unanswered / patience).coerceAtMost(maxStretch)
        return intervalMillis * stretch
    }

    fun nextAt(): Long = (lastShownAt ?: Long.MIN_VALUE / 2) + currentInterval()

    fun decide(s: PulseSignals): PulseDecision {
        val now = s.now
        fun wait(reason: String, at: Long = now + 5_000): PulseDecision { lastReason = reason; return PulseDecision.Wait(reason, at) }
        if (s.paused) return wait("你暂停了陪伴")
        if (now < s.quietUntil) return wait("你让我安静到 " + clock(s.quietUntil), s.quietUntil)
        if (s.inCall) return wait("在通话")
        if (s.capturingVoice) return wait("正在听你说")
        if (s.typing) return wait("你在打字")
        if (s.sleepHours) return wait("睡眠时段", now + 5 * 60_000)
        if (!s.screenOn) return wait("屏幕关着")
        s.busyReason?.let { return wait("别的事情占着气泡：" + it) }
        lastShownAt?.let { last ->
            val due = last + currentInterval()
            if (now < due) return wait("下一句在 " + ((due - now) / 1000) + " 秒后", due)
        }
        lastAttemptAt?.let { if (now - it < retryMillis) return wait("上一次没显示出来，稍后重试", it + retryMillis) }
        lastAttemptAt = now
        lastReason = "该说了"
        return PulseDecision.Speak
    }

    /** 界面确认显示 / 语音确认播出之后调用。 */
    fun onShown(now: Long) {
        lastShownAt?.let { if (lastWasUnanswered) unanswered++ }
        lastShownAt = now
        lastAttemptAt = null
        lastWasUnanswered = true
        lastReason = "已开口"
    }

    /** 这一次没显示出来（悬浮窗被拒、生成失败且没有兜底）。 */
    fun onFailed(reason: String) { lastReason = "没能开口：" + reason }

    /** 他说了任何一句：恢复原节奏。 */
    fun onUserReplied() {
        unanswered = 0
        lastWasUnanswered = false
    }

    private var lastWasUnanswered = false

    private fun clock(at: Long): String =
        java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString()

    companion object {
        const val MIN_INTERVAL = 30_000L
        const val MAX_INTERVAL = 600_000L
    }
}

/**
 * 模型用不了（没 Key、没额度、超时、回包不合规）时的本机句子。
 * 只用真实存在的锚点（屏幕原句、他说过的话、话题线标题、目标）；没有锚点才用闲聊句。
 * 最近用过的模板不重复。
 */
data class PulseContext(
    val screenQuote: String = "",
    val lastUserSaid: String = "",
    val threadTitle: String = "",
    val goal: String = "",
    val appLabel: String = "",
)

object PulseLines {
    private val SCREEN = listOf(
        "「%s」——这句你认同吗？",
        "刚看到「%s」，换成你会怎么说？",
        "「%s」这句，你觉得哪里站不住？",
        "「%s」——你看到这句时想到了什么？",
    )
    private val USER = listOf(
        "你刚才说「%s」，我想多问一句：为什么会这么想？",
        "接着你那句「%s」——后来呢？",
        "「%s」，这件事现在最让你卡住的是哪一步？",
    )
    private val THREAD = listOf(
        "上次说到「%s」，后来怎么样了？",
        "「%s」那件事，今天有新进展吗？",
    )
    private val GOAL = listOf(
        "你在推进「%s」，现在做到哪一步了？",
        "「%s」——今天离它近了一点吗？",
    )
    private val APP = listOf(
        "在%s里看到什么有意思的了？",
        "刷%s刷到现在，最记得住的是哪一条？",
    )
    private val SMALL = listOf(
        "现在脑子里在转什么？随便说一句也行。",
        "今天到现在，哪件事最让你在意？",
        "如果现在只能做一件事，你会做什么？",
        "刚才那几分钟，你在想什么？",
        "今天有没有一件小事让你觉得还不错？",
        "现在是在放空，还是在躲一件事？",
    )

    fun compose(c: PulseContext, recent: Set<String>, seed: Int): String {
        val pools = buildList {
            if (c.lastUserSaid.isNotBlank()) add(USER to c.lastUserSaid.take(30))
            if (c.screenQuote.isNotBlank()) add(SCREEN to c.screenQuote.take(40))
            if (c.threadTitle.isNotBlank()) add(THREAD to c.threadTitle.take(30))
            if (c.goal.isNotBlank()) add(GOAL to c.goal.take(30))
            if (c.appLabel.isNotBlank()) add(APP to c.appLabel.take(12))
        }
        val candidates = pools.flatMap { (templates, arg) -> templates.map { it.format(arg) } } + SMALL
        val fresh = candidates.filter { it !in recent }.ifEmpty { candidates }
        // 有锚点的优先：先在前半（锚点句）里轮换，锚点句都用过才轮到闲聊。
        val anchored = fresh.filter { it !in SMALL }
        val pool = anchored.ifEmpty { fresh }
        return pool[Math.floorMod(seed, pool.size)]
    }
}
