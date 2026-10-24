package cn.companion.pet.core.companion

import cn.companion.pet.core.memory.ProfileRules

/** A correction/forget invalidates its old sources and their derived material, not just one spelling. */
class EventVisibility(
    events: List<CompanionEvent>,
    private val tombstones: Set<String> = emptySet(),
    now: Long = System.currentTimeMillis(),
) {
    val invalidSourceIds: Set<String>

    init {
        val invalid = events.filter { it.kind == EventKind.FORGET || it.kind == EventKind.CORRECTION }
            .flatMap { it.sourceIds }.toMutableSet()
        invalid.addAll(events.filter { it.expiresAt?.let { end -> end <= now } == true }.map { it.id })
        // Control events point at the sources they replace; their new content remains usable.
        do {
            var changed = false
            events.forEach { event ->
                if (event.kind != EventKind.CORRECTION && event.kind != EventKind.FORGET &&
                    event.sourceIds.any { it in invalid } && invalid.add(event.id)
                ) changed = true
            }
        } while (changed)
        invalidSourceIds = invalid
    }

    fun allows(event: CompanionEvent): Boolean = event.kind != EventKind.FORGET &&
        event.id !in invalidSourceIds &&
        if (event.kind == EventKind.CORRECTION) ProfileRules.normalize(event.text) !in tombstones
        else !containsForgotten(event.text, tombstones)

    fun allows(fact: Fact): Boolean = !fact.deleted && fact.supersededBy == null &&
        fact.sourceEventIds.none { it in invalidSourceIds } && ProfileRules.normalize(fact.text) !in tombstones

    companion object {
        fun containsForgotten(text: String, tombstones: Set<String>): Boolean {
            val normalized = ProfileRules.normalize(text)
            return normalized.isNotEmpty() && tombstones.any { it.isNotEmpty() && normalized.contains(it) }
        }
    }
}
