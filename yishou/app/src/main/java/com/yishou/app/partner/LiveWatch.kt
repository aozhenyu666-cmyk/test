package com.yishou.app.partner

/**
 * Gemini 伴读的“盯场”：一手听不到 Live 在说什么，只看两件事——
 * 前台是哪个应用（无障碍只给包名），以及此刻有没有应用在用麦克风（系统只说“有”，不说是谁、录了什么）。
 *
 * Live 开着时麦克风一直开着，所以：
 * - 在 Gemini 里 → 在场；开了 Live 模式却没开麦 → 提醒点 Live（不拉）
 * - 在登记的材料应用里，且 Live 还开着（麦克风在用） → 在场（比如一边让 Live 看屏一边刷）
 * - 麦克风停了、人也不在 Gemini → Live 断了：先提醒，再把 Gemini 拉回前台
 * - Live 还开着，人却去了没登记的应用 → 只提醒，不拉
 * 输入法、通知栏、相机、拨号、一手自己这些界面不算离开，也不算回来。
 */
class LiveWatch(
    /** 这一场用 Live（语音）：要求麦克风开着 */
    val live: Boolean,
    /** 离开太久把 Gemini 拉回前台 */
    val pull: Boolean,
    strict: Boolean = false,
) {
    enum class State { ON, IDLE, OFF_TRACK, AWAY, UNKNOWN }

    sealed interface Act {
        data object None : Act
        data class Nudge(val text: String) : Act
        data object Pull : Act
    }

    /** 一个时刻看到的情况 */
    data class Seen(
        val pkg: String?,
        val inGemini: Boolean = false,
        /** 登记的材料应用 */
        val material: Boolean = false,
        /** 输入法、通知栏、相机、拨号、一手自己 */
        val neutral: Boolean = false,
        val mic: Boolean = false,
    )

    val nudgeMs = if (strict) 10_000L else 20_000L
    val pullMs = if (strict) 20_000L else 45_000L

    var state: State = State.UNKNOWN
        private set
    var pulls = 0
        private set
    var nudges = 0
        private set
    private var since = 0L
    private var nudged = false
    private var pulledThisTime = false

    /** 每次前台切换、或每秒调一次 */
    fun step(at: Long, s: Seen): Act {
        if (s.pkg == null) {
            state = State.UNKNOWN
            reset(at)
            return Act.None
        }
        // 中性界面：不改变状态，计时照走，但这时不拉（可能在打字、接电话）
        if (s.neutral) return Act.None
        val micOk = !live || s.mic
        val next = when {
            s.inGemini -> if (micOk) State.ON else State.IDLE
            s.material && micOk -> State.ON
            s.mic && live -> State.OFF_TRACK
            else -> State.AWAY
        }
        if (next != state) {
            state = next
            reset(at)
        }
        if (state == State.ON) return Act.None
        val gone = at - since
        return when (state) {
            State.IDLE -> if (!nudged && gone >= IDLE_NUDGE_MS) nudge("Gemini 开着，Live 没开：点 Live，说「${Opening.TRIGGER}」") else Act.None
            State.OFF_TRACK -> if (!nudged && gone >= nudgeMs) nudge("Live 还开着，你去了别的应用。要它看这个，就把这个应用登记成材料 App") else Act.None
            State.AWAY -> when {
                pull && !pulledThisTime && pulls < MAX_PULLS && gone >= pullMs -> {
                    pulledThisTime = true
                    pulls++
                    Act.Pull
                }
                !nudged && gone >= nudgeMs -> nudge(
                    if (live) "Live 断了。${if (pull && pulls < MAX_PULLS) "${(pullMs - gone).coerceAtLeast(0) / 1000} 秒后拉你回 Gemini" else "回 Gemini 接着聊"}"
                    else "离开伴读了，回 Gemini 吗？",
                )
                else -> Act.None
            }
            else -> Act.None
        }
    }

    /** 通知栏上那一小段 */
    fun label(at: Long): String? = when (state) {
        State.ON -> if (live) "Live 在" else null
        State.IDLE -> "Live 没开"
        State.OFF_TRACK -> "在别的应用里"
        State.AWAY -> (if (live) "Live 断了 " else "离开 ") + "${(at - since) / 1000} 秒"
        State.UNKNOWN -> null
    }?.let { if (pulls > 0) "$it · 拉回 $pulls 次" else it }

    private fun nudge(text: String): Act {
        nudged = true
        nudges++
        return Act.Nudge(text)
    }

    private fun reset(at: Long) {
        since = at
        nudged = false
        pulledThisTime = false
    }

    companion object {
        const val MAX_PULLS = 3
        const val IDLE_NUDGE_MS = 30_000L
        /** Gemini 在不同手机上可能跑在 Gemini App 里，也可能跑在 Google App 里 */
        val GEMINI = setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
    }
}
