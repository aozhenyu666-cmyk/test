package com.yishou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.scroll.ScrollActivity
import com.yishou.app.scroll.ScrollService
import com.yishou.app.settings.AppZone
import java.time.LocalDate

/** 首页“刷着练”：开关、自动问的间隔、今天问了几句 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val running by ScrollService.running.collectAsStateWithLifecycle()
    val status by ScrollService.status.collectAsStateWithLifecycle()
    val version by app.stats.version.collectAsStateWithLifecycle()
    var interval by remember { mutableIntStateOf(ScrollService.interval(context)) }
    val today = remember(version) { app.stats.day(LocalDate.now(AppZone.zone)) }
    val asked = today.event(ScrollService.EVENT_ASK)
    val hit = today.event(ScrollService.EVENT_HIT)
    val near = today.event(ScrollService.EVENT_NEAR)

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("刷着练", fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            running -> status ?: (if (interval > 0) "开着 · 刷满 $interval 分钟问一句" else "开着 · 点“一手 ●”里的“考我一下”")
                            asked > 0 -> "今天问了 $asked 句 · 接住 $hit" + if (near > 0) " · 差一点 $near" else ""
                            else -> "刷抖音、贴吧时看这一屏问你一句，通知里回"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (running) {
                    OutlinedButton(onClick = { ScrollService.stop(context) }) { Text("停") }
                } else {
                    Button(onClick = { context.startActivity(ScrollActivity.startIntent(context)) }) { Text("开始") }
                }
            }
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "不自动", 3 to "3 分钟", 5 to "5 分钟", 10 to "10 分钟").forEach { (m, label) ->
                        FilterChip(
                            selected = interval == m,
                            onClick = {
                                interval = m
                                ScrollService.setInterval(context, m)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}
