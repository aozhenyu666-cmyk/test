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
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("刷着练", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (asked > 0) {
                    Text(
                        "今天问了 $asked 句 · 接住 $hit" + if (near > 0) " · 差一点 $near" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!running) {
                Text(
                    "刷抖音、贴吧时也能练：它看这一屏，问你一句，你在通知里回一句，接着刷。只在点“考我一下”或刷满设定时间时截屏，截图不保存。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(status ?: "开着。刷到想练的，下拉通知点“考我一下”。", style = MaterialTheme.typography.bodySmall)
            }
            Text("在关注的应用里自动问", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "不问", 3 to "3 分钟", 5 to "5 分钟", 10 to "10 分钟").forEach { (m, label) ->
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
            if (running) {
                OutlinedButton(onClick = { ScrollService.stop(context) }) { Text("停") }
            } else {
                Button(onClick = { context.startActivity(ScrollActivity.startIntent(context)) }) { Text("开始刷着练") }
            }
        }
    }
}
