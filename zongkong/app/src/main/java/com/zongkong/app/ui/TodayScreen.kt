package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zongkong.app.data.Actions
import com.zongkong.app.zk
import com.zongkong.core.plan.DayReport
import com.zongkong.core.work.WorkOps
import kotlinx.coroutines.launch

/**
 * 今天的清单：照着记录列出今天排了什么、做了没有、另外做了什么、时间花在哪。
 * 不用写报告：看一眼，没做的可以顺手点“做完了 / 明天再做”，补一句明天第一步，交。
 * 日终验收交的就是这张单子，不评分、不打回。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    // 这几样一变，清单就重算
    val work by app.store.work.collectAsState()
    val plans by app.store.plans.collectAsState()
    val today by app.store.today.collectAsState()
    val hq by app.store.hq.collectAsState()
    val r = remember(work, plans, today, hq) { app.actions.dayReport() }
    val status = remember(today) { app.store.status() }
    val gate = status.gates.firstOrNull { it.gate.id == "hq_review" }
    var tomorrow by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar("今天的清单", { nav.popBackStack() }) {
            TextButton(onClick = { clip.setText(AnnotatedString(DayReport.text(r))) }) { Text("复制") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(r.date, style = MaterialTheme.typography.labelLarge, color = sig.muted)
            if (r.planned.isNotEmpty()) {
                Text("排了的 · 做完 ${r.doneCount}/${r.planned.size}", style = MaterialTheme.typography.titleMedium)
            }
            Panel(modifier = Modifier.testTag("today-list")) {
                if (r.planned.isEmpty()) Hint("今天没有排进时间的行动。")
                r.planned.forEach { item ->
                    ItemRow(item, sig) {
                        if (item.mark == DayReport.Mark.TODO || item.mark == DayReport.Mark.STUCK || item.mark == DayReport.Mark.DOING) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                QuickChip("做完了", sig.free) { app.actions.work.complete(item.key, "清单上确认做完", WorkOps.Met.YES, "") }
                                QuickChip("明天再做", sig.plan) { app.actions.work.reschedule(item.key, "明天 09:00") }
                            }
                        }
                    }
                }
            }
            if (r.extra.isNotEmpty()) {
                Text("另外做了的", style = MaterialTheme.typography.titleMedium)
                Panel { r.extra.forEach { ItemRow(it, sig) {} } }
            }
            if (r.promises.isNotEmpty()) {
                Text("今天到期的约定", style = MaterialTheme.typography.titleMedium)
                Panel { r.promises.forEach { (w, s) -> Text("· $w：$s", style = MaterialTheme.typography.bodyMedium) } }
            }
            Text("时间", style = MaterialTheme.typography.titleMedium)
            Panel {
                Fact("做事", DayReport.hm(r.workMinutes), sig.plan)
                Fact("手机娱乐", DayReport.hm(r.playMinutes) + (if (r.bounces > 0) " · 被弹回 ${r.bounces} 次" else ""), sig.act)
                if (r.emergencies > 0) Fact("紧急放行", "${r.emergencies} 次", sig.warn)
                if (r.pcKnown) Fact("电脑", DayReport.hm(r.pcMinutes) + " · 娱乐 " + DayReport.hm(r.pcFunMinutes), sig.info)
                Fact("总控时间", if (r.hqDone) "做了" else "还没做", sig.hq)
            }

            Text("明天第一步", style = MaterialTheme.typography.titleMedium)
            if (r.tomorrow.isNotBlank()) Hint("已经排了：${r.tomorrow}")
            OutlinedTextField(
                tomorrow, { tomorrow = it },
                label = { Text(if (r.tomorrow.isBlank()) "明天先做什么（可不写）" else "要改就写（可不写）") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("today-tomorrow"),
            )
            OutlinedTextField(line, { line = it }, label = { Text("想说的一句话（可不写）") }, modifier = Modifier.fillMaxWidth())

            result?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = sig.hq) }
            if (gate != null && !gate.done) {
                BigButton(if (busy) "正在交…" else "交清单", {
                    if (busy) return@BigButton
                    busy = true
                    scope.launch {
                        val res = app.actions.submitDayList(tomorrow, line)
                        busy = false
                        result = when (res) {
                            is Actions.SubmitResult.Judged -> if (res.verdict.pass) "交了。今天辛苦。" else res.verdict.feedback
                            is Actions.SubmitResult.Unavailable -> res.reason
                            is Actions.SubmitResult.Refused -> res.message
                        }
                    }
                }, color = sig.hq, modifier = Modifier.testTag("today-submit"))
            } else if (gate?.done == true) {
                Hint("今天的日终验收已经交了。清单会一直更新到今天结束。")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ItemRow(item: DayReport.Item, sig: Signals, actions: @Composable () -> Unit) {
    val c = when (item.mark) {
        DayReport.Mark.DONE, DayReport.Mark.WAIT -> sig.free
        DayReport.Mark.DOING -> sig.plan
        DayReport.Mark.STUCK, DayReport.Mark.TODO -> sig.strict
        DayReport.Mark.MOVED -> sig.warn
        DayReport.Mark.CANCELLED -> sig.muted
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val it = item
        Row(verticalAlignment = Alignment.Top) {
            Text(it.mark.sign, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace), color = c, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(it.title + if (it.main) "（主线）" else "", style = MaterialTheme.typography.titleSmall)
                val sub = listOfNotNull(it.mark.label, it.minutes.takeIf { m -> m > 0 }?.let { m -> "$m 分钟" }, it.note.takeIf { n -> n.isNotBlank() }).joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = sig.muted)
            }
        }
        Row { Spacer(Modifier.width(28.dp)); actions() }
    }
}

@Composable
private fun Fact(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.width(88.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
