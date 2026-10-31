package com.yishou.app.think

/**
 * 逻辑词：转折、限定、否定、条件、因果。读材料时让它们发亮，背景的废话暗下去；
 * 点一下就能钉到屏幕顶上的“已锁定条件”里，推到后面不用回头找。
 */
object Logic {

    enum class Kind(val label: String) { TURN("转折"), LIMIT("限定"), NEG("否定"), COND("条件"), CAUSE("因果") }

    private val WORDS: List<Pair<String, Kind>> = (
        listOf("但是", "然而", "不过", "可是", "却", "但", "反而", "尽管", "虽然").map { it to Kind.TURN } +
            listOf("只有", "唯有", "必须", "仅仅", "仅", "只", "至少", "至多", "所有", "全部", "一切", "凡是", "都", "一定", "必然", "绝对", "完全", "主要", "大多数", "部分", "有的").map { it to Kind.LIMIT } +
            listOf("并非", "不是", "没有", "不会", "不能", "无法", "未必", "不", "没", "无", "未", "非", "否").map { it to Kind.NEG } +
            listOf("如果", "假如", "倘若", "除非", "只要", "一旦", "若", "则", "才", "否则").map { it to Kind.COND } +
            listOf("因为", "所以", "因此", "由于", "导致", "从而", "因而", "可见", "说明", "以致").map { it to Kind.CAUSE }
        ).sortedByDescending { it.first.length }

    data class Mark(val start: Int, val end: Int, val kind: Kind) {
        val range: IntRange get() = start until end
    }

    /** 找出所有逻辑词的位置（长词优先，不重叠） */
    fun marks(text: String): List<Mark> {
        val taken = BooleanArray(text.length)
        val out = mutableListOf<Mark>()
        for ((w, k) in WORDS) {
            var i = text.indexOf(w)
            while (i >= 0) {
                val end = i + w.length
                if ((i until end).none { taken[it] }) {
                    for (j in i until end) taken[j] = true
                    out += Mark(i, end, k)
                }
                i = text.indexOf(w, i + 1)
            }
        }
        return out.sortedBy { it.start }
    }

    /** 点了某个位置：取出包含它的那一小段（到逗号句号为止），当作一个条件钉到顶上 */
    fun clauseAt(text: String, index: Int): String {
        if (text.isEmpty()) return ""
        val i = index.coerceIn(0, text.length - 1)
        val stops = "，。；！？,;!?\n"
        var a = i
        while (a > 0 && text[a - 1] !in stops) a--
        var b = i
        while (b < text.length && text[b] !in stops) b++
        return text.substring(a, b).trim()
    }

    /** 一句话是不是“只有当 X，才 Y”这类条件句 */
    fun conditional(text: String): Boolean {
        val head = listOf("只有", "如果", "当", "一旦", "除非", "只要", "假如", "若")
        val tail = listOf("才", "就", "则", "那么", "否则", "会", "能")
        val h = head.mapNotNull { w -> text.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: return false
        return tail.any { w -> text.indexOf(w, h + 1) > h }
    }
}
