package com.zongkong.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.plan.QuickPlan
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.Rec
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 今天的安排（0.12）：首页的主角。几点到几点做什么，一眼看完。
 *   - 上面一行：说一句就排上（“下午2点到4点刷题”）
 *   - 时间线：做完的、正在的、过点没开始的、还没到的；点一段 → 开始 / 做完 / 延期 / 取消
 *   - 下面：待安排（没排时间的待办），点一下排进上午 / 下午 / 今晚 / 明天
 *   - 让 GPT 排一整天：带着手上的事去 GPT，它按交接块交回来，一次导入
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayPlanCard(nav: NavHostController, modifier: Modifier = Modifier) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val work by app.store.work.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    val zone = app.store.zone
    val items = remember(work, now) { QuickPlan.today(work, now, zone) }
    val loose = remember(work) { QuickPlan.unscheduled(work).take(5) }
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val done = items.count { it.first.actionStatus == ActionStatus.DONE || it.first.actionStatus == ActionStatus.CONFIRM }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(modifier = Modifier.testTag("day-plan")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今天的安排", style = MaterialTheme.typography.titleMedium.merge(SerifTitle), modifier = Modifier.weight(1f))
                if (items.isNotEmpty()) Text("做完 $done / ${items.size}", style = MaterialTheme.typography.labelLarge.merge(Mono), color = sig.muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    input, { input = it; msg = null },
                    placeholder = { Text("下午2点到4点刷题", maxLines = 1) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("quick-plan"),
                )
                TextButton(onClick = {
                    msg = app.actions.work.quickAdd(input)
                    if (input.isNotBlank()) {
                        input = ""
                        focus.clearFocus()
                        // 自己排的就算今天部署过了
                        scope.launch { runCatching { app.actions.autoVerify(confirmPlan = true) } }
                    }
                }, modifier = Modifier.testTag("quick-plan-add")) { Text("排上", color = sig.plan) }
            }
            msg?.let { Hint(it) }

            if (items.isEmpty()) {
                WaveLines(sig.plan, Modifier.fillMaxWidth().height(44.dp))
                Hint("今天还没排。说一句排上；或者让总控在对话里帮你排一整天。排上的每一段：到点叫你，过点没开始娱乐先关，改个时间就解开。")
            }
            items.forEach { (a, t) ->
                val (s, e) = t
                PlanRow(a, s, e, now, expanded = open == a.key) { open = if (open == a.key) null else a.key }
                AnimatedVisibility(open == a.key && a.actionStatus.open) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 64.dp).testTag("plan-actions-${a.key}"),
                    ) {
                        QuickChip("开始", sig.plan) { nav.navigate("start/${a.key}") }
                        QuickChip("做完", sig.free) { nav.navigate("complete/${a.key}") }
                        QuickPlan.Slot.entries.filter { it != QuickPlan.Slot.MORNING }.forEach { slot ->
                            QuickChip("延到${slot.label}", sig.warn) { msg = app.actions.work.moveTo(a.key, slot); open = null }
                        }
                        QuickChip("不做了", sig.muted) { app.actions.work.cancel(a.key, "在今天的安排里取消"); open = null }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 主要跟总控说：让它在对话里问清楚、排好、你确认（0.14）
                TextButton(onClick = {
                    app.chatDraft = "帮我把今天排一下"
                    nav.navigate("chat")
                }, modifier = Modifier.testTag("plan-ask")) { Text("让总控排一整天", color = sig.ai) }
                TextButton(onClick = { nav.navigate("import") }) { Text("导入 GPT 排好的", color = sig.muted) }
            }
        }

        if (loose.isNotEmpty()) {
            Panel(modifier = Modifier.testTag("plan-loose")) {
                Text("待安排 · ${loose.size}", style = MaterialTheme.typography.titleSmall)
                Hint("没排时间的事（GPT、Notion 同步来的、随手记的）。点一下排进今天，不排就一直挂在这里，总控不会拿它反复追问。")
                loose.forEach { a ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val thread = work.rec(a.threadKey)
                        Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { nav.navigate("action/${a.key}") })
                        thread?.takeIf { it.title != QuickPlan.DAILY }?.let { Text(it.title, style = MaterialTheme.typography.labelSmall, color = sig.muted) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            QuickPlan.Slot.entries.filter { it != QuickPlan.Slot.LATER }.forEach { slot ->
                                QuickChip(slot.label, sig.plan) { msg = app.actions.work.moveTo(a.key, slot) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRow(a: Rec, start: Long, end: Long, now: Long, expanded: Boolean, onClick: () -> Unit) {
    val sig = LocalSignals.current
    val zone = com.zongkong.core.DayClock.BEIJING
    fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
    val st = a.actionStatus
    val doneish = st == ActionStatus.DONE || st == ActionStatus.CONFIRM
    val running = now in start until end && st.open
    val late = st.open && now >= end
    val (label, color) = when {
        doneish -> "做完" to sig.free
        st == ActionStatus.STUCK -> "卡住" to sig.warn
        running -> "还剩 ${(end - now) / 60_000 + 1} 分钟" to sig.plan
        late -> "过点了" to sig.strict
        now < start -> (if (start - now < 60 * 60_000L) "${(start - now) / 60_000 + 1} 分钟后" else "待开始") to sig.muted
        else -> st.label to sig.muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (expanded || running) color.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .testTag("plan-row-${a.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.End) {
            Text(hm(start), style = MaterialTheme.typography.labelLarge.merge(Mono), color = if (doneish) sig.muted else MaterialTheme.colorScheme.onSurface)
            Text(hm(end), style = MaterialTheme.typography.labelSmall.merge(Mono), color = sig.muted)
        }
        // 时间线上的一颗珠子：正在的那一段是个沙漏
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                running -> HourglassGlyph(((end - now).toFloat() / (end - start)).coerceIn(0f, 1f), color)
                doneish -> Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
                else -> Box(Modifier.size(10.dp).background(color.copy(alpha = 0.25f), RoundedCornerShape(50)))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                a.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal),
                color = if (doneish) sig.muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}
