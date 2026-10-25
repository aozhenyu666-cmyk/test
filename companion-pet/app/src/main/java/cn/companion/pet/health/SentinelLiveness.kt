package cn.companion.pet.health

/*
 * “哨兵到底还活着吗”的纯判定（可在 JVM 单测里推进时间，不依赖 Android）。
 *
 * 报告 §7/§8：客户端只有在**哨兵确认失联**、且自己仍然健康时，才能按本地规则有限兜底锁屏。
 * 这里刻意把三种情况分开，避免“没装哨兵”被当成“哨兵掉线”而抢着锁屏：
 *
 *   1. 从来没接通过  → 未接入（UNREGISTERED），不是失联，不兜底。
 *   2. 以前通过、最近一次失败但没超确认预算 → 还在预算内，不动。
 *   3. 以前通过、且超过确认预算没有成功回应 → 确认失联，可以兜底。
 */
object SentinelLiveness {

    enum class Verdict {
        /** 从没接通过：这是“没装”，不是“掉线”。界面写「未接入」。 */
        NEVER_LINKED,

        /** 最近有成功回应，仍在确认预算内。 */
        ALIVE,

        /** 曾经线上过，但已经超过确认预算没有成功回应：确认失联。 */
        UNRESPONSIVE,
    }

    fun verdict(state: SentinelLink.LinkState, nowMono: Long, confirmMs: Long = 30_000): Verdict {
        if (!state.everOk) return Verdict.NEVER_LINKED
        val baseline = if (state.lastOk) state.lastAtMono else state.everOkAtMono
        if (baseline <= 0) return Verdict.NEVER_LINKED
        val silentFor = nowMono - baseline
        return if (silentFor > confirmMs) Verdict.UNRESPONSIVE else Verdict.ALIVE
    }

    fun unresponsive(state: SentinelLink.LinkState, nowMono: Long, confirmMs: Long = 30_000): Boolean =
        verdict(state, nowMono, confirmMs) == Verdict.UNRESPONSIVE
}
