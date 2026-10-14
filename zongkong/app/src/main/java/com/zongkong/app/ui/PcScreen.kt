package com.zongkong.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.device.DeviceLink
import kotlinx.coroutines.launch

/**
 * 我 → 电脑：和电脑上的总控连起来。
 * 建“总控·设备”库 → 复制配对码到电脑 → 之后这里能看到电脑今天用了多久、在做什么，也能锁电脑、拦网站、给电脑发话。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val config by app.store.config.collectAsStateWithLifecycle()
    val book by app.store.device.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    var msg by remember { mutableStateOf<String?>(null) }
    var withAi by remember { mutableStateOf(true) }
    var site by remember { mutableStateOf("") }
    var say by remember { mutableStateOf("") }
    val ready = config.notion.token.isNotBlank() && config.notion.devicesDb.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        BackBar("电脑", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("pc-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel {
                Text("电脑上的总控", style = MaterialTheme.typography.titleMedium)
                Hint("Windows 版：管电脑上的娱乐、游戏、社交（额度用完就拦，网站在网络层打不开），每次用电脑先说做什么、用多久，夜里锁机；想多用就向它申请，它对着你的记录和这边的计划判断。和手机连上以后，总控时间开始电脑跟着锁。")
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aozhenyu666-cmyk/test/releases/download/zongkong-pc-latest/zongkong-pc.msi"))) }
                }) { Text("电脑版下载地址（在电脑上打开）") }
            }
            if (!ready) {
                Panel(accent = sig.ai) {
                    Text("第一步：在 Notion 里建一个传话的小库", style = MaterialTheme.typography.titleSmall)
                    Hint("手机和电脑通过你自己的 Notion 传话（一台一行，各写各的）。要先在 我 → Notion 里连好、一键搭建过。")
                    Button(onClick = { scope.launch { msg = app.actions.device.setup() } }, modifier = Modifier.testTag("pc-setup")) { Text("建立连接") }
                }
            } else {
                Panel {
                    Text("配对码", style = MaterialTheme.typography.titleSmall)
                    Hint("复制下来，发到电脑上（微信文件传输助手就行），在电脑版 连接与设置 里粘贴。里面有你的 Notion 密钥，别发给别人。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("顺带 AI 接口（电脑上不用再填）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(withAi, { withAi = it })
                    }
                    Button(onClick = {
                        val code = app.actions.device.pairingCode(withAi)
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("总控配对码", code))
                        msg = "配对码已复制"
                    }, modifier = Modifier.testTag("pc-copy")) { Text("复制配对码") }
                }
                val pc = book.pc
                Panel(modifier = Modifier.testTag("pc-status")) {
                    Text("电脑现在", style = MaterialTheme.typography.titleSmall)
                    Text(DeviceLink.brief(pc, now, app.store.zone), style = MaterialTheme.typography.bodyMedium)
                    pc?.top?.takeIf { it.isNotEmpty() }?.let { top ->
                        top.forEach { u ->
                            Row {
                                Text(u.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text("${u.minutes} 分 · ${u.cat}", style = MaterialTheme.typography.labelMedium.merge(Mono), color = sig.muted)
                            }
                        }
                    }
                    Hint(
                        (if (book.lastSync > 0) "上次同步 ${clock(book.lastSync)}" else "还没同步过") +
                            (if (book.lastError.isNotBlank()) " · ${book.lastError.take(60)}" else "") +
                            (if (book.cmds.isNotEmpty()) " · 有 ${book.cmds.size} 条命令等电脑执行" else ""),
                    )
                    OutlinedButton(onClick = { scope.launch { msg = app.actions.device.sync() } }) { Text("现在同步") }
                }
                Panel {
                    Text("管电脑", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 120).forEach { m -> QuickChip("锁 $m 分钟", sig.strict) { msg = app.actions.device.command("lock", m, "手机上锁的") } }
                        QuickChip("解锁", sig.free) { msg = app.actions.device.command("unlock") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(site, { site = it }, label = { Text("拦一个网站（B站 / hupu.com）") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { msg = app.actions.device.command("block", 0, site.trim()); site = "" }, enabled = site.isNotBlank()) { Text("拦") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(say, { say = it }, label = { Text("让电脑说一句") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { msg = app.actions.device.command("say", 0, say.trim()); say = "" }, enabled = say.isNotBlank()) { Text("发") }
                    }
                    Hint("总控时间开始时电脑自动锁到结束，不用手动。对话里也可以直接说“把电脑锁一小时”。")
                }
            }
            msg?.let { Text(it, color = sig.ai, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
