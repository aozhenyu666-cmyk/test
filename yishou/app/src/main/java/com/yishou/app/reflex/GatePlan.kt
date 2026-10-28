package com.yishou.app.reflex

/**
 * 入口这一次出什么。顺序：思维线程的断点 → 题库题 → 现出一题（题库没题时让模型出、锁好再给）→ 低能量路（默写触发器）→ 等待兜底。
 * 到期的触发器不单独占一关，而是在主题目之前闪一屏默写（回响）。
 */
enum class Main { STRAND, BANK, FRESH, LOW_ENERGY, WAIT }

data class GateInput(
    /** 有一条没定完的线程，并且断点那一问已经锁好（或判卷在，能马上出） */
    val strandReady: Boolean,
    val bankReady: Boolean,
    val judgeOk: Boolean,
    /** 有可以默写的触发器 */
    val hasReflex: Boolean,
    /** 这一回入口里已经连着答错几次 */
    val wrongInRow: Int,
    /** 题库没题时，能让模型现出一道锁题（文字模型配好了） */
    val freshReady: Boolean = false,
)

object GatePlan {

    /** 低能量路放行分钟数；正常答对是 15 */
    const val LOW_PASS_MIN = 5
    const val FULL_PASS_MIN = 15

    fun pick(i: GateInput): Main = when {
        i.judgeOk && i.wrongInRow < 2 && i.strandReady -> Main.STRAND
        i.judgeOk && i.wrongInRow < 2 && i.bankReady -> Main.BANK
        i.judgeOk && i.wrongInRow < 2 && i.freshReady -> Main.FRESH
        i.hasReflex -> Main.LOW_ENERGY
        else -> Main.WAIT
    }
}
