package com.moling.core.intake

import com.moling.core.web.SourceChannel
import kotlinx.serialization.Serializable

/** 第十批 T3：统一情报池。未采用的临时原文 24 小时后清掉；采用引用不在这里清。 */
@Serializable
data class InhaleItem(
    val id: String,
    val kind: String,
    val focusId: String = "",
    val url: String = "",
    val title: String = "",
    val text: String = "",
    val sourceId: String = "",
    val dedupeKey: String = "",
    val at: Long = 0,
    val expiresAt: Long = 0,
    val consumedAt: Long = 0,
)

@Serializable
data class InhaleState(val items: List<InhaleItem> = emptyList())

object Inhale {
    const val MAX_ITEMS = 200
    const val MAX_BYTES = 5 * 1024 * 1024
    const val TTL_MS = 24 * 3_600_000L

    fun add(st: InhaleState, item: InhaleItem, now: Long): Pair<InhaleState, InhaleItem?> {
        val key = item.dedupeKey.ifBlank { item.url.ifBlank { item.text.take(200) } }
        if (key.isNotBlank() && st.items.any { it.dedupeKey == key && it.consumedAt == 0L && it.expiresAt > now }) return st to null
        val fit = st.items.asSequence().filter { it.consumedAt == 0L }.sumOf { it.text.toByteArray().size } + item.text.toByteArray().size <= MAX_BYTES
        if (!fit) return st to null
        val clean = item.copy(id = item.id.ifBlank { "i-" + now }, dedupeKey = key, at = item.at.takeIf { it > 0 } ?: now, expiresAt = item.expiresAt.takeIf { it > 0 } ?: (now + TTL_MS))
        val next = (st.items + clean).filter { it.consumedAt > 0 || it.expiresAt > now }.takeLast(MAX_ITEMS)
        return st.copy(items = next) to clean
    }

    fun consume(st: InhaleState, id: String, now: Long): InhaleState =
        st.copy(items = st.items.map { if (it.id == id) it.copy(consumedAt = now) else it })

    fun sweep(st: InhaleState, now: Long): InhaleState =
        st.copy(items = st.items.filter { it.consumedAt > 0 || it.expiresAt > now })

    fun live(st: InhaleState, now: Long): List<InhaleItem> =
        st.items.filter { it.consumedAt == 0L && it.expiresAt > now }
}

/** 分享/粘贴统一转成情报池的一条；URL 只取真正的链接，不在分享文案里瞎抓。 */
object InhaleParse {
    private val URL_RE = Regex("""https?://[^\s"<>]+""")

    fun fromShare(text: String, now: Long): InhaleItem {
        val trimmed = text.trim()
        val url = URL_RE.find(trimmed)?.value?.trimEnd('.', ',', '。', '，', ')', '）') ?: ""
        // 只有“文案里几乎只有这个链接”才当纯链接；带一句话的分享仍是 share，正文保留。
        val bareUrl = url.isNotBlank() && trimmed.replace(url, "").trim().length <= 2
        return InhaleItem(
            id = "i-" + now,
            kind = if (bareUrl) "url" else "share",
            url = url,
            title = if (bareUrl) "" else trimmed.take(40),
            text = if (bareUrl) "" else trimmed.take(20_000),
            at = now,
            expiresAt = now + Inhale.TTL_MS,
        )
    }

    fun channelOf(item: InhaleItem): SourceChannel = when (item.kind) {
        "url" -> SourceChannel.URL
        "rss" -> SourceChannel.RSS
        "search" -> SourceChannel.SEARCH
        "share" -> SourceChannel.SHARE
        else -> SourceChannel.PASTE
    }
}