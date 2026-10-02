package com.behaviordept.app.today

import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.data.SubSkill
import com.behaviordept.app.data.Template
import com.behaviordept.app.study.Spacing
import com.behaviordept.app.study.UnitStep
import com.behaviordept.app.study.currentStep
import com.behaviordept.app.training.RetestState
import com.behaviordept.app.training.SkillPlan
import kotlin.math.roundToInt

/** 今日一件事：系统替你算好的唯一下一步。 */
sealed interface NextAction {
    data class Review(val unit: StudyUnit) : NextAction
    data class Step(val unit: StudyUnit, val step: UnitStep) : NextAction

    /** 当前短板的专项练。 */
    data class Drill(val plan: SkillPlan, val focus: SubSkill, val drill: com.behaviordept.app.data.Drill) : NextAction

    /** 练满 7 天后的实战复测（或复测结果出来了，等你决定）。 */
    data class Retest(val plan: SkillPlan, val focus: SubSkill, val state: RetestState) : NextAction

    data object NewUnit : NextAction

    /** 学习单元相关的动作对应的单元 id；新建为 -1，其他动作为 null。 */
    val unitId: Long?
        get() = when (this) {
            is Review -> unit.id
            is Step -> unit.id
            NewUnit -> -1L
            else -> null
        }
}

fun StudyUnit.isReviewDue(now: Long): Boolean = nextReviewAt != null && nextReviewAt <= now

/**
 * 顺序（PRD M1）：到期自测 → 进行中单元的下一步 → 实战复测 → 当前短板专项练 → 新建。
 * 到期自测里先做过期最久的；进行中的单元里先做走得最远的，先把开了头的做完。
 * 专项练只在今天还没练过这个重点时出现。
 */
fun computeNextAction(units: List<StudyUnit>, now: Long, plans: List<SkillPlan> = emptyList()): NextAction {
    units.filter { it.isReviewDue(now) }
        .minByOrNull { it.nextReviewAt ?: Long.MAX_VALUE }
        ?.let { return NextAction.Review(it) }
    units.filter { it.currentStep() != UnitStep.DONE }
        .sortedWith(compareByDescending<StudyUnit> { it.currentStep().number }.thenBy { it.createdAt })
        .firstOrNull()
        ?.let { return NextAction.Step(it, it.currentStep()) }
    plans.firstOrNull { it.focus != null && it.retest !is RetestState.NotDue }
        ?.let { return NextAction.Retest(it, it.focus!!, it.retest) }
    plans.firstOrNull { it.focus != null && it.nextDrill != null && !it.drilledToday }
        ?.let { return NextAction.Drill(it, it.focus!!, it.nextDrill!!) }
    return NextAction.NewUnit
}

private fun pct(v: Double?): String = v?.let { "${(it * 100).roundToInt()}%" } ?: "—"

fun NextAction.headline(): String = when (this) {
    is NextAction.Review -> "自测：${unit.title}"
    is NextAction.Step -> "${step.title}：${unit.title}"
    is NextAction.Drill -> "专项练：${drill.title}"
    is NextAction.Retest -> when (state) {
        is RetestState.Ready -> "复测结果：${focus.name}"
        else -> when (plan.skill.template) {
            Template.COMPETITIVE -> "复测：打几局，登记死因"
            Template.EXAM -> "复测：做一套模考并登记"
            else -> "复测：实战一次，对照标准"
        }
    }
    NextAction.NewUnit -> "新建一个学习单元"
}

fun NextAction.description(): String = when (this) {
    is NextAction.Review -> "不看资料，回答 3 道新题。距上次约 ${Spacing.days(unit.intervalLevel)} 天"
    is NextAction.Step -> step.hint
    is NextAction.Drill -> buildString {
        append("当前重点「${focus.name}」· ${drill.minutes} 分钟")
        if (drill.metric.isNotBlank()) append(" · 练完记 ${drill.metric}")
    }
    is NextAction.Retest -> when (val s = state) {
        is RetestState.Ready -> "${pct(s.before)} → ${pct(s.after)}，" + (if (s.improved) "有进步。" else "还没见效。") + "去决定继续练还是换下一个短板"
        is RetestState.Collecting -> when (plan.skill.template) {
            Template.COMPETITIVE -> "「${focus.name}」练满 7 天了。再登记 ${s.need - s.have} 次阵亡，看这一类死因有没有变少"
            Template.EXAM -> "「${focus.name}」练满 7 天了。做一套模考，看这个模块正确率有没有提高"
            else -> "「${focus.name}」练满 7 天了。实战录一段，逐条对照标准打分"
        }
        RetestState.NotDue -> ""
    }
    NextAction.NewUnit -> "粘贴一小块资料——一次学得完的量，然后先写预习问题"
}

fun NextAction.kindLabel(): String = when (this) {
    is NextAction.Review -> "间隔自测"
    is NextAction.Step -> "学习 · 第 ${step.number} 步"
    is NextAction.Drill -> "专项练 · ${plan.skill.name}"
    is NextAction.Retest -> "实战复测 · ${plan.skill.name}"
    NextAction.NewUnit -> "新建"
}
