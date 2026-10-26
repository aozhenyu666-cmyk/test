package com.yishou.app.deed

import com.yishou.app.data.Deed
import kotlin.math.abs

/**
 * 实操：做一件真事之前，先估一个数（花多少钱、用多久、买几样），做完拿现实（小票、订单、计时的截图或手填）对照。
 * 判卷的是现实本身，不是模型。
 */
object Deeds {

    /** 偏差：|实际 − 预计| ÷ 实际。实际为 0 时按预计算。 */
    fun error(estimate: Double, actual: Double): Double {
        val base = if (actual != 0.0) abs(actual) else abs(estimate).coerceAtLeast(1.0)
        return abs(actual - estimate) / base
    }

    /** 准（≤10%）、接近（≤25%）、偏了 */
    fun verdict(estimate: Double, actual: Double): String {
        val e = error(estimate, actual)
        return when {
            e <= 0.10 -> "准"
            e <= 0.25 -> "接近"
            else -> "偏了"
        }
    }

    fun good(estimate: Double, actual: Double): Boolean = error(estimate, actual) <= 0.25

    data class Summary(
        val resolved: Int,
        /** 最近 10 次偏差的中位数（百分比） */
        val medianError: Int?,
        /** 平均带方向的偏差：正数是习惯估高，负数是习惯估低（百分比） */
        val bias: Int?,
    )

    fun summary(deeds: List<Deed>): Summary {
        val done = deeds.filter { it.actual != null }.sortedByDescending { it.resolvedAt ?: it.createdAt }.take(10)
        if (done.isEmpty()) return Summary(0, null, null)
        val errs = done.map { error(it.estimate, it.actual!!) }.sorted()
        val median = errs[errs.size / 2]
        val signed = done.map { d ->
            val a = d.actual!!
            if (a == 0.0) 0.0 else (d.estimate - a) / abs(a)
        }.average()
        return Summary(deeds.count { it.actual != null }, (median * 100).toInt(), (signed * 100).toInt())
    }

    fun line(s: Summary): String {
        val m = s.medianError ?: return "还没有对照过的实操。做事之前估一个数，做完回来对。"
        val bias = s.bias ?: 0
        val lean = when {
            bias >= 10 -> "，习惯估高 $bias%"
            bias <= -10 -> "，习惯估低 ${-bias}%"
            else -> ""
        }
        return "最近对照 ${minOf(s.resolved, 10)} 次，偏差中位数 $m%$lean。"
    }
}
