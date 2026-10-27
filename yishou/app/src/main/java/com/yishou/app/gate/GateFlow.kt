package com.yishou.app.gate

import com.yishou.app.judge.*

/** 要点与选项分开推进，时长只决定交互，不授予放行。 */
class GateFlow(val question: LockedQ, private val clock: () -> Long) {
    val recorder = ProbeRecorder(clock)
    var answer = ""; private set
    var verdict: Verdict? = null; private set
    var trace: ProbeTrace? = null; private set
    var attempts = 0; private set
    var busy = false; private set
    var freezeUntil = 0L; private set
    var fragment = false; private set
    var optionsVisible = false; private set
    val exhausted get() = attempts >= 3 && !optionsVisible
    val remainingFreeze get() = (freezeUntil - clock()).coerceAtLeast(0)
    private var shown = false

    fun shown() { if (!shown) { recorder.shown(); shown = true } }
    fun edit(text: String, voice: Boolean = false) {
        if (!shown || busy || exhausted || optionsVisible || remainingFreeze > 0) return
        if (voice) recorder.voiceEdit(answer, text) else recorder.edit(answer, text)
        answer = text
    }
    fun voiceStart() { if (shown && !busy && remainingFreeze == 0L) recorder.voiceStart() }
    suspend fun submit(judge: Judge) {
        if (!shown || busy || exhausted || optionsVisible || remainingFreeze > 0 || answer.isBlank()) return
        busy = true
        try {
            val submitted = recorder.submit()
            val v = judge.judge(question, answer, submitted)
            attempts++
            verdict = v
            trace = submitted
            val action = probeAction(submitted, answer, v.passed)
            freezeUntil = if (action == ProbeAction.FREEZE_15S) clock() + 15_000L else 0
            fragment = action == ProbeAction.ASK_FRAGMENT
            optionsVisible = v.passed && !fragment
        } finally { busy = false }
    }
    fun canSelect() = optionsVisible && verdict?.passed == true && !busy
}
