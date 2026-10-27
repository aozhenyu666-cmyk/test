package cn.companion.pet.core.voice

/**
 * 「想说的话」队列：入队去重、过期不选、说完划掉、撞车就换下一件。
 *
 * 纯内存、纯逻辑，无 I/O。持久化由外层负责。
 *
 * 两条语义边界（都写过测试盯着）：
 * 1. **过期只是「不再被挑中」，不是「这件事永远不许再说」**。过期条目会被清掉，
 *    之后同一内容可以重新入队。去重只看「现在还有效的」，不看「历史上入过队」。
 * 2. **归一化后为空的内容不进队**。纯标点、「嗯」这类东西没有语义，
 *    放进来只会变成可以无限重复的空壳。
 */
class VoiceIntentQueue {

    private data class Entry(val intent: VoiceIntent, val seq: Long)

    private val entries = mutableListOf<Entry>()
    private var nextSeq = 0L

    /**
     * 入队。内容（归一化后）与**仍然有效**的意图重复时返回 null，不重复攒。
     *
     * 「仍然有效」= 未过期且没说过。过期/说过的老条目不该把同一件事永久禁言。
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
        if (TextNormalize.isEmpty(text)) return null
        val normalized = TextNormalize.of(text)
        val duplicated = entries.any {
            val live = it.intent.spokenAt == null && it.intent.expiresAt > now
            live && TextNormalize.of(it.intent.summary) == normalized
        }
        if (duplicated) return null
        val intent = VoiceIntent(
            id = "vi-" + nextSeq.toString(36) + "-" + now.toString(36),
            source = source,
            summary = text,
            sourceIds = sourceIds,
            createdAt = now,
            expiresAt = now + ttlMillis,
        )
        entries.add(Entry(intent, nextSeq++))
        return intent
    }

    /**
     * 挑一件现在能说的：未过期、没说过、且 [avoid] 为假（通常是「和最近说过的太像」）。
     * 同一件被拦下时换下一件，而不是索性沉默。返回最早攒下的那件。
     *
     * 排序先按 [VoiceIntent.createdAt]，再按入队序号——不依赖 `sortedBy` 的稳定性，
     * 同一毫秒入队的两件事也有确定的先后。
     */
    fun next(now: Long, avoid: (String) -> Boolean): VoiceIntent? {
        prune(now)
        return entries
            .filter { it.intent.spokenAt == null && it.intent.expiresAt > now }
            .sortedWith(compareBy({ it.intent.createdAt }, { it.seq }))
            .firstOrNull { !avoid(it.intent.summary) }
            ?.intent
    }

    /**
     * 清掉「已过期且没说过」的条目。说过的那条留着，作为「这件事说过了」的痕迹。
     *
     * 只增不减的队列会无限长；更实际的问题是过期条目继续占着去重名额，
     * 让同一件事再也进不来。返回清掉的条数。
     */
    fun prune(now: Long): Int {
        val before = entries.size
        entries.removeAll { it.intent.spokenAt == null && it.intent.expiresAt <= now }
        return before - entries.size
    }

    /** 说完划掉：写 [VoiceIntent.spokenAt]，之后不会再被挑中。 */
    fun markSpoken(id: String, now: Long) {
        val index = entries.indexOfFirst { it.intent.id == id }
        if (index >= 0) entries[index] = entries[index].copy(intent = entries[index].intent.copy(spokenAt = now))
    }

    /** 全量快照，供持久化与测试观察。 */
    fun all(): List<VoiceIntent> = entries.map { it.intent }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 6 * 60 * 60_000L
    }
}
