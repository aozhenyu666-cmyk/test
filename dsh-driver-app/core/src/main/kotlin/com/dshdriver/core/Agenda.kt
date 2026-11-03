package com.dshdriver.core

/**
 * 跨会话思考议程：没接上的断点、没补上的依据、没把握的结论，存成待办；下次开场主动从里面挑题。
 * 纯规则，不调模型。落盘由 APP 负责（agenda.json）。
 * 结论类待办必须带你的原话，原话不在你说过的话里就不收（Mika MemoryExtractor 的校验思路）。
 */
class Agenda(items: List<Item> = emptyList(), private val max: Int = 30, private val newId: () -> String = defaultId()) {

    data class Item(
        val id: String,
        val text: String,
        val anchor: String,
        val source: String,
        val quote: String? = null,
        var missing: String? = null,
        val createdAt: Long,
        var lastSeenAt: Long,
        var touches: Int = 1,
        var skips: Int = 0,
        var status: String = "open",
        var closedAt: Long? = null,
    )

    val items: MutableList<Item> = items.toMutableList()

    fun open(): List<Item> = items.filter { it.status == "open" }

    fun add(source: String, text: String, at: Long, anchor: String? = null, quote: String? = null, missing: String? = null, utterances: List<String> = emptyList()): Item? {
        if (source !in SOURCES || text.isBlank()) return null
        if (source == "conclusion_doubt") {
            val q = quote?.trim().orEmpty()
            if (q.isEmpty() || utterances.none { it.contains(q) }) return null
        }
        val a = (anchor ?: text).take(40)
        val same = open().find { Similar.jaccard(it.anchor, a) >= 0.8 }
        if (same != null) {
            same.lastSeenAt = at
            same.touches++
            if (missing != null) same.missing = missing
            return same
        }
        val row = Item(newId(), text.take(80), a, source, quote?.take(120), missing, at, at)
        items += row
        trim()
        return row
    }

    /** 按这次的主题/任务挑最相关的一条 open 待办；查询词被覆盖得不到 minScore 就返回 null。 */
    fun match(topic: String, task: String, minScore: Double = 0.15): Item? {
        val q = Similar.terms("$topic $task")
        if (q.isEmpty()) return null
        var best: Item? = null
        var bestScore = 0.0
        for (x in open()) {
            val t = Similar.terms("${x.anchor} ${x.text}")
            val score = q.count { it in t }.toDouble() / q.size
            if (score >= minScore && (best == null || score > bestScore || (score == bestScore && x.lastSeenAt > best.lastSeenAt))) {
                best = x
                bestScore = score
            }
        }
        return best
    }

    fun done(id: String, at: Long): Item? = open().find { it.id == id }?.also { it.status = "done"; it.closedAt = at }

    /** 开场被跳过：连续 3 次就不再纠缠。 */
    fun skip(id: String, at: Long): Item? = open().find { it.id == id }?.also {
        it.skips++
        it.lastSeenAt = at
        if (it.skips >= MAX_SKIPS) it.status = "dropped"
    }

    private fun trim() {
        while (items.size > max) {
            val closed = items.indexOfFirst { it.status != "open" }
            if (closed >= 0) { items.removeAt(closed); continue }
            items.remove(items.minBy { it.lastSeenAt })
        }
    }

    companion object {
        val SOURCES = setOf("unresumed", "missing", "conclusion_doubt")
        const val MAX_SKIPS = 3
        private fun defaultId(): () -> String { var n = 0; return { "a${System.currentTimeMillis().toString(36)}${(n++).toString(36)}" } }
    }
}
