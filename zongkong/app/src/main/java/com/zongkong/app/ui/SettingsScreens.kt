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
    val moling by context.zk.store.moling.collectAsStateWithLifecycle()
    val grantNow = rememberNow()
    val grants = com.zongkong.core.moling.Grants.applyDue(moling.grants, grantNow)
    val sig = LocalSignals.current
    val yishouInstalled = remember { com.zongkong.app.link.YishouClient.installed(context) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenTitle("我", "连接、规则、运行状态。改规则的规矩：收紧立即生效，放宽要等 24 小时，期间可以撤回。", Modifier.enter(0))

        SectionLabel("总控时间")
        MenuItem(
            "进入总控时间",
            if (config.hq.enabled) "每天 ${"%02d:%02d".format(config.hq.at / 60, config.hq.at % 60)} · ${config.hq.minutes} 分钟 · 万籁俱寂，只和总控说话" else "没开每日总控时间——现在也可以手动进",
            "hq", nav,
        )
        MenuItem(
            "总控时间与叫醒",
            listOfNotNull(
                if (config.hq.vibrate) "震动" else null,
                if (config.hq.escalation) "失联升级" else null,
                if (config.hq.proactive) "总控主动找你" else null,
                if (config.hq.speak) "语音" else null,
            ).joinToString(" · ").ifBlank { "全关了" },
            "settings/hq", nav,
        )

        SectionLabel("互动")
        MenuItem("强督导与一次缓行", "等待与接管范围、每日一次延后、跨日补交清单", "control", nav)
        MenuItem("总控怎么运转", "收→谋→筹→行→验；今天几点主动找你、信用怎么算、行动和记录怎么用", "flow", nav)
        MenuItem("对讲、语音、桌面宠物", "${if (config.voice.apiReady) "语音接口已接" else "用手机自带的语音"} · 主动程度：${com.zongkong.core.boss.BossConfig.INITIATIVE.getOrElse(config.boss.initiative) { "?" }} · ${com.zongkong.app.system.PetView.kindOf(config.boss.petKind).label}${if (config.boss.pet) "在桌面上" else "收起了"}", "voice/settings", nav)
        MenuItem(
            "外接谋划",
            "${config.directives.count { it.status == com.zongkong.core.plan.Directive.STATUS_ACTIVE }} 份在办 · Gemini / GPT / Notion 交进来的报告，总控只负责盯落实",
            "directives", nav,
        )

        SectionLabel("工具")
        MenuItem("脉络", "谋划思考部的知识结构：体系 → 层级 → 节点 → 关系", "vein", nav)
        MenuItem("线条动效", "潮汐、叶脉、矩阵、雷霆、漩涡、涟漪、年轮、流线、月相、星图、轨道", "gallery", nav)

        SectionLabel("运行")
        // 墨灵导演（试验）：默认关。关着时总控的一切行为都和以前一样
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("墨灵导演（试验）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (config.mailuo.enabled) "小圆点会出现，事件交给导演决定说不说；桌面宠物的几条主动提醒暂时安静"
                        else "关着：和以前一样。打开后小窗、接力、感知都交给导演（现在装的是替身导演）",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalSignals.current.muted,
                    )
                }
                Checkbox(
                    checked = config.mailuo.enabled,
                    onCheckedChange = { on ->
                        context.zk.store.updateConfig { it.copy(mailuo = it.mailuo.copy(enabled = on)) }
                    },
                )
            }
            Hint("打开后：解锁 / 熄屏 / 前台切换 / 连续娱乐 / 计划到点 / 夜里的整理都会交给导演；小圆点停在屏幕边上，点开是此刻这一问。导演还在施工，先用替身跑通链路。")
            Hint(com.zongkong.app.health.HealthWatch.text(context, System.currentTimeMillis()))
        }
        MenuItem("权限与运行状态", if (PermissionStatus.accessibility(context)) "无障碍已开启" else "无障碍未开启——总控没在盯", "settings/perm", nav, warn = !PermissionStatus.accessibility(context))
        MenuItem(
            "无障碍诊断",
            if (PermissionStatus.guardRunning(context)) "服务在运行 · 失联和失效原因都记着，可导出"
            else "服务没在运行——看看为什么，导出日志",
            "settings/diag", nav, warn = !PermissionStatus.guardRunning(context),
        )
        MenuItem("今天的节律", "今日部署、收集、判断、一手训练、日终验收；严管与放行", "rhythm", nav)
        MenuItem("历史记录", "最近 14 天", "history", nav)

        SectionLabel("连接")
        MenuItem("账本（试验）", "今天、本周花了多少；导入微信 / 支付宝账单", "ledger", nav)
        MenuItem("电脑", context.zk.store.device.value.pc?.takeIf { config.notion.devicesDb.isNotBlank() }?.let { "今天 ${it.todayMinutes / 60} 小时 ${it.todayMinutes % 60} 分 · 被拦 ${it.blocks} 次" } ?: "电脑上的总控：锁机、拦网站、和手机连起来", "settings/pc", nav)
        MenuItem("AI 接口", if (config.ai.ready) "${config.ai.model} · 总控者、日终验收用" else "未配置：总控者和 AI 验收用不了", "settings/ai", nav, warn = !config.ai.ready)
        MenuItem("一手", if (yishouInstalled) "已安装：行为管理部读它的组数，可以把行动交给它陪练" else "没装：点开下载", "dept/ACT", nav)
        MenuItem("Notion", when {
            config.notion.workReady -> "事项 / 行动 / 记录 双向同步中"
            config.notion.ready -> "已填密钥，还没建库"
            else -> "未连接：记录只在手机上"
        }, "settings/notion", nav)
        MenuItem("ChatGPT 配合", "给 GPT 的说明、交接块、各条连接的现状", "settings/gptguide", nav)

        SectionLabel("规则")
        MenuItem("关卡", "${config.gates.size} 道：每个部门每天要交什么", "settings/gates", nav)
        MenuItem("拦截名单", "${config.blocked.size} 个应用：严管时打开就被弹回", "settings/block", nav)
        MenuItem("墨灵的授权书", if (grants.grants.isEmpty()) "没有授权：墨灵的建议都要你点执行" else "已授权 ${grants.grants.size} 类", "settings/moling", nav)
        MenuItem(
            "低屏模式",
            if (config.watch.enabled && config.watch.rules.isNotEmpty())
                "${config.watch.rules.size} 个应用：连续用 ${config.watch.rules.firstOrNull()?.maxMinutes ?: 2} 分钟弹回做题，冷却后放行"
            else "没开：闲鱼 / Twitter 这类全封太狠、放开太浪费时间",
            "settings/watch", nav,
        )
        MenuItem(
            "以思代锁",
            if (config.training.enabled) "开着 · 每次 ${config.training.minutesPerPass} 分钟 · 每天 ${config.training.dailyMaxPasses} 次" else "关着：答几道题换一点今天的娱乐（只在挣来的用完时）",
            "settings/training", nav,
        )
        MenuItem("规则", "沉默 ${if (config.silenceHours == 0) "不查" else "${config.silenceHours} 小时"} · 娱乐 ${if (config.dailyQuotaMin == 0) "不限" else "${config.dailyQuotaMin} 分钟"} · 紧急放行 ${config.emergencyPerDay} 次", "settings/rules", nav)
        MenuItem("待生效的放宽", if (config.pending.isEmpty()) "没有" else "${config.pending.size} 项", "settings/pending", nav)

        Hint("总控 ${com.zongkong.app.BuildConfig.VERSION_NAME}", Modifier.padding(top = 12.dp))
        Hint("数据存在这台手机上；连了 Notion 才同步事项和记录；总控只把对话发给你自己填的 AI 接口。", Modifier.padding(bottom = 8.dp))
        if (config.pausedUntil > System.currentTimeMillis()) Text("休假中", color = sig.warn)
    }
}

@Composable
private fun MenuItem(title: String, sub: String, route: String, nav: NavHostController, warn: Boolean = false) {
    Panel(onClick = { nav.navigate(route) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (warn) LocalSignals.current.warn else LocalSignals.current.muted)
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
            Hint("严管时，打开勾选的应用会立刻被弹回总控；放行时打开要先答一题、每次给 10 分钟，用的时长算进今天挣到的娱乐。手机上的常见娱乐应用和游戏、视频类应用会自动勾上。加入立即生效，移出要等 24 小时。")
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val labels = apps.orEmpty().associate { it.pkg to it.label }
                    val out = Policy.setBlocked(config, selected, { labels[it] ?: it }, System.currentTimeMillis())
                    // 你自己拿掉的，自动识别时不再加回去；重新勾上的从“拿掉”里删
                    store.updateConfig { out.config.copy(unblocked = (config.unblocked + (config.blocked - selected)) - selected) }
                    message = out.message
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存（已选 ${selected.size} 个）") }
        }
        val list = apps
        if (list == null) {
            Hint("读取应用列表…", Modifier.padding(16.dp).shimmer())
        } else {
            val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                .sortedWith(compareByDescending<AppInfo> { it.pkg in selected || it.pkg in pendingRemoval }.thenBy { it.label })
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.pkg }) { a ->
                    val on = a.pkg in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .animateItem()
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
