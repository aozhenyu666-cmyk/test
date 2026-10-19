package com.zongkong.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.ledger.Expense
import com.zongkong.core.ledger.Ledger

/**
 * 账本（试验）：今天 / 本周花了多少、花在哪。
 * 记账三种办法：导入微信 / 支付宝导出的账单（CSV）、在这里手记一笔、把付款截图发给总控。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val book by app.store.ledger.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    val zone = app.store.zone
    val today = remember(book, now) { Ledger.day(book, DayClock.date(now, zone), zone) }
    val week = remember(book, now) { Ledger.week(book, now, zone) }
    var msg by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var what by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        msg = try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            // 支付宝导出的是 GBK，微信是 UTF-8：先按 UTF-8 读，认不出表头再按 GBK
            val utf = String(bytes, Charsets.UTF_8)
            val text = if (utf.contains("交易时间")) utf else String(bytes, charset("GBK"))
            val r = Ledger.parseBill(text, zone)
            if (r.error != null) r.error
            else {
                var added = 0
                app.store.updateLedger { b -> Ledger.add(b, r.items).also { added = it.second }.first }
                "${r.source}账单：${r.items.size} 笔支出，新记 $added 笔（重复的跳过），收入 / 退款等 ${r.skipped} 行没记。"
            }
        } catch (e: Exception) {
            "读不了这个文件：${e.message ?: e.javaClass.simpleName}"
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("账本", { nav.popBackStack() }) { Tag("试验", sig.ai) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Panel(modifier = Modifier.testTag("ledger-summary")) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("今天", style = MaterialTheme.typography.labelLarge, color = sig.muted, modifier = Modifier.width(48.dp))
                    Text("${Ledger.yuan(Ledger.total(today))} 元", style = MaterialTheme.typography.headlineMedium.merge(Mono))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("本周", style = MaterialTheme.typography.labelLarge, color = sig.muted, modifier = Modifier.width(48.dp))
                    Text("${Ledger.yuan(Ledger.total(week))} 元", style = MaterialTheme.typography.titleLarge.merge(Mono))
                }
                val cats = Ledger.byCat(week)
                val max = cats.maxOfOrNull { it.second } ?: 0L
                cats.forEach { (c, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(6.dp).background(sig.line, RoundedCornerShape(50))) {
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(v.toFloat() / max.coerceAtLeast(1)).height(6.dp).background(sig.plan, RoundedCornerShape(50)))
                        }
                        Text(Ledger.yuan(v), style = MaterialTheme.typography.bodySmall.merge(Mono), modifier = Modifier.width(64.dp))
                    }
                }
                if (week.isEmpty()) Hint("这周还没记。导入一份账单，或者手记一笔。")
            }

            Text("记一笔", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it }, label = { Text("金额") }, singleLine = true, modifier = Modifier.width(110.dp).testTag("ledger-amount"))
                OutlinedTextField(what, { what = it }, label = { Text("花在什么上") }, singleLine = true, modifier = Modifier.weight(1f).testTag("ledger-what"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Ledger.CATS.forEach { c -> QuickChip(c + if ((cat ?: Ledger.guessCat(what)) == c) " ✓" else "", sig.plan) { cat = c } }
            }
            BigButton("记上", {
                val c = Ledger.cents(amount)
                if (c == null || what.isBlank()) { msg = "金额和花在什么上都要写"; return@BigButton }
                val t = System.currentTimeMillis()
                app.store.updateLedger { Ledger.add(it, listOf(Expense("e" + t.toString(36), t, c, what.trim(), cat ?: Ledger.guessCat(what)))).first }
                msg = "记了：${what.trim()} ${Ledger.yuan(c)} 元"
                amount = ""; what = ""; cat = null
            }, color = sig.plan, modifier = Modifier.testTag("ledger-add"))

            Text("导入", style = MaterialTheme.typography.titleMedium)
            Hint(
                "微信：我 → 服务 → 钱包 → 账单 → 右上角 ⋯ → 下载账单 → 用于个人对账（发到邮箱，解压后是 CSV；是 xlsx 的话用 WPS 另存为 CSV）。" +
                    "支付宝：我的 → 账单 → 右上角 ⋯ → 开具交易流水证明 → 用于个人对账（CSV）。只记支出，重复导入不会记两次。",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "application/csv", "application/octet-stream", "application/vnd.ms-excel")) }) { Text("选账单文件") }
                OutlinedButton(onClick = {
                    app.chatDraft = "这是我的付款截图，帮我记进账本："
                    nav.navigate("chat") { launchSingleTop = true }
                }) { Text("发截图给总控记") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = sig.ai) }

            Text("最近", style = MaterialTheme.typography.titleMedium)
            Panel {
                val recent = book.items.takeLast(30).reversed()
                if (recent.isEmpty()) Hint("还没有。")
                recent.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.what, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                java.time.Instant.ofEpochMilli(e.at).atZone(zone).let { "%d.%d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) } + " · ${e.cat} · ${e.source}",
                                style = MaterialTheme.typography.labelSmall, color = sig.muted,
                            )
                        }
                        Text(Ledger.yuan(e.cents), style = MaterialTheme.typography.bodyMedium.merge(Mono))
                        TextButton(onClick = { app.store.updateLedger { Ledger.remove(it, e.id) } }) { Text("删", color = sig.muted) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
