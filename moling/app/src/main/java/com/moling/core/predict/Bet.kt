package com.moling.core.predict

import kotlinx.serialization.Serializable

/*
 * 思维选择赌局（v13 第十一批 R5）。
 *
 * 她说“我猜你会选 A / 我猜你愿意接这一轮”，这不算权柄，也不得变成逼他回应的工具：
 *  - 一笔赌局有 id / sessionId / 题目 / 选项 / 截止时间 / 她的猜测 / 观测证据；
 *  - 只有观测到结果才结算；看不到就 UNOBSERVABLE（不是输），他取消就是 CANCELLED（也不是输）；
 *  - 同一个 id 在卡片 / 电台 / 弹幕反复展示，只算一笔；重复结算不会多记一次；
 *  - 玩笑赌不显示伪概率、不进 Brier；只有明确把握（p）的可评分赌局才进自己的 Brier 集合，
 *    和旧的娱乐分钟预测分开算（旧的对账仍在 [Prediction]）；
 *  - 输赢不改 Powers、不改授权、不剥夺退出。
 */

@Serializable
enum class BetKind(val label: String) {
    /** “我赌你…”：玩笑，不显示概率，不进 Brier。 */
    FUN("猜着玩"),
    /** 有明确把握和结果定义的可评分预测。 */
    SCORED("可评分"),
}

@Serializable
enum class BetState(val label: String) {
    OPEN("还开着"),
    /** 观测到的选项就是她猜的那个。 */
    WON("猜对了"),
    LOST("猜错了"),
    /** 到截止 / 看不到结果：未知，不算输。 */
    UNOBSERVABLE("看不到结果"),
    /** 他取消 / 关掉赌局：不算输，旧记录保留。 */
    CANCELLED("取消了"),
}

@Serializable
data class BetOption(val id: String, val label: String)

/** [pick] = 她猜的选项 id；[p] = 可评分赌局里的把握（0..1，-1 = 不适用）。 */
@Serializable
data class ChoiceBet(
    val id: String,
    val kind: BetKind,
    /** 这次赌局属于哪个会话（电台 sessionId、卡片 id、弹幕 id）；同一会话重复展示用同一个 id。 */
    val sessionId: String,
    val prompt: String,
    val options: List<BetOption>,
    val pick: String,
    val deadline: Long,
    val madeAt: Long,
    val p: Double = -1.0,
    val state: BetState = BetState.OPEN,
    /** 观测到的选项 id；null = 还没观测到。 */
    val observedOptionId: String? = null,
    val settledAt: Long = 0,
    /** 依据 / 为什么看不到。 */
    val evidence: String = "",
) {
    val settled: Boolean get() = state != BetState.OPEN
    val guessed: BetOption? get() = options.firstOrNull { it.id == pick }
}

object Bets {
    /** 只留最近这么多笔。 */
    const val KEEP = 60

    fun optionId(betId: String, label: String): String = "$betId:${label.hashCode().toUInt().toString(16)}"

    /**
     * 开一笔；同一 [id] 已经存在就原样返回（换卡片 / 弹幕 / 电台展示不会多记一笔）。
     * [guess] 是选项文字，必须在这道题里；不在就返回 null。
     */
    fun open(
        existing: List<ChoiceBet>,
        id: String,
        sessionId: String,
        prompt: String,
        optionLabels: List<String>,
        guess: String,
        deadline: Long,
        now: Long,
        kind: BetKind = BetKind.FUN,
        p: Double = -1.0,
        evidence: String = "",
    ): Pair<List<ChoiceBet>, ChoiceBet>? {
        existing.firstOrNull { it.id == id }?.let { return existing to it }
        val labels = optionLabels.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)
        if (labels.size < 2 || prompt.isBlank()) return null
        val options = labels.map { BetOption(optionId(id, it), it) }
        val pick = options.firstOrNull { it.label == guess.trim() } ?: return null
        val bet = ChoiceBet(id, kind, sessionId, prompt.take(80), options, pick.id, deadline.coerceAtLeast(now + 1_000L), now, if (kind == BetKind.SCORED) p.coerceIn(0.05, 0.95) else -1.0, evidence = evidence.take(60))
        return (existing + bet).takeLast(KEEP) to bet
    }

    /** 观测到结果：只有还开着、且观测到的是这道题里的选项才结算；重复结算原样返回。 */
    fun settle(b: ChoiceBet, observedOptionId: String?, now: Long, evidence: String = ""): ChoiceBet {
        if (b.state != BetState.OPEN) return b
        if (observedOptionId == null) return unobservable(b, now, evidence.ifBlank { "没有观测到结果" })
        if (b.options.none { it.id == observedOptionId }) return unobservable(b, now, evidence.ifBlank { "观测到的不是这道题的选项" })
        return b.copy(
            state = if (observedOptionId == b.pick) BetState.WON else BetState.LOST,
            observedOptionId = observedOptionId,
            settledAt = now,
            evidence = evidence.take(80),
        )
    }

    fun unobservable(b: ChoiceBet, now: Long, why: String = "看不到结果"): ChoiceBet =
        if (b.state != BetState.OPEN) b else b.copy(state = BetState.UNOBSERVABLE, settledAt = now, evidence = why.take(80))

    /** 他关掉 / 取消：不算输。 */
    fun cancel(b: ChoiceBet, now: Long): ChoiceBet =
        if (b.state != BetState.OPEN) b else b.copy(state = BetState.CANCELLED, settledAt = now, evidence = "他取消了")

    /** 到截止还没观测到 → 未知（不是输）。 */
    fun expire(b: ChoiceBet, now: Long): ChoiceBet =
        if (b.state == BetState.OPEN && now >= b.deadline) unobservable(b, now, "到截止时间还没看到结果") else b

    fun expireAll(list: List<ChoiceBet>, now: Long): List<ChoiceBet> = list.map { expire(it, now) }

    /** 只算可评分、有明确把握、已经知道输赢的赌局。0 最好，0.25 等于瞎猜；没有返回 -1。 */
    fun brier(list: List<ChoiceBet>): Double {
        val done = list.filter { it.kind == BetKind.SCORED && it.p in 0.0..1.0 && (it.state == BetState.WON || it.state == BetState.LOST) }
        if (done.isEmpty()) return -1.0
        return done.sumOf {
            val o = if (it.state == BetState.WON) 1.0 else 0.0
            (it.p - o) * (it.p - o)
        } / done.size
    }

    /** 给人看的一行。 */
    fun line(b: ChoiceBet): String {
        val guess = b.guessed?.label.orEmpty()
        val tail = when (b.state) {
            BetState.OPEN -> "还开着（到 " + hm(b.deadline) + "）"
            BetState.WON -> "猜对了"
            BetState.LOST -> "猜错了" + (b.observedOptionId?.let { id -> "（你选了 " + (b.options.firstOrNull { it.id == id }?.label ?: "?") + "）" }.orEmpty())
            BetState.UNOBSERVABLE -> "看不到结果" + if (b.evidence.isNotBlank()) "（${b.evidence}）" else ""
            BetState.CANCELLED -> "取消了（不算输）"
        }
        val prob = if (b.kind == BetKind.SCORED && b.p >= 0) "（把握 ${(b.p * 100).toInt()}%）" else ""
        return "我猜你会选「$guess」$prob：$tail"
    }

    /** 错开算：从旧娱乐分钟预测里区分出来（旧 Brier 继续用 Predict.brier）。 */
    fun scoredOnly(list: List<ChoiceBet>): List<ChoiceBet> = list.filter { it.kind == BetKind.SCORED }

    private fun hm(at: Long): String =
        java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
}
