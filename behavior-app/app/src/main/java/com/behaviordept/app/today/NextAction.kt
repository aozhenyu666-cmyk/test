package com.behaviordept.app.today

import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.study.Spacing
import com.behaviordept.app.study.UnitStep
import com.behaviordept.app.study.currentStep

/** 今日一件事：系统替你算好的唯一下一步。 */
sealed interface NextAction {
    data class Review(val unit: StudyUnit) : NextAction
    data class Step(val unit: StudyUnit, val step: UnitStep) : NextAction
    data object NewUnit : NextAction

    /** 进入专注模式时传的单元 id，新建为 -1。 */
    val unitId: Long
        get() = when (this) {
            is Review -> unit.id
            is Step -> unit.id
            NewUnit -> -1L
        }
}

fun StudyUnit.isReviewDue(now: Long): Boolean = nextReviewAt != null && nextReviewAt <= now

/**
 * 顺序（PRD M1）：到期自测 → 进行中单元的下一步 → 当前短板专项练（阶段 3 才有）→ 新建。
 * 到期自测里先做过期最久的；进行中的单元里先做走得最远的，先把开了头的做完。
 */
fun computeNextAction(units: List<StudyUnit>, now: Long): NextAction {
    units.filter { it.isReviewDue(now) }
        .minByOrNull { it.nextReviewAt ?: Long.MAX_VALUE }
        ?.let { return NextAction.Review(it) }
    units.filter { it.currentStep() != UnitStep.DONE }
        .sortedWith(compareByDescending<StudyUnit> { it.currentStep().number }.thenBy { it.createdAt })
        .firstOrNull()
        ?.let { return NextAction.Step(it, it.currentStep()) }
    return NextAction.NewUnit
}

fun NextAction.headline(): String = when (this) {
    is NextAction.Review -> "自测：${unit.title}"
    is NextAction.Step -> "${step.title}：${unit.title}"
    NextAction.NewUnit -> "新建一个学习单元"
}

fun NextAction.description(): String = when (this) {
    is NextAction.Review -> "不看资料，回答 3 道新题。距上次约 ${Spacing.days(unit.intervalLevel)} 天"
    is NextAction.Step -> step.hint
    NextAction.NewUnit -> "粘贴一小块资料——一次学得完的量，然后先写预习问题"
}

fun NextAction.kindLabel(): String = when (this) {
    is NextAction.Review -> "间隔自测"
    is NextAction.Step -> "学习 · 第 ${step.number} 步"
    NextAction.NewUnit -> "新建"
}
