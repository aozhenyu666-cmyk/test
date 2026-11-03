package com.dshdriver.core

/**
 * 运行与推进日志。只认五个状态，不拿"聊了几轮"充当有效思考：
 *   GENERATED → DISPLAYED（手机上真的弹出了）→ REPLIED → ADVANCED（确实推进了）
 *   RESUMED：这张卡曾因切屏被冻结为断点，后来被接回并答上了
 * 每一步只能在前一步之后发生；跳步不补写，而是如实记一个 flag。
 *
 * 另按三级数"有没有在思考"（点 A/B 是为了把人拉回来，不算思考）：
 *   醒 awake / 回 responded / 想 grounded（主指标）
 */
class Telemetry(private val sink: (Map<String, Any?>) -> Unit = {}) {

    class Item(val id: String, val intent: String, val kind: String, val source: String, val generatedAt: Long) {
        var displayedAt: Long? = null
        var awakeAt: Long? = null
        var repliedAt: Long? = null
        var advancedAt: Long? = null
        var advancedWhy: String? = null
        var interrupted = 0
        var resumedAt: Long? = null
        val flags = mutableListOf<String>()
    }

    data class Summary(
        val generated: Int, val displayed: Int, val replied: Int, val advanced: Int, val resumed: Int,
        val interruptions: Int, val displayedUnproven: Int, val advanceRate: Double,
        val awake: Int, val responded: Int, val grounded: Int,
    )

    val items = LinkedHashMap<String, Item>()
    val rows = mutableListOf<Map<String, Any?>>()

    private fun log(row: Map<String, Any?>) {
        rows += row
        runCatching { sink(row) } // 写盘失败不影响驾驶
    }

    fun generated(card: Card, at: Long) {
        if (items.containsKey(card.id)) return
        items[card.id] = Item(card.id, card.intent, card.kind, card.source, at)
        log(mapOf("at" to at, "step" to "GENERATED", "card" to card.id, "intent" to card.intent, "kind" to card.kind, "source" to card.source, "text" to card.text))
    }

    fun displayed(cardId: String, at: Long, via: String): Boolean {
        val it = items[cardId] ?: return false
        if (it.displayedAt != null) return false
        it.displayedAt = at
        log(mapOf("at" to at, "step" to "DISPLAYED", "card" to cardId, "via" to via))
        return true
    }

    /** 卡亮出后有对话迹象；inWindow=false 时不记（迟到的回复由 replied 补记）。 */
    fun awake(cardId: String, at: Long, inWindow: Boolean): Boolean {
        val it = items[cardId] ?: return false
        if (it.awakeAt != null || it.displayedAt == null || !inWindow) return false
        it.awakeAt = at
        log(mapOf("at" to at, "step" to "AWAKE", "card" to cardId))
        return true
    }

    fun replied(cardId: String, at: Long, text: String, latencyMs: Long, score: Double): Boolean {
        val it = items[cardId] ?: return false
        if (it.repliedAt != null) return false
        if (it.displayedAt == null) it.flags += "displayed_unproven" // 有回复却没有可见证明：不补写 DISPLAYED
        it.repliedAt = at
        if (it.awakeAt == null) it.awakeAt = at
        log(mapOf("at" to at, "step" to "REPLIED", "card" to cardId, "text" to text, "latencyMs" to latencyMs, "score" to score))
        return true
    }

    fun advanced(cardId: String, at: Long, why: String): Boolean {
        val it = items[cardId] ?: return false
        if (it.repliedAt == null || it.advancedAt != null) return false
        it.advancedAt = at
        it.advancedWhy = why
        log(mapOf("at" to at, "step" to "ADVANCED", "card" to cardId, "why" to why))
        return true
    }

    fun interrupted(cardId: String, at: Long, reason: String) {
        val it = items[cardId] ?: return
        it.interrupted++
        log(mapOf("at" to at, "step" to "INTERRUPTED_BY_SWITCH", "card" to cardId, "reason" to reason))
    }

    fun resumed(cardId: String, at: Long): Boolean {
        val it = items[cardId] ?: return false
        if (it.interrupted == 0 || it.resumedAt != null) return false
        it.resumedAt = at
        log(mapOf("at" to at, "step" to "RESUMED", "card" to cardId))
        return true
    }

    fun note(at: Long, kind: String, data: Map<String, Any?> = emptyMap()) {
        log(mapOf("at" to at, "step" to "NOTE", "kind" to kind) + data)
    }

    fun summary(): Summary {
        val all = items.values
        val generated = all.size
        val advanced = all.count { it.advancedAt != null }
        return Summary(
            generated = generated,
            displayed = all.count { it.displayedAt != null },
            replied = all.count { it.repliedAt != null },
            advanced = advanced,
            resumed = all.count { it.resumedAt != null },
            interruptions = all.sumOf { it.interrupted },
            displayedUnproven = all.count { "displayed_unproven" in it.flags },
            advanceRate = if (generated == 0) 0.0 else Math.round(advanced * 100.0 / generated) / 100.0,
            awake = all.count { it.awakeAt != null },
            responded = advanced,
            grounded = all.count { isGrounded(it.advancedWhy) },
        )
    }

    companion object {
        private val GROUNDED = setOf("basis_given", "substantive", "conclusion_grounded")

        /** 这个推进理由算不算"有依据的思考"。 */
        fun isGrounded(why: String?) = why in GROUNDED
    }
}
