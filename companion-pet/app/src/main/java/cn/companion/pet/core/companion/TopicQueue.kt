package cn.companion.pet.core.companion

import cn.companion.pet.core.memory.ProfileRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.util.UUID

@Serializable
data class Topic(
    val id: String,
    val summary: String,
    /** 只来自真实交流、用户保存的念头或已有日记摘要，不允许凭空生成。 */
    val sourceIds: List<String>,
    val createdAt: Long,
    val expiresAt: Long,
    val sharedAt: Long? = null,
    val dismissed: Boolean = false,
)

/**
 * 待分享话题队列。来源必须带 id；每项有创建/过期/已分享状态。
 * 同一个话题只分享一次；被忽略就 dismiss，不反复追问。
 */
class TopicQueue(
    private val root: File,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val file = File(root, "topics.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun enqueue(summary: String, sourceIds: List<String>, ttlMillis: Long = DEFAULT_TTL, now: Long = clock.millis()): Topic? {
        val clean = summary.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty() || sourceIds.isEmpty()) return null
        return synchronized(lock) {
            val list = read()
            if (list.any { ProfileRules.normalize(it.summary) == ProfileRules.normalize(clean) }) return null
            val topic = Topic(
                id = UUID.randomUUID().toString(),
                summary = clean.take(120),
                sourceIds = sourceIds,
                createdAt = now,
                expiresAt = now + ttlMillis,
            )
            write(list + topic)
            topic
        }
    }

    fun candidates(now: Long = clock.millis()): List<Topic> = synchronized(lock) {
        read().filter { !it.dismissed && it.sharedAt == null && it.expiresAt > now }
            .sortedBy { it.createdAt }
    }

    fun markShared(id: String, now: Long = clock.millis()) {
        synchronized(lock) {
            write(read().map { if (it.id == id && it.sharedAt == null) it.copy(sharedAt = now) else it })
        }
    }

    fun dismiss(id: String) {
        synchronized(lock) { write(read().map { if (it.id == id) it.copy(dismissed = true) else it }) }
    }

    fun all(): List<Topic> = synchronized(lock) { read() }

    private fun read(): List<Topic> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Topic.serializer()), file.readText()) }.getOrElse { emptyList() }
    }

    private fun write(list: List<Topic>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Topic.serializer()), list))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "rename failed: topics.json" }
        }
    }

    companion object {
        const val DEFAULT_TTL = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * 话题进入模型前的本地闸门：安静时段、用户不在、冷却、重复、过期都在这里拦掉。
 * 返回 null 表示这次没有内容可说，不硬凑。
 */
class TopicGate(private val queue: TopicQueue) {
    fun next(
        now: Long,
        quiet: Boolean,
        userActive: Boolean,
        lastProactiveAt: Long,
        cooldownMillis: Long = DEFAULT_COOLDOWN,
        recentTexts: Set<String> = emptySet(),
    ): Topic? {
        if (quiet || !userActive) return null
        if (lastProactiveAt > 0 && now - lastProactiveAt < cooldownMillis) return null
        val recent = recentTexts.map { ProfileRules.normalize(it) }.toSet()
        return queue.candidates(now).firstOrNull { ProfileRules.normalize(it.summary) !in recent }
    }

    companion object {
        const val DEFAULT_COOLDOWN = 30L * 60 * 1000
    }
}

/** 外部素材端口。本轮只提供本地 fixture adapter，不做联网抓取或发布。 */
interface MaterialAdapter {
    fun current(): MaterialItem?
}

class LocalFixtureMaterialAdapter(private val items: List<MaterialItem>) : MaterialAdapter {
    override fun current(): MaterialItem? = items.firstOrNull()
}
