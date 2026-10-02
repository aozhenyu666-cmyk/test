package com.behaviordept.app.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.behaviordept.app.data.Rating
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.BigNumber
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.PageHeader
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.roundToInt

data class DayLog(val date: LocalDate, val events: List<Event>)

data class RecordState(val days: List<DayLog>, val weekTraining: Int, val weekFiddling: Int)

class RecordViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<RecordState?> = c.events.observeSince(Time.startOf(Time.today().minusDays(30)))
        .map { events ->
            val monday = Time.weekStart()
            val thisWeek = events.filter { !Time.dateOf(it.time).isBefore(monday) }
            RecordState(
                days = events.groupBy { Time.dateOf(it.time) }
                    .toSortedMap(compareByDescending<LocalDate> { it })
                    .map { (d, list) -> DayLog(d, list.sortedByDescending { it.time }) },
                weekTraining = thisWeek.filter { it.type == EventType.SESSION }.sumOf { it.value ?: 0.0 }.roundToInt(),
                weekFiddling = thisWeek.filter { it.type == EventType.SETTINGS_TIME }.sumOf { it.value ?: 0.0 }.roundToInt(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** 记录：事件日志。所有训练、自测、专注时长、设置页停留都自动落在这里，不用手填。 */
@Composable
fun RecordScreen() {
    val vm = appViewModel { RecordViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val p = Paper.colors

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeader("记录", "自动记下的行为，近 30 天") }
        item {
            PaperCard {
                SectionLabel("本周")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BigNumber("训练", s.weekTraining.toString(), "分钟")
                    BigNumber("折腾系统", s.weekFiddling.toString(), "分钟", red = s.weekFiddling * 4 > s.weekTraining && s.weekFiddling > 0)
                }
                Spacer(Modifier.height(6.dp))
                Hint("折腾系统 = 在设置页停留的时间。目标：折腾 : 训练 ≤ 1 : 4")
            }
        }
        if (s.days.isEmpty()) {
            item { Hint("还没有记录。回到“今日”，点开始。") }
        }
        s.days.forEach { day ->
            item(key = "h-${day.date}") {
                Text(
                    Time.md(day.date) + " " + Time.weekdayName(day.date),
                    style = MaterialTheme.typography.titleLarge,
                    color = p.ink,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(day.events, key = { it.id }) { e -> EventRow(e) }
        }
    }
}

@Composable
private fun EventRow(e: Event) {
    val p = Paper.colors
    val isTraining = e.type in EventType.TRAINING
    Row(
        Modifier.fillMaxWidth().background(p.page).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Time.hm(e.time), style = MaterialTheme.typography.labelMedium, color = p.ink2, modifier = Modifier.width(48.dp))
        val dot = Modifier.size(8.dp)
        androidx.compose.foundation.layout.Box(
            if (isTraining) dot.background(p.red) else dot.background(p.divider),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(EventType.label(e.type), style = MaterialTheme.typography.titleSmall, color = p.ink)
            if (e.note.isNotBlank()) {
                Text(e.note, style = MaterialTheme.typography.bodySmall, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        valueText(e)?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = if (isTraining) p.red else p.ink2)
        }
    }
}

private fun valueText(e: Event): String? = when (e.type) {
    EventType.SESSION, EventType.SETTINGS_TIME -> "${(e.value ?: 0.0).roundToInt()} 分钟"
    EventType.REVIEW_DONE -> when ((e.value ?: -1.0).roundToInt()) {
        2 -> "${Rating.mark(Rating.REMEMBER)} 记得"
        1 -> "${Rating.mark(Rating.FUZZY)} 模糊"
        0 -> "${Rating.mark(Rating.FORGOT)} 忘了"
        else -> null
    }
    EventType.AI_CALL -> e.value?.let { "${it.roundToInt()} tokens" }
    else -> null
}
