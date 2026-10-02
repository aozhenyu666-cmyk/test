package com.behaviordept.app.study

import com.behaviordept.app.data.Rating
import com.behaviordept.app.data.StudyUnit
import java.time.LocalDate

/** 学习单元的四步。 */
enum class UnitStep(val number: Int, val title: String, val hint: String) {
    PREVIEW(1, "预习提问", "看资料前，先写下 2–3 个你想弄明白的问题"),
    STUDY(2, "读资料", "带着问题读一遍资料"),
    EXPLAIN(3, "合上讲一遍", "合上资料，用自己的话讲一遍"),
    TRANSFER(4, "举一反三", "找一个表面不同、底层相同的例子"),
    DONE(5, "完成", "四步都走完了，按间隔自测巩固");

    companion object {
        val steps = listOf(PREVIEW, STUDY, EXPLAIN, TRANSFER)
    }
}

fun StudyUnit.currentStep(): UnitStep = when {
    preQuestions.isBlank() -> UnitStep.PREVIEW
    studiedAt == null -> UnitStep.STUDY
    explainedAt == null -> UnitStep.EXPLAIN
    transferredAt == null -> UnitStep.TRANSFER
    else -> UnitStep.DONE
}

fun StudyUnit.preQuestionList(): List<String> =
    preQuestions.lines().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 间隔规则（PRD M2）：间隔 1/3/7/14/30/60 天。
 * 记得 → 升一级；模糊 → 不变；忘了 → 回到 1 天。讲完一遍后第一次自测在第二天。
 */
object Spacing {
    val INTERVALS = listOf(1, 3, 7, 14, 30, 60)

    fun days(level: Int): Int = INTERVALS[level.coerceIn(0, INTERVALS.lastIndex)]

    /** 讲完一遍之后：等级 0，第二天自测。 */
    fun afterExplain(today: LocalDate): Pair<Int, LocalDate> = 0 to today.plusDays(1)

    /** 自测判定后：返回新的等级和下次自测日期。 */
    fun afterReview(level: Int, rating: String, today: LocalDate): Pair<Int, LocalDate> {
        val newLevel = when (rating) {
            Rating.REMEMBER -> (level + 1).coerceAtMost(INTERVALS.lastIndex)
            Rating.FUZZY -> level.coerceIn(0, INTERVALS.lastIndex)
            else -> 0
        }
        return newLevel to today.plusDays(days(newLevel).toLong())
    }
}
