package com.yishou.app.gate

import com.yishou.app.card.*
import com.yishou.app.data.*
import com.yishou.app.card.Round
import org.junit.Assert.*
import org.junit.Test
import com.yishou.app.FakeDao
import kotlinx.coroutines.test.runTest

class PassIssuerTest {
    private val card = Card(Skill.MAIN, 1, listOf("第一句。", "第二句。"), "问题", listOf("甲", "乙", "丙", "丁"), 1, setOf(2), emptyMap(), "解析")
    private val row = CardRow(id = 1, skill = card.skill.name, difficulty = 1, json = "", verified = true, source = CardSource.BANK, createdAt = 1)
    private fun session(r: Round = Round(card, RoundConfig(0, false)).bet(1, Conf.MID).evidence(setOf(2))) =
        SessionState(active = true, mode = CardMode.GATE, done = true, dealt = Dealt(row, card, false), round = r, gateVerdict = com.yishou.app.judge.Verdict(emptyMap(), false, true, "测试"))

    @Test fun onlyCompletedIndependentCorrectAnswerAndEvidenceCanPass() {
        val good = session()
        assertTrue(PassIssuer.eligible(good))
        for (step in Step.entries) for (help in 0..3) for (answer in 0..3) for (evidence in listOf(emptySet(), setOf(1), setOf(2))) {
            val round = good.round!!.copy(step = step, help = help, bet = answer, evidence = evidence)
            assertEquals(step == Step.REVEAL && help == 0 && answer == 1, PassIssuer.eligible(session(round)))
        }
    }

    @Test fun missingOrUntrustedFactsNeverPass() {
        val s = session()
        assertFalse(PassIssuer.eligible(s.copy(done = false)))
        assertFalse(PassIssuer.eligible(s.copy(mode = CardMode.HOME)))
        assertFalse(PassIssuer.eligible(s.copy(dealt = null)))
        assertFalse(PassIssuer.eligible(s.copy(dealt = s.dealt!!.copy(row = row.copy(verified = false)))))
        assertFalse(PassIssuer.eligible(s.copy(dealt = s.dealt!!.copy(row = row.copy(source = CardSource.AI)))))
        assertFalse(PassIssuer.eligible(s.copy(gateVerdict = null)))
        assertFalse(PassIssuer.eligible(s.copy(round = s.round!!.copy(card = card.copy(answer = 0)))))
    }

    @Test fun issuerWritesOnlyEligiblePassAndUsesFifteenMinutes() = runTest {
        val dao = FakeDao()
        val issuer = PassIssuer(dao) { 1_000L }
        dao.insertPass(Pass(packageGroup = "游戏", startAt = 0, endAt = 2_000_000, roundId = null))
        assertNull(issuer.issue(session().copy(done = false), "游戏"))
        assertNull(dao.activePass("游戏", 1_000L))
        val pass = issuer.issue(session(), "游戏")!!
        assertEquals(901_000L, pass.endAt)
        assertNotNull(dao.activePass("游戏", 900_999L))
        assertNull(dao.activePass("游戏", 901_000L))
    }

    @Test fun optionalExitCannotMakeWrongAnswerEligible() = runTest {
        val exit = "因为一手"
        assertTrue(exit.isNotBlank())
        val issuer = PassIssuer(FakeDao())
        val wrong = session().round!!.copy(bet = 0)
        assertNull(issuer.issue(session(wrong), "游戏"))
    }
}
