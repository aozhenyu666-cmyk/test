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

    /** 线程核对通过的这一手，只能换规定的正常时长。 */
    suspend fun issueVerified(group: String, minutes: Int, verdict: com.yishou.app.judge.Verdict): Pass? {
        if (group.isBlank() || minutes != com.yishou.app.reflex.GatePlan.FULL_PASS_MIN || !verdict.passed || verdict.unavailable) return null
        return save(group, minutes)
    }

    /** 默写也在这里重算；界面不能把一个布尔值当凭证。 */
    suspend fun issueRecall(group: String, reflex: com.yishou.app.reflex.Reflex, answer: String): Pass? {
        if (group.isBlank() || !com.yishou.app.reflex.Reflexes.recalled(reflex.action, answer)) return null
        return save(group, com.yishou.app.reflex.GatePlan.LOW_PASS_MIN)
    }

    private suspend fun save(group: String, minutes: Int): Pass {
        val now = clock()
        val pass = Pass(packageGroup = group, startAt = now, endAt = now + minutes * 60_000L, roundId = VERIFIED_GATE_PASS_MARKER)
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
                round.card == dealt.card && s.gateVerdict?.passed == true && s.gateVerdict.unavailable == false && round.step == Step.REVEAL &&
                round.help == 0 && round.finalCorrect
        }
    }
}
