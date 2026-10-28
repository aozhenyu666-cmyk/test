package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.Confidence
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.Fact
import cn.companion.pet.core.companion.FactStore
import cn.companion.pet.core.companion.FactOrigin

/** User edits cross both stores under the same lock used by nightly commits. */
class MemoryEdits(
    private val memory: MemoryStore,
    private val facts: FactStore,
    private val events: EventLog,
    private val onForgotten: (String) -> Unit = {},
    private val onCorrected: () -> Unit = {},
) {
    fun confirm(id: String): Fact? = synchronized(memory.lock) {
        val fact = facts.live().firstOrNull { it.id == id } ?: return null
        val confirmed = facts.confirm(fact.id) ?: return null
        replaceProfile(listOf(fact.text), ProfileRules.SAID + " " + confirmed.text)
        confirmed
    }

    fun correct(id: String, text: String): Fact? = synchronized(memory.lock) {
        val old = facts.live().firstOrNull { it.id == id } ?: return null
        val clean = text.trim().removePrefix("-").trim()
            .removePrefix(ProfileRules.SAID).removePrefix(ProfileRules.GUESS).trim()
        if (clean.isEmpty() || ProfileRules.normalize(clean) in memory.tombstones()) return null
        if (ProfileRules.normalize(clean) == ProfileRules.normalize(old.text)) return confirm(id)
        val correction = events.append(EventKind.CORRECTION, clean, source = "user",
            sourceIds = old.sourceEventIds, factKey = old.factKey)
        val corrected = facts.correct(id, clean, memory.tombstones(), listOf(correction.id)) ?: return null
        replaceProfile(facts.all().filter { it.factKey == old.factKey }.map { it.text },
            ProfileRules.SAID + " " + corrected.text)
        onCorrected()
        corrected
    }

    fun forget(id: String): Boolean = synchronized(memory.lock) {
        val old = facts.live().firstOrNull { it.id == id } ?: return false
        val versions = facts.all().filter { it.factKey == old.factKey }
        facts.forget(id)
        versions.forEach { memory.addDeletedText(it.text) }
        versions.forEach { onForgotten(it.id) }
        replaceProfile(versions.map { it.text }, null)
        events.append(EventKind.FORGET, old.text, source = "user",
            sourceIds = versions.flatMap { it.sourceEventIds }.distinct(), factKey = old.factKey)
        true
    }

    /**
     * 用户刚记下又点了“撤销”。追加一条指向原事件的 FORGET：念头本身、派生的交流行、
     * 由它入队的话题和夜间整理都会通过来源失效看不到它。
     * 不写删除清单——以后他重新说同样的话，是新的授权，仍然可以记。
     */
    fun retractNote(noteEventId: String) = synchronized(memory.lock) {
        events.append(EventKind.FORGET, "（撤销的念头）", source = "user", sourceIds = listOf(noteEventId))
        Unit
    }

    fun confirmProfileLine(line: String) = synchronized(memory.lock) {
        val matching = facts.live().firstOrNull { ProfileRules.normalize(it.text) == ProfileRules.normalize(line) }
        if (matching != null) confirm(matching.id) else {
            memory.confirmProfileLine(line)
            syncProfile()
        }
    }

    fun deleteProfileLine(line: String) = synchronized(memory.lock) {
        val matching = facts.live().filter { ProfileRules.normalize(it.text) == ProfileRules.normalize(line) }
        matching.forEach { forget(it.id) }
        memory.deleteProfileLine(line)
    }

    fun saveProfile(text: String) = synchronized(memory.lock) {
        val before = memory.profileLines()
        val after = ProfileRules.clamp(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
        val kept = after.map { ProfileRules.normalize(it) }.toSet()
        before.filter { ProfileRules.normalize(it) !in kept }.forEach { deleteProfileLine(it) }
        memory.saveProfileByUser(after.joinToString("\n"))
        syncProfile()
    }

    private fun syncProfile() {
        memory.profileLines().forEach { line ->
            if (!ProfileRules.hasTag(line)) return@forEach
            val text = line.removePrefix(ProfileRules.SAID).removePrefix(ProfileRules.GUESS).trim()
            val confidence = if (line.startsWith(ProfileRules.SAID)) Confidence.SAID else Confidence.GUESSED
            if (facts.live().any { ProfileRules.normalize(it.text) == ProfileRules.normalize(text) &&
                    it.confidence == confidence && it.origin == FactOrigin.USER_EDIT }) return@forEach
            val event = events.append(EventKind.FACT, text, source = "user")
            facts.record(text, confidence, listOf(event.id), memory.tombstones(), origin = FactOrigin.USER_EDIT)
        }
    }

    private fun replaceProfile(oldTexts: List<String>, newLine: String?) {
        val replaced = oldTexts.map { ProfileRules.normalize(it) }.toSet()
        val lines = memory.profileLines().filter { ProfileRules.normalize(it) !in replaced }
        // Explicit edits go first so a full profile cannot silently truncate the user's new fact.
        memory.writeAtomic(memory.profileFile, ProfileRules.clamp(listOfNotNull(newLine) + lines).joinToString("\n"))
    }
}
