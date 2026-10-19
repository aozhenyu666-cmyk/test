package com.zongkong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    private val now = at(12)

    @Test
    fun tighteningGateAppliesImmediately() {
        val g = gate(deadline = 12 * 60, minChars = 10)
        val c = config(g)
        val out = Policy.putGate(c, g.copy(deadline = 11 * 60, minChars = 50), now)
        assertEquals(11 * 60, out.config.gates.single().deadline)
        assertTrue(out.config.pending.isEmpty())
        assertTrue(out.later.isEmpty())
    }

    @Test
    fun looseningGateIsDelayed24h() {
        val g = gate(deadline = 12 * 60, verify = VerifyMode.AI)
        val c = config(g)
        val out = Policy.putGate(c, g.copy(deadline = 14 * 60, verify = VerifyMode.TEXT), now)
        assertEquals(12 * 60, out.config.gates.single().deadline)
        val p = out.config.pending.single()
        assertEquals(now + Policy.DELAY_MILLIS, p.effectiveAt)
        assertTrue(p.summary.contains("14:00"))
        assertTrue(p.summary.contains("字数验收"))

        assertEquals(out.config, Policy.applyDue(out.config, now + Policy.DELAY_MILLIS - 1))
        val applied = Policy.applyDue(out.config, now + Policy.DELAY_MILLIS)
        assertEquals(14 * 60, applied.gates.single().deadline)
        assertTrue(applied.pending.isEmpty())
    }

    @Test
    fun deadlineAfterMidnightIsLaterThanEvening() {
        val g = gate(open = 20 * 60, deadline = 23 * 60)
        val parts = Policy.looserParts(g, g.copy(deadline = 60))
        assertTrue(parts.any { it.contains("01:00") })
        assertTrue(Policy.looserParts(g.copy(deadline = 60), g).isEmpty())
    }

    @Test
    fun editingSameGateAgainReplacesPending() {
        val g = gate(deadline = 12 * 60)
        val c1 = Policy.putGate(config(g), g.copy(deadline = 14 * 60), now).config
        assertEquals(1, c1.pending.size)
        val c2 = Policy.putGate(c1, g.copy(deadline = 11 * 60), now + 1000).config
        assertTrue(c2.pending.isEmpty())
        assertEquals(11 * 60, c2.gates.single().deadline)
    }

    @Test
    fun newGateImmediateRemoveDelayed() {
        val c = config(gate("a"))
        val added = Policy.putGate(c, gate("b"), now).config
        assertEquals(2, added.gates.size)
        val removed = Policy.removeGate(added, "a", now).config
        assertEquals(2, removed.gates.size)
        assertTrue(removed.pending.single().op is ChangeOp.RemoveGate)
        assertEquals(listOf("b"), Policy.applyDue(removed, now + Policy.DELAY_MILLIS).gates.map { it.id })
    }

    @Test
    fun blockListAddNowRemoveLaterAndReAddCancels() {
        val c = config().copy(blocked = setOf("a", "b"))
        val out = Policy.setBlocked(c, setOf("b", "c"), { it }, now)
        assertEquals(setOf("a", "b", "c"), out.config.blocked)
        assertEquals(ChangeOp.Unblock("a"), out.config.pending.single().op)
        val readd = Policy.setBlocked(out.config, setOf("a", "b", "c"), { it }, now)
        assertTrue(readd.config.pending.isEmpty())
        assertEquals(setOf("b", "c"), Policy.applyDue(out.config, now + Policy.DELAY_MILLIS).blocked)
    }

    @Test
    fun rulesSplitIntoImmediateAndDelayedFields() {
        val c = config().copy(silenceHours = 3, dailyQuotaMin = 120, emergencyPerDay = 1)
        val r = Policy.rulesOf(c).copy(silenceHours = 2, dailyQuotaMin = 0, emergencyPerDay = 3)
        val out = Policy.setRules(c, r, now)
        assertEquals(2, out.config.silenceHours) // 收紧：立即
        assertEquals(120, out.config.dailyQuotaMin) // 不限 = 放宽：延迟
        assertEquals(1, out.config.emergencyPerDay)
        val op = out.config.pending.single().op as ChangeOp.Rules
        assertEquals(null, op.silenceHours)
        val applied = Policy.applyDue(out.config, now + Policy.DELAY_MILLIS)
        assertEquals(0, applied.dailyQuotaMin)
        assertEquals(3, applied.emergencyPerDay)
        assertEquals(2, applied.silenceHours)
    }

    @Test
    fun laterTighteningIsNotRevertedByPendingRules() {
        val c = config().copy(dailyQuotaMin = 120, silenceHours = 3)
        val c1 = Policy.setRules(c, Policy.rulesOf(c).copy(dailyQuotaMin = 180), now).config
        val c2 = Policy.setRules(c1, Policy.rulesOf(c1).copy(silenceHours = 1), now + 1).config
        val applied = Policy.applyDue(c2, now + Policy.DELAY_MILLIS + 1)
        assertEquals(1, applied.silenceHours)
        assertEquals(180, applied.dailyQuotaMin)
    }

    @Test
    fun pauseStartsAfterDelayAndCanEndEarly() {
        val c = config()
        val out = Policy.requestPause(c, 2, now)
        assertFalse(out.config.pausedUntil > now)
        val applied = Policy.applyDue(out.config, now + Policy.DELAY_MILLIS)
        assertEquals(now + Policy.DELAY_MILLIS + 2 * 24 * 3600_000L, applied.pausedUntil)
        val ended = Policy.endPause(applied, now + Policy.DELAY_MILLIS + 1000).config
        assertFalse(ended.pausedUntil > now + Policy.DELAY_MILLIS + 1000)
    }

    @Test
    fun cancelRemovesPending() {
        val g = gate()
        val c = Policy.removeGate(config(g), "g", now).config
        assertTrue(Policy.cancel(c, c.pending.single().id).pending.isEmpty())
    }
}
