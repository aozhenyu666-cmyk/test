package com.zongkong.core.moling

import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.Policy
import com.zongkong.core.Reason
import com.zongkong.core.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 以思代锁（总控侧）：只在“挣来的娱乐用完了”时能换；题和答案在总控；重放 / 过期 / 改版 / 额度都拒绝。 */
class TrainingTest {
    private val zone = DayClock.BEIJING
    private fun at(h: Int, mi: Int = 0, d: Int = 22) = LocalDateTime.of(2026, 10, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val douyin = "com.ss.android.ugc.aweme"
    private val policy = TrainingPolicy(enabled = true, packages = setOf(douyin), minutesPerPass = 10, dailyMaxPasses = 2, dailyMaxMinutes = 20, attemptsPerDay = 3, revision = 1)

    private fun status(vararg reasons: Reason, paused: Boolean = false, emergency: Long? = null) = Status(
        now = at(15), date = "2026-10-22", strict = reasons.isNotEmpty() && emergency == null && !paused, reasons = reasons.toList(), gates = emptyList(),
        emergencyUntil = emergency, emergencyLeft = 1, degradedLeft = 2, paused = paused, lastActivity = null, silenceDueAt = null, playMin = 40, quotaMin = 40,
    )
    private val budgetOnly = status(Reason.Budget(40, 40))

    private fun rnd(seed: Long = 7) = java.util.Random(seed)

    /** 按标准答案答对 [right] 道。 */
    private fun answers(c: TrainingChallenge, right: Int) = c.questions.mapIndexed { i, q ->
        q.id to (if (i < right) q.answer else q.options.first { it.id != q.answer }.id)
    }.toMap()

    private fun issue(book: TrainingBook = TrainingBook(), rid: String = "r1", now: Long = at(15), st: Status = budgetOnly, p: TrainingPolicy = policy) =
        Training.issue(p, book, st, douyin, rid, now, zone, rnd())

    @Test fun passOnlyWhenBudgetIsTheOnlyReason() {
        val o = issue()
        assertTrue(o.ok)
        val c = o.value!!
        assertEquals(Training.QUESTIONS, c.questions.size)
        val r = Training.submit(policy, o.book, budgetOnly, "s1", c.id, c.nonce, answers(c, 3), at(15, 2), zone)
        assertEquals("PASSED", r.value!!.state)
        val pass = r.value!!.pass!!
        assertEquals(10, pass.minutes)
        assertEquals(c.id, pass.challengeId)
        assertEquals(douyin, pass.pkg)
        assertEquals("通行加进今天挣到的分钟", 10, Training.bonusMinutes(r.book, at(15, 3), zone))
        assertEquals("一小时内不用就作废，不让攒", 0, Training.bonusMinutes(r.book, at(16, 3), zone))
    }

    @Test fun otherReasonsCannotBeTrainedAway() {
        val gate = Reason.Debt("k", "写申论")
        val o = issue(st = status(Reason.Budget(40, 40), gate))
        assertEquals(Training.STRICT_MODE, o.code)
        assertEquals(Training.NO_QUOTA, issue(st = status(Reason.Quota(120, 120))).code)
        assertEquals("没在拦就不用换", Training.POLICY_DENIED, issue(st = status()).code)
        assertEquals("休假中不用换", Training.POLICY_DENIED, issue(st = status(Reason.Budget(1, 1), paused = true)).code)
        assertEquals("紧急放行中不用换", Training.POLICY_DENIED, issue(st = status(Reason.Budget(1, 1), emergency = at(16))).code)
    }

    @Test fun policyAndScopeFailClosed() {
        assertEquals(Training.POLICY_DENIED, issue(p = policy.copy(enabled = false)).code)
        assertEquals("空名单 = 一个都不行", Training.SCOPE_DENIED, issue(p = policy.copy(packages = emptySet())).code)
        assertEquals(Training.SCOPE_DENIED, Training.issue(policy, TrainingBook(), budgetOnly, "com.tencent.mm", "r", at(15), zone, rnd()).code)
    }

    @Test fun threeOfFourNeededAndFailureCoolsDown() {
        val o = issue()
        val c = o.value!!
        val r = Training.submit(policy, o.book, budgetOnly, "s1", c.id, c.nonce, answers(c, 2), at(15, 1), zone)
        assertEquals("FAILED", r.value!!.state)
        assertNull(r.value!!.pass)
        val again = Training.issue(policy, r.book, budgetOnly, douyin, "r2", at(15, 2), zone, rnd())
        assertEquals("答错要歇一会儿", Training.RATE_LIMITED, again.code)
        assertNotNull(again.retryAfterMs)
        assertTrue(Training.issue(policy, r.book, budgetOnly, douyin, "r2", at(15, 5), zone, rnd()).ok)
    }

    @Test fun duplicateSubmitReturnsSameReceiptAndNeverDoublesMinutes() {
        val o = issue()
        val c = o.value!!
        val r1 = Training.submit(policy, o.book, budgetOnly, "s1", c.id, c.nonce, answers(c, 4), at(15, 1), zone)
        val r2 = Training.submit(policy, r1.book, budgetOnly, "s1", c.id, c.nonce, answers(c, 4), at(15, 2), zone)
        assertEquals(r1.value, r2.value)
        assertEquals(1, r2.book.passes.size)
        // 换个 requestId 再交同一局：重放，拒绝
        val r3 = Training.submit(policy, r2.book, budgetOnly, "s2", c.id, c.nonce, answers(c, 4), at(15, 2), zone)
        assertEquals("REJECTED", r3.value!!.state)
        assertEquals(1, r3.book.passes.size)
    }

    @Test fun wrongNonceExpiredOrRevisedChallengesAreRejected() {
        val o = issue()
        val c = o.value!!
        assertEquals("REJECTED", Training.submit(policy, o.book, budgetOnly, "a", c.id, "bad", answers(c, 4), at(15, 1), zone).value!!.state)
        assertEquals("EXPIRED", Training.submit(policy, o.book, budgetOnly, "b", c.id, c.nonce, answers(c, 4), at(15, 6), zone).value!!.state)
        val revised = policy.copy(revision = 2)
        val r = Training.submit(revised, o.book, budgetOnly, "c", c.id, c.nonce, answers(c, 4), at(15, 1), zone).value!!
        assertEquals("REJECTED", r.state)
        assertNull(r.pass)
        // 答案对不上题目（选项不存在）：作废，不碰运气
        val bad = Training.submit(policy, o.book, budgetOnly, "d", c.id, c.nonce, mapOf("q1" to "zzz"), at(15, 1), zone).value!!
        assertEquals("REJECTED", bad.state)
    }

    @Test fun rulesAreRecheckedAtSubmitTime() {
        val o = issue()
        val c = o.value!!
        // 发题后关卡到点了：答对也不放
        val r = Training.submit(policy, o.book, status(Reason.Budget(40, 40), Reason.Debt("k", "写申论")), "s", c.id, c.nonce, answers(c, 4), at(15, 1), zone).value!!
        assertEquals("DENIED", r.state)
        assertNull(r.pass)
    }

    @Test fun dailyQuotaAndAttemptsAreEnforced() {
        var book = TrainingBook()
        repeat(2) { i ->
            val o = Training.issue(policy, book, budgetOnly, douyin, "r$i", at(15, i * 10), zone, rnd(i.toLong()))
            val c = o.value!!
            book = Training.submit(policy, o.book, budgetOnly, "s$i", c.id, c.nonce, answers(c, 4), at(15, i * 10 + 1), zone).book
        }
        assertEquals(20, Training.usedToday(book, at(16), zone).second)
        assertEquals("每天两次用完", Training.NO_QUOTA, Training.issue(policy, book, budgetOnly, douyin, "r9", at(16), zone, rnd()).code)
        // 局数上限：开三局不交
        var b2 = TrainingBook()
        repeat(3) { i -> b2 = Training.issue(policy, b2, budgetOnly, douyin, "x$i", at(15, i), zone, rnd(i.toLong())).book }
        assertEquals(Training.RATE_LIMITED, Training.issue(policy, b2, budgetOnly, douyin, "x9", at(15, 4), zone, rnd()).code)
        assertEquals("新开一局会作废没交的旧局", 1, b2.challenges.count { it.state == ChallengeState.OPEN })
        // 第二天重新算
        assertTrue(Training.issue(policy, b2, budgetOnly, douyin, "y", at(9, d = 23), zone, rnd()).ok)
    }

    @Test fun sameIssueRequestIdIsIdempotent() {
        val o1 = issue()
        val o2 = Training.issue(policy, o1.book, budgetOnly, douyin, "r1", at(15, 1), zone, rnd(99))
        assertEquals(o1.value!!.id, o2.value!!.id)
        assertEquals(1, o2.book.challenges.size)
    }

    @Test fun enablingOrLooseningWaits24hTighteningIsImmediate() {
        val c0 = Config()
        val on = Policy.setTraining(c0, policy, at(10))
        assertFalse("开启要等 24 小时", on.config.training.enabled)
        assertTrue(on.later.single().contains("开启"))
        val live = Policy.applyDue(on.config, at(10) + Policy.DELAY_MILLIS)
        assertTrue(live.training.enabled)
        assertEquals(setOf(douyin), live.training.packages)
        val rev = live.training.revision
        // 收紧：立即生效，版本 + 1
        val tight = Policy.setTraining(live, live.training.copy(minutesPerPass = 5), at(12, d = 23))
        assertEquals(5, tight.config.training.minutesPerPass)
        assertEquals(rev + 1, tight.config.training.revision)
        assertTrue(tight.config.pending.isEmpty())
        // 放宽：排队
        val loose = Policy.setTraining(tight.config, tight.config.training.copy(minutesPerPass = 15, dailyMaxPasses = 4), at(13, d = 23))
        assertEquals(5, loose.config.training.minutesPerPass)
        assertEquals(1, loose.config.pending.size)
        // 单次上限 15 分钟：写 60 也只会是 15
        assertEquals(Training.MAX_MINUTES_PER_PASS, policy.copy(minutesPerPass = 60).normalized().minutesPerPass)
    }

    @Test fun quizAnswersAreComputedAndOptionsDistinct() {
        repeat(200) { seed ->
            val qs = TrainingQuiz.make(java.util.Random(seed.toLong()))
            assertEquals(4, qs.size)
            qs.forEach { q ->
                assertEquals(4, q.options.size)
                assertEquals("选项不重复：${q.stem}", 4, q.options.map { it.text }.distinct().size)
                assertTrue(q.options.any { it.id == q.answer })
            }
            val arith = Regex("""心算：(\d+) × (\d+) \+ (\d+)""").find(qs[0].stem)!!.groupValues.drop(1).map { it.toInt() }
            assertEquals((arith[0] * arith[1] + arith[2]).toString(), qs[0].options.first { it.id == qs[0].answer }.text)
        }
    }

    @Test fun wireNeverSendsAnswersAndChecksVersion() {
        val req = mapOf("version" to "2", "requestId" to "r1", "method" to TrainingWire.CHALLENGE_ISSUE, "payloadJson" to """{"policyId":"training","packageName":"$douyin"}""")
        val (book, resp) = TrainingWire.handle(TrainingWire.CHALLENGE_ISSUE, req, policy, TrainingBook(), budgetOnly, at(15), zone, rnd())
        assertEquals("true", resp["ok"])
        val c = book.challenges.single()
        val payload = resp["payloadJson"]!!
        c.questions.forEach { assertFalse("题目下发不带答案字段", payload.contains("\"answer\"")) }
        assertTrue(payload.contains(c.nonce))
        val (_, v1) = TrainingWire.handle(TrainingWire.CAPABILITIES, mapOf("version" to "1", "requestId" to "x"), policy, book, budgetOnly, at(15), zone, rnd())
        assertEquals(TrainingWire.UNSUPPORTED_VERSION, v1["code"])
        val (_, dup) = TrainingWire.handle(
            TrainingWire.CHALLENGE_SUBMIT,
            mapOf("version" to "2", "requestId" to "s", "payloadJson" to """{"challengeId":"${c.id}","nonce":"${c.nonce}","answers":[{"questionId":"q1","optionId":"q1-a"},{"questionId":"q1","optionId":"q1-b"}]}"""),
            policy, book, budgetOnly, at(15, 1), zone, rnd(),
        )
        assertEquals("同一道题两个答案直接拒", Training.INVALID_ANSWER, dup["code"])
    }
}
