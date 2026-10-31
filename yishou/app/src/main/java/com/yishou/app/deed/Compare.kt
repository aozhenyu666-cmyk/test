package com.yishou.app.deed

import com.yishou.app.think.Words

/** 规格的量纲：按重量、按容量、按个数 */
enum class Dim(val per: String) {
    MASS("斤"),
    VOLUME("升"),
    COUNT("个"),
}

/** 规格：折成基本单位（克、毫升、个）的总量 */
data class Spec(val qty: Double, val dim: Dim) {

    /** 每“斤 / 升 / 个”多少钱 */
    fun unitPrice(price: Double): Double = when (dim) {
        Dim.MASS -> price / qty * 500
        Dim.VOLUME -> price / qty * 1000
        Dim.COUNT -> price / qty
    }

    companion object {
        private val MASS = linkedMapOf("kg" to 1000.0, "千克" to 1000.0, "公斤" to 1000.0, "斤" to 500.0, "两" to 50.0, "克" to 1.0, "g" to 1.0)
        private val VOLUME = linkedMapOf("ml" to 1.0, "毫升" to 1.0, "l" to 1000.0, "升" to 1000.0)
        private val COUNT = listOf("个", "枚", "只", "片", "包", "袋", "瓶", "盒", "支", "根", "卷", "条", "听", "罐", "颗", "粒", "件", "双", "pcs")
        private val TIMES = Regex("[×xX*]\\s*(\\d+)|(\\d+)\\s*[×xX*]")

        /**
         * 认规格：500g、2.5kg、1斤、半斤、2两、1.5L、250ml×4、6个装、12枚。
         * 认不出来返回 null（这一件不参与比较）。
         */
        fun parse(raw: String): Spec? {
            val s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            // 乘几：250ml×4、4×250ml
            var times = 1.0
            var body = s
            TIMES.find(s)?.let { m ->
                val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toDoubleOrNull()
                if (n != null && n > 0) {
                    times = n
                    body = s.removeRange(m.range)
                }
            }
            val amount = Words.numbers(body).firstOrNull() ?: if ("半" in body) 0.5 else null
            fun first(map: Map<String, Double>): Double? = map.entries.firstOrNull { it.key in body }?.value
            val counted = COUNT.any { it in body }
            // “两瓶”的“两”是数，不是重量单位
            val mass = MASS.entries.firstOrNull { it.key in body && !(it.key == "两" && counted) }?.value
            mass?.let { u -> return Spec((amount ?: 1.0) * u * times, Dim.MASS).takeIf { it.qty > 0 } }
            first(VOLUME)?.let { u -> return Spec((amount ?: 1.0) * u * times, Dim.VOLUME).takeIf { it.qty > 0 } }
            if (counted) return Spec((amount ?: 1.0) * times, Dim.COUNT).takeIf { it.qty > 0 }
            return null
        }
    }
}

/** 截图里认出的一件商品 */
data class Goods(val name: String, val price: Double, val spec: String) {
    val parsed: Spec? get() = Spec.parse(spec)
}

/** 比完的一件：单价（认不出规格时为 null） */
data class Priced(val index: Int, val goods: Goods, val unitPrice: Double?, val dim: Dim?)

/**
 * 比一比：先凭感觉挑最划算的那件，再看折算后的单价。
 * 判对错只靠算术：你挑的那件单价不高于最便宜的 1%，就算挑对。
 * 只在量纲相同的商品之间比（重量和个数没法比），取件数最多的那一组。
 */
object Compare {

    fun price(goods: List<Goods>): List<Priced> = goods.mapIndexed { i, g ->
        val sp = g.parsed
        Priced(i, g, sp?.unitPrice(g.price)?.takeIf { g.price > 0 }, sp?.dim)
    }

    /** 参与比较的那一组（量纲相同、件数最多） */
    fun group(goods: List<Goods>): List<Priced> {
        val priced = price(goods).filter { it.unitPrice != null }
        val dim = priced.groupBy { it.dim }.maxByOrNull { it.value.size }?.key ?: return emptyList()
        return priced.filter { it.dim == dim }
    }

    /** 最划算的下标；比不了（可比的不到两件）返回 null */
    fun best(goods: List<Goods>): Int? {
        val g = group(goods)
        if (g.size < 2) return null
        return g.minByOrNull { it.unitPrice!! }!!.index
    }

    fun correct(goods: List<Goods>, pick: Int): Boolean? {
        val b = best(goods) ?: return null
        val g = group(goods)
        val mine = g.firstOrNull { it.index == pick }?.unitPrice ?: return false
        val low = g.first { it.index == b }.unitPrice!!
        return mine <= low * 1.01
    }

    /** 你挑的比最便宜的贵多少（百分比，挑对是 0） */
    fun overpay(goods: List<Goods>, pick: Int): Int? {
        val b = best(goods) ?: return null
        val g = group(goods)
        val mine = g.firstOrNull { it.index == pick }?.unitPrice ?: return null
        val low = g.first { it.index == b }.unitPrice!!
        return ((mine / low - 1) * 100).toInt().coerceAtLeast(0)
    }

    fun show(v: Double): String = if (v >= 100) "%.0f".format(v) else "%.2f".format(v)
}
