package com.zongkong.core.moling

import com.zongkong.core.Reason
import com.zongkong.core.Status
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/*
 * 以思代锁：用一局有标准答案的训练，换一点今天的娱乐分钟（v13 W13 G3 的总控侧）。
 *
 * 规矩（写死在这里，墨灵改不了）：
 *  - 策略默认关；他自己选哪些应用能换、每次几分钟（最多 15）、每天几次 / 几分钟。开启或放宽走 24 小时冷静期，收紧立即生效（见 Policy.setTraining）；
 *  - 只在“今天挣来的娱乐用完了”这一个原因拦着他时才能换：关卡到点、欠着的事、专注锁、墨灵约的临时严管、每天总上限……任何别的原因都拒绝；
 *  - 换来的是**今天的娱乐分钟**，走总控原有的“挣来的娱乐”账，仍然不超过每天总上限；用完照旧拦；
 *  - 题目和标准答案都在总控生成、保存、判分；墨灵只拿题干和选项；聊天里的分数一概不认；
 *  - 4 道单选，对 3 道才算过（瞎猜能过的概率约 5%）；每天有尝试次数，答错要冷却一会儿；
 *  - 同一个 requestId 重复提交返回同一张回执；一局只能交一次；nonce / 过期 / 策略改版 / 应用不在范围 一律拒绝。
 */

@Serializable
data class TrainingPolicy(
    val enabled: Boolean = false,
    /** 能拿训练换时间的应用（他自己选；空 = 一个都不行）。 */
    val packages: Set<String> = emptySet(),
    /** 每次换几分钟（1–15）。 */
    val minutesPerPass: Int = 10,
    val dailyMaxPasses: Int = 2,
    val dailyMaxMinutes: Int = 20,
    /** 每天最多开几局（答错、过期都算）。 */
    val attemptsPerDay: Int = 3,
    /** 每次生效的改动 + 1；挑战绑定发出时的版本，改了就作废。 */
    val revision: Long = 0,
) {
    fun normalized() = copy(
        minutesPerPass = minutesPerPass.coerceIn(1, Training.MAX_MINUTES_PER_PASS),
        dailyMaxPasses = dailyMaxPasses.coerceIn(0, 6),
        dailyMaxMinutes = dailyMaxMinutes.coerceIn(0, 60),
        attemptsPerDay = attemptsPerDay.coerceIn(1, 10),
    )
}

@Serializable
data class TrainingOption(val id: String, val text: String)

/** 一道题。[answer] 是正确选项 id，只留在总控。 */
@Serializable
data class TrainingQuestion(val id: String, val stem: String, val options: List<TrainingOption>, val answer: String)

@Serializable
enum class ChallengeState { OPEN, PASSED, FAILED, EXPIRED }

@Serializable
data class TrainingChallenge(
    val id: String,
    val requestId: String,
    val nonce: String,
    val pkg: String,
    val policyRevision: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val questions: List<TrainingQuestion>,
    val state: ChallengeState = ChallengeState.OPEN,
    val closedAt: Long = 0,
)

/** 总控发的临时通行：今天的娱乐账上多 [minutes] 分钟，到 [expiresAt] 还没用完就作废。 */
@Serializable
data class TrainingPass(
    val passId: String,
    val challengeId: String,
    val pkg: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val minutes: Int,
    val policyRevision: Long,
)

/** 回执状态：PASSED / FAILED / EXPIRED / REJECTED / DENIED。 */
@Serializable
data class TrainingReceipt(
    val receiptId: String,
    val requestId: String,
    val challengeId: String,
    val state: String,
    val code: String = "",
    val at: Long,
    val message: String,
    val pass: TrainingPass? = null,
)

@Serializable
data class TrainingBook(
    val challenges: List<TrainingChallenge> = emptyList(),
    val receipts: List<TrainingReceipt> = emptyList(),
    val passes: List<TrainingPass> = emptyList(),
    val seq: Long = 0,
)

/** 发题 / 交卷的结果：要么新账本 + 值，要么一个错误码（账本可能也变了，比如把旧的一局作废）。 */
data class TrainingOutcome<T>(val book: TrainingBook, val value: T? = null, val code: String = "", val message: String = "", val retryAfterMs: Long? = null) {
    val ok: Boolean get() = value != null && code.isEmpty()
}

object Training {
    const val MAX_MINUTES_PER_PASS = 15
    const val QUESTIONS = 4
    const val PASS_MIN = 3
    const val CHALLENGE_TTL_MS = 5 * 60_000L
    /** 换来的分钟多久内要用（过了没用完就作废，不让人攒着）。 */
    const val PASS_TTL_MS = 60 * 60_000L
    const val FAIL_COOLDOWN_MS = 3 * 60_000L
    private const val KEEP_DAYS_MS = 3 * 24 * 3_600_000L

    // 错误码和墨灵侧 GateError 一致
    const val POLICY_DENIED = "POLICY_DENIED"
    const val SCOPE_DENIED = "SCOPE_DENIED"
    const val STRICT_MODE = "STRICT_MODE"
    const val NO_QUOTA = "NO_QUOTA"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val REPLAY = "REPLAY"
    const val EXPIRED = "EXPIRED"
    const val INVALID_ANSWER = "INVALID_ANSWER"
    const val REVISION_CONFLICT = "REVISION_CONFLICT"

    // ------------------------------------------------------------------ 能不能换

    /**
     * 此刻能不能拿训练换时间：null = 可以；否则返回 (错误码, 说明)。
     * 只有“挣来的娱乐用完了”这一条原因在拦时才可以——别的原因都不是几道题能抵的。
     */
    fun blocker(status: Status): Pair<String, String>? {
        if (status.paused) return POLICY_DENIED to "总控休假中，不用换"
        if (status.emergencyUntil != null) return POLICY_DENIED to "紧急放行中，不用换"
        if (!status.strict || status.reasons.isEmpty()) return POLICY_DENIED to "现在没拦你，不用换"
        val other = status.reasons.firstOrNull { it !is Reason.Budget }
        if (other is Reason.Quota) return NO_QUOTA to "今天的娱乐已经到每天总上限，训练也换不了"
        if (other != null) return STRICT_MODE to "现在拦你的是：${other.text}。这个不能用训练换"
        return null
    }

    /** 今天（按 [zone] 的自然日）已经换了几次 / 几分钟、开过几局。 */
    fun usedToday(book: TrainingBook, now: Long, zone: ZoneId): Triple<Int, Int, Int> {
        val day = dayOf(now, zone)
        val passes = book.passes.filter { dayOf(it.issuedAt, zone) == day }
        val attempts = book.challenges.count { dayOf(it.issuedAt, zone) == day }
        return Triple(passes.size, passes.sumOf { it.minutes }, attempts)
    }

    /** 加到“今天挣到的娱乐分钟”上的部分：今天发的、还没过期的通行。 */
    fun bonusMinutes(book: TrainingBook, now: Long, zone: ZoneId): Int {
        val day = dayOf(now, zone)
        return book.passes.filter { dayOf(it.issuedAt, zone) == day && now < it.expiresAt }.sumOf { it.minutes }
    }

    // ------------------------------------------------------------------ 发题

    /**
     * 发一局。同一个 [requestId] 重复来：那局还开着就原样返回，交过 / 过期了就 REPLAY。
     * 新开一局会把他手上没交的旧局作废（也算一次尝试），防止挑题。
     */
    fun issue(
        policy: TrainingPolicy,
        book0: TrainingBook,
        status: Status,
        pkg: String,
        requestId: String,
        now: Long,
        zone: ZoneId,
        rnd: java.util.Random,
    ): TrainingOutcome<TrainingChallenge> {
        val book = prune(expire(book0, now), now)
        book.challenges.firstOrNull { it.requestId == requestId }?.let { c ->
            return if (c.state == ChallengeState.OPEN) TrainingOutcome(book, c) else TrainingOutcome(book, code = REPLAY, message = "这一局已经结束了")
        }
        val p = policy.normalized()
        if (!p.enabled) return TrainingOutcome(book, code = POLICY_DENIED, message = "总控里没有开训练兑换")
        if (pkg.isBlank() || pkg !in p.packages) return TrainingOutcome(book, code = SCOPE_DENIED, message = "这个应用不在你选的训练兑换范围里")
        blocker(status)?.let { (code, msg) -> return TrainingOutcome(book, code = code, message = msg) }
        val (passes, minutes, attempts) = usedToday(book, now, zone)
        if (passes >= p.dailyMaxPasses || minutes >= p.dailyMaxMinutes) return TrainingOutcome(book, code = NO_QUOTA, message = "今天能换的次数 / 分钟用完了")
        if (attempts >= p.attemptsPerDay) return TrainingOutcome(book, code = RATE_LIMITED, message = "今天的训练局数用完了")
        val lastFail = book.challenges.filter { it.state == ChallengeState.FAILED }.maxOfOrNull { it.closedAt } ?: 0L
        if (now - lastFail < FAIL_COOLDOWN_MS) {
            val wait = FAIL_COOLDOWN_MS - (now - lastFail)
            return TrainingOutcome(book, code = RATE_LIMITED, message = "刚答错，歇 ${(wait / 60_000L) + 1} 分钟再来", retryAfterMs = wait)
        }
        val seq = book.seq + 1
        val c = TrainingChallenge(
            id = "tc-$seq-$now",
            requestId = requestId,
            nonce = hex(rnd, 16),
            pkg = pkg,
            policyRevision = p.revision,
            issuedAt = now,
            expiresAt = now + CHALLENGE_TTL_MS,
            questions = TrainingQuiz.make(rnd),
        )
        val closedOld = book.challenges.map { if (it.state == ChallengeState.OPEN) it.copy(state = ChallengeState.EXPIRED, closedAt = now) else it }
        return TrainingOutcome(book.copy(challenges = closedOld + c, seq = seq), c)
    }

    // ------------------------------------------------------------------ 交卷

    /**
     * 交卷。永远返回一张回执（也记下来）：同一个 [requestId] 再来返回同一张，不重复加时间。
     * 交卷那一刻再查一遍策略、范围、额度和此刻的拦截原因——发题后规则变了也不放。
     */
    fun submit(
        policy: TrainingPolicy,
        book0: TrainingBook,
        status: Status,
        requestId: String,
        challengeId: String,
        nonce: String,
        answers: Map<String, String>,
        now: Long,
        zone: ZoneId,
    ): TrainingOutcome<TrainingReceipt> {
        book0.receipts.firstOrNull { it.requestId == requestId }?.let { return TrainingOutcome(book0, it) }
        val book = prune(expire(book0, now), now)
        val p = policy.normalized()
        val seq = book.seq + 1
        fun receipt(state: String, code: String, msg: String, b: TrainingBook = book, pass: TrainingPass? = null): TrainingOutcome<TrainingReceipt> {
            val r = TrainingReceipt("tr-$seq-$now", requestId, challengeId, state, code, now, msg, pass)
            return TrainingOutcome(b.copy(receipts = b.receipts + r, seq = seq), r)
        }
        val c = book.challenges.firstOrNull { it.id == challengeId } ?: return receipt("REJECTED", REPLAY, "没有这一局")
        if (c.nonce != nonce) return receipt("REJECTED", REPLAY, "这一局对不上")
        if (c.state == ChallengeState.EXPIRED || now > c.expiresAt) {
            val b = book.copy(challenges = book.challenges.map { if (it.id == c.id && it.state == ChallengeState.OPEN) it.copy(state = ChallengeState.EXPIRED, closedAt = now) else it })
            return receipt("EXPIRED", EXPIRED, "超时了，这一局作废", b)
        }
        if (c.state != ChallengeState.OPEN) return receipt("REJECTED", REPLAY, "这一局已经交过了")
        if (!p.enabled || c.pkg !in p.packages) return receipt("REJECTED", POLICY_DENIED, "训练兑换的设置变了，这一局作废", close(book, c.id, ChallengeState.EXPIRED, now))
        if (c.policyRevision != p.revision) return receipt("REJECTED", REVISION_CONFLICT, "训练兑换的设置改过了，这一局作废", close(book, c.id, ChallengeState.EXPIRED, now))
        val byId = c.questions.associateBy { it.id }
        if (answers.size > c.questions.size || answers.any { (q, o) -> byId[q]?.options?.none { it.id == o } != false }) {
            return receipt("REJECTED", INVALID_ANSWER, "答案对不上题目", close(book, c.id, ChallengeState.FAILED, now))
        }
        val right = c.questions.count { answers[it.id] == it.answer }
        if (right < PASS_MIN) {
            return receipt("FAILED", "", "对了 $right / ${c.questions.size} 道，没过（要对 $PASS_MIN 道）。歇一会儿再来", close(book, c.id, ChallengeState.FAILED, now))
        }
        // 过了：此刻还得是“只因为挣来的娱乐用完了”才放
        val closed = close(book, c.id, ChallengeState.PASSED, now)
        blocker(status)?.let { (code, msg) -> return receipt("DENIED", code, "答对了 $right 道，但$msg", closed) }
        val (passes, minutes, _) = usedToday(book, now, zone)
        val give = minOf(p.minutesPerPass, p.dailyMaxMinutes - minutes)
        if (passes >= p.dailyMaxPasses || give <= 0) return receipt("DENIED", NO_QUOTA, "答对了 $right 道，但今天能换的已经用完了", closed)
        val pass = TrainingPass("tp-$seq-$now", c.id, c.pkg, now, now + PASS_TTL_MS, give, c.policyRevision)
        return receipt("PASSED", "", "对了 $right / ${c.questions.size} 道：今天的娱乐多 $give 分钟（一小时内有效，用完照旧）", closed.copy(passes = closed.passes + pass), pass)
    }

    fun receipt(book: TrainingBook, requestId: String): TrainingReceipt? = book.receipts.firstOrNull { it.requestId == requestId }

    // ------------------------------------------------------------------ 内部

    private fun close(b: TrainingBook, id: String, s: ChallengeState, now: Long) =
        b.copy(challenges = b.challenges.map { if (it.id == id) it.copy(state = s, closedAt = now) else it })

    private fun expire(b: TrainingBook, now: Long) =
        b.copy(challenges = b.challenges.map { if (it.state == ChallengeState.OPEN && now > it.expiresAt) it.copy(state = ChallengeState.EXPIRED, closedAt = it.expiresAt) else it })

    private fun prune(b: TrainingBook, now: Long) = b.copy(
        challenges = b.challenges.filter { now - it.issuedAt < KEEP_DAYS_MS },
        receipts = b.receipts.filter { now - it.at < KEEP_DAYS_MS },
        passes = b.passes.filter { now - it.issuedAt < KEEP_DAYS_MS },
    )

    private fun dayOf(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    private fun hex(rnd: java.util.Random, n: Int) = (1..n).joinToString("") { "%x".format(rnd.nextInt(16)) }
}

/**
 * 题库：程序当场出题，答案是算出来的，不靠模型判分。四类：心算、数列、百分比、推理（三段论 / 条件句）。
 * 每题四个选项，选项打乱。
 */
object TrainingQuiz {
    private val nouns = listOf("猫", "鸟", "画家", "学生", "医生", "跑者", "诗人", "厨师", "司机", "棋手")

    fun make(rnd: java.util.Random): List<TrainingQuestion> =
        listOf(arithmetic(rnd, "q1"), sequence(rnd, "q2"), percent(rnd, "q3"), logic(rnd, "q4"))

    private fun numeric(rnd: java.util.Random, id: String, stem: String, answer: Int, near: List<Int>): TrainingQuestion {
        val wrong = near.filter { it != answer && it >= 0 }.distinct().shuffled(rnd).take(3).toMutableList()
        var k = 2
        while (wrong.size < 3) { val v = answer + k * (if (rnd.nextBoolean()) 1 else -1); if (v != answer && v >= 0 && v !in wrong) wrong += v; k++ }
        return options(rnd, id, stem, answer.toString(), wrong.map { it.toString() })
    }

    private fun options(rnd: java.util.Random, id: String, stem: String, right: String, wrong: List<String>): TrainingQuestion {
        val all = (wrong + right).shuffled(rnd)
        val opts = all.mapIndexed { i, t -> TrainingOption("$id-${'a' + i}", t) }
        return TrainingQuestion(id, stem, opts, opts.first { it.text == right }.id)
    }

    fun arithmetic(rnd: java.util.Random, id: String): TrainingQuestion {
        val a = 12 + rnd.nextInt(38); val b = 3 + rnd.nextInt(7); val c = 10 + rnd.nextInt(90)
        val ans = a * b + c
        return numeric(rnd, id, "心算：$a × $b + $c = ?", ans, listOf(ans + 10, ans - 10, ans + b, ans - b, (a + 1) * b + c, a * b - c))
    }

    fun sequence(rnd: java.util.Random, id: String): TrainingQuestion {
        return if (rnd.nextBoolean()) {
            val s = 2 + rnd.nextInt(19); val d = 3 + rnd.nextInt(10)
            val xs = (0..3).map { s + it * d }
            val ans = s + 4 * d
            numeric(rnd, id, "数列：${xs.joinToString("，")}，下一个是？", ans, listOf(ans + 1, ans - 1, ans + d, ans - d))
        } else {
            val s = 1 + rnd.nextInt(5); val r = 2 + rnd.nextInt(2)
            val xs = (0..3).map { s * pow(r, it) }
            val ans = s * pow(r, 4)
            numeric(rnd, id, "数列：${xs.joinToString("，")}，下一个是？", ans, listOf(ans + xs[3], ans - xs[3], xs[3] * (r + 1), ans + r))
        }
    }

    fun percent(rnd: java.util.Random, id: String): TrainingQuestion {
        val base = 20 * (2 + rnd.nextInt(24)); val pct = listOf(5, 10, 15, 20, 25, 30, 40, 50, 75)[rnd.nextInt(9)]
        val ans = base * pct / 100
        return numeric(rnd, id, "$base 的 $pct% 是多少？", ans, listOf(ans + 5, ans - 5, base * pct / 10, ans * 2, ans + base / 10))
    }

    private data class Form(val text: String, val answer: String)

    fun logic(rnd: java.util.Random, id: String): TrainingQuestion {
        val n = nouns.shuffled(rnd)
        val (a, b, c) = Triple(n[0], n[1], n[2])
        val forms = listOf(
            Form("所有$a 都是$b；小周是$a。小周是$b 吗？", "一定是"),
            Form("所有$a 都是$b；小周是$b。小周是$a 吗？", "不一定"),
            Form("没有一个$a 是$b；小周是$a。小周是$b 吗？", "一定不是"),
            Form("如果小周是$a，他就是$b；小周不是$b。小周是$a 吗？", "一定不是"),
            Form("如果小周是$a，他就是$b；小周是$b。小周是$a 吗？", "不一定"),
            Form("有些$a 是$b，有些$b 是$c；小周是$a。小周是$c 吗？", "不一定"),
            Form("所有$a 都是$b，所有$b 都是$c；小周是$a。小周是$c 吗？", "一定是"),
        )
        val f = forms[rnd.nextInt(forms.size)]
        val all = listOf("一定是", "一定不是", "不一定", "题目自相矛盾")
        return options(rnd, id, "推理：${f.text}", f.answer, all - f.answer)
    }

    private fun pow(b: Int, e: Int): Int { var r = 1; repeat(e) { r *= b }; return r }
}
