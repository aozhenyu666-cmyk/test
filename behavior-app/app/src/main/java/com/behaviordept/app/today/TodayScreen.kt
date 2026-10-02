package com.behaviordept.app.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.data.Event
import com.behaviordept.app.data.EventType
import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.study.IntervalLadder
import com.behaviordept.app.study.StepGrid
import com.behaviordept.app.study.UnitStep
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.data.RuleToday
import com.behaviordept.app.record.Metrics
import com.behaviordept.app.training.RetestState
import com.behaviordept.app.training.SkillPlan
import com.behaviordept.app.ui.components.LimitBar
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.SevenDays
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.mapLatest
import com.behaviordept.app.ui.components.BigNumber
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.components.TianZiGeCell
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.roundToInt

data class DayCell(val date: LocalDate, val trained: Boolean, val isToday: Boolean, val isFuture: Boolean)

data class TodayState(
    val next: NextAction,
    val otherDue: Int,
    val week: List<DayCell>,
    val trainedDays: Int,
    val todayMinutes: Int,
    val weekMinutes: Int,
    /** 防线状态条：每条规则今天的用时；没开权限时为空。 */
    val guard: List<RuleToday> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    /** 每分钟走一次，跨过零点时“今天”和到期自测自动更新。 */
    private val clock = flow {
        while (true) {
            emit(Time.now())
            delay(60_000)
        }
    }

    val state: StateFlow<TodayState?> = combine(
        c.study.observeUnits(),
        c.events.observeSince(Time.startOf(Time.weekStart().minusDays(7))),
        clock,
        c.training.observeSkills(),
        c.guard.observeRules(),
    ) { units, events, now, _, _ -> Triple(units, events, now) }
        .mapLatest { (units, events, now) -> build(units, events, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun build(units: List<StudyUnit>, events: List<Event>, now: Long): TodayState {
        val today = Time.dateOf(now)
        val monday = Time.weekStart(today)
        val trainedDates = Metrics.trainedDates(events, Time::dateOf)
        val week = (0L..6L).map { i ->
            val d = monday.plusDays(i)
            DayCell(d, d in trainedDates, d == today, d.isAfter(today))
        }
        val sessionMinutes = events.filter { it.type == EventType.SESSION }
        val todayMinutes = sessionMinutes.filter { Time.dateOf(it.time) == today }.sumOf { it.value ?: 0.0 }
        val weekMinutes = sessionMinutes.filter { !Time.dateOf(it.time).isBefore(monday) }.sumOf { it.value ?: 0.0 }
        val next = computeNextAction(units, now, c.training.plans(now))
        val dueCount = units.count { it.isReviewDue(now) }
        return TodayState(
            next = next,
            otherDue = if (next is NextAction.Review) dueCount - 1 else dueCount,
            week = week,
            trainedDays = week.count { it.trained },
            todayMinutes = todayMinutes.roundToInt(),
            weekMinutes = weekMinutes.roundToInt(),
            guard = if (c.guard.hasUsagePermission) c.guard.today(now) else emptyList(),
        )
    }
}

/**
 * 今日：打开只看到一件事和一个按钮。冷启动 → 点“开始” → 进入专注，全程没有需要自己做的选择。
 */
@Composable
fun TodayScreen(onStart: (NextAction) -> Unit, onUrge: () -> Unit) {
    val vm = appViewModel { TodayViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return
    TodayContent(s, Time.today(), onStart, onUrge)
}

/** 专项练 / 复测时，卡片里显示当前重点 7 天里练了哪几天。 */
@Composable
private fun FocusDays(plan: SkillPlan, today: LocalDate) {
    val since = plan.skill.focusSince ?: return
    val start = Time.dateOf(since)
    SevenDays(
        checked = (0 until 7).map { start.plusDays(it.toLong()) in plan.drillDates },
        todayIndex = (today.toEpochDay() - start.toEpochDay()).toInt(),
        cell = 32.dp,
    )
}

/** 无状态的今日页面，方便预览和截图。 */
@Composable
fun TodayContent(s: TodayState, today: LocalDate, onStart: (NextAction) -> Unit, onUrge: () -> Unit = {}) {
    val p = Paper.colors

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // 一件事卡至少占首屏上半部分，按钮压在卡片底部。
    val cardHeight = maxOf(400.dp, maxHeight * 0.58f)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Time.md(today) + " " + Time.weekdayName(today),
                style = MaterialTheme.typography.titleMedium,
                color = p.ink2,
            )
        }

        // 今日一件事：大号宋体标题 + 一句说明 + 一个墨水色按钮，占首屏上半部分。
        PaperCard(
            modifier = Modifier.heightIn(min = cardHeight),
            padding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                SectionLabel("今日一件事 · ${s.next.kindLabel()}")
                Spacer(Modifier.height(18.dp))
                Text(
                    s.next.headline(),
                    style = MaterialTheme.typography.displaySmall,
                    color = p.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    s.next.description(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.ink2,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.padding(top = 28.dp)) {
                // 下一步在整个流程里的位置：学习单元看步骤格，自测看间隔阶梯。
                when (val n = s.next) {
                    is NextAction.Review -> IntervalLadder(n.unit.intervalLevel)
                    is NextAction.Step -> StepGrid(n.step)
                    NextAction.NewUnit -> StepGrid(UnitStep.PREVIEW)
                    is NextAction.Drill -> FocusDays(n.plan, today)
                    is NextAction.Retest -> FocusDays(n.plan, today)
                }
                Spacer(Modifier.height(24.dp))
                InkButton(
                    if (s.next is NextAction.Retest && (s.next as NextAction.Retest).state is RetestState.Ready) "去看结果" else "开始",
                    onClick = { onStart(s.next) },
                    height = 60.dp,
                )
                if (s.otherDue > 0) {
                    Spacer(Modifier.height(10.dp))
                    Hint("另有 ${s.otherDue} 个自测今天到期，做完这一件再说", Modifier.fillMaxWidth())
                }
            }
        }

        // 本周田字格
        PaperCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("本周", Modifier.weight(1f))
                BigNumber("已练", s.trainedDays.toString(), "天", red = s.trainedDays >= 5)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                s.week.forEach { day ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TianZiGeCell(done = day.trained, highlight = day.isToday, size = 40.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            Time.weekdayName(day.date).removePrefix("周"),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (day.isToday) p.ink else p.ink2,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Hint("今天专注 ${s.todayMinutes} 分钟 · 本周 ${s.weekMinutes} 分钟 · 目标每周至少 5 天")
        }

        // 防线状态条：今天还剩多少娱乐额度，超限变红。
        s.guard.forEach { rt ->
            PaperCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("防线 · ${rt.rule.name.ifBlank { "娱乐" }}", Modifier.weight(1f))
                    Text(
                        if (rt.over) "超限 ${rt.minutes - rt.rule.dailyLimitMin} 分钟" else "还剩 ${rt.rule.dailyLimitMin - rt.minutes} 分钟",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (rt.over) p.red else p.ink,
                    )
                }
                Spacer(Modifier.height(10.dp))
                LimitBar(rt.minutes, rt.rule.dailyLimitMin)
            }
        }
        QuietButton("我想刷…", onClick = onUrge)
    }
    }
}
