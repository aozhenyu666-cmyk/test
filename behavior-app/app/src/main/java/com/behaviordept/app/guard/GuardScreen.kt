package com.behaviordept.app.guard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.data.Rule
import com.behaviordept.app.data.RuleToday
import com.behaviordept.app.data.UrgeLog
import com.behaviordept.app.data.UsageDay
import com.behaviordept.app.record.Metrics
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.LimitBar
import com.behaviordept.app.ui.components.UsageBars
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.NumberField
import com.behaviordept.app.ui.components.PageHeader
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.RuledTextField
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.components.TianZiGeCell
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GuardViewModel(private val c: AppContainer) : ViewModel() {
    val rules: StateFlow<List<Rule>> = c.guard.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val usage: StateFlow<List<UsageDay>> = c.guard.observeUsageSince(Time.today().minusDays(29))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val urges: StateFlow<List<UrgeLog>> = c.guard.observeUrgesSince(Time.startOf(Time.today().minusDays(29)))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var today by mutableStateOf<List<RuleToday>>(emptyList())
        private set
    var hasPermission by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        viewModelScope.launch {
            c.guard.applyDue()
            hasPermission = c.guard.hasUsagePermission
            today = c.guard.today()
            c.guard.sync()
        }
    }

    fun appName(pkg: String) = c.guard.appName(pkg)

    fun launchable(): List<Pair<String, String>> = UsageReader.launchableApps(c.appContext)

    fun save(old: Rule?, name: String, limit: Int, packages: List<String>) {
        viewModelScope.launch {
            if (old == null) {
                c.guard.addRule(name, limit, packages)
                message = "已新增，立即生效"
            } else {
                c.guard.editRule(old, name, limit, packages)
                val loosening = limit > old.dailyLimitMin || !packages.containsAll(old.packageList)
                message = if (loosening) "放宽的部分要等 24 小时才生效，期间可以撤回" else "已收紧，立即生效"
            }
            refresh()
        }
    }

    fun delete(rule: Rule) {
        viewModelScope.launch {
            c.guard.requestDelete(rule)
            message = "删除要等 24 小时才生效，期间可以撤回"
        }
    }

    fun cancelPending(rule: Rule) {
        viewModelScope.launch { c.guard.cancelPending(rule) }
    }
}

/** 防线：让“不做”有数据、有障碍、改规则有代价（PRD M5）。 */
@Composable
fun GuardScreen(onUrge: () -> Unit) {
    val vm = appViewModel { GuardViewModel(it) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val urges by vm.urges.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val p = Paper.colors
    var editing by remember { mutableStateOf<Rule?>(null) }
    var adding by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            vm.refresh()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader("防线", "不做比去做容易。收紧立刻生效，放宽要等 24 小时。")

        if (!vm.hasPermission) {
            PaperCard {
                SectionLabel("需要一个权限")
                Spacer(Modifier.height(6.dp))
                Text("打开“使用情况访问权限”，才能自动读取抖音、小红书这些 App 每天用了多久。不用手填。", style = MaterialTheme.typography.bodyMedium, color = p.ink)
                Spacer(Modifier.height(12.dp))
                LineButton("去开启", onClick = { UsageReader.openSettings(context) }, modifier = Modifier.fillMaxWidth())
            }
        }

        vm.today.forEach { rt -> TodayRuleCard(rt, vm.hasPermission) }

        InkButton("我想刷", onClick = onUrge)
        Hint("想刷的时候先点这里：记下原因，等 3 分钟，再看今天那一件事。")

        vm.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.red) }

        val dates = (29 downTo 0).map { Time.today().minusDays(it.toLong()) }
        rules.forEach { rule ->
            val daily = Metrics.ruleDaily(usage, rule, dates)
            val withData = usage.map { it.date }.toSet()
            val counted = daily.filter { it.first.toString() in withData && it.first.isBefore(Time.today()) }
            val kept = counted.count { it.second <= rule.dailyLimitMin }
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("${rule.name.ifBlank { "规则" }} · 近 30 天", Modifier.weight(1f))
                    if (counted.isNotEmpty()) {
                        Text("守住 $kept/${counted.size} 天", style = MaterialTheme.typography.labelLarge, color = if (kept * 5 >= counted.size * 4) p.ink else p.red)
                    }
                }
                Spacer(Modifier.height(12.dp))
                UsageBars(daily.map { it.second }, rule.dailyLimitMin)
                Spacer(Modifier.height(6.dp))
                Hint("横线是上限 ${rule.dailyLimitMin} 分钟，超出的部分是红色。")
            }
        }

        Text("规则", style = MaterialTheme.typography.headlineSmall, color = p.ink)
        rules.forEach { rule ->
            RuleCard(rule, vm, onEdit = { editing = rule })
        }
        LineButton("新增一条规则", onClick = { adding = true }, modifier = Modifier.fillMaxWidth())
        Hint("执行锁在「不做手机控」里设置：把同样的 App 和上限同步过去。这里负责定义规则、看数据。")

        UrgeStats(urges)
        Spacer(Modifier.height(24.dp))
    }

    if (adding || editing != null) {
        RuleDialog(
            rule = editing,
            vm = vm,
            onDismiss = { adding = false; editing = null },
        ) { name, limit, pkgs ->
            vm.save(editing, name, limit, pkgs)
            adding = false
            editing = null
        }
    }
}

@Composable
private fun TodayRuleCard(rt: RuleToday, hasPermission: Boolean) {
    val p = Paper.colors
    PaperCard {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel("今天 · ${rt.rule.name.ifBlank { "规则" }}", Modifier.weight(1f).padding(bottom = 8.dp))
            Text("${rt.minutes}", style = MaterialTheme.typography.displaySmall, color = if (rt.over) p.red else p.ink)
            Text(" / ${rt.rule.dailyLimitMin} 分钟", style = MaterialTheme.typography.titleMedium, color = p.ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        LimitBar(rt.minutes, rt.rule.dailyLimitMin)
        if (!hasPermission) {
            Spacer(Modifier.height(6.dp))
            Hint("没有权限，读不到用时。")
        } else if (rt.over) {
            Spacer(Modifier.height(6.dp))
            Text("已超限 ${rt.minutes - rt.rule.dailyLimitMin} 分钟", style = MaterialTheme.typography.labelLarge, color = p.red)
        }
    }
}

@Composable
private fun RuleCard(rule: Rule, vm: GuardViewModel, onEdit: () -> Unit) {
    val p = Paper.colors
    PaperCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rule.name.ifBlank { "规则" }, style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.weight(1f))
            Text("每天 ${rule.dailyLimitMin} 分钟", style = MaterialTheme.typography.titleSmall, color = p.ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(rule.packageList.joinToString(" · ") { vm.appName(it) }, style = MaterialTheme.typography.bodySmall, color = p.ink2)
        val at = rule.pendingEffectiveAt
        if (at != null) {
            Spacer(Modifier.height(8.dp))
            val what = when {
                rule.pendingDelete -> "删除这条规则"
                else -> buildList {
                    rule.pendingLimitMin?.takeIf { it != rule.dailyLimitMin }?.let { add("上限改为 $it 分钟") }
                    rule.pendingPackages?.split(',')?.filter { it.isNotBlank() }?.let { pk ->
                        val removed = rule.packageList - pk.toSet()
                        if (removed.isNotEmpty()) add("不再管 " + removed.joinToString("、") { vm.appName(it) })
                    }
                }.joinToString("，").ifBlank { "放宽规则" }
            }
            Text("冷静期：${Time.md(at)} ${Time.hm(at)} 之后$what", style = MaterialTheme.typography.labelLarge, color = p.red)
        }
        Row {
            QuietButton("修改", onClick = onEdit)
            if (at != null) QuietButton("撤回放宽", onClick = { vm.cancelPending(rule) })
            else QuietButton("删除", onClick = { vm.delete(rule) })
        }
    }
}

@Composable
private fun UrgeStats(urges: List<UrgeLog>) {
    if (urges.isEmpty()) return
    val p = Paper.colors
    PaperCard {
        SectionLabel("近 30 天的“我想刷”")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${urges.size}", style = MaterialTheme.typography.displaySmall, color = p.ink)
            Text(" 次，其中 ", style = MaterialTheme.typography.titleMedium, color = p.ink2, modifier = Modifier.padding(bottom = 6.dp))
            Text("${urges.count { it.startedTraining }}", style = MaterialTheme.typography.displaySmall, color = p.red)
            Text(" 次转去训练", style = MaterialTheme.typography.titleMedium, color = p.ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Hint("原因：" + urges.groupBy { it.reason }.entries.sortedByDescending { it.value.size }.joinToString("、") { "${it.key} ${it.value.size}" })
    }
}

/** 新增 / 修改规则：名字、每日上限、管哪些 App。 */
@Composable
private fun RuleDialog(rule: Rule?, vm: GuardViewModel, onDismiss: () -> Unit, onSave: (String, Int, List<String>) -> Unit) {
    val p = Paper.colors
    var name by remember { mutableStateOf(rule?.name ?: "") }
    var limit by remember { mutableStateOf((rule?.dailyLimitMin ?: 30).toString()) }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(rule?.packageList.orEmpty()) } }
    val apps = remember { vm.launchable() }
    val all = remember(apps) {
        // 规则里有、但手机上没装的 App 也列出来，方便取消。
        val installed = apps.map { it.first }.toSet()
        rule?.packageList.orEmpty().filter { it !in installed }.map { it to vm.appName(it) } + apps
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.page,
        title = { Text(if (rule == null) "新增规则" else "修改规则", style = MaterialTheme.typography.titleLarge, color = p.ink) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("收紧（降低上限、多管 App）立即生效；放宽要等 24 小时。")
                SectionLabel("名字")
                RuledTextField(name, { name = it }, singleLine = true, placeholder = "例如：娱乐 App")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("每天合计上限")
                    NumberField(limit, { limit = it }, width = 80.dp)
                    Text("分钟", style = MaterialTheme.typography.bodyMedium, color = p.ink2)
                }
                SectionLabel("管哪些 App（已选 ${chosen.size} 个）")
                all.forEach { (pkg, label) ->
                    val on = pkg in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (on) chosen.remove(pkg) else chosen.add(pkg)
                        }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TianZiGeCell(done = on, size = 26.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = p.ink)
                    }
                }
            }
        },
        confirmButton = {
            val ok = chosen.isNotEmpty() && (limit.toIntOrNull() ?: -1) >= 0
            TextButton(onClick = { onSave(name.ifBlank { "规则" }, limit.toIntOrNull() ?: 30, chosen.toList()) }, enabled = ok) {
                Text("保存", color = if (ok) p.red else p.ink2, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.ink) } },
    )
}
