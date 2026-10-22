package com.moling.core.redeem

import com.moling.core.link.GateCapabilities
import com.moling.core.link.GateError
import com.moling.core.mind.BEIJING
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private fun <T> failed(code: GateError, message: String): PortResult<T> =
    PortResult.Failed(PortError(code.code, message))

/** 第十三批 G3：训练兑换墨灵侧协议。FakeGate 只是测试替身，模拟总控判分；生产总控侧本批不改。 */
class TrainingRedeemTest {
    private val pkg = "com.example.game"
    private val at = LocalDateTime.of(2026, 10, 22, 10, 0).atZone(BEIJING).toInstant().toEpochMilli()

    private fun fails(r: PortResult<*>, code: GateError): Boolean =
        r is PortResult.Failed && r.error.code == code.code

    /** 测试用总控：rubric/nonce/TTL/额度都在“总控侧”，墨灵拿不到 rubric。 */
    private class FakeGate(
        var policy: UnlockPolicyView = UnlockPolicyView(
            policyId = "pol-1",
            enabled = true,
            allowedPackages = setOf("com.example.game"),
            minutesPerPass = 15,
            dailyMaxPasses = 2,
            dailyMaxMinutes = 30,
            remainingPassesToday = 2,
            remainingMinutesToday = 30,
            policyRevision = 3,
        ),
        var caps: GateCapabilities? = GateCapabilities(listOf(1, 2), training = true),
    ) : GateV2Port {
        var clock = 0L
        var issueCalls = 0
        var submitCalls = 0
        var receiptCalls = 0
        var passesIssued = 0
        var totalGrantedMinutes = 0
        var issued: ChallengeBundle? = null
        var expectedNonce: String? = null
        var issueOverride: (() -> PortResult<ChallengeBundle>)? = null
        var submitOverride: ((UnlockSubmission) -> PortResult<ExecutionReceipt>)? = null
        var receiptOverride: ((String) -> PortResult<ExecutionReceipt>)? = null
        var policyOverride: (() -> PortResult<UnlockPolicyView>)? = null
        private val byRequest = mutableMapOf<String, ExecutionReceipt>()
        private val rubric = mapOf("q1" to "a", "q2" to "b", "q3" to "c")

        override fun capabilities(): GateCapabilities? = caps

        override fun policy(): PortResult<UnlockPolicyView> = policyOverride?.invoke() ?: PortResult.Ok(policy)

        override fun issue(policyId: String, packageName: String, requestId: String): PortResult<ChallengeBundle> {
            issueCalls++
            issueOverride?.let { return it() }
            val bundle = ChallengeBundle(
                challengeId = "ch-1",
                nonce = "nonce-1",
                policyId = policyId,
                packageName = packageName,
                issuedAt = clock,
                expiresAt = clock + 10 * 60_000L,
                rubricVersion = 1,
                policyRevision = policy.policyRevision,
                questions = listOf(
                    ChallengeQuestion("q1", "1+1=?", listOf(ChallengeChoice("a", "2"), ChallengeChoice("b", "3"))),
                    ChallengeQuestion("q2", "2+2=?", listOf(ChallengeChoice("a", "5"), ChallengeChoice("b", "4"))),
                    ChallengeQuestion("q3", "3+3=?", listOf(ChallengeChoice("a", "7"), ChallengeChoice("c", "6"))),
                ),
            )
            issued = bundle
            return PortResult.Ok(bundle)
        }

        override fun submit(submission: UnlockSubmission): PortResult<ExecutionReceipt> {
            submitCalls++
            submitOverride?.let { return it(submission) }
            val c = issued ?: return failed(GateError.NOT_AVAILABLE, "没有这一局")
            if (submission.challengeId != c.challengeId || submission.nonce != (expectedNonce ?: c.nonce)) {
                return deny(submission, "challenge/nonce 不对")
            }
            if (clock >= c.expiresAt) return deny(submission, "挑战过期")
            if (submission.answers.any { a -> c.questions.firstOrNull { it.id == a.questionId }?.options?.none { it.id == a.optionId } != false }) {
                return deny(submission, "答案越界")
            }
            byRequest[submission.requestId]?.let { return PortResult.Ok(it) }
            val correct = submission.answers.count { rubric[it.questionId] == it.optionId }
            if (correct < 2) return deny(submission, "只对了 " + correct + " 题")
            if (policy.remainingPassesToday == 0 || policy.remainingMinutesToday in 0 until policy.minutesPerPass) {
                return deny(submission, "今天没有额度")
            }
            val pass = TemporaryPass(
                passId = "pass-" + (passesIssued + 1),
                challengeId = c.challengeId,
                packageName = c.packageName,
                issuedAt = clock,
                expiresAt = clock + policy.minutesPerPass * 60_000L,
                minutes = policy.minutesPerPass,
                policyRevision = policy.policyRevision,
            )
            passesIssued++
            totalGrantedMinutes += pass.minutes
            val receipt = ExecutionReceipt("rcpt-" + passesIssued, submission.requestId, "PASSED", clock, "这次放行 " + pass.minutes + " 分钟", pass)
            byRequest[submission.requestId] = receipt
            return PortResult.Ok(receipt)
        }

        override fun receipt(requestId: String): PortResult<ExecutionReceipt> {
            receiptCalls++
            receiptOverride?.let { return it(requestId) }
            return byRequest[requestId]?.let { PortResult.Ok(it) } ?: failed(GateError.NOT_AVAILABLE, "没有回执")
        }

        private fun deny(s: UnlockSubmission, why: String): PortResult<ExecutionReceipt> {
            val receipt = ExecutionReceipt("rcpt-deny-" + submitCalls, s.requestId, "DENIED", clock, why, null)
            byRequest[s.requestId] = receipt
            return PortResult.Ok(receipt)
        }
    }

    private fun correctAnswers(bundle: ChallengeBundle) = listOf(
        ChallengeAnswer("q1", "a"),
        ChallengeAnswer("q2", "b"),
        ChallengeAnswer("q3", "c"),
    ).filter { a -> bundle.questions.any { it.id == a.questionId } }

    private fun client(gate: GateV2Port?): TrainingRedeemClient = TrainingRedeemClient(gate)

    @Test fun correctAnswersGetServerIssuedPassAndCanBeUsedOnce() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        assertEquals(3, bundle.questions.size)
        assertNull(c.snapshot().pass)
        val receipt = (c.submit(correctAnswers(bundle), at + 60_000) as PortResult.Ok).value
        assertNotNull("总控发了临时通行令", receipt.pass)
        assertEquals(15, receipt.pass!!.minutes)
        assertEquals(1, gate.passesIssued)
        assertEquals(15, gate.totalGrantedMinutes)
        val pass = c.activePass(at + 2 * 60_000)
        assertNotNull(pass)
        assertEquals("com.example.game", pass!!.packageName)
        assertEquals("ch-1", pass.challengeId)
    }

    @Test fun duplicateSubmitReturnsSameReceiptAndDoesNotExtend() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        val first = (c.submit(correctAnswers(bundle), at + 60_000) as PortResult.Ok).value
        val second = (c.submit(correctAnswers(bundle), at + 90_000) as PortResult.Ok).value
        assertEquals(first.receiptId, second.receiptId)
        assertEquals(1, gate.submitCalls)
        assertEquals(1, gate.passesIssued)
        assertEquals("重复提交不增加时长", 15, gate.totalGrantedMinutes)
    }

    @Test fun concurrentSubmitOnlyOnePass() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        val ready = CountDownLatch(1)
        val done = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        val results = mutableListOf<PortResult<ExecutionReceipt>>()
        repeat(2) {
            pool.submit {
                ready.await()
                val r = c.submit(correctAnswers(bundle), at + 60_000)
                synchronized(results) { results += r }
                done.countDown()
            }
        }
        ready.countDown()
        assertTrue("并发提交应在 5 秒内结束", done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(2, results.size)
        assertEquals("并发只成功一次", 1, gate.passesIssued)
        assertEquals(15, gate.totalGrantedMinutes)
        val ids = results.filterIsInstance<PortResult.Ok<ExecutionReceipt>>().map { it.value.receiptId }.toSet()
        assertEquals(1, ids.size)
    }

    @Test fun serverDeniedLeavesNoPass() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        val wrong = listOf(ChallengeAnswer("q1", "b"), ChallengeAnswer("q2", "a"), ChallengeAnswer("q3", "a"))
        val receipt = (c.submit(wrong, at + 60_000) as PortResult.Ok).value
        assertNull(receipt.pass)
        assertNull(c.activePass(at + 2 * 60_000))
        assertEquals(0, gate.passesIssued)
    }

    @Test fun passedStateWithoutPassIsNotAwarded() {
        val gate = FakeGate()
        gate.clock = at
        gate.submitOverride = { s -> PortResult.Ok(ExecutionReceipt("r1", s.requestId, "PASSED", gate.clock, "过了但没发令", null)) }
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        val r = c.submit(correctAnswers(bundle), at + 60_000)
        assertTrue(fails(r, GateError.NOT_AVAILABLE))
        assertNull(c.activePass(at + 60_000))
    }

    @Test fun wrongNonceIsDeniedByServer() {
        val gate = FakeGate()
        gate.clock = at
        gate.expectedNonce = "server-nonce-2"
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        val receipt = (c.submit(correctAnswers(bundle), at + 60_000) as PortResult.Ok).value
        assertNull(receipt.pass)
        assertNull(c.activePass(at + 60_000))
    }

    @Test fun expiredChallengeIsRejectedLocally() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        val r = c.submit(correctAnswers(bundle), at + 11 * 60_000)
        assertTrue(fails(r, GateError.EXPIRED))
        assertEquals("过期后不再请求总控", 0, gate.submitCalls)
    }

    @Test fun answerOutsideOptionsIsRejectedBeforeSending() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        c.start(pkg, at)
        val r = c.submit(listOf(ChallengeAnswer("q1", "zzz"), ChallengeAnswer("q2", "b")), at + 60_000)
        assertTrue(fails(r, GateError.INVALID_ANSWER))
        assertEquals(0, gate.submitCalls)
    }

    @Test fun unknownQuestionAndDuplicateAnswerRejectedLocally() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        c.start(pkg, at)
        assertTrue(fails(c.submit(listOf(ChallengeAnswer("q9", "a")), at + 60_000), GateError.INVALID_ANSWER))
        assertTrue(fails(c.submit(listOf(ChallengeAnswer("q1", "a"), ChallengeAnswer("q1", "b")), at + 60_000), GateError.INVALID_ANSWER))
        assertEquals(0, gate.submitCalls)
    }

    @Test fun passForDifferentPackageIsNotStored() {
        val gate = FakeGate()
        gate.clock = at
        gate.submitOverride = { s ->
            PortResult.Ok(
                ExecutionReceipt(
                    "r1", s.requestId, "PASSED", gate.clock, "放行",
                    TemporaryPass("p1", "ch-1", "com.other.app", gate.clock, gate.clock + 15 * 60_000, 15, 3),
                ),
            )
        }
        val c = client(gate)
        c.start(pkg, at)
        val r = c.submit(listOf(ChallengeAnswer("q1", "a"), ChallengeAnswer("q2", "b")), at + 60_000)
        assertTrue(fails(r, GateError.REPLAY))
        assertNull(c.activePass(at + 60_000))
    }

    @Test fun passForOldPolicyRevisionIsNotStored() {
        val gate = FakeGate()
        gate.clock = at
        gate.submitOverride = { s ->
            PortResult.Ok(
                ExecutionReceipt(
                    "r1", s.requestId, "PASSED", gate.clock, "放行",
                    TemporaryPass("p1", "ch-1", pkg, gate.clock, gate.clock + 15 * 60_000, 15, 99),
                ),
            )
        }
        val c = client(gate)
        c.start(pkg, at)
        val r = c.submit(listOf(ChallengeAnswer("q1", "a"), ChallengeAnswer("q2", "b")), at + 60_000)
        assertTrue(fails(r, GateError.REPLAY))
        assertNull(c.activePass(at + 60_000))
    }

    @Test fun activePassExpiresAutomatically() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        c.submit(correctAnswers(bundle), at + 60_000)
        assertNotNull(c.activePass(at + 15 * 60_000 - 1))
        assertNull("到点恢复原闸门规则", c.activePass(at + 15 * 60_000 + 1))
        assertNull(c.snapshot().pass)
    }

    @Test fun noQuotaOrStrictPeriodRejectedBeforeIssuingChallenge() {
        val noQuota = FakeGate(FakeGate().policy.copy(remainingPassesToday = 0))
        noQuota.clock = at
        assertTrue(fails(client(noQuota).start(pkg, at), GateError.NO_QUOTA))
        assertEquals(0, noQuota.issueCalls)

        val noMinutes = FakeGate(FakeGate().policy.copy(remainingMinutesToday = 0))
        noMinutes.clock = at
        assertTrue(fails(client(noMinutes).start(pkg, at), GateError.NO_QUOTA))

        val strict = FakeGate(FakeGate().policy.copy(strict = true))
        strict.clock = at
        assertTrue(fails(client(strict).start(pkg, at), GateError.STRICT_MODE))
        assertEquals(0, strict.issueCalls)
    }

    @Test fun disabledPolicyOrWrongPackageRejected() {
        val disabled = FakeGate(FakeGate().policy.copy(enabled = false))
        disabled.clock = at
        assertTrue(fails(client(disabled).start(pkg, at), GateError.POLICY_DENIED))

        val other = FakeGate(FakeGate().policy.copy(allowedPackages = setOf("com.other.app")))
        other.clock = at
        assertTrue(fails(client(other).start(pkg, at), GateError.SCOPE_DENIED))
        assertEquals(0, other.issueCalls)

        val longPass = FakeGate(FakeGate().policy.copy(minutesPerPass = 60))
        longPass.clock = at
        assertTrue(fails(client(longPass).start(pkg, at), GateError.POLICY_DENIED))
    }

    @Test fun capabilitiesNullIsNotAvailable() {
        val gate = FakeGate(caps = null)
        gate.clock = at
        val c = client(gate)
        assertTrue("没拿到能力协商不能当放行", fails(c.start(pkg, at), GateError.NOT_AVAILABLE))
        assertEquals(0, gate.issueCalls)
        assertNull(c.snapshot().challenge)
    }

    @Test fun unsupportedV2OrNoPortIsRefused() {
        val oldGate = FakeGate(caps = GateCapabilities(listOf(1), training = false))
        oldGate.clock = at
        assertTrue(fails(client(oldGate).start(pkg, at), GateError.NOT_AVAILABLE))
        assertEquals(0, oldGate.issueCalls)

        val none = client(null)
        assertTrue(fails(none.start(pkg, at), GateError.NOT_AVAILABLE))
        assertTrue(fails(none.submit(emptyList(), at), GateError.NOT_AVAILABLE))
        assertNull(none.activePass(at))
    }

    @Test fun policyFetchFailurePropagatesWithoutChallenge() {
        val gate = FakeGate()
        gate.clock = at
        gate.policyOverride = { failed(GateError.NOT_AVAILABLE, "总控忙") }
        val c = client(gate)
        assertTrue(fails(c.start(pkg, at), GateError.NOT_AVAILABLE))
        assertNull(c.snapshot().challenge)
    }

    @Test fun queryNeverFabricatesPass() {
        val gate = FakeGate()
        gate.clock = at
        gate.receiptOverride = { req -> PortResult.Ok(ExecutionReceipt("r1", req, "PASSED", gate.clock, "过了", null)) }
        val c = client(gate)
        c.start(pkg, at)
        val r = c.query(at + 60_000)
        assertTrue(fails(r, GateError.NOT_AVAILABLE))
        assertNull(c.activePass(at + 60_000))
        assertEquals(1, gate.receiptCalls)
    }

    @Test fun challengeWithNoQuestionsIsRefused() {
        val gate = FakeGate()
        gate.clock = at
        gate.issueOverride = {
            PortResult.Ok(ChallengeBundle("ch-x", "n", "pol-1", pkg, at, at + 600_000, questions = emptyList()))
        }
        val c = client(gate)
        assertTrue(fails(c.start(pkg, at), GateError.NOT_AVAILABLE))
        assertNull(c.snapshot().challenge)
    }

    @Test fun threeQuestionsWithTwoCorrectAtLeast() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        c.start(pkg, at)
        val receipt = (c.submit(
            listOf(ChallengeAnswer("q1", "a"), ChallengeAnswer("q2", "b"), ChallengeAnswer("q3", "a")),
            at + 60_000,
        ) as PortResult.Ok).value
        assertNotNull(receipt.pass)
    }

    @Test fun passedStateWithoutPassCannotBecomeAuthorization() {
        val gate = FakeGate()
        gate.clock = at
        gate.submitOverride = { s -> PortResult.Ok(ExecutionReceipt("r1", s.requestId, "REDEEMED", gate.clock, "看起来通过了", null)) }
        val c = client(gate)
        val bundle = (c.start(pkg, at) as PortResult.Ok).value
        val r = c.submit(correctAnswers(bundle), at + 60_000)
        assertTrue(fails(r, GateError.NOT_AVAILABLE))
        assertNull(c.activePass(at + 60_000))
    }

    @Test fun stateIsSerializable() {
        val gate = FakeGate()
        gate.clock = at
        val c = client(gate)
        c.start(pkg, at)
        val json = kotlinx.serialization.json.Json.encodeToString(RedeemState.serializer(), c.snapshot())
        val back = kotlinx.serialization.json.Json.decodeFromString(RedeemState.serializer(), json)
        assertEquals(c.snapshot(), back)
    }
}
