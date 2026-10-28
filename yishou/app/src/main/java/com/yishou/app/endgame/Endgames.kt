package com.yishou.app.endgame

import com.yishou.app.card.Card
import com.yishou.app.data.Endgame
import com.yishou.app.data.EndgameSource
import com.yishou.app.think.Words

/**
 * 残局的本机规则：怎么切成能点的小句、点没点中、点错以后怎么缩包围圈、什么时候再出、
 * 题卡怎么在本机直接变成残局、模型加工出来的残局怎么核对（必须是原文，破绽必须在残局里）。
 *
 * 为什么是“点”而不是“写”：入口那几秒在地铁、在被窝，打字和开口都难；
 * 而随手写一个词就能过的关，等于没有关。点一句是能核对的——点中就是点中，蒙不过去：
 * 点错了不罚，只把外围的废话涂暗、再点一次；两下都没中就直接翻底牌，这一局明天再来。
 */
object Endgames {

    /** 能点的一小句：到逗号、句号这类标点为止（标点留在这一句末尾） */
    data class Seg(val start: Int, val end: Int, val text: String)

    private const val STOPS = "，。；！？：,;!?:\n"

    fun segments(text: String): List<Seg> {
        val out = mutableListOf<Seg>()
        var a = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in STOPS) {
                // 连着的标点、右引号一起归到这一句
                var b = i + 1
                while (b < text.length && (text[b] in STOPS || text[b] in "”」』）)")) b++
                add(out, text, a, b)
                a = b
                i = b
            } else {
                i++
            }
        }
        add(out, text, a, text.length)
        return out
    }

    private fun add(out: MutableList<Seg>, text: String, a: Int, b: Int) {
        if (b <= a) return
        val t = text.substring(a, b)
        if (t.isBlank() || t.none { it.isLetterOrDigit() }) {
            // 只有标点：并到上一句
            if (out.isNotEmpty()) {
                val last = out.removeAt(out.lastIndex)
                out += Seg(last.start, b, text.substring(last.start, b))
            }
            return
        }
        out += Seg(a, b, t)
    }

    fun cruxRange(e: Endgame): IntRange? {
        val i = e.text.indexOf(e.crux)
        return if (i < 0 || e.crux.isEmpty()) null else i until i + e.crux.length
    }

    /** 破绽落在哪几句（破绽可能跨两句） */
    fun cruxSegs(e: Endgame, segs: List<Seg> = segments(e.text)): Set<Int> {
        val r = cruxRange(e) ?: return emptySet()
        return segs.indices.filter { i -> segs[i].start <= r.last && segs[i].end > r.first }.toSet()
    }

    fun isHit(e: Endgame, segs: List<Seg>, tapped: Int): Boolean = tapped in cruxSegs(e, segs)

    /**
     * 缩包围圈：点错一次，只留破绽附近的三句（窗口的位置按残局编号错开，不总是正中间，免得你学会“点中间”），
     * 其余涂暗；点错的那一句也涂暗。
     */
    fun dimmed(e: Endgame, segs: List<Seg>, wrong: Set<Int>): Set<Int> {
        if (wrong.isEmpty()) return emptySet()
        val c = cruxSegs(e, segs).minOrNull() ?: return wrong
        val shift = (e.id % 3).toInt() // 0、1、2：破绽在窗口的左、中、右
        var from = c - shift
        var to = from + 2
        if (from < 0) { to -= from; from = 0 }
        if (to > segs.lastIndex) { from -= to - segs.lastIndex; to = segs.lastIndex }
        val keep = (from.coerceAtLeast(0)..to) + cruxSegs(e, segs)
        return segs.indices.filter { it !in keep || it in wrong }.toSet()
    }

    /** 读完才能点：按字数锁一会儿（1.5 秒起，最多 6 秒） */
    fun minReadMs(text: String): Long = (1_500L + Words.norm(text).length * 35L).coerceAtMost(6_000L)

    /** 最多点几下：两下没中就翻底牌 */
    const val TRIES = 2

    private const val DAY = 86_400_000L
    /** 点中了：隔 1、3、7、21 天再来；没点中：明天再来 */
    private val GAPS = listOf(1 * DAY, 3 * DAY, 7 * DAY, 21 * DAY)

    fun after(e: Endgame, firstHit: Boolean, now: Long): Endgame {
        val hits = e.hits + if (firstHit) 1 else 0
        val gap = if (firstHit) GAPS[(hits - 1).coerceIn(0, GAPS.lastIndex)] else DAY - 4 * 3_600_000L
        return e.copy(seen = e.seen + 1, hits = hits, lastAt = now, lastHit = firstHit, dueAt = now + gap)
    }

    /** 下一局：上次没点中的先回来；再是没见过的；再是到期复习的。同一来源不连着出。 */
    fun pick(due: List<Endgame>, now: Long, lastKey: String? = null): Endgame? {
        val ready = due.filter { it.dueAt <= now }
        val pool = ready.filter { lastKey == null || family(it.sourceKey) != family(lastKey) }.ifEmpty { ready }
        return pool.sortedWith(
            compareBy<Endgame>({
                when {
                    it.seen > 0 && !it.lastHit -> 0
                    it.seen == 0 -> 1
                    else -> 2
                }
            }, { it.dueAt }, { it.id }),
        ).firstOrNull()
    }

    private fun family(key: String) = key.substringBefore('#')

    /** 一行统计：见过几局、第一下点中几成 */
    fun line(all: List<Endgame>): String {
        val seen = all.filter { it.seen > 0 }
        if (seen.isEmpty()) return if (all.isEmpty()) "还没有残局。导入题库或分享一段文字进来，后台会加工成残局。" else "${all.size} 局待下。"
        val tries = seen.sumOf { it.seen }
        val hits = seen.sumOf { it.hits }
        return "${all.size} 局 · 下过 $tries 次 · 第一下点中 ${hits * 100 / tries}%"
    }

    // ---- 来源一：题库里带依据句的题，本机直接变成残局（不经过模型） ----

    /** 截多长：残局要一屏看完 */
    const val MAX_TEXT = 160

    fun fromCard(cardId: Long, card: Card, now: Long): Endgame? {
        if (!card.hasEvidence) return null
        val ev = card.evidence.min() - 1
        if (ev !in card.sentences.indices) return null
        // 从依据句往两边轮流加句子，直到再加就超长
        var from = ev
        var to = ev
        fun len(a: Int = from, b: Int = to) = (a..b).sumOf { card.sentences[it].length }
        while (true) {
            val canL = from > 0 && len(from - 1, to) <= MAX_TEXT
            val canR = to < card.sentences.lastIndex && len(from, to + 1) <= MAX_TEXT
            if (!canL && !canR) break
            if (canL && (!canR || ev - from <= to - ev)) from-- else to++
        }
        if (len() > MAX_TEXT + 60) return null
        val text = (from..to).joinToString("") { card.sentences[it] }
        val crux = card.sentences[ev]
        val e = Endgame(
            text = text, crux = crux,
            ask = card.question.take(80),
            aim = "这道题的答案，靠哪一句定下来？点它。",
            shot = "答案 ${Card.letter(card.answer)}：${card.options[card.answer]}" +
                card.explain.lines().firstOrNull { it.isNotBlank() }?.let { "\n" + it.trim().take(80) }.orEmpty(),
            source = EndgameSource.CARD, sourceKey = "card:$cardId", createdAt = now,
        )
        // 只有一两句的不算残局：点哪都中
        if (segments(text).size < 3 || cruxSegs(e).size * 2 > segments(text).size) return null
        return e
    }

    // ---- 来源二：模型把你导入 / 分享的原文加工成残局，本机核对 ----

    data class Draft(val text: String, val crux: String, val aim: String, val shot: String, val law: String)

    /** 不合格的原因；合格返回 null */
    fun problem(source: String, d: Draft): String? = when {
        d.text.length < 30 -> "残局太短"
        d.text.length > MAX_TEXT + 60 -> "残局太长"
        d.crux.length < 2 || d.crux.length > 40 -> "破绽长度不对"
        !d.text.contains(d.crux) -> "破绽不在残局里"
        !Words.quoted(source, d.text.replace("……", "").replace("...", "")) && !pieces(source, d.text) -> "残局不是原文"
        d.shot.isBlank() -> "没有底牌"
        segments(d.text).size < 3 -> "残局太碎"
        d.crux.length * 2 > d.text.length -> "破绽太大"
        else -> null
    }

    /** 用“……”接起来的几段，每一段都得是原文 */
    private fun pieces(source: String, text: String): Boolean {
        val parts = text.split("……", "...").map { it.trim() }.filter { Words.norm(it).length >= 4 }
        return parts.size >= 2 && parts.all { Words.quoted(source, it) }
    }

    fun fromDraft(d: Draft, key: String, now: Long) = Endgame(
        text = d.text.trim(), crux = d.crux.trim(),
        ask = "破绽在哪？点出来。",
        aim = d.aim.trim().ifEmpty { "找那个一换就翻盘的条件。" }.take(30),
        shot = d.shot.trim().take(80), law = d.law.trim().take(40),
        source = EndgameSource.MATERIAL, sourceKey = key, createdAt = now,
    )
}
