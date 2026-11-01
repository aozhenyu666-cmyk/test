package com.yishou.app.reflex

/**
 * 入口这一次出什么。顺序：思维线程的断点 → 题库题 → 低能量路（默写触发器）。
 * 到期的触发器不单独占一关，而是在主题目之前闪一屏默写（回响）。
 *
 * 没有“都没得做就罚站”这一档：题库用完由循环池接回来（见 [com.yishou.app.card.BankCycle]），
 * 判卷不在就换本机能判的路。进不了题只能是因为一个触发器都没有、线程也没起头——
 * 那时也该让人说一句“看完做什么”就走，不该拿等待当门槛。
 */
enum class Main { STRAND, BANK, LOW_ENERGY }

data class GateInput(
    /** 有一条没定完的线程，并且断点那一问已经锁好（或判卷在，能马上出） */
    val strandReady: Boolean,
    val bankReady: Boolean,
    val judgeOk: Boolean,
    /** 有可以默写的触发器 */
    val hasReflex: Boolean,
    /** 这一回入口里已经连着答错几次 */
    val wrongInRow: Int,
)

object GatePlan {

    /** 低能量路放行分钟数 */
    const val LOW_PASS_MIN = 5
    const val FULL_PASS_MIN = 15

    /**
     * 连错不再把人踢走：错满两次只降一档（另开一题、给更多提示），
     * 因为“这一题不会”不等于“这一回不配进去”。
     */
    fun pick(i: GateInput): Main = when {
        i.judgeOk && i.strandReady -> Main.STRAND
        i.judgeOk && i.bankReady -> Main.BANK
        i.hasReflex -> Main.LOW_ENERGY
        i.bankReady -> Main.BANK
        else -> Main.LOW_ENERGY
    }
}
