package com.zongkong.core.work

/**
 * 随手收：先原样保留，再自动整理能整理的——类型、标题、链接、可能相关的事项。
 * 不需要你分类、复制粘贴、填表；猜错了点一下改。
 */
object Capture {
    data class Draft(val type: NoteType, val title: String, val url: String, val raw: String)

    private val URL_RE = Regex("https?://[^\\s，。、）)\\]】]+")

    fun guess(text: String): Draft {
        val raw = text.trim()
        val url = URL_RE.find(raw)?.value.orEmpty()
        val withoutUrl = raw.replace(url, "").trim()
        val first = withoutUrl.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val isQuestion = first.endsWith("？") || first.endsWith("?") ||
            Regex("^(为什么|怎么|如何|要不要|是否|能不能|该不该|是不是|哪)").containsMatchIn(first) ||
            Regex("(吗|呢)[？?]?$").containsMatchIn(first)
        val type = when {
            url.isNotEmpty() -> NoteType.MATERIAL
            isQuestion -> NoteType.QUESTION
            else -> NoteType.IDEA
        }
        val title = when {
            first.isNotEmpty() -> first.take(60)
            url.isNotEmpty() -> url.substringAfter("://").substringBefore('/').take(60)
            else -> "（空）"
        }
        return Draft(type, title, url, raw)
    }

    /** 从网页 HTML 里取标题（og:title 优先）。 */
    fun htmlTitle(html: String): String? {
        val og = Regex("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:title[\"']", RegexOption.IGNORE_CASE).find(html)
        val t = og?.groupValues?.get(1) ?: Regex("<title[^>]*>([^<]{1,300})</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        return t?.let(::unescape)?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }?.take(80)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    /** 可能相关的事项：按字面重合度打分（中文按双字切）。 */
    fun suggest(work: Work, text: String, limit: Int = 3): List<Rec> {
        val q = grams(text)
        if (q.isEmpty()) return emptyList()
        return work.threads().filter { it.threadStatus.open }
            .map { t -> t to grams(listOf(t.title, t[F.WHY], t[F.NEXT], t[F.UNSURE], t[F.CODE]).joinToString(" ")).intersect(q).size }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun grams(s: String): Set<String> {
        val clean = s.lowercase().replace(URL_RE, " ")
        val out = mutableSetOf<String>()
        Regex("[a-z0-9-]{2,}").findAll(clean).forEach { out += it.value }
        val han = clean.filter { it in '一'..'龥' || it == ' ' }
        han.split(' ').forEach { w -> for (i in 0 until w.length - 1) out += w.substring(i, i + 2) }
        return out - STOP
    }

    private val STOP = setOf("一个", "这个", "什么", "怎么", "为什么", "我们", "自己", "没有", "可以", "就是", "还是", "不是", "今天", "明天")
}
