package cn.companion.pet.core.voice

import kotlin.math.max

/**
 * 文本归一化：消除「只改标点/空格/大小写/语气词就能绕过重复检测」这条路。
 *
 * 归一化之后，两种写法相同的句子必须得到完全相同的串。
 */
object TextNormalize {
    /**
     * 中文里最常见、且不承载信息量的语气词；去掉它们不会改变句子意思。
     *
     * 含句末疑问语气词「吗」：同一句话只把「吗」换成「吧/呢」，说的是同一件事，
     * 必须判为重复而不是新话 —— 真实对抗的是「换个语气词再来一遍」这条路。
     */
    private const val PARTICLES = "啊吧呢呀嘛哦噢啦咯嗯吗"

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

    /**
     * 归一化之后还剩不剩东西。
     *
     * 「……」「嗯」「啊吧呢」这类内容归一化后为空，本身不承载任何信息，
     * 不应该被记进账本、也不应该进队列——否则它们能无限次重复，因为空集跟谁都不重合。
     */
    fun isEmpty(s: String): Boolean = of(s).isEmpty()

    /** 归一化后的字符二元组集合，用于算重合比例。 */
    internal fun bigrams(s: String): Set<String> {
        val n = of(s)
        if (n.length < 2) return if (n.isEmpty()) emptySet() else setOf(n)
        val out = HashSet<String>(n.length)
        for (i in 0 until n.length - 1) out.add(n.substring(i, i + 2))
        return out
    }

    /**
     * 对称口径：交集 / 并集。**单独使用时会被「加尾注」稀释**，因此不作为判定口径，
     * 只在测试里用来证明「只看这个数会漏判」。
     */
    internal fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        if (intersection == 0) return 0.0
        return intersection.toDouble() / (a.size + b.size - intersection)
    }

    /**
     * 判定口径的重合度：`max(|∩| / |A ∪ B|, |∩| / min(|A|, |B|))`。
     *
     * 取两个数的**较大值**，是为了抗「稀释」：把说过的句子原样保留、再补一句解释，
     * 并集会变大（Jaccard 掉下去，实测 0.59 < 0.72，会被放行），
     * 但「交集 / 较短者」维度的分子分母都不变（仍是 1.0），所以判定不会降级。
     * 单靠 `|∩|/min` 又太容易被一句极短的旧话卡住，所以两者取大、而不是只用后者。
     */
    internal fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        val intersection = small.count { it in large }
        if (intersection == 0) return 0.0
        val containment = intersection.toDouble() / small.size
        return max(jaccard(a, b), containment)
    }

    internal fun overlap(a: String, b: String): Double = overlap(bigrams(a), bigrams(b))
}

/** 记过的一句话。 */
data class Saying(val text: String, val at: Long)

/**
 * 「说过的话」账本：最近说过什么，以及「这句是不是刚说过」的硬判定。
 *
 * 判定用归一化后的字符二元组重合度（口径见 [TextNormalize.overlap]）。
 * 改标点、改空格、改大小写、改语气词都绕不过去；补一句尾注也绕不过去；
 * 换一件事说则重合度很低，正常放行。
 */
class SayingLedger(private val capacity: Int = 30) {

    private val items = ArrayDeque<Saying>()

    /**
     * 记一句已经说出口的话。超出容量丢最旧的。
     *
     * 归一化后为空的内容（纯标点、「嗯」、「…… 」）不记账：它没有语义，
     * 记下来也拦不住任何东西，只会白占容量。
     */
    fun record(text: String, now: Long) {
        val trimmed = text.trim()
        if (TextNormalize.isEmpty(trimmed)) return
        items.addLast(Saying(trimmed, now))
        while (items.size > capacity.coerceAtLeast(1)) items.removeFirst()
    }

    /** 最近说过的话，最新的在后；[n] 是上限，不是保证条数。 */
    fun recent(n: Int = 12): List<String> =
        if (n <= 0) emptyList() else items.takeLast(n).map { it.text }

    /** 这句话是不是和最近说过的话太像。像到 [threshold] 以上就当它说过了。 */
    fun isTooSimilar(text: String, threshold: Double = DEFAULT_THRESHOLD): Boolean {
        val probe = TextNormalize.bigrams(text)
        if (probe.isEmpty()) return false
        return items.any { saying ->
            TextNormalize.overlap(probe, TextNormalize.bigrams(saying.text)) >= threshold
        }
    }

    companion object {
        /** 卡在「同一句式的改写要拦」与「换一件事要说」之间。 */
        const val DEFAULT_THRESHOLD: Double = 0.72
    }
}
