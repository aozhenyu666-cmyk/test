package cn.companion.pet.core.session

/**
 * AI 会话：用户在和 Gemini / ChatGPT / 千问 聊的时候，桌宠帮他“守住”这段对话。
 *
 * - 文字会话：切到别的 App 后先倒计时，到点把 Gemini 拉回前台。
 * - Live 会话：Live 共享屏幕时本来就要切去看别的东西，所以只记录、不拉回。
 *
 * 切出去时的调整：
 * - 系统界面、输入法、桌宠自己的窗口不算离开（由感知层过滤）。
 * - “允许的 App”（例如笔记、词典、要复制资料的阅读器）宽限期更长。
 * - 用户可以“给我 5 分钟”暂停；暂停结束还没回来，再开始倒计时。
 * - 短时间内拉回太多次，说明他真有别的事：停止拉回，改成问要不要结束。
 * - 熄屏太久、离开太久，会话自动结束。
 *
 * 纯逻辑，所有时间由调用方传入；调用方每秒调一次 [update]。
 */
enum class SessionMode { TEXT, LIVE }

data class SessionConfig(
    val graceMillis: Long = 10_000L,
    val allowedGraceMillis: Long = 90_000L,
    val pauseMillis: Long = 5 * 60_000L,
    val pullWindowMillis: Long = 3 * 60_000L,
    val maxPullsInWindow: Int = 3,
    /** 问“要不要结束”之后多久没回应就自动结束。 */
    val askTimeoutMillis: Long = 2 * 60_000L,
    /** 连续离开 Gemini 这么久，会话自动结束。 */
    val awayEndMillis: Long = 30 * 60_000L,
    /** 熄屏这么久，会话自动结束。 */
    val screenOffEndMillis: Long = 15 * 60_000L,
)

data class SessionSummary(
    val mode: SessionMode,
    val startedAt: Long,
    val endedAt: Long,
    val leaves: Int,
    val pullBacks: Int,
    val awayMillis: Long,
    val reason: EndReason,
) {
    val durationMillis: Long get() = endedAt - startedAt
}

enum class EndReason(val label: String) { USER("自己结束"), AWAY_TOO_LONG("离开太久"), SCREEN_OFF("熄屏太久"), NO_ANSWER("没回应") }

sealed interface SessionAction {
    /** 什么都不用做。 */
    data object None : SessionAction

    /** 刚回到 Gemini：收起提示。 */
    data object Back : SessionAction

    /** 离开中，还剩几秒拉回。[allowed] 表示当前在“允许的 App”里。 */
    data class Countdown(val secondsLeft: Int, val allowed: Boolean) : SessionAction

    /** 现在把 Gemini 拉回前台。 */
    data object PullBack : SessionAction

    /** 拉回太多次：问用户要不要结束会话。 */
    data object AskEnd : SessionAction

    /** 会话结束。 */
    data class Ended(val summary: SessionSummary) : SessionAction
}

class ChatSession(
    val mode: SessionMode,
    val startedAt: Long,
    /** 算作“在这个 AI App 里”的包名（含托管它界面的 App）。 */
    private val assistantPackages: Set<String>,
    var allowedPackages: Set<String> = emptySet(),
    var config: SessionConfig = SessionConfig(),
) {
    enum class State { IN_GEMINI, AWAY, PAUSED, ASKING, ENDED }

    var state = State.IN_GEMINI
        private set
    var leaves = 0
        private set
    var pullBacks = 0
        private set
    private var awayTotal = 0L
    private var awaySince = 0L
    private var deadline = 0L
    private var pausedUntil = 0L
    private var askedAt = 0L
    private var screenOffSince: Long? = null
    private val pullTimes = ArrayDeque<Long>()

    val isPaused: Boolean get() = state == State.PAUSED

    fun isAssistant(pkg: String?) = pkg != null && pkg in assistantPackages

    fun update(now: Long, foreground: String?, screenOn: Boolean): SessionAction {
        if (state == State.ENDED) return SessionAction.None
        if (!screenOn) {
            val since = screenOffSince ?: now.also { screenOffSince = it }
            return if (now - since >= config.screenOffEndMillis) end(now, EndReason.SCREEN_OFF) else SessionAction.None
        }
        val wasOff = screenOffSince != null
        screenOffSince = null
        if (foreground == null) return SessionAction.None

        if (isAssistant(foreground)) {
            if (state == State.IN_GEMINI) return SessionAction.None
            awayTotal += now - awaySince
            state = State.IN_GEMINI
            return SessionAction.Back
        }

        val allowed = foreground in allowedPackages
        if (state == State.IN_GEMINI) {
            leaves++
            awaySince = now
            state = State.AWAY
            // 刚亮屏时给的宽限和允许 App 一样长：解锁后先看一眼通知很正常
            deadline = now + if (allowed || wasOff) config.allowedGraceMillis else config.graceMillis
            if (mode == SessionMode.LIVE) return SessionAction.None
            return countdown(now, allowed)
        }

        if (now - awaySince >= config.awayEndMillis) return end(now, EndReason.AWAY_TOO_LONG)
        if (mode == SessionMode.LIVE) return SessionAction.None

        return when (state) {
            State.PAUSED -> {
                if (now < pausedUntil) return SessionAction.None
                state = State.AWAY
                deadline = now + config.graceMillis
                countdown(now, allowed)
            }
            State.ASKING -> if (now - askedAt >= config.askTimeoutMillis) end(now, EndReason.NO_ANSWER) else SessionAction.None
            State.AWAY -> {
                // 从允许的 App 换到了别的 App：宽限缩回普通长度
                if (!allowed && deadline > now + config.graceMillis) deadline = now + config.graceMillis
                if (now < deadline) return countdown(now, allowed)
                while (pullTimes.isNotEmpty() && now - pullTimes.first() > config.pullWindowMillis) pullTimes.removeFirst()
                if (pullTimes.size >= config.maxPullsInWindow) {
                    state = State.ASKING
                    askedAt = now
                    return SessionAction.AskEnd
                }
                pullTimes.addLast(now)
                pullBacks++
                // 拉回失败（例如系统没让启动）时，过一个宽限期再试
                deadline = now + config.graceMillis
                SessionAction.PullBack
            }
            else -> SessionAction.None
        }
    }

    /** “给我 5 分钟”。 */
    fun pause(now: Long) {
        if (state == State.ENDED) return
        state = State.PAUSED
        pausedUntil = now + config.pauseMillis
    }

    /** 用户点了“现在回去”：直接拉回（不计入“拉回太多次”）。 */
    fun returnNow(): SessionAction = if (state == State.ENDED) SessionAction.None else SessionAction.PullBack

    /** 问要不要结束时，用户选“继续”：清掉拉回记录，重新给一个宽限期。 */
    fun keepGoing(now: Long) {
        if (state == State.ENDED) return
        pullTimes.clear()
        state = State.AWAY
        deadline = now + config.graceMillis
    }

    fun end(now: Long, reason: EndReason = EndReason.USER): SessionAction.Ended {
        if (state == State.AWAY || state == State.PAUSED || state == State.ASKING) awayTotal += now - awaySince
        state = State.ENDED
        return SessionAction.Ended(SessionSummary(mode, startedAt, now, leaves, pullBacks, awayTotal, reason))
    }

    private fun countdown(now: Long, allowed: Boolean) =
        SessionAction.Countdown((((deadline - now) + 999) / 1000).toInt().coerceAtLeast(0), allowed)
}
