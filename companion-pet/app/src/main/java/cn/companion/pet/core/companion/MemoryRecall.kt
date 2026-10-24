package cn.companion.pet.core.companion

import cn.companion.pet.core.memory.ProfileRules
import cn.companion.pet.core.text.UnicodeText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class RecallItem(
    val text: String,
    val sourceLabel: String,
    val day: String,
    val occurredAt: Long,
    val score: Int,
)

data class RecallLimits(
    val maxItems: Int = 6,
    val maxChars: Int = 800,
)

/**
 * 本地关键词/中文二元组召回。不引入向量库或额外模型服务。
 *
 * 只从“真实交流 + 用户保存的念头 + 结构化事实 + 旧流水”里选；命中 tombstone、
 * 已被替代或已删除的事实不进入结果。相关度不够就不补满。
 */
class MemoryRecall(
    private val events: EventLog,
    private val facts: FactStore,
    private val zone: ZoneId = events.zone,
) {
    fun recall(
        query: String,
        now: Long,
        tombstones: Set<String> = emptySet(),
        excludeTexts: Set<String> = emptySet(),
        limits: RecallLimits = RecallLimits(),
    ): List<RecallItem> {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return emptyList()

        val candidates = mutableListOf<RecallItem>()
        val snapshot = events.all()
        val visibility = EventVisibility(snapshot, tombstones)
        facts.live().forEach { fact ->
            if (!visibility.allows(fact)) return@forEach
            val label = if (fact.confidence == Confidence.SAID) "你说过" else "尚未确认"
            candidates += RecallItem(
                text = fact.text,
                sourceLabel = label,
                day = dayOf(fact.createdAt),
                occurredAt = fact.createdAt,
                score = 0,
            )
        }
        snapshot.forEach { event ->
            if (event.kind !in RECALL_KINDS) return@forEach
            val normalized = ProfileRules.normalize(event.text)
            if (normalized.isEmpty() || !visibility.allows(event)) return@forEach
            candidates += RecallItem(
                text = event.text,
                sourceLabel = "交流",
                day = event.day,
                occurredAt = event.occurredAt,
                score = 0,
            )
        }

        val excluded = excludeTexts.map { ProfileRules.normalize(it) }.toSet()
        return candidates
            .filter { ProfileRules.normalize(it.text) !in excluded }
            .map { item ->
                val overlap = queryTokens.count { it in tokens(item.text) }
                if (overlap == 0) return@map item.copy(score = 0)
                val daysAgo = ((now - item.occurredAt) / DAY_MILLIS).coerceAtLeast(0L)
                val recency = (7L - daysAgo).coerceIn(0L, 7L)
                item.copy(score = (overlap * 10L + recency).toInt())
            }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<RecallItem> { it.score }.thenByDescending { it.occurredAt }.thenBy { it.text })
            .distinctBy { ProfileRules.normalize(it.text) }
            .take(limits.maxItems)
            .let { trimByChars(it, limits.maxChars) }
    }

    private fun trimByChars(items: List<RecallItem>, maxChars: Int): List<RecallItem> {
        val out = mutableListOf<RecallItem>()
        var used = 0
        items.forEach { item ->
            val cost = UnicodeText.count(item.text) + 1
            if (used + cost > maxChars) return@forEach
            out += item
            used += cost
        }
        if (out.isEmpty() && items.isNotEmpty()) {
            val first = items.first()
            out += first.copy(text = UnicodeText.truncate(first.text, maxChars))
        }
        return out
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private val RECALL_KINDS = setOf(
            EventKind.USER_INPUT,
            EventKind.NOTE,
            EventKind.CORRECTION,
            EventKind.LEGACY_LINE,
        )

        private val LATIN = Regex("[a-z0-9_]+")
        private val CJK = Regex("[\\u4e00-\\u9fff]+")

        fun tokens(text: String): Set<String> {
            if (text.isBlank()) return emptySet()
            val out = mutableSetOf<String>()
            LATIN.findAll(text.lowercase()).forEach { if (it.value.length >= 2) out += it.value }
            CJK.findAll(text).forEach { match ->
                val run = match.value
                if (run.length == 1) {
                    out += run
                } else {
                    // 二元组 + 单字：单字查询（例如“猫”）也能命中多字文本。
                    run.forEach { out += it.toString() }
                    for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
                }
            }
            return out
        }

        fun dayOf(epochMillis: Long, zone: ZoneId): String =
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()
    }

    private fun dayOf(epochMillis: Long): String = dayOf(epochMillis, zone)
}
