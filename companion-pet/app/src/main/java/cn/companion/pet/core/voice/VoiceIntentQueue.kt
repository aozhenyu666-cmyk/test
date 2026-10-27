package cn.companion.pet.core.voice

/**
 * 「想说的话」队列：入队去重、过期不选、说完划掉、撞车就换下一件。
 *
 * 纯内存、纯逻辑，无 I/O。持久化由外层负责。
 */
class VoiceIntentQueue {

    /** 默认 6 小时：攒太久的话，说出来已经不合时了。 */
    private val items = mutableListOf<VoiceIntent>()
    private var seq = 0L

    /**
     * 入队。内容（归一化后）与已存在的意图重复时返回 null，不重复攒。
     *
     * [source] 是开放字符串，任何新来源都能直接进来，不需要改这里。
     */
    fun enqueue(
        source: String,
        summary: String,
        sourceIds: List<String> = emptyList(),
        now: Long,
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
    ): VoiceIntent? {
        val text = summary.trim()
        if (text.isEmpty()) return null
        val normalized = TextNormalize.of(text)
        if (items.any { TextNormalize.of(it.summary) == normalized }) return null
        val intent = VoiceIntent(
            id = "vi-" + (seq++).toString(36) + "-" + now.toString(36),
            source = source,
            summary = text,
            sourceIds = sourceIds,
            createdAt = now,
            expiresAt = now + ttlMillis,
        )
        items.add(intent)
        return intent
    }

    /**
     * 挑一件现在能说的：未过期、没说过、且 [avoid] 为假（通常是「和最近说过的太像」）。
     * 同一件被拦下时换下一件，而不是索性沉默。返回最早攒下的那件。
     */
    fun next(now: Long, avoid: (String) -> Boolean): VoiceIntent? =
        items
            .filter { it.spokenAt == null && it.expiresAt > now }
            .sortedBy { it.createdAt }
            .firstOrNull { !avoid(it.summary) }

    /** 说完划掉：写 [VoiceIntent.spokenAt]，之后不会再被挑中。 */
    fun markSpoken(id: String, now: Long) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(spokenAt = now)
    }

    /** 全量快照，供持久化与测试观察。 */
    fun all(): List<VoiceIntent> = items.toList()

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 6 * 60 * 60_000L
    }
}
