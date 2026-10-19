@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yishou.pc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yishou.pc.Controller
import com.yishou.pc.core.Category
import com.yishou.pc.core.DayState
import com.yishou.pc.core.Event
import com.yishou.pc.core.EventType
import com.yishou.pc.core.FocusSlot
import com.yishou.pc.core.Focus
import com.yishou.pc.core.Gatekeeper
import com.yishou.pc.core.Prefs
import com.yishou.pc.core.Rule
import com.yishou.pc.core.RuleKind
import com.yishou.pc.win.OpenWindow
import com.yishou.pc.win.Win32
import java.awt.Desktop
import java.time.ZoneId

/** 主窗口：今天、规则、设置。关掉窗口只是收回托盘，守门照常。 */
@Composable
fun MainScreen(c: Controller) {
    val quitAsked by c.quitAsked.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(quitAsked) { if (quitAsked) tab = 2 }
    Box(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {}
        BoardBackdrop()
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 24.dp, top = 18.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                StoneMark(30.dp)
                Spacer(Modifier.width(12.dp))
                Text("一手", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("电脑上的守门", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0f), modifier = Modifier.padding(horizontal = 16.dp)) {
                listOf("今天", "规则", "设置").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> TodayTab(c)
                    1 -> RulesTab(c)
                    else -> SettingsTab(c)
                }
            }
        }
    }
}

// ---------------- 今天 ----------------

@Composable
private fun TodayTab(c: Controller) {
    val prefs by c.prefs.collectAsState()
    val day by c.day.collectAsState()
    val focus by c.focus.collectAsState()
    val now by c.now.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!prefs.welcomed) item { Welcome(c) }
        item { FocusCard(c, prefs, focus, now) }
        item { Budgets(prefs, day) }
        item { Totals(day) }
        item { Text("今天的记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (day.events.isEmpty()) item { Text("还没有记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(day.events.asReversed()) { e -> EventRow(e, prefs) }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Welcome(c: Controller) {
    Panel {
        Text("先说清楚它会做什么", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf(
            "它只看前台窗口的程序名和标题，用来认出夸克里的视频网站、WeGame 和它启动的游戏、雷电模拟器。不看窗口里的内容，不截屏，不联网。",
            "认出来时盖一个全屏的守门页：先写这次去做什么、回来做什么，等冷静期过去才放行；每类每天有额度。",
            "专注时段里（和手机上的陪练窗口设成同一个时间），这些东西一律不开。",
            "开机自动启动，托盘里一直有一颗黑子。随时可以在设置里关自启、退出或卸载；退出要写一句原因，被强行结束会在下次记一笔。",
            "记录只存在这台电脑上：${c.dataDir.absolutePath}",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = { c.updatePrefs { it.copy(welcomed = true) } }) { Text("知道了") }
    }
}

@Composable
private fun FocusCard(c: Controller, prefs: Prefs, focus: Focus.Span?, now: Long) {
    var stopping by remember { mutableStateOf(false) }
    Panel {
        if (focus != null) {
            Text("专注中，还剩 ${mmss(focus.end - now)}", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("要守的东西一律不开。拿起手机，应那一手。", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { stopping = true }) { Text("提前结束…") }
        } else {
            Text("专注时段", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val next = Focus.next(now, prefs, ZoneId.systemDefault())
            Text(
                if (next != null) "下一段：${Controller.time(next)}" else "还没有定时的专注时段，可以在设置里加，和手机上的陪练窗口设成同一个时间。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("现在开始：")
                listOf(25, 45, 60, 90).forEach { m -> OutlinedButton(onClick = { c.startFocus(m) }) { Text("$m 分钟") } }
            }
        }
    }
    if (stopping) {
        ReasonDialog(
            title = "提前结束专注？",
            hint = "写一句为什么要提前结束（至少 ${Gatekeeper.QUIT_MIN_CHARS} 个字），会记进今天的记录。",
            confirm = "结束专注",
            onDismiss = { stopping = false },
        ) { reason -> c.stopFocus(reason).also { if (it) stopping = false } }
    }
}

@Composable
private fun Budgets(prefs: Prefs, day: DayState) {
    Panel {
        Text("额度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        prefs.categories.forEach { cat ->
            val used = day.used[cat.id] ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(cat.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("$used / ${cat.dailyMinutes} 分钟", fontFamily = FontFamily.Monospace)
                }
                ThinBar(if (cat.dailyMinutes == 0) 1f else used.toFloat() / cat.dailyMinutes)
                Text(
                    "想打开 ${day.gates[cat.id] ?: 0} 次，放行 ${day.grants[cat.id] ?: 0} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Totals(day: DayState) {
    Panel {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Stat("不去了", "${day.declines}")
            Stat("到点回去", "${day.returns}")
            Stat("急事", "${day.emergencies}")
            Stat("说到做到", if (day.answered == 0) "-" else "${day.kept}/${day.answered}")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventRow(e: Event, prefs: Prefs) {
    val cat = prefs.category(e.category)?.name ?: e.category
    val text = when (e.type) {
        EventType.GATE -> "想打开$cat：${e.target}"
        EventType.GRANT -> "放行 ${e.minutes} 分钟去${e.what}，说好回来${e.then}"
        EventType.DECLINE -> "不去了"
        EventType.EMERGENCY -> "急事放行 ${e.minutes} 分钟：${e.note}"
        EventType.RETURN -> "时间到，回去${e.then}"
        EventType.FOLLOWUP -> if (e.done) "说好的「${e.then}」做了" else "说好的「${e.then}」没做"
        EventType.QUIT -> "退出了一手：${e.note}"
        EventType.UNCLEAN -> "上次一手没有正常退出（被结束或断电），${e.note}"
        EventType.FOCUS_START -> "专注时段开始（${e.minutes} 分钟）"
        EventType.FOCUS_STOP -> "提前结束专注：${e.note}"
    }
    val warn = e.type in setOf(EventType.QUIT, EventType.UNCLEAN, EventType.FOCUS_STOP, EventType.EMERGENCY) ||
        (e.type == EventType.FOLLOWUP && !e.done)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(Controller.time(e.time), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Text(text, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- 规则 ----------------

private fun kindName(k: RuleKind) = when (k) {
    RuleKind.PROCESS -> "程序"
    RuleKind.TITLE -> "网页标题含"
    RuleKind.CHILD_OF -> "由它启动的程序"
}

@Composable
private fun RulesTab(c: Controller) {
    val prefs by c.prefs.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "认法有三种：程序名（例如 wegame.exe）；浏览器窗口标题里含某个词（例如“哔哩哔哩”，只看下面列出的浏览器）；" +
                "由某个程序启动的程序（例如 WeGame 启动的游戏，不用一个个加）。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { picking = true }) { Text("从打开着的窗口里选") }
        prefs.categories.forEach { cat -> CategoryPanel(c, prefs, cat) }
        BrowsersPanel(c, prefs)
    }
    if (picking) WindowPicker(c, prefs) { picking = false }
}

@Composable
private fun CategoryPanel(c: Controller, prefs: Prefs, cat: Category) {
    var kind by remember { mutableStateOf(RuleKind.TITLE) }
    var pattern by remember { mutableStateOf("") }
    fun updateCat(f: (Category) -> Category) = c.updatePrefs { p -> p.copy(categories = p.categories.map { if (it.id == cat.id) f(it) else it }) }
    Panel {
        Text(cat.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每天", modifier = Modifier.width(80.dp))
            Stepper(cat.dailyMinutes, 0..600, "分钟", step = 5) { v -> updateCat { it.copy(dailyMinutes = v) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("一次最多", modifier = Modifier.width(80.dp))
            Stepper(cat.maxPass, 1..180, "分钟", step = 5) { v -> updateCat { it.copy(maxPass = v) } }
        }
        HorizontalDivider()
        prefs.rules.filter { it.category == cat.id }.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindName(r.kind), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
                Text(r.pattern, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { c.updatePrefs { p -> p.copy(rules = p.rules - r) } }) { Text("删掉") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            RuleKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindName(k)) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                singleLine = true,
                label = { Text(if (kind == RuleKind.TITLE) "标题里的词" else "程序名，例如 xxx.exe") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val p = pattern.trim().let { if (kind == RuleKind.TITLE) it else it.lowercase() }
                    c.updatePrefs { it.copy(rules = (it.rules + Rule(kind, p, cat.id)).distinct()) }
                    pattern = ""
                },
                enabled = pattern.isNotBlank(),
            ) { Text("加上") }
        }
    }
}

@Composable
private fun BrowsersPanel(c: Controller, prefs: Prefs) {
    var text by remember(prefs.browsers) { mutableStateOf(prefs.browsers.sorted().joinToString(", ")) }
    Panel {
        Text("浏览器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("按标题认网站时只看这些程序的窗口。夸克如果没被认出来，用“从打开着的窗口里选”看看它的程序名，加在这里。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = {
            val set = text.split(',', '，', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            c.updatePrefs { it.copy(browsers = set) }
        }) { Text("保存") }
    }
}

@Composable
private fun WindowPicker(c: Controller, prefs: Prefs, onClose: () -> Unit) {
    val windows = remember { Win32.openWindows() }
    var cat by remember { mutableStateOf(prefs.categories.first().id) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
        title = { Text("从打开着的窗口里选") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    prefs.categories.forEach { k -> FilterChip(selected = cat == k.id, onClick = { cat = k.id }, label = { Text(k.name) }) }
                }
                if (windows.isEmpty()) Text("没有读到窗口（只在 Windows 上能用）。")
                LazyColumn(Modifier.heightIn(max = 420.dp).widthIn(min = 560.dp)) {
                    items(windows) { w -> PickRow(w, prefs) { rule -> c.updatePrefs { it.copy(rules = (it.rules + rule.copy(category = cat)).distinct()) } } }
                }
            }
        },
    )
}

@Composable
private fun PickRow(w: OpenWindow, prefs: Prefs, add: (Rule) -> Unit) {
    var added by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.exe, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            Text(w.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (added) {
            Text("加上了", color = MaterialTheme.colorScheme.tertiary)
        } else {
            TextButton(onClick = { add(Rule(RuleKind.PROCESS, w.exe, "")); added = true }) { Text("整个程序") }
            TextButton(onClick = { add(Rule(RuleKind.CHILD_OF, w.exe, "")); added = true }) { Text("和它启动的") }
            if (w.exe in prefs.browsers) Text("网页请在分类里按标题加", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------- 设置 ----------------

@Composable
private fun SettingsTab(c: Controller) {
    val prefs by c.prefs.collectAsState()
    val quitAsked by c.quitAsked.collectAsState()
    var h by remember { mutableIntStateOf(20) }
    var m by remember { mutableIntStateOf(0) }
    var len by remember { mutableIntStateOf(45) }
    var quitReason by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel {
            Text("定时专注", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("建议和手机上陪练窗口设成同一个时间：手机在等你应手的时候，电脑不给你逃。", style = MaterialTheme.typography.bodySmall)
            prefs.focusSlots.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每天 %02d:%02d，%d 分钟".format(s.hour, s.minute, s.minutes), modifier = Modifier.weight(1f))
                    TextButton(onClick = { c.updatePrefs { it.copy(focusSlots = it.focusSlots - s) } }) { Text("删掉") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper(h, 0..23, "时") { h = it }
                Stepper(m, 0..55, "分", step = 5) { m = it }
                Stepper(len, 5..240, "分钟", step = 5) { len = it }
                Button(onClick = { c.updatePrefs { it.copy(focusSlots = (it.focusSlots + FocusSlot(h, m, len)).distinct().sortedBy { s -> s.hour * 60 + s.minute }) } }) { Text("加上") }
            }
        }
        Panel {
            Text("提醒与启动", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SwitchRow("朗读提醒（Windows 自带语音）", prefs.speak) { on -> c.updatePrefs { it.copy(speak = on) } }
            SwitchRow("开机自动启动", prefs.autostart) { on -> c.updatePrefs { it.copy(autostart = on) } }
            if (prefs.autostart && !c.autostartOk) {
                Text("现在不是从安装版运行的，自启没设上。装好以后从开始菜单打开一次就会设上。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每天急事次数", modifier = Modifier.weight(1f))
                Stepper(prefs.emergencyPerDay, 0..5, "次") { v -> c.updatePrefs { it.copy(emergencyPerDay = v) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("冷静期起步", modifier = Modifier.weight(1f))
                Stepper(prefs.cooldownBase, 0..120, "秒", step = 5) { v -> c.updatePrefs { it.copy(cooldownBase = v) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每多放一次加", modifier = Modifier.weight(1f))
                Stepper(prefs.cooldownStep, 0..120, "秒", step = 5) { v -> c.updatePrefs { it.copy(cooldownStep = v) } }
            }
        }
        Panel {
            Text("数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(c.dataDir.absolutePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(c.dataDir) } }) { Text("打开文件夹") }
            Text("设置是 prefs.json，逃跑记录按天存在 events 文件夹里，可以直接用记事本看。不联网，不上传。", style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            Text("退出一手", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (quitAsked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("可以退出，但要写一句为什么（至少 ${Gatekeeper.QUIT_MIN_CHARS} 个字），会记进今天的记录。关掉主窗口不是退出，守门照常。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = quitReason, onValueChange = { quitReason = it }, label = { Text("为什么要退出") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.quit(quitReason) }, enabled = Gatekeeper.chars(quitReason) >= Gatekeeper.QUIT_MIN_CHARS) { Text("退出") }
                if (quitAsked) TextButton(onClick = { c.askQuit(false) }) { Text("不退了") }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
fun ReasonDialog(title: String, hint: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Boolean) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = reason, onValueChange = { reason = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }, enabled = Gatekeeper.chars(reason) >= Gatekeeper.QUIT_MIN_CHARS) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } },
    )
}
