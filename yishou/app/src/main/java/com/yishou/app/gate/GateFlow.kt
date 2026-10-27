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
    /** 已经给用户看过的提示：从这里抄的话不算命中 */
    var hints: List<String> = emptyList(); private set
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
            val v = judge.judge(question, answer, submitted, hints)
            verdict = v
            trace = submitted
            // 判卷不在：这次不算尝试，也不放水（界面给等待兜底）
            if (v.unavailable) return
            attempts++
            val action = probeAction(submitted, answer, v.passed)
            freezeUntil = if (action == ProbeAction.FREEZE_15S) clock() + 15_000L else 0
            // 探针只决定怎么问，不决定过不过：过了就亮选项
            fragment = action == ProbeAction.ASK_FRAGMENT && !v.passed
            optionsVisible = v.passed
            if (!v.passed) hints = (hints + missedHints()).distinct()
        } finally { busy = false }
    }
    /** 没命中的要点的提示（不是判据原文） */
    fun missedHints(): List<String> {
        val v = verdict ?: return emptyList()
        return question.points.filter { v.hits[it.id] == null }.map { it.hint }.filter { it.isNotBlank() }.distinct()
    }
    val unavailable get() = verdict?.unavailable == true
    fun canSelect() = optionsVisible && verdict?.passed == true && !busy
}
