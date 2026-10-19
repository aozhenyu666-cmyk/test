@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yishou.pc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yishou.pc.Controller
import com.yishou.pc.GateRequest
import com.yishou.pc.core.Gatekeeper
import kotlinx.coroutines.delay

/**
 * 守门页：盖满整个屏幕。想打开要守的东西时先停在这里——
 * 先回答上次说好的事做了没有，再写清这次去做什么、回来做什么，等冷静期过去才能放行。
 */
@Composable
fun GateScreen(req: GateRequest, focusEnd: Long?, now: Long, c: Controller) {
    val info = req.info
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {}
        BoardBackdrop()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 620.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StoneMark(36.dp)
                Text(
                    when {
                        info.focus && focusEnd != null -> "专注时段，还剩 ${mmss(focusEnd - now)}"
                        info.timeUp != null && info.followUp == null -> "时间到"
                        else -> "先停一下"
                    },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("想打开：${info.category.name}", style = MaterialTheme.typography.titleMedium)
                Text(req.target, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(
                    "今天第 ${info.attempt} 次想打开${info.category.name}。额度已用 ${info.used} / ${info.budget} 分钟。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (info.focus) {
                    Text("手机上那一手在等你。这段时间电脑上的${info.category.name}不开。", style = MaterialTheme.typography.bodyLarge)
                }

                val followUp = info.followUp
                if (followUp != null) {
                    Quote("上次你说看完回来做", followUp.then) {
                        Text("做了吗？", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { c.followUp(true) }) { Text("做了") }
                            OutlinedButton(onClick = { c.followUp(false) }) { Text("没做") }
                        }
                    }
                } else {
                    info.timeUp?.let { p ->
                        Quote("你说看完回来做", p.then) {
                            Button(onClick = c::goBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("回去做") }
                        }
                    }
                    if (info.canGrant) {
                        GrantForm(req, c)
                    } else if (!info.focus) {
                        Text("今天的${info.category.name}额度用完了。", style = MaterialTheme.typography.titleMedium, color = scheme.error)
                    }
                    Emergency(info.emergencyLeft, c)
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = c::decline, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("不去了") }
            }
        }
    }
}

@Composable
private fun Quote(label: String, text: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("「$text」", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
            content()
        }
    }
}

@Composable
private fun GrantForm(req: GateRequest, c: Controller) {
    val info = req.info
    var what by remember(req.shownAt) { mutableStateOf("") }
    var then by remember(req.shownAt) { mutableStateOf("") }
    var minutes by remember(req.shownAt) { mutableIntStateOf(0) }
    var left by remember(req.shownAt) { mutableIntStateOf(info.cooldownSec) }
    LaunchedEffect(req.shownAt) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = what,
                onValueChange = { what = it },
                label = { Text("这次去做什么") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = then,
                onValueChange = { then = it },
                label = { Text("看完回来做什么（具体到第一步）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("多久", style = MaterialTheme.typography.labelLarge)
                info.choices.forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m 分钟") })
                }
            }
            val ok = Gatekeeper.intentionOk(what, then) && minutes > 0
            Button(
                onClick = { c.grant(minutes, what, then) },
                enabled = ok && left == 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    when {
                        left > 0 -> "冷静 $left 秒"
                        minutes == 0 -> "选一个时长"
                        else -> "放行 $minutes 分钟"
                    },
                )
            }
            Text(
                "今天每放一次，下次的冷静期就长一点。到点会提醒你，再打开要重新写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Emergency(left: Int, c: Controller) {
    var open by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }, enabled = left > 0) { Text(if (left > 0) "真有急事" else "今天的急事次数用完了") }
        return
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("写清楚是什么急事（至少 ${Gatekeeper.EMERGENCY_MIN_CHARS} 个字）") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            val n = Gatekeeper.chars(reason)
            Button(onClick = { c.emergency(reason) }, enabled = n >= Gatekeeper.EMERGENCY_MIN_CHARS) {
                Text("放行 ${Gatekeeper.EMERGENCY_MINUTES} 分钟（今天还剩 $left 次）")
            }
            Text("$n / ${Gatekeeper.EMERGENCY_MIN_CHARS} 字。会记进今天的记录。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
