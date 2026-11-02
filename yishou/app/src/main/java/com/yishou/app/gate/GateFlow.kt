package com.yishou.app.gate

import com.yishou.app.judge.*

/**
 * 要点与选项分开推进，时长只决定交互，不授予放行。
 *
 * 两次没中就不再纠缠，直接亮选项并给出精简逻辑：
 * 原来的三次上限加“判卷不在就锁死”会把一次入口变成一道坎，越卡越不想用。
 * 但也没到“说一次就给选项”——那样等于没训练。两次正好够一次纠正。
 */
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
    /** 没中两次，这一题已经把标准逻辑给过了 */
    var revealed = false; private set
    /** 这一题最多让人试几次，过了就亮选项 */
    var maxAttempts = 2

    /** 机会用完了（不管选项有没有亮出来） */
    val exhausted get() = attempts >= maxAttempts
    val remainingFreeze get() = (freezeUntil - clock()).coerceAtLeast(0)
    private var shown = false

    fun shown() { if (!shown) { recorder.shown(); shown = true } }
    fun edit(text: String, voice: Boolean = false) {
        if (!shown || busy || optionsVisible || remainingFreeze > 0) return
        if (voice) recorder.voiceEdit(answer, text) else recorder.edit(answer, text)
        answer = text
    }
    /** 槽里打的字（程序拼的整句，不算粘贴） */
    fun slotEdit(text: String) {
        if (!shown || busy || optionsVisible || remainingFreeze > 0) return
        recorder.slotEdit(answer, text)
        answer = text
    }
    fun voiceStart() { if (shown && !busy && remainingFreeze == 0L) recorder.voiceStart() }
    suspend fun submit(judge: Judge) {
        if (!shown || busy || optionsVisible || remainingFreeze > 0 || answer.isBlank()) return
        busy = true
        try {
            val submitted = recorder.submit()
            val v = judge.judge(question, answer, submitted, hints)
            verdict = v
            trace = submitted
            // 判卷不在：这次不算尝试，也不放水
            if (v.unavailable) return
            val action = probeAction(submitted, answer, v.passed)
            freezeUntil = if (action == ProbeAction.FREEZE_15S) clock() + 15_000L else 0
            // 本机直接挡下的（空话、套话、照抄、粘贴）不算一次机会：
            // 不然随手打两次“因为一手”就能把选项和标准要点换出来，再点一下拿走放行
            if (v.block != null) return
            attempts++
            // 探针只决定怎么问，不决定过不过
            fragment = action == ProbeAction.ASK_FRAGMENT && !v.passed
            if (v.passed) {
                optionsVisible = true
            } else {
                hints = (hints + missedHints()).distinct()
                // 试满了就不再拦：亮选项、给标准逻辑，别让人卡在这里
                if (attempts >= maxAttempts) {
                    optionsVisible = true
                    revealed = true
                }
            }
        } finally { busy = false }
    }
    /** 没命中的要点的提示（不是判据原文） */
    fun missedHints(): List<String> {
        val v = verdict ?: return emptyList()
        return question.points.filter { v.hits[it.id] == null }.map { it.hint }.filter { it.isNotBlank() }.distinct()
    }
    val unavailable get() = verdict?.unavailable == true
    /** 亮出选项就能选：说中了，或者试满了（试满也让它做完，只是不算“预判成功”） */
    fun canSelect() = optionsVisible && !busy && !unavailable
}
