package cn.companion.pet.core.voice

import cn.companion.pet.core.moment.Moment
import cn.companion.pet.core.moment.MomentSignals
import cn.companion.pet.core.moment.MomentWatcher

/** 一次「要不要开口、开口说什么」的结论。 */
sealed interface TurnPlan {

    /** 现在不该开口。手里攒的话继续留着，不许划掉。 */
    data object StaySilent : TurnPlan

    /** 说手里攒好的那一句（意图优先）。 */
    data class SpeakIntent(
        val text: String,
        val intentId: String,
        val source: String,
        /** 认出来的时刻，交给调用方记账/统计。 */
        val moments: Set<Moment>,
    ) : TurnPlan
}

/**
 * 开口决策的唯一入口：**手里有话优先说手里的，别走规则临时编**。
 *
 * 优先级（不可重排）：
 * 1. 该闭嘴（打字 / 全屏视频 / 三分钟内刚说过）→ 沉默，且**不划掉**手里的话；
 * 2. 手里有能说的意图，且此刻认得出至少一个时刻 → 说那句意图；
 * 3. 其它 → 沉默，交回给调用方按原有规则处理。
 *
 * 纯函数、无 I/O：把判定从 PetBrain 里拆出来，才能被 JVM 测钉住。
 */
object VoiceTurnPlanner {

    fun plan(
        now: Long,
        signals: MomentSignals,
        queue: VoiceIntentQueue,
        ledger: SayingLedger,
    ): TurnPlan {
        // 1) 闭嘴优先：哪怕手里的话很好，也不许打断打字/看视频/刚说完的余温。
        if (MomentWatcher.mustStaySilent(signals)) return TurnPlan.StaySilent

        // 2) 意图优先。avoid 用账本硬拦，撞车就换下一件而不是索性沉默。
        val moments = MomentWatcher.detect(signals)
        if (moments.isEmpty()) return TurnPlan.StaySilent
        val intent = queue.next(now) { ledger.isTooSimilar(it) } ?: return TurnPlan.StaySilent
        return TurnPlan.SpeakIntent(
            text = intent.summary,
            intentId = intent.id,
            source = intent.source,
            moments = moments,
        )
    }
}
