package app.jobtracker.domain

import app.jobtracker.config.AppConfig
import app.jobtracker.data.db.ApplicationEntity
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.EndResult
import java.time.LocalDate

/**
 * 状态、日期变化时自动排"下次跟进日期"的规则。纯函数，不读写数据库。
 *
 * - 改为"已投递"：投递日期为空则填今天；下次跟进 = 投递日期 + N 天
 * - 改为"面试中"：跟进次数清零；填了面试日期则下次跟进 = 面试日期 + N 天，没填则不动
 * - 改为"已结束"：必须带结果，清空下次跟进日期
 * - 从"已结束"改回其他状态：清空结果，再按新状态套用规则
 * - 第一次填投递日期 / 面试日期：重算下次跟进日期
 * - 再改这两个日期：下次跟进日期还是自动算出来的值（或为空）就跟着重算，手改过就不动
 */
class FollowUpRules(private val config: AppConfig) {

    fun changeStatus(
        app: ApplicationEntity,
        status: ApplicationStatus,
        today: LocalDate,
        endResult: EndResult? = null,
    ): ApplicationEntity {
        if (status == ApplicationStatus.CLOSED) {
            require(endResult != null) { "已结束必须选择结果" }
            return app.copy(status = status, endResult = endResult, nextFollowUpAt = null)
        }
        if (status == app.status) return app
        val base = app.copy(status = status, endResult = null)
        return when (status) {
            ApplicationStatus.APPLIED -> {
                val appliedAt = base.appliedAt ?: today
                base.copy(appliedAt = appliedAt, nextFollowUpAt = afterApplied(appliedAt))
            }
            ApplicationStatus.INTERVIEWING -> base.copy(
                followUpCount = 0,
                nextFollowUpAt = base.interviewAt?.let(::afterInterview) ?: base.nextFollowUpAt,
            )
            else -> base
        }
    }

    fun changeAppliedAt(app: ApplicationEntity, date: LocalDate?): ApplicationEntity {
        val updated = app.copy(appliedAt = date)
        if (app.status != ApplicationStatus.APPLIED || date == null) return updated
        // 第一次填日期总是重算；之后只在跟进日期还是自动值时重算
        val wasAuto = app.appliedAt == null || app.nextFollowUpAt == null || app.nextFollowUpAt == afterApplied(app.appliedAt)
        return if (wasAuto) updated.copy(nextFollowUpAt = afterApplied(date)) else updated
    }

    fun changeInterviewAt(app: ApplicationEntity, date: LocalDate?): ApplicationEntity {
        val updated = app.copy(interviewAt = date)
        if (app.status != ApplicationStatus.INTERVIEWING || date == null) return updated
        // 第一次填日期总是重算；之后只在跟进日期还是自动值时重算
        val wasAuto = app.interviewAt == null || app.nextFollowUpAt == null || app.nextFollowUpAt == afterInterview(app.interviewAt)
        return if (wasAuto) updated.copy(nextFollowUpAt = afterInterview(date)) else updated
    }

    private fun afterApplied(date: LocalDate) = date.plusDays(config.followUpAfterAppliedDays)
    private fun afterInterview(date: LocalDate) = date.plusDays(config.followUpAfterInterviewDays)
}

/** 列表和详情页上"下次跟进日期"的状态 */
sealed interface DueState {
    data object None : DueState
    data object Today : DueState
    data class Overdue(val days: Long) : DueState
    data class Upcoming(val days: Long) : DueState

    companion object {
        fun of(app: ApplicationEntity, today: LocalDate): DueState {
            val next = app.nextFollowUpAt
            if (next == null || app.status == ApplicationStatus.CLOSED) return None
            val diff = next.toEpochDay() - today.toEpochDay()
            return when {
                diff == 0L -> Today
                diff < 0 -> Overdue(-diff)
                else -> Upcoming(diff)
            }
        }
    }
}
