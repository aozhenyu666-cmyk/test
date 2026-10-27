package cn.companion.pet.core.voice

/**
 * 文本归一化：消除「只改标点/空格/大小写就能绕过重复检测」这条路。
 *
 * 归一化之后，两种写法相同的句子必须得到完全相同的串。
 */
object TextNormalize {
    /** 中文里最常见、且不承载信息量的语气词；去掉它们不会改变句子意思。 */
    private const val PARTICLES = "啊吧呢呀嘛哦噢啦咯嗯"

    fun of(s: String): String {
        val lowered = s.lowercase()
        val sb = StringBuilder(lowered.length)
        for (ch in lowered) {
            // 标点、空白、各类符号全部丢掉，只留下文字与数字。
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        val trimmed = sb.toString()
        // 语气词单独再滤一遍（它们本身是 letter，不会被上面那步去掉）。
        return trimmed.filterNot { it in PARTICLES }
    }

    /** 归一化后的字符二元组集合，用于算重合比例。 */
    internal fun bigrams(s: String): Set<String> {
        val n = of(s)
        if (n.length < 2) return if (n.isEmpty()) emptySet() else setOf(n)
        val out = HashSet<String>(n.length)
        for (i in 0 until n.length - 1) out.add(n.substring(i, i + 2))
        return out
    }
}

/** 记过的一句话。 */
data class Saying(val text: String, val at: Long)

/**
 * 「说过的话」账本：最近说过什么，以及「这句是不是刚说过」的硬判定。
 *
 * 判定用归一化后的字符二元组 Jaccard 重合度。改标点、改空格、改大小写都绕不过去；
 * 换一件事说则重合度很低，正常放行。
 */
class SayingLedger(private val capacity: Int = 30) {

    private val items = ArrayDeque<Saying>()

    /** 记一句已经说出口的话。超出容量丢最旧的。 */
    fun record(text: String, now: Long) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        items.addLast(Saying(trimmed, now))
        while (items.size > capacity.coerceAtLeast(1)) items.removeFirst()
    }

    /** 最近说过的话，最新的在后；[n] 是上限，不是保证条数。 */
    fun recent(n: Int = 12): List<String> =
        if (n <= 0) emptyList() else items.takeLast(n).map { it.text }

    /** 这句话是不是和最近说过的话太像。像到 [threshold] 以上就当它说过了。 */
    fun isTooSimilar(text: String, threshold: Double = 0.72): Boolean {
        val probe = TextNormalize.of(text)
        if (probe.isEmpty()) return false
        val probeBigrams = TextNormalize.bigrams(text)
        if (probeBigrams.isEmpty()) return false
        return items.any { saying ->
            val other = TextNormalize.bigrams(saying.text)
            val union = probeBigrams.size + other.size - probeBigrams.count { it in other }
            if (union <= 0) false else {
                val intersection = probeBigrams.count { it in other }
                intersection.toDouble() / union >= threshold
            }
        }
    }
}
