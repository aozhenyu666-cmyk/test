package cn.companion.pet.core.action

import cn.companion.pet.core.companion.*
import cn.companion.pet.core.memory.MemoryStore
import cn.companion.pet.core.memory.ProfileRules
import cn.companion.pet.core.text.UnicodeText

/** The tool and conversation use the same source/tombstone rules; unconfirmed facts keep their label. */
class VisibleMemoryReader(private val memory: MemoryStore, private val events: EventLog, private val facts: FactStore) {
    fun read(query: String, limit: Int, now: Long = System.currentTimeMillis()): List<String> = synchronized(memory.lock) {
        require(limit in 1..20)
        val snapshot = events.all()
        val visibility = EventVisibility(snapshot, memory.tombstones(), now)
        val allFacts = facts.all()
        val visibleFacts = allFacts.filter { visibility.allows(it) }
        val visibleTexts = visibleFacts.map { ProfileRules.normalize(it.text) }.toSet()
        val hiddenTexts = allFacts.filterNot { visibility.allows(it) }.map { ProfileRules.normalize(it.text) }.toSet() - visibleTexts
        val factLines = visibleFacts.map { (if (it.confidence == Confidence.SAID) ProfileRules.SAID else ProfileRules.GUESS) + " " + it.text }
        val profile = ProfileRules.applyTombstones(memory.profileLines(), memory.tombstones())
            .filter { ProfileRules.normalize(it) !in hiddenTexts }
        val result = mutableListOf<String>()
        var used = 0
        (factLines + profile).distinctBy { ProfileRules.normalize(it) }
            .filter { query.isBlank() || it.contains(query, ignoreCase = true) }.take(limit).forEach { line ->
                val clipped = UnicodeText.truncate(line, minOf(300, (2000 - used).coerceAtLeast(0)))
                if (clipped.isNotEmpty()) { result += clipped; used += UnicodeText.count(clipped) + 1 }
            }
        result
    }
}
