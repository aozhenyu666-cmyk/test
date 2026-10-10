package app.jobtracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.jobtracker.R
import app.jobtracker.data.model.EndResult
import app.jobtracker.domain.DueState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日")

fun LocalDate.display(): String = format(DATE_FORMAT)

/** "10月13日（3 天后）"这类写法；没有日期时返回"未安排" */
@Composable
fun dueText(date: LocalDate?, due: DueState): String {
    if (date == null || due == DueState.None) return stringResource(R.string.due_none)
    val relative = when (due) {
        DueState.Today -> stringResource(R.string.due_today)
        is DueState.Overdue -> stringResource(R.string.due_overdue, due.days)
        is DueState.Upcoming -> stringResource(R.string.due_upcoming, due.days)
        DueState.None -> ""
    }
    return stringResource(R.string.due_format, date.display(), relative)
}

fun EndResult.labelRes(): Int = when (this) {
    EndResult.REJECTED -> R.string.end_rejected
    EndResult.NO_RESPONSE -> R.string.end_no_response
    EndResult.WITHDRAWN -> R.string.end_withdrawn
    EndResult.OFFER -> R.string.end_offer
}
