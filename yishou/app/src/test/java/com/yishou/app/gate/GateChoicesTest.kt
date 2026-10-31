package com.yishou.app.gate

import com.yishou.app.FakeDao
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardMode
import com.yishou.app.data.CardSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GateChoicesTest {
    private fun c(id: Long, created: Long, last: Long? = null, verified: Boolean = true, source: String = CardSource.BANK) =
        ChoiceCandidate(id, verified, source, created, last)

    private fun a(cardId: Long, at: Long, right: Boolean = true, mode: String = CardMode.GATE, help: Int = 0, verified: Boolean = true) = CardAttempt(
        cardId = cardId, skill = "MAIN", difficulty = 1, mode = mode, level = 1, bet = 0, conf = "中", evidenceOk = true,
        checks = 0, checksOk = 0, final = -1, firstCorrect = right, finalCorrect = right, help = help, cause = "",
        durationMs = 1, verified = verified, createdAt = at,
    )

    @Test fun libraryCardsAreUsedEvenAfterTheyWereDone() {
        // 题库页上的题都能进入口：拍照收的也算；做过的不再被扔掉，按最久没碰的轮
        val rows = listOf(c(1, 10, last = 500), c(2, 20, last = 100), c(3, 30), c(4, 40, source = CardSource.PHOTO), c(5, 5))
        assertEquals(listOf(5L, 3L, 4L, 2L, 1L), GateChoices.order(rows))
    }

    @Test fun skipsWhatCannotBeCheckedLocally() {
        val rows = listOf(c(1, 10, verified = false), c(2, 20, source = CardSource.AI), c(3, 30), c(4, 40))
        assertEquals(listOf(3L, 4L), GateChoices.order(rows))
        // 这一回已经做过的不再出
        assertEquals(listOf(4L), GateChoices.order(rows, skip = setOf(3L)))
        assertTrue(GateChoices.order(emptyList()).isEmpty())
    }

    @Test fun onlyRightAnswersInThisVisitCount() {
        val since = 1_000L
        val xs = listOf(
            a(1, 900),                       // 上一回的
            a(2, 1_100, mode = CardMode.HOME), // 主页练的
            a(3, 1_200, right = false),
            a(4, 1_300, help = 3),            // 看了解析
            a(5, 1_400, verified = false),
            a(6, 1_500),
        )
        assertEquals(1, GateChoices.rightSince(xs, since))
        assertFalse(GateChoices.passed(xs, since))
        // 同一道题答两次只算一道
        assertFalse(GateChoices.passed(xs + a(6, 1_600), since))
        assertTrue(GateChoices.passed(xs + a(7, 1_700), since))
    }

    @Test fun issuerRecountsAttemptsBeforeAPass() = runTest {
        val dao = FakeDao()
        val issuer = PassIssuer(dao) { 2_000L }
        assertNull(issuer.issueChoices("游戏", listOf(a(1, 1_100)), 1_000L))
        assertNull(issuer.issueChoices("", listOf(a(1, 1_100), a(2, 1_200)), 1_000L))
        assertNull(dao.activePass("游戏", 2_000L))
        val pass = issuer.issueChoices("游戏", listOf(a(1, 1_100), a(2, 1_200)), 1_000L)!!
        assertEquals(2_000L + 15 * 60_000L, pass.endAt)
        assertNotNull(dao.activePass("游戏", 2_000L))
    }
}
