package com.yishou.app.card

/**
 * 入口抽题的循环池：题做过不等于报废。
 *
 * 以前的写法是 `usedAt IS NULL`，做完一道就把这道永久划掉，题库做完那天入口就再也出不了题，
 * 只能靠“等 10 分钟换 5 分钟”兜底——那是把题库耗尽当成了用户的错。
 * 现在按“最久没做”接回来，并且给刚做完的一段冷却，让重出的是旧题而不是刚才那道。
 *
 * 全在本机算，不看模型；题库只有一道题时也照样出得题（冷却优先让位）。
 */
data class BankSlot(val id: Long, val usedAt: Long?, val createdAt: Long)

object BankCycle {

    /** 刚做完的题先让开两小时：马上重出会变成反刍，也容易被当成刷熟练度 */
    const val COOL_MS = 2 * 60 * 60_000L

    /**
     * 挑这一道。顺序：
     * 1. 没做过的（先出没见过的）；
     * 2. 做过但过了冷却的（最久没做的先来）；
     * 3. 全都在冷却里：挑最久没做的那道——宁可重出，也不让人卡在“没题”。
     *
     * exclude 是这一回入口里已经用掉的（答错/放弃过的），只在还有别的题时生效。
     */
    fun pick(slots: List<BankSlot>, now: Long, exclude: Set<Long> = emptySet(), coolMs: Long = COOL_MS): BankSlot? {
        if (slots.isEmpty()) return null
        val free = slots.filter { it.id !in exclude }.ifEmpty { slots }
        val fresh = free.filter { it.usedAt == null }.minByOrNull { it.createdAt }
        if (fresh != null) return fresh
        val cooled = free.filter { it.usedAt != null && now - it.usedAt >= coolMs }
        if (cooled.isNotEmpty()) return cooled.minByOrNull { it.usedAt ?: 0L }
        return free.minByOrNull { it.usedAt ?: 0L }
    }

    /** 这一道是不是还在冷却里（界面上可以说一句“这题做过”） */
    fun cooling(slot: BankSlot, now: Long, coolMs: Long = COOL_MS): Boolean =
        slot.usedAt != null && now - slot.usedAt!! < coolMs
}
