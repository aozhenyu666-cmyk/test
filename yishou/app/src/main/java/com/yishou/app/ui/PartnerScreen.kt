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
fun PartnerScreen(onBack: () -> Unit, materialId: Long? = null) {
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
    val voiceRun by app.partnerVoice.running.collectAsStateWithLifecycle()
    // 从素材池选一段一起聊：语音伙伴就围着这段说
    val pool by remember { app.database.think().observeMaterials() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var sourceId by rememberSaveable { mutableStateOf(materialId ?: -1L) }
    LaunchedEffect(materialId, pool.size) {
        val m = pool.firstOrNull { it.id == materialId } ?: return@LaunchedEffect
        if (goal.isBlank()) goal = m.title
    }
    fun currentPlan() = PartnerPlan(
        Material.valueOf(materialName), goal, minutes, Gear.valueOf(gearName),
        source = pool.firstOrNull { it.id == sourceId }?.text.orEmpty(),
    )
    fun startVoice() = work {
        val err = app.partnerVoice.start(currentPlan())
        if (err != null) note = err
        else if (!com.yishou.app.partner.PartnerVoiceService.start(context)) {
            app.partnerVoice.stop()
            note = "麦克风服务没启动，请检查通知和后台限制。"
        }
        Unit
    }
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startVoice() else note = "没有麦克风权限，语音伙伴开不了。"
        Unit
    }
    Scaffold(topBar = { BackTopBar("语音伙伴", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("外部伴读（可选）", style = MaterialTheme.typography.titleMedium)
            Text("这里只准备开场、计时和收线，不会读取外部对话，也不能判断你有没有接话。前台在场不等于参与。", style = MaterialTheme.typography.bodySmall)
            if (GateService.foreground == null) Text("无障碍服务没开：这场不能统计在场或提醒离开。可到系统设置手动开启。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            note?.let { Text(it) }
            val live = voiceRun
            if (live != null) {
                VoiceLivePanel(live, onInterrupt = { app.partnerVoice.interrupt() }, onEnd = { app.partnerVoice.end() },
                    onClose = { app.partnerVoice.stop() }, onGear = { app.partnerVoice.setGear(it) })
            } else if (state.loading) {
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
                val picks = (pool.filter { it.id == sourceId } + pool.filter { it.source != com.yishou.app.data.MaterialSource.BUILTIN && it.id != sourceId }.take(4))
                if (picks.isNotEmpty()) {
                    Text("一起聊素材池里的哪段？（只对“用一手自己的语音”有用）", style = MaterialTheme.typography.bodySmall)
                    picks.forEach { m ->
                        FilterChip(selected = sourceId == m.id, onClick = {
                            sourceId = if (sourceId == m.id) -1L else m.id
                            if (sourceId == m.id && goal.isBlank()) goal = m.title
                        }, label = { Text(m.title, maxLines = 1) })
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
                Button(enabled = goal.isNotBlank() && !busy && state.error == null, onClick = {
                    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                        note = "先开启一手的通知：麦克风在用时通知里要能看见、能停。"
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } else if (!app.voice.hasPermission()) {
                        askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else startVoice()
                }, modifier = Modifier.fillMaxWidth()) { Text("用一手自己的语音开始") }
                Text("一手自己的语音：你说完，它想好了念给你听；它说话时不开麦，想打断就点“停”。用设置里的文字模型和听写 / 朗读接口，没填听写朗读就用手机自带的。戴耳机效果更好。", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun VoiceLivePanel(
    r: com.yishou.app.partner.PartnerVoice.Running,
    onInterrupt: () -> Unit,
    onEnd: () -> Unit,
    onClose: () -> Unit,
    onGear: (Gear) -> Unit,
) {
    val s = r.live
    val min = s.remainingMs / 60_000
    val sec = (s.remainingMs / 1000) % 60
    Text(r.plan.goal, style = MaterialTheme.typography.titleLarge)
    Text(
        when {
            s.stage == com.yishou.app.partner.Stage.CLOSE -> "收线中"
            s.stage == com.yishou.app.partner.Stage.ENDED -> "收好了"
            s.phase == com.yishou.app.partner.Phase.SPEAKING -> "在说"
            s.phase == com.yishou.app.partner.Phase.THINKING -> "在想"
            s.stage == com.yishou.app.partner.Stage.PROBE -> "在等你回答"
            else -> "在听"
        } + " · 还剩 %d:%02d".format(min, sec) + if (s.missed > 0) " · 没接 ${s.missed}" else "",
        style = MaterialTheme.typography.titleMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Gear.entries.forEach { g -> FilterChip(selected = s.gear == g, onClick = { onGear(g) }, label = { Text(g.label) }) }
    }
    s.lines.takeLast(4).forEach { t ->
        Text("${t.who}：${t.text}", style = MaterialTheme.typography.bodyMedium,
            color = if (t.who == com.yishou.app.partner.PartnerController.ME) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary)
    }
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Button(onClick = onInterrupt, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("停（打断它，轮到我说）") }
    OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth()) { Text("结束并收线") }
    TextButton(onClick = onClose) { Text("直接关掉（收线留到下次）") }
    Text("口令：“今天到这”收线，“逼紧一点 / 放松点”换强度，“安静”先别主动问，说完一段话可以加一句“好了”。", style = MaterialTheme.typography.bodySmall)
}
