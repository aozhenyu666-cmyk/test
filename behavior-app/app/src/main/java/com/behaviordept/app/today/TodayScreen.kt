package com.behaviordept.app.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.behaviordept.app.ui.appViewModel
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
)

class TodayViewModel(c: AppContainer) : ViewModel() {
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
    ) { units, events, now -> build(units, events, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun build(units: List<StudyUnit>, events: List<Event>, now: Long): TodayState {
        val today = Time.dateOf(now)
        val monday = Time.weekStart(today)
        val trainedDates = events
            .filter { it.type in EventType.TRAINING && (it.type != EventType.SESSION || (it.value ?: 0.0) >= 1.0) }
            .map { Time.dateOf(it.time) }
            .toSet()
        val week = (0L..6L).map { i ->
            val d = monday.plusDays(i)
            DayCell(d, d in trainedDates, d == today, d.isAfter(today))
        }
        val sessionMinutes = events.filter { it.type == EventType.SESSION }
        val todayMinutes = sessionMinutes.filter { Time.dateOf(it.time) == today }.sumOf { it.value ?: 0.0 }
        val weekMinutes = sessionMinutes.filter { !Time.dateOf(it.time).isBefore(monday) }.sumOf { it.value ?: 0.0 }
        val next = computeNextAction(units, now)
        val dueCount = units.count { it.isReviewDue(now) }
        return TodayState(
            next = next,
            otherDue = if (next is NextAction.Review) dueCount - 1 else dueCount,
            week = week,
            trainedDays = week.count { it.trained },
            todayMinutes = todayMinutes.roundToInt(),
            weekMinutes = weekMinutes.roundToInt(),
        )
    }
}

/**
 * 今日：打开只看到一件事和一个按钮。冷启动 → 点“开始” → 进入专注，全程没有需要自己做的选择。
 */
@Composable
fun TodayScreen(onStart: (Long) -> Unit) {
    val vm = appViewModel { TodayViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val p = Paper.colors
    val s = state ?: return

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // 一件事卡占首屏上半部分：固定高度，按钮压在卡片底部。
    val cardHeight = maxOf(380.dp, maxHeight * 0.56f)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Time.md(Time.today()) + " " + Time.weekdayName(Time.today()),
                style = MaterialTheme.typography.titleMedium,
                color = p.ink2,
            )
        }

        // 今日一件事：大号宋体标题 + 一句说明 + 一个墨水色按钮，占首屏上半部分。
        PaperCard(
            modifier = Modifier.height(cardHeight),
            padding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        ) {
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
            Spacer(Modifier.weight(1f))
            InkButton("开始", onClick = { onStart(s.next.unitId) }, height = 60.dp)
            if (s.otherDue > 0) {
                Spacer(Modifier.height(10.dp))
                Hint("另有 ${s.otherDue} 个自测今天到期，做完这一件再说", Modifier.fillMaxWidth())
            }
        }

        // 本周田字格
        PaperCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                SectionLabel("本周", Modifier.weight(1f).padding(bottom = 10.dp))
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
    }
    }
}
