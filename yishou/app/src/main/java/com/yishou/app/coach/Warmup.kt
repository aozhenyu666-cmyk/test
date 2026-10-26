package com.yishou.app.coach

import kotlin.random.Random

/**
 * 热身的第二段：心算接龙。每一步都用上一步的结果——就是“把上一结论接到下一步”，只是负荷很低。
 * 像运动员热身用的是同一个动作的轻量版，而不是去做别的运动。
 */
object Warmup {
    data class Step(val op: String, val result: Int)
    data class Chain(val start: Int, val steps: List<Step>)

    const val BREATHS = 6
    const val INHALE_MS = 4_000L
    const val EXHALE_MS = 6_000L

    /** 生成一条接龙：数一直在 1–99 之间，乘法只在数小时出现 */
    fun chain(random: Random, steps: Int = 5): Chain {
        val start = random.nextInt(12, 40)
        var v = start
        val out = mutableListOf<Step>()
        repeat(steps) {
            val options = buildList {
                if (v <= 90) add("+")
                if (v > 12) add("-")
                if (v <= 45) add("x")
                if (v % 2 == 0 && v >= 20) add("/")
            }
            val step = when (options[random.nextInt(options.size)]) {
                "+" -> random.nextInt(3, 10).let { n -> Step("+$n", v + n) }
                "-" -> random.nextInt(3, minOf(10, v - 2)).let { n -> Step("−$n", v - n) }
                "x" -> Step("×2", v * 2)
                else -> Step("÷2", v / 2)
            }
            v = step.result
            out += step
        }
        return Chain(start, out)
    }

    fun score(right: Int, total: Int, seconds: Long): String = "心算 $right/$total，$seconds 秒"
}
