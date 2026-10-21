package com.moling.core.predict

import com.moling.core.link.ZkStatus
import kotlinx.serialization.Serializable

/*
 * 预言账本：墨灵对你下注，然后自己对账。
 *
 * 只允许能从总控记录里算出来的预言（娱乐分钟、做事分钟、主线开没开始、花了多少），这样对错不靠嘴说。
 * 命中率（Brier 分）决定墨灵能挣到多高的权柄；高把握的预言还会触发提前介入（预言今晚会刷，就提前一步说）。
 */

@Serializable
enum class Metric(val label: String) { PLAY("娱乐分钟"), WORK("做事分钟"), MAIN_STARTED("主线开始了"), SPEND("花了多少（元）") }

@Serializable
data class Prediction(
    val id: String,
    val text: String,
    val metric: Metric,
    /** “>=” 或 “<”；MAIN_STARTED 用 “==”（value 1 = 开始了，0 = 没开始）。 */
    val op: String,
    val value: Double,
    /** 墨灵说的把握（0..1）。 */
    val p: Double,
    val madeAt: Long,
    val dueAt: Long,
    /** null = 还没到点；true / false = 对账结果。 */
    val hit: Boolean? = null,
    val actual: Double? = null,
)

/** 每天的数（对账、找规律、给下一次预言当底子）。 */
@Serializable
data class DayStat(
    val day: String,
    val play: Int,
    val work: Int,
    val mainStarted: Boolean,
    val spendYuan: Double,
    /** 主线：没开始 0、开始了 0.5、做完了 1；-1 = 不知道（旧数据）。 */
    val mainScore: Double = -1.0,
    /** 这一天偏离了几次（阶梯从头开始的次数）、她出声打扰了几次。 */
    val drifts: Int = 0,
    val nudges: Int = 0,
)

object Predict {
    fun measure(m: Metric, s: ZkStatus): Double = when (m) {
        Metric.PLAY -> s.playMinutesToday.toDouble()
        Metric.WORK -> s.workMinutesToday.toDouble()
        Metric.MAIN_STARTED -> if (s.mainStarted || s.mainDone) 1.0 else 0.0
        Metric.SPEND -> s.spendTodayCents / 100.0
    }

    fun holds(op: String, actual: Double, value: Double) = when (op) {
        ">=" -> actual >= value
        "<" -> actual < value
        "==" -> actual == value
        else -> false
    }

    /** 到点的预言对账。 */
    fun settle(list: List<Prediction>, s: ZkStatus, now: Long): List<Prediction> = list.map {
        if (it.hit != null || now < it.dueAt) it else {
            val a = measure(it.metric, s)
            it.copy(hit = holds(it.op, a, it.value), actual = a)
        }
    }

    /** Brier 分：(把握 − 结果)² 的平均。0 最好，0.25 等于瞎猜。没有已对账的返回 -1。 */
    fun brier(list: List<Prediction>): Double {
        val done = list.filter { it.hit != null }
        if (done.isEmpty()) return -1.0
        return done.sumOf { val o = if (it.hit == true) 1.0 else 0.0; (it.p - o) * (it.p - o) } / done.size
    }

    /** 不调模型的预言：拿最近 7 天的中位数当底子。 */
    fun local(history: List<DayStat>, now: Long, dueAt: Long): List<Prediction> {
        val last = history.takeLast(7)
        if (last.size < 3) return emptyList()
        val play = median(last.map { it.play.toDouble() })
        val startedRate = last.count { it.mainStarted }.toDouble() / last.size
        val out = mutableListOf<Prediction>()
        if (play >= 20) out += Prediction("p-$now-0", "今天娱乐会超过 ${play.toInt()} 分钟", Metric.PLAY, ">=", play, 0.55, now, dueAt)
        val willStart = startedRate >= 0.5
        out += Prediction(
            "p-$now-1", if (willStart) "今天主线会开始" else "今天主线不会开始", Metric.MAIN_STARTED, "==", if (willStart) 1.0 else 0.0,
            (if (willStart) startedRate else 1 - startedRate).coerceIn(0.5, 0.9), now, dueAt,
        )
        return out
    }

    fun prompt(history: List<DayStat>, patterns: List<String>, flowText: String) = buildString {
        appendLine("【最近几天】")
        history.takeLast(7).forEach { appendLine("${it.day}：娱乐 ${it.play} 分钟，做事 ${it.work} 分钟，主线${if (it.mainStarted) "开始了" else "没开始"}，花了 ${"%.0f".format(it.spendYuan)} 元") }
        if (patterns.isNotEmpty()) appendLine("【模式】${patterns.joinToString("；")}")
        if (flowText.isNotBlank()) appendLine("【今天的水道】\n$flowText")
        appendLine()
        appendLine("对今天下两个注，必须能用这四个数对账：娱乐分钟 / 做事分钟 / 主线开始了 / 花了多少。把握要诚实（0.5 = 不知道）。只输出：")
        append("【预言】娱乐分钟｜>=｜60｜0.7｜一句话（会发生什么、为什么）")
    }

    fun parse(reply: String, now: Long, dueAt: Long): List<Prediction> =
        Regex("""【预言】([^\n]+)""").findAll(reply).mapIndexedNotNull { i, m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            val metric = Metric.entries.firstOrNull { it.label == f.getOrNull(0) } ?: return@mapIndexedNotNull null
            val op = f.getOrNull(1)?.takeIf { it in setOf(">=", "<", "==") } ?: return@mapIndexedNotNull null
            val v = f.getOrNull(2)?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val p = f.getOrNull(3)?.toDoubleOrNull()?.coerceIn(0.05, 0.95) ?: return@mapIndexedNotNull null
            Prediction("p-$now-$i", f.getOrNull(4).orEmpty().ifBlank { "${metric.label} $op $v" }.take(60), metric, op, v, p, now, dueAt)
        }.take(3).toList()

    /** 给人看的成绩单。 */
    fun scoreText(list: List<Prediction>): String {
        val done = list.filter { it.hit != null }
        if (done.isEmpty()) return "还没有对过账的预言"
        val b = brier(list)
        return "预言对了 ${done.count { it.hit == true }} / ${done.size}，Brier ${"%.2f".format(b)}（0 最好，0.25 等于瞎猜）"
    }

    private fun median(xs: List<Double>): Double = xs.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
}
