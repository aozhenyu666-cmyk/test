package com.yishou.app.gate

import com.yishou.app.card.SessionState
import com.yishou.app.card.Step
import com.yishou.app.data.AppDao
import com.yishou.app.data.CardMode
import com.yishou.app.data.CardSource
import com.yishou.app.data.Pass
import com.yishou.app.data.VERIFIED_GATE_PASS_MARKER
import com.yishou.app.reflex.GatePlan

/**
 * 入口放行只认这一轮题卡的实际作答，不接受调用方提供的判定。
 *
 * 放行多久由应用组的设置决定（设置里 5–30 分钟），不再写死 15 分钟：
 * 以前改了设置也不生效，改的是同一个写死的数。
 */
class PassIssuer(
    private val dao: AppDao,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 这一组设置的放行分钟数；没设过就走默认 15 */
    private val minutesFor: (String) -> Int = { GatePlan.FULL_PASS_MIN },
) {
    suspend fun issue(session: SessionState, group: String): Pass? {
        if (group.isBlank() || !eligible(session)) return null
        return save(group, minutesFor(group))
    }

    /** 线程核对通过的这一手。 */
    suspend fun issueVerified(group: String, verdict: com.yishou.app.judge.Verdict): Pass? {
        if (group.isBlank() || !verdict.passed || verdict.unavailable) return null
        return save(group, minutesFor(group))
    }

    /**
     * 没说到要点、但两次机会用完、最后选项选对了：给低能量那一档时长。
     * 不装成“预判成功”，也不让人卡死在这里——训练做过了，只是没做对。
     */
    suspend fun issueRevealed(group: String, session: SessionState, revealed: Boolean): Pass? {
        if (!revealed || group.isBlank()) return null
        if (!answered(session)) return null
        return save(group, minOf(minutesFor(group), GatePlan.LOW_PASS_MIN))
    }

    /** 默写也在这里重算；界面不能把一个布尔值当凭证。默写只换低能量那一档时长。 */
    suspend fun issueRecall(group: String, reflex: com.yishou.app.reflex.Reflex, answer: String): Pass? {
        if (group.isBlank() || !com.yishou.app.reflex.Reflexes.recalled(reflex.action, answer)) return null
        return save(group, minOf(minutesFor(group), GatePlan.LOW_PASS_MIN))
    }

    private suspend fun save(group: String, minutes: Int): Pass {
        val now = clock()
        val pass = Pass(packageGroup = group, startAt = now, endAt = now + minutes.coerceAtLeast(1) * 60_000L, roundId = VERIFIED_GATE_PASS_MARKER)
        return pass.copy(id = dao.insertPass(pass))
    }

    companion object {
        /** 题真的做完了、选项真的选对了（不看要点判没判过） */
        fun answered(s: SessionState): Boolean {
            val dealt = s.dealt ?: return false
            val round = s.round ?: return false
            return s.active && s.mode == CardMode.GATE && s.done && !s.loading && !s.needsKey &&
                dealt.row.id > 0 && dealt.row.source == CardSource.BANK && dealt.row.verified &&
                round.card == dealt.card && round.step == Step.REVEAL && round.help == 0 && round.finalCorrect
        }

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
