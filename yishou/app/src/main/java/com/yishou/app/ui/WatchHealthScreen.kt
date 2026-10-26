package com.yishou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yishou.app.YishouApp
import com.yishou.app.health.HealthStatus
import com.yishou.app.healthwire.EmergencyStop
import com.yishou.app.healthwire.Facts
import com.yishou.app.healthwire.LocalLockExecutor
import com.yishou.app.healthwire.SentinelLink
import com.yishou.app.healthwire.YishouGuard
import kotlinx.coroutines.delay

/**
 * 健康与监管页（规范 §6 / §7）。
 *
 * 两件最重要的事：
 *  1. **紧急暂停**：一次点击、本地生效、不依赖网络与模型、立即停止自己的自动锁屏；
 *     并如实说明「其他应用是否也停了要看它们的回执，不替它们担保」（规范 §6）。
 *  2. **健康状态照实说**：只有真的拿到 dispatcherAck 才显示「真实响应中」，其余按状态给文案，
 *     宁可显示「未知」，不涂绿（规范 §7 文案规则）。
 *
 * 这里说的「暂停」与「提前结束本轮陪练窗口」是两件事，后者仍在窗口页走冷静期 + 二次确认。
 */
@Composable
fun WatchHealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    var tick by remember { mutableIntStateOf(0) }
    // 页面在前台时每秒刷新一次读数（只是内存读，不发起网络/跨进程）。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    var snap by remember { mutableStateOf<YishouGuard.Snapshot?>(null) }
    LaunchedEffect(tick) {
        snap = try {
            YishouGuard.snapshot(app)
        } catch (e: Exception) {
            null
        }
    }
    var showStop by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }

    Scaffold(topBar = { BackTopBar("健康与监管", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "这一页说的是「一手」自己：无障碍执行线程还在不在响应、本地规则有没有生效、锁屏权限有没有。" +
                    "三个应用之间只交换健康与执行回执，不同步任务、对话和记忆。",
                style = MaterialTheme.typography.bodySmall,
            )

            val s = snap

            // ---- 紧急暂停：显眼横幅（规范 §6 + §10 口径） ----
            // 暂停是「到用户点恢复为止」，跨窗口、跨重启仍然生效。这件事**不能安静地什么都不做**：
            // 报告 §10 要求宁可显示「已停」，也不能让用户以为自己在被监管、实际没有。
            if (s?.emergencyStopped == true || Facts.paused) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "⚠ 紧急暂停生效中，本轮自动锁屏已停止",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "「一手」现在不会自动锁屏。这个暂停会一直生效——换到下一个时间窗口、" +
                                "关掉应用再打开，都不会自己恢复成会锁的状态。要恢复请点下面的「恢复自动锁屏」。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val at = (s?.pausedAtWall ?: 0L).takeIf { it > 0 }
                            ?: EmergencyStop.last()?.atWall
                            ?: 0L
                        if (at > 0) {
                            Text("暂停时间：${timeText(at)}", style = MaterialTheme.typography.bodySmall)
                        }
                        val why = s?.pauseReason?.takeIf { it.isNotBlank() }
                            ?: EmergencyStop.last()?.reason?.takeIf { it.isNotBlank() }
                        Text(
                            "原因：${why ?: "（未填）"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (s?.stoppedBySentinel == true) {
                                "来源：哨兵传来的紧急暂停（一手已确认停止自己的自动锁屏）"
                            } else {
                                "来源：你在一手自己按下的紧急暂停"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "只停一手的；其他应用有没有停要看各自的回执，这里不替它们担保。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                EmergencyStop.resume(context)
                                tick++
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("恢复自动锁屏") }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (s?.status == HealthStatus.HEALTHY) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("一手自己", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            statusText(s?.status),
                            color = if (s?.status == HealthStatus.HEALTHY) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(s?.reason ?: "还没有读到本地事实", style = MaterialTheme.typography.bodySmall)
                    s?.ackAgeMs?.let { Text("最近一次真实响应：${it / 1000}s 前", style = MaterialTheme.typography.bodySmall) }
                        ?: Text("还没有过真实响应（没有 ack 就不算健康）", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("哨兵", fontWeight = FontWeight.Bold)
                    Text(
                        if (s?.sentinelReachable == true) "哨兵可达" else "哨兵失联" +
                            (s?.sentinelError?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (s?.sentinelReachable != true) {
                            "哨兵失联：一手按本地规则有限兜底（自己健康、规则生效、且通过去重时才锁一次）。"
                        } else {
                            "哨兵在线：锁屏由哨兵统管，一手不重复锁。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val link = SentinelLink.state()
                    if (link.policyRevision > 0) {
                        Text("哨兵已接受的规则版本：${link.policyRevision}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("本地规则条数：${s?.localRules ?: 0}", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- 紧急出口（规范 §6，必须先做） ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("紧急暂停", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "立刻停掉「一手」自己的自动锁屏。本地直接生效，不等网络、不问模型、不需要写理由。" +
                            "只停一手的；其他应用有没有停要看各自的回执，这里不替它们担保。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s?.emergencyStopped == true || Facts.paused) {
                        val rec = EmergencyStop.last()
                        Text(
                            "现在是暂停状态" + (rec?.atWall?.let { "（${timeText(it)}）" } ?: ""),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        rec?.reason?.takeIf { it.isNotBlank() }?.let {
                            Text("理由：$it", style = MaterialTheme.typography.bodySmall)
                        }
                        if (s?.stoppedBySentinel == true) {
                            // 哨兵传播过来的暂停：如实说来源，顺手说明「已确认」是指这一手真的停了。
                            Text(
                                "这是哨兵传过来的紧急暂停：一手已确认停止自己的自动锁屏。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(
                                "已同步给哨兵：${if (rec?.propagated == true) "已确认" else "未确认（以哨兵回执为准）"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = {
                            EmergencyStop.resume(context)
                            tick++
                        }, modifier = Modifier.fillMaxWidth()) { Text("恢复自动锁屏") }
                    } else {
                        Button(
                            onClick = { showStop = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("紧急暂停（立即停掉自动锁屏）") }
                    }
                    Text(
                        "注意：「正常提前结束本轮陪练窗口」是另一件事，仍在陪练窗口页里走冷静期和二次确认。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---- 本地锁屏权限（规范 §5.1：每个 APP 自己也要有能力与权限状态） ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本地锁屏能力", fontWeight = FontWeight.Bold)
                    Text(
                        "设备管理员（force-lock）：${if (s?.adminActive == true) "已激活" else "未激活"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "系统安全锁屏：${if (s?.secureLockSet == true) "已设置" else "没设置（没设密码时锁屏 API 可能只是休眠，不会当成安全锁定）"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s?.adminActive != true) {
                        OutlinedButton(
                            onClick = {
                                LocalLockExecutor.requestAdmin(context)
                                tick++
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("去激活设备管理员（只申请锁屏）") }
                    }
                    Text(
                        "设备管理员只申请锁屏这一项能力：不读、不设、不重置你的锁屏密码，也不清除任何数据；" +
                            "随时可以在系统设置里停用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "说明：调试签名只用于本地装机，不构成抗伪造方案；普通 APK 也可能被系统强制停止，" +
                    "本应用不承诺任何绕过能力。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showStop) {
        AlertDialog(
            onDismissRequest = { showStop = false },
            title = { Text("确认紧急暂停？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会立刻停掉「一手」自己的自动锁屏，并记下时间。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("为什么（可以不填）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    EmergencyStop.stopNow(context, reason)
                    reason = ""
                    showStop = false
                    tick++
                }) { Text("立即暂停") }
            },
            dismissButton = { TextButton(onClick = { showStop = false }) { Text("取消") } },
        )
    }
}

/** 状态文案（规范 §7：宁可显示「未知」，不要涂绿）。 */
private fun statusText(s: HealthStatus?): String = when (s) {
    null -> "未知"
    HealthStatus.UNREGISTERED -> "未接入"
    HealthStatus.HEALTHY -> "真实响应中"
    HealthStatus.SUSPECT -> "可疑"
    HealthStatus.DISABLED -> "无障碍未开启"
    HealthStatus.UNRESPONSIVE -> "确认失联"
    HealthStatus.RECOVERING -> "正在恢复"
    HealthStatus.UNKNOWN -> "未知：证据不足"
    HealthStatus.PAUSED -> "已暂停"
}

private fun timeText(at: Long): String = try {
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
} catch (e: Exception) {
    ""
}
