package cn.companion.pet.core.partner

import cn.companion.pet.core.companion.MemoryRecall
import cn.companion.pet.core.companion.RecallLimits

/** Select related memories before building the actual model request, rather than the last N facts. */
class PartnerContext(private val recall: MemoryRecall) {
    fun memories(query: String, now: Long, tombstones: Set<String>, excludeIds: Set<String> = emptySet()): Map<String, String> =
        recall.recall(query, now, tombstones, excludeTexts = setOf(query), excludeIds = excludeIds,
            limits = RecallLimits(maxItems = 6, maxChars = 720))
            .filter { it.id.isNotBlank() }
            .associate { it.id to "${it.day} · ${it.sourceLabel}：${it.text}" }

    companion object {
        /** Character limits are an input bound, not a claim about tokenizer or billed token usage. */
        fun project(history: List<String>, state: String, thread: String, maxChars: Int = 6000): List<String> {
            val prefix = listOfNotNull(
                state.takeIf { it.isNotBlank() }?.let { "【角色的表现状态，不代表真实情绪】" + it.take(180) },
                thread.takeIf { it.isNotBlank() }?.let { "【仍在聊的事】" + it.take(240) },
            )
            var left = (maxChars - prefix.sumOf { it.length }).coerceAtLeast(0)
            val selected = mutableListOf<String>()
            for (line in history.asReversed()) {
                if (left <= 0) break
                val kept = line.take(left)
                selected.add(kept)
                left -= kept.length
            }
            return prefix + selected.asReversed()
        }
    }
}
