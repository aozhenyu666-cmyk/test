package com.zongkong.app.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.ChangeOp
import com.zongkong.core.DayClock
import com.zongkong.core.Defaults
import com.zongkong.core.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val config by context.zk.store.config.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Hint("改规则的规矩：收紧立即生效；放宽（删关卡、推迟截止、降低验收、移出拦截名单、加额度）要等 24 小时，期间可以撤回。")
        MenuItem("权限与运行状态", if (PermissionStatus.accessibility(context)) "无障碍已开启" else "无障碍未开启——总控没在盯", "settings/perm", nav)
        MenuItem("拦截名单", "${config.blocked.size} 个应用：严管时打开就被弹回", "settings/block", nav)
        MenuItem("关卡", "${config.gates.size} 道：每个部门每天要交什么", "settings/gates", nav)
        MenuItem("规则", "沉默 ${if (config.silenceHours == 0) "不查" else "${config.silenceHours} 小时"} · 娱乐 ${if (config.dailyQuotaMin == 0) "不限" else "${config.dailyQuotaMin} 分钟"} · 紧急放行 ${config.emergencyPerDay} 次", "settings/rules", nav)
        MenuItem("Notion", when {
            config.notion.workReady -> "事项 / 行动 / 记录 双向同步中"
            config.notion.ready -> "已填密钥，还没建库"
            else -> "未连接：记录只在手机上"
        }, "settings/notion", nav)
        MenuItem("ChatGPT 配合", "给 GPT 的说明、交接块、各条连接的现状", "settings/gptguide", nav)
        MenuItem("每日节律", "今日部署、收集、判断、训练、日终验收；严管与放行", "rhythm", nav)
        MenuItem("AI 接口", if (config.ai.ready) "${config.ai.model}（日终验收等用）" else "未配置", "settings/ai", nav)
        MenuItem("四部门说明", "信息收集、谋划思考、统筹规划、行为管理怎么衔接", "depts", nav)
        MenuItem("待生效的放宽", if (config.pending.isEmpty()) "没有" else "${config.pending.size} 项", "settings/pending", nav)
        MenuItem("历史记录", "最近 14 天", "history", nav)
        Hint("总控 ${com.zongkong.app.BuildConfig.VERSION_NAME}", Modifier.padding(top = 8.dp))
        Hint("所有数据只存在这台手机上；配置了 Notion 才会把验收结果写到你的 Notion。", Modifier.padding(bottom = 8.dp))
        if (config.pausedUntil > System.currentTimeMillis()) Text("休假中", color = sig.warn)
    }
}

@Composable
private fun MenuItem(title: String, sub: String, route: String, nav: NavHostController) {
    Panel(onClick = { nav.navigate(route) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSignals.current.muted)
        }
    }
}

/** 改动结果弹窗。 */
@Composable
fun OutcomeDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
        text = { Text(message) },
    )
}

// ---------- 拦截名单 ----------

private data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

@Composable
fun BlockListScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val pendingRemoval = config.pending.mapNotNull { (it.op as? ChangeOp.Unblock)?.pkg }.toSet()
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var selected by remember { mutableStateOf(config.blocked - pendingRemoval) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { launchableApps(context.packageManager, context.packageName) }
        // 第一次打开且名单为空：常见娱乐应用默认勾上
        if (config.blocked.isEmpty()) {
            val installed = apps.orEmpty().map { it.pkg }.toSet()
            selected = Defaults.suggestedBlocked.filter { it in installed }.toSet()
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("拦截名单", { nav.popBackStack() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("严管时，打开勾选的应用会立刻被弹回总控；放行时会累计它们的使用时长，算进每日娱乐上限。加入立即生效，移出要等 24 小时。")
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val labels = apps.orEmpty().associate { it.pkg to it.label }
                    val out = Policy.setBlocked(config, selected, { labels[it] ?: it }, System.currentTimeMillis())
                    store.updateConfig { out.config }
                    message = out.message
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存（已选 ${selected.size} 个）") }
        }
        val list = apps
        if (list == null) {
            Hint("读取应用列表…", Modifier.padding(16.dp))
        } else {
            val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                .sortedWith(compareByDescending<AppInfo> { it.pkg in selected || it.pkg in pendingRemoval }.thenBy { it.label })
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.pkg }) { a ->
                    val on = a.pkg in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (on) selected - a.pkg else selected + a.pkg }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(36.dp)) { a.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) } }
                        Column(Modifier.weight(1f)) {
                            Text(a.label, style = MaterialTheme.typography.bodyLarge)
                            val note = when {
                                a.pkg in pendingRemoval && !on -> "移出待生效"
                                a.pkg in Defaults.suggestedBlocked -> "常见娱乐应用"
                                else -> a.pkg
                            }
                            Text(note, style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
                        }
                        Checkbox(checked = on, onCheckedChange = { selected = if (it) selected + a.pkg else selected - a.pkg })
                    }
                }
            }
        }
    }
    OutcomeDialog(message) { message = null }
}

private fun launchableApps(pm: PackageManager, self: String): List<AppInfo> {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != self && it.packageName !in Defaults.neverBlock }
        .map { ai ->
            val icon = try {
                pm.getApplicationIcon(ai).toBitmap(72, 72, Bitmap.Config.ARGB_8888).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            AppInfo(ai.packageName, pm.getApplicationLabel(ai).toString(), icon)
        }
}

// ---------- 规则 ----------

@Composable
fun RulesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    var silence by remember { mutableStateOf(config.silenceHours.toString()) }
    var start by remember { mutableStateOf(DayClock.hhmm(config.activeStart)) }
    var end by remember { mutableStateOf(DayClock.hhmm(config.activeEnd)) }
    var quota by remember { mutableStateOf(config.dailyQuotaMin.toString()) }
    var emgCount by remember { mutableStateOf(config.emergencyPerDay.toString()) }
    var emgMin by remember { mutableStateOf(config.emergencyMinutes.toString()) }
    var degraded by remember { mutableStateOf(config.degradedPerDay.toString()) }
    var pauseDays by remember { mutableStateOf("1") }
    var message by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize()) {
        BackBar("规则", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("沉默检查：不能一直不汇报")
            NumberField("多少小时没汇报就严管（0 = 不查）", silence, { silence = it }, Modifier.fillMaxWidth(), "小时")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("检查从") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it }, label = { Text("到") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            SectionLabel("娱乐上限：不能一直妄为")
            NumberField("每天娱乐应用总时长（0 = 不限）", quota, { quota = it }, Modifier.fillMaxWidth(), "分钟")
            SectionLabel("出口")
            NumberField("紧急放行每天几次", emgCount, { emgCount = it }, Modifier.fillMaxWidth(), "次")
            NumberField("紧急放行每次多久", emgMin, { emgMin = it }, Modifier.fillMaxWidth(), "分钟")
            NumberField("AI/Notion 连不上时，降级验收每天几次", degraded, { degraded = it }, Modifier.fillMaxWidth(), "次")
            Button(
                onClick = {
                    val s = DayClock.parse(start)
                    val e = DayClock.parse(end)
                    if (s == null || e == null) {
                        message = "时间格式不对，写成 08:00 这样"
                    } else {
                    val r = Policy.Rules(
                        silenceHours = silence.toIntOrNull() ?: config.silenceHours,
                        activeStart = s,
                        activeEnd = e,
                        dailyQuotaMin = quota.toIntOrNull() ?: config.dailyQuotaMin,
                        emergencyPerDay = emgCount.toIntOrNull() ?: config.emergencyPerDay,
                        emergencyMinutes = (emgMin.toIntOrNull() ?: config.emergencyMinutes).coerceAtLeast(1),
                        degradedPerDay = degraded.toIntOrNull() ?: config.degradedPerDay,
                    )
                    val out = Policy.setRules(config, r, System.currentTimeMillis())
                    store.updateConfig { out.config }
                    message = out.message
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("休假")
            if (config.pausedUntil > now) {
                Text("休假到 ${java.time.Instant.ofEpochMilli(config.pausedUntil).atZone(store.zone).toLocalDateTime().toString().replace('T', ' ').take(16)}")
                OutlinedButton(onClick = {
                    val out = Policy.endPause(config, System.currentTimeMillis())
                    store.updateConfig { out.config }
                    message = out.message
                }) { Text("提前结束休假") }
            } else {
                Hint("生病、出远门时用。申请后 24 小时才开始，期间可以撤回。休假期间不拦截。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField("休几天", pauseDays, { pauseDays = it }, Modifier.weight(1f), "天")
                    OutlinedButton(onClick = {
                        val d = (pauseDays.toIntOrNull() ?: 1).coerceIn(1, 14)
                        val out = Policy.requestPause(config, d, System.currentTimeMillis())
                        store.updateConfig { out.config }
                        message = out.message
                    }) { Text("申请休假") }
                }
            }
        }
    }
    OutcomeDialog(message) { message = null }
}

// ---------- 待生效 ----------

@Composable
fun PendingScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val now = rememberNow()
    Column(Modifier.fillMaxSize()) {
        BackBar("待生效的放宽", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (config.pending.isEmpty()) Hint("没有等待生效的改动。")
            config.pending.sortedBy { it.effectiveAt }.forEach { p ->
                Panel {
                    Text(p.summary, style = MaterialTheme.typography.bodyLarge)
                    KeyValue("提交于", clock(p.createdAt))
                    KeyValue("生效", "${clock(p.effectiveAt)}（还有 ${span(p.effectiveAt - now)}）", LocalSignals.current.warn)
                    OutlinedButton(onClick = { store.updateConfig { Policy.cancel(it, p.id) } }) { Text("撤回") }
                }
            }
        }
    }
}
