package com.behaviordept.app.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
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
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.PageHeader
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.roundToInt

data class DayLog(val date: LocalDate, val events: List<Event>)

data class WeekBar(val weekStart: LocalDate, val training: Int, val fiddling: Int)

data class RecordState(
    val days: List<DayLog>,
    val trainedThisWeek: Int,
    val onTime: Pair<Int, Int>,
    val usageKept: Pair<Int, Int>,
    val weekTraining: Int,
    val weekFiddling: Int,
    val heat: List<Pair<LocalDate, Int>>,
    val weeks: List<WeekBar>,
)

class RecordViewModel(c: AppContainer) : ViewModel() {
    private val from: LocalDate = Time.weekStart().minusWeeks(4)

    val state: StateFlow<RecordState?> = combine(
        c.events.observeSince(Time.startOf(from)),
        c.study.observeAllReviews(),
        c.study.observeUnits(),
        c.guard.observeUsageSince(Time.today().minusDays(28)),
        c.guard.observeRules(),
    ) { events, reviews, units, usage, rules ->
        val today = Time.today()
        val monday = Time.weekStart(today)
        val thisWeek = events.filter { !Time.dateOf(it.time).isBefore(monday) }
        val logFrom = Time.startOf(today.minusDays(30))
        val todayStart = Time.startOf(today)
        val overdue = units.count { it.nextReviewAt != null && it.nextReviewAt < todayStart }
        val past28 = (28 downTo 1).map { today.minusDays(it.toLong()) }
        RecordState(
            days = events.filter { it.time >= logFrom }.groupBy { Time.dateOf(it.time) }
                .toSortedMap(compareByDescending<LocalDate> { it })
                .map { (d, list) -> DayLog(d, list.sortedByDescending { it.time }) },
            trainedThisWeek = Metrics.trainedDates(thisWeek, Time::dateOf).size,
            onTime = Metrics.reviewOnTime(reviews, Time.startOf(today.minusDays(28)), Time.now(), overdue, Time::dateOf),
            usageKept = Metrics.usageKept(usage, rules, past28),
            weekTraining = Metrics.minutesOf(thisWeek, EventType.SESSION),
            weekFiddling = Metrics.minutesOf(thisWeek, EventType.SETTINGS_TIME),
            heat = Metrics.dailyMinutes(events, (0L..34L).map { from.plusDays(it) }, Time::dateOf),
            weeks = (3 downTo 0).map { back ->
                val ws = monday.minusWeeks(back.toLong())
                val inWeek = events.filter { val d = Time.dateOf(it.time); !d.isBefore(ws) && d.isBefore(ws.plusDays(7)) }
                WeekBar(ws, Metrics.minutesOf(inWeek, EventType.SESSION), Metrics.minutesOf(inWeek, EventType.SETTINGS_TIME))
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** 记录：看板 + 事件日志。所有数字都来自自动记录，不用手填（PRD M6）。 */
@Composable
fun RecordScreen(onWeekly: () -> Unit) {
    val vm = appViewModel { RecordViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val p = Paper.colors

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeader("记录", "所有数字都来自自动记录") }
        item {
            PaperCard {
                SectionLabel("周复盘")
                Spacer(Modifier.height(6.dp))
                Text("数据整理好了，看这周断在哪，定下周唯一的重点。", style = MaterialTheme.typography.bodyMedium, color = p.ink)
                Spacer(Modifier.height(12.dp))
                LineButton("做本周复盘", onClick = onWeekly, modifier = Modifier.fillMaxWidth())
            }
        }
        item { KpiGrid(s) }
        item {
            PaperCard {
                SectionLabel("近 5 周 · 每天专注分钟")
                Spacer(Modifier.height(12.dp))
                Heatmap(s.heat)
            }
        }
        item {
            PaperCard {
                SectionLabel("折腾系统 vs 实际训练 · 每周分钟")
                Spacer(Modifier.height(12.dp))
                WeekBars(s.weeks)
                Spacer(Modifier.height(6.dp))
                Hint("折腾系统 = 在设置页停留的时间。我是在练，还是在搭系统？")
            }
        }
        item { Text("事件日志", style = MaterialTheme.typography.headlineSmall, color = p.ink, modifier = Modifier.padding(top = 8.dp)) }
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

/** PRD 的四个成功标准，没达标的数字用红笔。 */
@Composable
private fun KpiGrid(s: RecordState) {
    val onTimePct = Metrics.percent(s.onTime.first, s.onTime.second)
    val keptPct = Metrics.percent(s.usageKept.first, s.usageKept.second)
    val ratio = if (s.weekFiddling == 0) null else s.weekTraining.toDouble() / s.weekFiddling
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("本周训练", "${s.trainedThisWeek}", "天", "目标 ≥ 5 天", ok = s.trainedThisWeek >= 5, Modifier.weight(1f))
            Kpi("自测按时", onTimePct?.toString() ?: "—", "%", "近 4 周 ${s.onTime.first}/${s.onTime.second} · 目标 ≥ 70%", ok = onTimePct == null || onTimePct >= 70, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("娱乐守住", keptPct?.toString() ?: "—", "%", "近 4 周 ${s.usageKept.first}/${s.usageKept.second} 天 · 目标 ≥ 80%", ok = keptPct == null || keptPct >= 80, Modifier.weight(1f))
            Kpi(
                "折腾 : 训练",
                if (ratio == null) "0" else "1:${if (ratio >= 10) ratio.roundToInt().toString() else "%.1f".format(ratio)}",
                "",
                "本周 ${s.weekFiddling} : ${s.weekTraining} 分钟 · 目标 ≤ 1:4",
                ok = ratio == null || ratio >= 4,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, unit: String, hint: String, ok: Boolean, modifier: Modifier = Modifier) {
    val p = Paper.colors
    PaperCard(modifier = modifier, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        SectionLabel(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.displaySmall, color = if (ok) p.ink else p.red)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.titleMedium, color = p.ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = p.ink2)
    }
}

/** 训练热力图：田字格样式，每格一天，颜色越深练得越久。 */
@Composable
private fun Heatmap(days: List<Pair<LocalDate, Int>>) {
    val p = Paper.colors
    val today = Time.today()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = p.ink2, modifier = Modifier.width(38.dp), textAlign = TextAlign.Center)
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                week.forEach { (d, m) ->
                    val alpha = when {
                        d.isAfter(today) -> 0f
                        m <= 0 -> 0f
                        m < 15 -> 0.25f
                        m < 30 -> 0.45f
                        m < 60 -> 0.7f
                        else -> 1f
                    }
                    Box(
                        Modifier
                            .size(38.dp)
                            .background(p.page)
                            .border(if (d == today) 1.5.dp else 1.dp, if (d == today) p.ink else p.grid)
                            .padding(3.dp)
                            .background(p.red.copy(alpha = alpha)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            d.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (alpha >= 0.7f) p.page else if (d.isAfter(today)) p.grid else p.ink2,
                        )
                    }
                }
            }
        }
        Hint("浅到深：15 / 30 / 60 分钟以上")
    }
}

@Composable
private fun WeekBars(weeks: List<WeekBar>) {
    val p = Paper.colors
    val max = weeks.maxOfOrNull { maxOf(it.training, it.fiddling) }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        weeks.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Time.md(w.weekStart), style = MaterialTheme.typography.labelMedium, color = p.ink2, modifier = Modifier.width(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Bar(w.training.toFloat() / max, p.ink2, "${w.training}")
                    Bar(w.fiddling.toFloat() / max, p.red, "${w.fiddling}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(p.ink2, "训练")
            Legend(p.red, "折腾系统")
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: androidx.compose.ui.graphics.Color, label: String) {
    val p = Paper.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(10.dp)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f).let { if (it > 0f) maxOf(it, 0.02f) else 0f }).height(10.dp).background(color))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = p.ink2, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Text("  $text", style = MaterialTheme.typography.labelSmall, color = Paper.colors.ink2)
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
