package com.yishou.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.gate.GateService
import com.yishou.app.outside.AiApp
import com.yishou.app.partner.Closing
import com.yishou.app.partner.Gear
import com.yishou.app.partner.Material
import com.yishou.app.partner.PartnerPlan
import com.yishou.app.partner.PartnerService
import com.yishou.app.partner.PartnerSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PartnerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val partner = app.partner
    val state by partner.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var goal by rememberSaveable { mutableStateOf("") }
    var materialName by rememberSaveable { mutableStateOf(Material.ARTICLE.name) }
    var gearName by rememberSaveable { mutableStateOf(Gear.ACCOMPANY.name) }
    var minutes by rememberSaveable { mutableStateOf(25) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var line by rememberSaveable(state.row?.id) { mutableStateOf("") }
    var next by rememberSaveable(state.row?.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var picker by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) } }
    val external = remember { AiApp("Gemini", PartnerSessions.EXTERNAL_PACKAGE, PartnerSessions.EXTERNAL_URL) }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: Exception) { note = "没保存成功，请重试；这一场还留在本机。" }
            finally { busy = false }
        }
    }
    Scaffold(topBar = { BackTopBar("语音伙伴", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("外部伴读（可选）", style = MaterialTheme.typography.titleMedium)
            Text("这里只准备开场、计时和收线，不会读取外部对话，也不能判断你有没有接话。前台在场不等于参与。", style = MaterialTheme.typography.bodySmall)
            if (GateService.foreground == null) Text("无障碍服务没开：这场不能统计在场或提醒离开。可到系统设置手动开启。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            note?.let { Text(it) }
            if (state.loading) {
                Text("正在读本机记录……")
            } else if (state.row == null) {
                Text("今天看什么？")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Material.VIDEO to "网课", Material.ARTICLE to "文章", Material.DRILL to "做题").forEach { (m, label) ->
                        FilterChip(selected = materialName == m.name, onClick = { materialName = m.name }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Material.CHESS to "象棋", Material.OTHER to "其他").forEach { (m, label) ->
                        FilterChip(selected = materialName == m.name, onClick = { materialName = m.name }, label = { Text(label) })
                    }
                }
                VoiceTextField(goal, { goal = it }, "这次想弄懂什么", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 25, 45).forEach { m -> FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m 分钟") }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Gear.entries.forEach { g -> FilterChip(selected = gearName == g.name, onClick = { gearName = g.name }, label = { Text(g.label) }) }
                }
                OutlinedButton(onClick = { picker = true }) { Text("登记材料 App（已选 ${selected.size} 个）") }
                Text("这一场在 Gemini、材料 App 和一手之间切换算在场；其他关注应用照常过入口。网页版请把浏览器登记为材料 App。", style = MaterialTheme.typography.bodySmall)
                Text("开场白会复制到剪贴板。打开后：进伴读 Gem → 粘贴 → 点 Live → 共享屏幕。按钮由你自己点。", style = MaterialTheme.typography.bodyMedium)
                Button(enabled = goal.isNotBlank() && !busy && state.error == null, onClick = {
                    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                        note = "先开启一手的通知，伴读计时和结束入口才会一直可见。"
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } else work {
                        val opening = partner.start(PartnerPlan(Material.valueOf(materialName), goal, minutes, Gear.valueOf(gearName)), selected.toSet())
                        if (opening == null) { note = "上一场还没收线，请先完成它。"; return@work }
                        Launch.copy(context, opening)
                        if (!PartnerService.start(context)) {
                            partner.finish()
                            note = "计时服务没启动，请先收线，再检查系统后台限制。"
                        } else if (!Launch.open(context, external)) {
                            partner.finish()
                            PartnerService.stop(context)
                            note = "没能打开外部 App 或网页，开场白已复制；先结束这一场再试。"
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "准备中……" else "用 Gemini 开始") }
            } else {
                val row = state.row!!
                Text(row.goal, style = MaterialTheme.typography.titleLarge)
                Text(state.text.ifBlank { "上次伴读还没收线" })
                if (!state.closing) {
                    OutlinedButton(onClick = { Launch.open(context, external) }) { Text("回 Gemini") }
                    Button(enabled = !busy, onClick = { work { partner.finish(); PartnerService.stop(context) } }) { Text("结束并收线") }
                } else {
                    Text("在场 ${row.presentMs / 60_000} 分钟 · 长时间离开 ${row.leaves} 次（不是接话统计）")
                    VoiceTextField(line, { line = it }, "一句话，今天聊出了什么", Modifier.fillMaxWidth(), minLines = 2)
                    VoiceTextField(next, { next = it }, "下一步做什么", Modifier.fillMaxWidth())
                    Text("收线至少 6 个字，下一步至少 4 个字；口水不算。", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && Closing.ok(line, next), onClick = { work {
                        if (partner.close(line, next)) {
                            PartnerService.stop(context)
                            PartnerService.clearClosing(context)
                            note = "收好了，下次开场接着这一步。"
                        }
                    } }, modifier = Modifier.fillMaxWidth()) { Text("保存收线") }
                    TextButton(enabled = !busy, onClick = { delete = true }) { Text("删除这场本机记录") }
                }
            }
        }
    }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("这一场允许的材料 App") },
        text = { LazyColumn {
            items(apps.filter { it.pkg != PartnerSessions.EXTERNAL_PACKAGE }, key = { it.pkg }) { item ->
                fun toggle() { selected = ArrayList(if (item.pkg in selected) selected - item.pkg else selected + item.pkg) }
                Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.pkg in selected, onCheckedChange = { toggle() })
                    Text(item.label)
                }
            }
        } }, confirmButton = { TextButton(onClick = { picker = false }) { Text("选好了") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这一场？") },
        text = { Text("计划、在场统计和未写完的收线都会从本机删除。") },
        confirmButton = { TextButton(onClick = { delete = false; work { partner.deletePending(); PartnerService.clearClosing(context) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("保留") } })
}
