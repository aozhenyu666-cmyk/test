package com.yishou.app.companion

import com.yishou.app.companion.core.CoachAction
import com.yishou.app.companion.core.CoachDecision
import com.yishou.app.companion.core.CompanionSession
import com.yishou.app.companion.core.DecisionOutcome
import com.yishou.app.companion.core.MediaReceipt
import com.yishou.app.companion.core.PauseOutcome
import com.yishou.app.companion.core.Phase
import com.yishou.app.companion.core.SessionEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话协调：一处出声、过期作废、没接不算错、停了不复活 */
class SessionTest {

    private var now = 1_000L
    private fun session(wait: Long = 15_000) = CompanionSession(sessionId = 3, clock = { now }, answerWaitMs = wait)

    private fun ask(
        epoch: Int = 1,
        q: String = "他从样本 A 推到全部人，中间还缺哪个条件？",
        ttl: Long = 60_000,
        evidence: List<Long> = listOf(11L, 12L),
    ) = CoachDecision(
        action = CoachAction.ASK,
        question = q,
        criteria = "缺样本代表性",
        evidenceIds = evidence,
        reason = "材料出现以偏概全",
        epoch = epoch,
        expiresAtMs = now + ttl,
    )

    /** 走到「问题已经播完、正在等回答」 */
    private fun asked(s: CompanionSession): Long {
        val (outcome, _) = s.offer(ask())
        assertEquals(DecisionOutcome.ACCEPTED, outcome)
        s.onPauseReceipt(MediaReceipt("pkg/session", PauseOutcome.VERIFIED, now, 1))
        val spoken = s.question()!!
        assertTrue(spoken.isNotBlank())
        s.onSpoken(1)
        assertEquals(Phase.WAIT_ANSWER, s.phase())
        return 1
    }

    @Test
    fun `暂停核实成功才出声`() {
        val s = session()
        s.offer(ask())
        assertEquals(Phase.PREPARE, s.phase())
        val effects = s.onPauseReceipt(MediaReceipt("tag", PauseOutcome.VERIFIED, now, 1))
        assertEquals(Phase.ASK, s.phase())
        assertTrue(effects.any { it is SessionEffect.Speak })
    }

    @Test
    fun `暂停没核实就让人手动按，不宣称停了`() {
        val s = session()
        s.offer(ask())
        val effects = s.onPauseReceipt(MediaReceipt("tag", PauseOutcome.STILL_PLAYING, now, 1))
        assertEquals(Phase.PAUSE_PENDING, s.phase())
        assertTrue(effects.any { it is SessionEffect.AskManually })
        assertTrue(effects.none { it is SessionEffect.Speak })
        // 用户按好了才出声
        val after = s.onUserReady()
        assertEquals(Phase.ASK, s.phase())
        assertTrue(after.any { it is SessionEffect.Speak })
    }

    @Test
    fun `同一时刻只有一个可发声的问题`() {
        val s = session()
        s.offer(ask())
        val (second, _) = s.offer(ask(q = "第二个问题"))
        assertEquals(DecisionOutcome.BUSY, second)
    }

    @Test
    fun `过期的建议不接`() {
        val s = session()
        val stale = ask().copy(expiresAtMs = now - 1)
        assertEquals(DecisionOutcome.EXPIRED, s.offer(stale).first)
        assertEquals(Phase.OBSERVE, s.phase())
    }

    @Test
    fun `别的段的建议不接`() {
        val s = session()
        assertEquals(DecisionOutcome.WRONG_EPOCH, s.offer(ask(epoch = 99)).first)
    }

    @Test
    fun `策略说等就继续听`() {
        val s = session()
        val wait = ask().copy(action = CoachAction.WAIT)
        assertEquals(DecisionOutcome.WAITED, s.offer(wait).first)
        assertEquals(Phase.OBSERVE, s.phase())
    }

    @Test
    fun `没有依据的建议也不接`() {
        val s = session()
        assertEquals(DecisionOutcome.WAITED, s.offer(ask(evidence = emptyList())).first)
    }

    @Test
    fun `问题播完才开始计时，没接记一笔而不是答错`() {
        val s = session(wait = 15_000)
        asked(s)
        now += 14_000
        assertTrue(s.tick(now).isEmpty())
        now += 2_000
        val effects = s.tick(now)
        assertTrue(effects.any { it is SessionEffect.Missed })
        assertEquals(Phase.OBSERVE, s.phase())
        assertNull(s.question())
    }

    @Test
    fun `答完先审后记，记完回去听`() {
        val s = session()
        asked(s)
        s.answer("缺样本能不能代表整体")
        assertEquals(Phase.REVIEW, s.phase())
        val rec = s.onReviewed("你说了样本，但没说它怎么选的")
        assertTrue(rec.any { it is SessionEffect.Record })
        assertEquals(Phase.RECORD, s.phase())
        s.onRecorded()
        assertEquals(Phase.OBSERVE, s.phase())
    }

    @Test
    fun `晚到的旧说话回调不认`() {
        val s = session()
        s.offer(ask())
        s.onPauseReceipt(MediaReceipt("tag", PauseOutcome.VERIFIED, now, 1))
        // 上一轮的 turnId 晚到了
        assertTrue(s.onSpoken(99).isEmpty())
        assertEquals(Phase.ASK, s.phase())
    }

    @Test
    fun `停下以后什么都不复活`() {
        val s = session()
        asked(s)
        val effects = s.stop()
        assertEquals(Phase.ENDED, s.phase())
        assertTrue(effects.contains(SessionEffect.CancelPending))
        assertTrue(effects.contains(SessionEffect.ReleaseResources))
        assertEquals(DecisionOutcome.STOPPED, s.offer(ask()).first)
        assertTrue(s.answer("我说了").isEmpty())
        assertTrue(s.tick(now + 100_000).isEmpty())
        assertTrue(s.onSpoken(1).isEmpty())
    }

    @Test
    fun `换视频时把旧问题作废`() {
        val s = session()
        asked(s)
        val effects = s.sourceChanged()
        assertEquals(Phase.OBSERVE, s.phase())
        assertNull(s.question())
        assertTrue(effects.contains(SessionEffect.CancelPending))
        assertEquals(2, s.epoch())
    }

    @Test
    fun `准备阶段拖太久就作废`() {
        val s = session()
        s.offer(ask(ttl = 5_000))
        now += 6_000
        val effects = s.tick(now)
        assertEquals(Phase.OBSERVE, s.phase())
        assertNull(s.pendingDecision())
        assertTrue(effects.contains(SessionEffect.CancelPending))
    }

    @Test
    fun `暂停结果属于旧段时整单作废`() {
        val s = session()
        s.offer(ask())
        // 换段的时候已经把手上这一问作废了，暂停结果再回来也没有单子可结
        s.sourceChanged()
        val effects = s.onPauseReceipt(MediaReceipt("tag", PauseOutcome.VERIFIED, now, 1))
        assertEquals(Phase.OBSERVE, s.phase())
        assertTrue(effects.isEmpty())
        assertNull(s.pendingDecision())
    }
}
