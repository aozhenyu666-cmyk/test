package com.yishou.app.gate

import com.yishou.app.card.SessionState
import com.yishou.app.card.Step
import com.yishou.app.data.AppDao
import com.yishou.app.data.CardMode
import com.yishou.app.data.CardSource
import com.yishou.app.data.Pass
import com.yishou.app.data.VERIFIED_GATE_PASS_MARKER

/** 入口放行只认这一轮题卡的实际作答，不接受调用方提供的判定。 */
class PassIssuer(private val dao: AppDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun issue(session: SessionState, group: String): Pass? {
        if (group.isBlank() || !eligible(session)) return null
        val now = clock()
        val pass = Pass(packageGroup = group, startAt = now, endAt = now + 15 * 60_000L, roundId = VERIFIED_GATE_PASS_MARKER)
        return pass.copy(id = dao.insertPass(pass))
    }

    suspend fun issueAfterWait(group: String, fallback: WaitFallback): Pass? {
        if (group.isBlank() || !fallback.consume()) return null
        val now = clock()
        val pass = Pass(packageGroup = group, startAt = now, endAt = now + WAIT_PASS_MS, roundId = VERIFIED_GATE_PASS_MARKER)
        return pass.copy(id = dao.insertPass(pass))
    }

    companion object {
        fun eligible(s: SessionState): Boolean {
            val dealt = s.dealt ?: return false
            val round = s.round ?: return false
            return s.active && s.mode == CardMode.GATE && s.done && !s.loading && !s.needsKey &&
                dealt.row.id > 0 && dealt.row.source == CardSource.BANK && dealt.row.verified &&
                round.card == dealt.card && s.gateVerdict?.passed == true && round.step == Step.REVEAL &&
                round.help == 0 && round.finalCorrect
        }
    }
}
