package com.behaviordept.app.data

/** 一条规则当天的用时。 */
data class RuleToday(val rule: Rule, val minutes: Int) {
    val over: Boolean get() = minutes > rule.dailyLimitMin
    val ratio: Float get() = if (rule.dailyLimitMin <= 0) 1f else minutes.toFloat() / rule.dailyLimitMin
}
