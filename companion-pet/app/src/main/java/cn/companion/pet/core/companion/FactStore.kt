package cn.companion.pet.core.companion

import cn.companion.pet.core.memory.ProfileRules
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 稳定事实库。文件很小，整份读改写即可；所有操作串行。
 *
 * 纠正规则：同 factKey 的新版本让旧版本失效；猜测不能覆盖已有的“他说过”；
 * 删除写 tombstone，之后的写入和召回都要先核对 tombstone。
 */
class FactStore(
    root: File,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = clock.zone,
) {
    private val file = File(root, "facts.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<Fact> = synchronized(lock) { readLocked() }

    fun live(): List<Fact> = all().filter { !it.deleted && it.supersededBy == null }

    fun find(id: String): Fact? = all().firstOrNull { it.id == id }

    /**
     * 记一条“他说过/我推测”。tombstone 命中或空文本返回 null。
     * 同 key 已有“他说过”时，猜测量不覆盖；同 key 的旧版本被新版本替代。
     */
    fun record(
        text: String,
        confidence: Confidence,
        sourceEventIds: List<String> = emptyList(),
        tombstones: Set<String> = emptySet(),
        factKey: String? = null,
        now: Long = clock.millis(),
    ): Fact? {
        val clean = text.trim().removePrefix("-").trim()
        if (clean.isEmpty()) return null
        if (ProfileRules.normalize(clean) in tombstones) return null
        return synchronized(lock) {
            val list = readLocked()
            val key = factKey ?: FactKeys.of(clean).ifEmpty { return null }
            val current = list.filter { it.factKey == key && !it.deleted && it.supersededBy == null }
            if (confidence == Confidence.GUESSED && current.any { it.confidence == Confidence.SAID }) {
                return current.first { it.confidence == Confidence.SAID }
            }
            if (current.any { ProfileRules.normalize(it.text) == ProfileRules.normalize(clean) }) {
                return current.first { ProfileRules.normalize(it.text) == ProfileRules.normalize(clean) }
            }
            val version = (current.maxOfOrNull { it.version } ?: 0) + 1
            val fact = Fact(
                id = UUID.randomUUID().toString(),
                factKey = key,
                text = clean,
                confidence = confidence,
                createdAt = now,
                sourceEventIds = sourceEventIds,
                version = version,
            )
            val replaced = list.map { old ->
                if (old.factKey == key && old.supersededBy == null && !old.deleted) old.copy(supersededBy = fact.id) else old
            }
            writeLocked(replaced + fact)
            fact
        }
    }

    fun recordSaid(
        text: String,
        sourceEventIds: List<String> = emptyList(),
        tombstones: Set<String> = emptySet(),
        now: Long = clock.millis(),
    ): Fact? = record(text, Confidence.SAID, sourceEventIds, tombstones, now = now)

    fun recordGuess(
        text: String,
        sourceEventIds: List<String> = emptyList(),
        tombstones: Set<String> = emptySet(),
        now: Long = clock.millis(),
    ): Fact? = record(text, Confidence.GUESSED, sourceEventIds, tombstones, now = now)

    /** 用户确认“猜对了”：同一条事实升级为 [Confidence.SAID]，版本 +1。 */
    fun confirm(id: String): Fact? = synchronized(lock) {
        val list = readLocked()
        val fact = list.firstOrNull { it.id == id && !it.deleted } ?: return null
        val updated = fact.copy(confidence = Confidence.SAID, version = fact.version + 1)
        writeLocked(list.map { if (it.id == id) updated else it })
        updated
    }

    /** 用户更正：生成同 factKey 的新版本，旧版本失效。 */
    fun correct(id: String, newText: String, tombstones: Set<String> = emptySet()): Fact? = synchronized(lock) {
        val list = readLocked()
        val old = list.firstOrNull { it.id == id && !it.deleted } ?: return null
        val clean = newText.trim().removePrefix("-").trim()
        if (clean.isEmpty() || ProfileRules.normalize(clean) in tombstones) return null
        val fact = Fact(
            id = UUID.randomUUID().toString(),
            factKey = old.factKey,
            text = clean,
            confidence = Confidence.SAID,
            createdAt = clock.millis(),
            sourceEventIds = old.sourceEventIds,
            version = old.version + 1,
        )
        writeLocked(list.map { if (it.id == id) it.copy(supersededBy = fact.id) else it } + fact)
        fact
    }

    /** 用户忘记：软删除，返回原事实供调用方写 tombstone / 事件。 */
    fun forget(id: String): Fact? = synchronized(lock) {
        val list = readLocked()
        val fact = list.firstOrNull { it.id == id && !it.deleted } ?: return null
        writeLocked(list.map { if (it.id == id) it.copy(deleted = true) else it })
        fact
    }

    /**
     * 夜间整理产出的档案行同步成结构化事实。先按 tombstone 过滤，避免旧内容复活；
     * 已有“他说过”的同 key 事实不被“我推测”覆盖。
     */
    fun syncFromProfile(lines: List<String>, sourceEventIds: List<String>, tombstones: Set<String>): Int {
        var added = 0
        ProfileRules.applyTombstones(lines, tombstones).forEach { line ->
            val confidence = when {
                line.startsWith(ProfileRules.SAID) -> Confidence.SAID
                line.startsWith(ProfileRules.GUESS) -> Confidence.GUESSED
                else -> return@forEach
            }
            val text = line.removePrefix(ProfileRules.SAID).removePrefix(ProfileRules.GUESS).trim()
            val before = live().size
            record(text, confidence, sourceEventIds, tombstones)
            if (live().size > before) added += 1
        }
        return added
    }

    /** 事实集合的版本指纹，供调试和状态记录使用。 */
    fun versionHash(): String = FactKeys.of(live().sortedBy { it.id }.joinToString("\n") { it.id + ":" + it.version })

    fun dayOf(occurredAt: Long): String = Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate().toString()

    private fun readLocked(): List<Fact> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Fact.serializer()), file.readText())
        }.getOrElse { emptyList() }
    }

    private fun writeLocked(list: List<Fact>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Fact.serializer()), list))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "rename failed: facts.json" }
        }
    }
}
