package cn.companion.pet.core.skim

import cn.companion.pet.core.text.TextCandidate
import cn.companion.pet.core.text.UnicodeText

/**
 * 划过留痕：快速滑动时，把从屏幕上经过的段落悄悄收集起来，事后可以回看“刚才划过了啥”。
 *
 * - 只留像正文的段落（够长、优先来自可滚动容器），按钮和标签这类短词丢掉。
 * - 同一段只记一次；只留最近 [windowMillis] 内、总共不超过 [maxChars] 码点。
 * - 只在内存里，不写文件；熄屏或用户清空时丢掉。
 *
 * 非线程安全，由主线程访问。
 */
class SkimBuffer(
    private val windowMillis: Long = 10 * 60_000L,
    private val maxChars: Int = 6000,
    private val minParagraphChars: Int = 12,
) {
    data class Paragraph(val text: String, val pkg: String, val at: Long)

    private val items = ArrayDeque<Paragraph>()
    private val seen = HashSet<String>()

    val size: Int get() = items.size

    /** 记一次屏幕快照，返回新收进来的段落数。 */
    fun add(now: Long, pkg: String, candidates: List<TextCandidate>): Int {
        prune(now)
        var added = 0
        for (c in candidates) {
            if (!c.inScrollableContainer) continue
            val text = c.text.replace(Regex("\\s+"), " ").trim()
            if (UnicodeText.count(text) < minParagraphChars) continue
            val key = normalize(text)
            if (!seen.add(key)) continue
            items.addLast(Paragraph(text, pkg, now))
            added++
        }
        trimToSize()
        return added
    }

    /** 最近划过的段落，按出现顺序，最多 [maxChars] 码点（从最新往回取）。 */
    fun recent(now: Long, maxChars: Int = 3000): List<Paragraph> {
        prune(now)
        val out = ArrayDeque<Paragraph>()
        var total = 0
        for (p in items.reversed()) {
            val n = UnicodeText.count(p.text)
            if (total + n > maxChars) {
                if (out.isEmpty()) out.addFirst(p.copy(text = UnicodeText.truncate(p.text, maxChars)))
                break
            }
            out.addFirst(p)
            total += n
        }
        return out.toList()
    }

    fun text(now: Long, maxChars: Int = 3000): String = recent(now, maxChars).joinToString("\n") { it.text }

    fun isEmpty(now: Long): Boolean { prune(now); return items.isEmpty() }

    fun clear() { items.clear(); seen.clear() }

    private fun prune(now: Long) {
        while (items.isNotEmpty() && now - items.first().at > windowMillis) {
            seen.remove(normalize(items.removeFirst().text))
        }
    }

    private fun trimToSize() {
        var total = items.sumOf { UnicodeText.count(it.text) }
        while (total > maxChars && items.size > 1) {
            val p = items.removeFirst()
            seen.remove(normalize(p.text))
            total -= UnicodeText.count(p.text)
        }
    }

    private fun normalize(s: String) = s.replace(Regex("\\s+"), "")
}
