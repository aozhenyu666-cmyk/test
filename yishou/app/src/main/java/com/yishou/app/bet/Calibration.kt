package com.yishou.app.bet

/** 一个把握档位上的战绩：说 confidence% 的有 n 次，中了 hits 次。 */
data class Bucket(val confidence: Int, val n: Int, val hits: Int) {
    val rate: Int get() = if (n == 0) 0 else hits * 100 / n
}

data class CalibrationReport(
    val buckets: List<Bucket>,
    val resolved: Int,
    val hits: Int,
    /** 平均把握减去实际命中率（百分点）：正数是自信过头，负数是太保守 */
    val gap: Int?,
    /** Brier 分数，0 最好，0.25 相当于瞎猜 */
    val brier: Double?,
)

/** 校准：你说的把握和实际命中率对不对得上。 */
object Calibration {

    /** pairs：(把握 50–100, 是否中了) */
    fun of(pairs: List<Pair<Int, Boolean>>): CalibrationReport {
        if (pairs.isEmpty()) return CalibrationReport(emptyList(), 0, 0, null, null)
        val buckets = pairs.groupBy { snap(it.first) }
            .map { (c, ps) -> Bucket(c, ps.size, ps.count { it.second }) }
            .sortedBy { it.confidence }
        val hits = pairs.count { it.second }
        val meanConf = pairs.sumOf { it.first }.toDouble() / pairs.size
        val gap = Math.round(meanConf - hits * 100.0 / pairs.size).toInt()
        val brier = pairs.sumOf { (c, hit) ->
            val p = c / 100.0
            val o = if (hit) 1.0 else 0.0
            (p - o) * (p - o)
        } / pairs.size
        return CalibrationReport(buckets, pairs.size, hits, gap, brier)
    }

    /** 档位：50、60……100 */
    fun snap(confidence: Int): Int = ((confidence.coerceIn(50, 100) + 5) / 10) * 10

    /** 一句话结论；样本太少时先不下结论 */
    fun verdict(r: CalibrationReport): String {
        val gap = r.gap ?: return "还没有对照过的下注。押一个，做完回来点中或没中。"
        if (r.resolved < MIN_SAMPLES) return "已对照 ${r.resolved} 次，攒够 $MIN_SAMPLES 次再看准不准。"
        return when {
            gap >= 15 -> "自信过头：平均把握比实际命中率高 $gap 个百分点。下次押之前多找一条依据。"
            gap <= -15 -> "太保守：实际命中率比你说的把握高 ${-gap} 个百分点。可以更敢押。"
            else -> "把握和命中率差 ${kotlin.math.abs(gap)} 个百分点，校得不错。"
        }
    }

    const val MIN_SAMPLES = 5
}
