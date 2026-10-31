package com.zongkong.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import com.zongkong.app.guard.Diag
import com.zongkong.core.DayClock
import java.time.Instant
import java.time.format.DateTimeFormatter

private val Hms = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun hms(at: Long): String = Hms.format(Instant.ofEpochMilli(at).atZone(DayClock.BEIJING))

/** 事件种类的说法：给用户看的中文。 */
private fun kindLabel(kind: String): String = when (kind) {
    "connected" -> "接上"
    "unbound" -> "被解绑"
    "destroyed" -> "被销毁"
    "offline" -> "掉线"
    "stale" -> "开着没跑"
    "rebind_ok" -> "重新挂上"
    "rebind_fail" -> "挂不上"
    "disabled" -> "被关掉"
    "bounce" -> "弹回"
    "lockdown" -> "总控时间"
    "ai_focus_block" -> "AI 专注拦截"
    "event_error" -> "事件出错"
    "watchdog" -> "看门狗"
    else -> kind
}

@Composable
private fun kindColor(kind: String): Color {
    val sig = LocalSignals.current
    return when (kind) {
        "connected", "rebind_ok" -> sig.free
        "unbound", "destroyed", "offline", "stale", "disabled", "rebind_fail" -> sig.strict
        "watchdog", "event_error" -> sig.warn
        else -> sig.muted
    }
}

/**
 * 眼睛日志：回答“这次为什么失效了”。
 *
 * 顶部是今天的摘要（开没开、跑没跑、掉了几次、多久、最近一次的原因猜测、电池优化），
 * 下面是按天分组的事件时间线：好事件绿、坏事件红/琥珀，带秒级时间和一句说明。
 * 能一键去权限页、刷新、清空、分享（文本 + 导出文件路径，不依赖 FileProvider）。
 */
@Composable
fun DiagScreen(nav: NavHostController) {
    val context = LocalContext.current
    val sig = LocalSignals.current
    var key by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { key++ }

    val now = remember(key) { System.currentTimeMillis() }
    val summary = remember(key) { Diag.summaryToday(context, now) }
    val events = remember(key) { Diag.recent(context, 200) }
    val today = remember(key) { DayClock.date(now, DayClock.BEIJING).toString() }
    val groups = remember(key, events) { events.asReversed().groupBy { DayClock.date(it.at, DayClock.BEIJING).toString() } }
    var confirmClear by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    // v2：healthy 只认「无障碍开了 + 真实 dispatcherAck 新鲜」，不看进程里的 running 布尔。
    val healthy = summary.accessibilityEnabled && summary.serviceRunning

    fun share() {
        try {
            val file = Diag.export(context)
            val text = buildString {
                appendLine("总控 · 眼睛日志")
                appendLine(
                    "无障碍设置：" + (if (summary.accessibilityEnabled) "已开启" else "关着") +
                        " · 服务：" + (if (summary.serviceRunning) "在运行" else "没在运行") +
                        " · 电池优化：" + (if (summary.ignoringBattery) "已忽略" else "没忽略"),
                )
                appendLine(
                    "今天（${summary.date}）：接上 ${summary.connected} 次 · 失联 ${summary.lostCount} 次" +
                        " · 共 ${span(summary.lostTotalMs)} · 最长 ${span(summary.longestGapMs)}" +
                        " · 开着没跑 ${summary.stale} 次",
                )
                appendLine("原因猜测：${summary.reasonGuess}")
                appendLine("导出文件：${file.absolutePath}")
                appendLine()
                events.forEach { appendLine("${hms(it.at)} ${it.kind} ${it.detail}") }
            }
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "分享眼睛日志"))
        } catch (e: Exception) {
            msg = "分享失败：${e.message}"
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("眼睛日志", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------- 摘要 ----------
            Panel(accent = if (healthy) sig.free else sig.strict, modifier = Modifier.enter(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("眼睛状态", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Tag(if (healthy) "在线" else "离线", if (healthy) sig.free else sig.strict)
                }
                KeyValue("无障碍设置", if (summary.accessibilityEnabled) "已开启" else "关着", if (summary.accessibilityEnabled) sig.free else sig.strict)
                KeyValue("服务", if (summary.serviceRunning) "真实响应中" else "没有真实响应", if (summary.serviceRunning) sig.free else sig.strict)
                // v2：把健康判定依据摊开，避免把「进程活着」读成「在干活」。
                run {
                    val v = remember(key) {
                        runCatching {
                            com.zongkong.app.healthwire.GuardHealth.init(context)
                            com.zongkong.app.healthwire.GuardHealth.verdict(
                                com.zongkong.app.healthwire.GuardHealth.permissionEnabled(context),
                            )
                        }.getOrNull()
                    }
                    KeyValue(
                        "健康判定",
                        v?.let { "${it.status.name}：${it.reason}" } ?: "证据不足",
                        if (v?.healthy == true) sig.free else sig.warn,
                    )
                    KeyValue(
                        "执行台账",
                        runCatching { com.zongkong.app.healthwire.GuardHealth.ledger.summary() }.getOrDefault("读不到"),
                        sig.muted,
                    )
                    KeyValue(
                        "最近一次挑战",
                        runCatching { com.zongkong.app.healthwire.GuardHealth.lastChallenge.ifBlank { "还没收到挑战" } }
                            .getOrDefault("读不到"),
                        sig.muted,
                    )
                    KeyValue(
                        "哨兵",
                        runCatching { com.zongkong.app.guard.SentinelClient.describe(context) }.getOrDefault("读不到"),
                        sig.muted,
                    )
                }
                KeyValue(
                    "今天失联",
                    "${summary.lostCount} 次 · 共 ${span(summary.lostTotalMs)}" +
                        (if (summary.longestGapMs > 0) " · 最长 ${span(summary.longestGapMs)}" else ""),
                    if (summary.lostCount > 0) sig.warn else sig.muted,
                )
                KeyValue("开着没跑", "${summary.stale} 次", if (summary.stale > 0) sig.warn else sig.muted)
                KeyValue("电池优化", if (summary.ignoringBattery) "已忽略（不容易被清）" else "没忽略", if (summary.ignoringBattery) sig.free else sig.warn)
                Hint("最近原因猜测：${summary.reasonGuess}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("settings/perm") }) { Text("去权限页") }
                    OutlinedButton(onClick = { key++ }) { Text("刷新") }
                    TextButton(onClick = { share() }) { Text("分享日志") }
                }
                TextButton(onClick = { confirmClear = true }) { Text("清空日志（确认）") }
                msg?.let { Hint(it) }
                Hint("日志存在手机上（filesDir/zk/diag.jsonl），只保留最近 1500 条；分享时会把文本和导出路径一起发出去。")
            }

            // ---------- 事件时间线 ----------
            SectionLabel("事件时间线")
            if (events.isEmpty()) {
                Hint("还没有记录。眼睛每次接上、掉线、被关、弹回，都会记在这里。")
            }
            groups.forEach { (day, list) ->
                Text(
                    if (day == today) "今天（${day.substring(5)}）" else day,
                    style = MaterialTheme.typography.titleSmall,
                    color = sig.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEachIndexed { i, e ->
                        Row(
                            Modifier.fillMaxWidth().enter(i.coerceAtMost(8)),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(hms(e.at), style = MaterialTheme.typography.bodySmall.merge(Mono), color = sig.muted)
                            Tag(kindLabel(e.kind), kindColor(e.kind))
                            if (e.detail.isNotBlank()) {
                                Text(e.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Hint("好事件（接上、重新挂上）是绿的；坏事件（被解绑、被销毁、掉线、开着没跑、被关）是红的；看门狗提醒是琥珀。")
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空眼睛日志？") },
            text = { Text("全部事件都会删掉，不能恢复。“今天失联几次”也会从头开始算。") },
            confirmButton = {
                TextButton(onClick = {
                    Diag.clear(context)
                    confirmClear = false
                    key++
                    msg = "日志清空了"
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
