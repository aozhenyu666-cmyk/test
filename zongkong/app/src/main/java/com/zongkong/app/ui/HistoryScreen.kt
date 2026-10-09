package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.ReportKind

/** 最近 14 天：每天交了几道、娱乐多久、被弹回几次。点开看当天交的内容。 */
@Composable
fun HistoryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val today by store.today.collectAsStateWithLifecycle()
    val days = remember(today) { store.history(14) }
    val sig = LocalSignals.current
    var open by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackBar("历史记录", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            days.forEach { d ->
                val passed = d.submissions.filter { it.verdict.pass }
                val late = passed.count { it.late }
                val expanded = open == d.date
                Panel(onClick = { open = if (expanded) null else d.date }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(d.date.substring(5), style = MaterialTheme.typography.titleMedium.merge(Mono))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "验收 ${passed.size}" + (if (late > 0) "（迟交 $late）" else "") +
                                    " · 打回 ${d.submissions.size - passed.size} · 报到 ${d.reports.count { it.kind == ReportKind.CHECKIN }}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "娱乐 ${d.playMinutes} 分 · 弹回 ${d.bounces} 次 · 紧急 ${d.emergencies.size} 次" +
                                    if (d.offline.isNotEmpty()) " · 失联 ${d.offline.sumOf { (it.to - it.from) / 60_000 }} 分" else "",
                                style = MaterialTheme.typography.bodySmall, color = sig.muted,
                            )
                        }
                    }
                    if (expanded) {
                        if (d.submissions.isEmpty()) Hint("这天没有交任何关卡。")
                        passed.forEach { s ->
                            val g = config.gates.firstOrNull { it.id == s.gateId }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${clock(s.at)} ${g?.title ?: s.gateId}" + (s.verdict.score?.let { " · $it 分" } ?: "") + if (s.late) " · 迟交" else "",
                                    style = MaterialTheme.typography.titleSmall, color = if (s.late) sig.warn else sig.free,
                                )
                                Text(s.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
