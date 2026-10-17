package com.zongkong.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.data.AppNames
import com.zongkong.app.watch.BankQuestion
import com.zongkong.app.watch.BankStore
import com.zongkong.app.zk
import com.zongkong.core.WatchConfig
import com.zongkong.core.WatchRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 低屏模式设置页。
 *
 * 不是全封：规则里的应用可以连续用几分钟，到点弹回来做一道题，冷却完再放行。
 * 这里管三件事：总开关、限时的应用（每条的分钟数和冷却秒数）、题库（导入 / 预览 / 清空）。
 */
@Composable
fun WatchScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()

    // 题库很小（本机一个 JSON），同步读一眼没关系；导入/清空后手动刷新
    var bankCount by remember { mutableIntStateOf(BankStore.count(context)) }
    var bankPreview by remember { mutableStateOf(BankStore.load(context).questions.take(5)) }

    fun reloadBank() {
        bankCount = BankStore.count(context)
        bankPreview = BankStore.load(context).questions.take(5)
    }

    var message by remember { mutableStateOf<String?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppChoice>?>(null) }
    var query by remember { mutableStateOf("") }

    fun saveWatch(f: (WatchConfig) -> WatchConfig) {
        store.updateConfig { it.copy(watch = f(it.watch)) }
    }

    fun updateRule(pkg: String, f: (WatchRule) -> WatchRule) = saveWatch { w ->
        w.copy(rules = w.rules.map { if (it.pkg == pkg) f(it) else it })
    }

    fun deleteRule(pkg: String) = saveWatch { w -> w.copy(rules = w.rules.filterNot { it.pkg == pkg }) }

    /** 一键预置：常用的两个就不用在应用列表里找了。 */
    fun addPreset(pkg: String, label: String) {
        if (config.watch.rules.any { it.pkg == pkg }) {
            message = "「$label」已经在规则里"
            return
        }
        saveWatch { w -> w.copy(rules = w.rules + WatchRule(pkg = pkg, label = label)) }
        message = "已加入「$label」：连续 2 分钟弹回做题，冷却 10 秒"
    }

    // 从文件导入题库：UTF-8 的 TXT / MD，一行一题
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
            }
            if (text.isNullOrBlank()) {
                message = "这个文件读不出文字（只支持 UTF-8 的 TXT / MD）"
            } else {
                val added = withContext(Dispatchers.IO) { BankStore.importText(context, text) }
                reloadBank()
                message = if (added > 0) "收进题库 $added 道题" else "没有新题：可能都已经在题库里，或者不是一行一题"
            }
        }
    }

    LaunchedEffect(pickerOpen) {
        if (pickerOpen && apps == null) {
            apps = withContext(Dispatchers.IO) { launchableApps(context.packageManager, context.packageName) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("低屏模式", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Hint("不是全封：连续用几分钟就弹回来做一道题，冷却后再放行。给闲鱼、Twitter/X 这种“全封太狠、放开太浪费时间”的应用用。")

            Panel(accent = if (config.watch.enabled) sig.free else null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (config.watch.enabled) "低屏模式已开启" else "低屏模式没开",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Hint(
                            when {
                                config.watch.rules.isEmpty() -> "先加一个应用，规则才会生效"
                                else -> "${config.watch.rules.size} 个应用在限时"
                            },
                        )
                    }
                    Switch(
                        checked = config.watch.enabled,
                        onCheckedChange = { on -> saveWatch { it.copy(enabled = on) } },
                    )
                }
            }

            SectionLabel("限时的应用")
            if (config.watch.rules.isEmpty()) {
                Hint("还没有规则。点“添加应用”，选闲鱼、Twitter 这类应用；默认连续 2 分钟、冷却 10 秒。", Modifier.padding(vertical = 4.dp))
            }
            config.watch.rules.forEach { rule ->
                RuleCard(
                    rule = rule,
                    onMinutes = { v -> updateRule(rule.pkg) { it.copy(maxMinutes = v) } },
                    onCooldown = { v -> updateRule(rule.pkg) { it.copy(cooldownSeconds = v) } },
                    onDelete = { deleteRule(rule.pkg) },
                )
            }
            OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("添加应用") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { addPreset(IDLEFISH, "闲鱼") },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("＋闲鱼") }
                OutlinedButton(
                    onClick = { addPreset(TWITTER, "Twitter/X") },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("＋Twitter/X") }
            }

            SectionLabel("题库")
            Panel {
                Text("$bankCount 道题", style = MaterialTheme.typography.titleMedium)
                Hint("一行一题：题干||答案（写一个竖线也行），也可以只写题干。导入 UTF-8 的 TXT / MD，或直接粘贴。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { importDoc.launch(arrayOf("text/plain", "text/markdown", "text/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("选文件导入") }
                    OutlinedButton(onClick = { pasteOpen = true }, modifier = Modifier.weight(1f)) { Text("粘贴导入") }
                }
                if (bankPreview.isNotEmpty()) {
                    bankPreview.forEachIndexed { i, q ->
                        Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            Text(
                                "${i + 1}. ${q.stem}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (q.answer.isNotBlank()) Hint("答案：${q.answer}")
                        }
                    }
                    if (bankCount > bankPreview.size) Hint("……还有 ${bankCount - bankPreview.size} 道")
                }
                if (bankCount > 0) {
                    TextButton(
                        onClick = {
                            BankStore.clear(context)
                            reloadBank()
                            message = "题库已清空"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("清空题库", color = sig.strict) }
                }
            }
            Hint("题库存在手机本地：${BankStore.file(context).absolutePath}", Modifier.padding(top = 4.dp))
        }
    }

    if (pasteOpen) {
        EditDialog(
            title = "粘贴题库",
            initial = "",
            hint = "一行一题。题干||答案；只有题干也行。重复的会自动跳过。",
            onDismiss = { pasteOpen = false },
            onSave = { text ->
                scope.launch {
                    val added = withContext(Dispatchers.IO) { BankStore.importText(context, text) }
                    reloadBank()
                    message = if (added > 0) "收进题库 $added 道题" else "没有新题：可能都已经在题库里，或者格式不对"
                }
            },
        )
    }

    if (pickerOpen) {
        val list = apps
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("添加要限时的应用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (list == null) {
                        Hint("读取应用列表…")
                    } else {
                        val shown = list
                            .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                            .sortedBy { it.label }
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(shown, key = { it.pkg }) { a ->
                                val already = config.watch.rules.any { it.pkg == a.pkg }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !already) {
                                            saveWatch { w -> w.copy(rules = w.rules + WatchRule(pkg = a.pkg, label = a.label)) }
                                            pickerOpen = false
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(Modifier.size(30.dp)) {
                                        a.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(30.dp)) }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(a.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = sig.muted)
                                    }
                                    if (already) Text("已在规则里", style = MaterialTheme.typography.labelSmall, color = sig.muted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("完成") } },
        )
    }

    OutcomeDialog(message) { message = null }
}

/** 一条守门规则：应用名、包名、连续分钟数、冷却秒数。 */
@Composable
private fun RuleCard(
    rule: WatchRule,
    onMinutes: (Int) -> Unit,
    onCooldown: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val sig = LocalSignals.current
    val label = rule.label.ifBlank { AppNames.label(context, rule.pkg) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(rule.pkg, style = MaterialTheme.typography.bodySmall, color = sig.muted)
            }
            TextButton(onClick = onDelete) { Text("删除", color = sig.strict) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stepper(
                title = "连续用",
                value = "${rule.maxMinutes} 分钟",
                onMinus = { onMinutes((rule.maxMinutes - 1).coerceAtLeast(1)) },
                onPlus = { onMinutes((rule.maxMinutes + 1).coerceAtMost(60)) },
                modifier = Modifier.weight(1f),
            )
            Stepper(
                title = "冷却",
                value = "${rule.cooldownSeconds} 秒",
                onMinus = { onCooldown((rule.cooldownSeconds - 1).coerceAtLeast(0)) },
                onPlus = { onCooldown((rule.cooldownSeconds + 1).coerceAtMost(120)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 一个可加减的数字：− 值 +。 */
@Composable
private fun Stepper(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = LocalSignals.current.muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onMinus,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("−") }
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.merge(Mono),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onPlus,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("+") }
        }
    }
}

private data class AppChoice(val pkg: String, val label: String, val icon: ImageBitmap?)

/** 一键预置的包名：没装也先加规则，装上了就生效。 */
private const val IDLEFISH = "com.taobao.idlefish"
private const val TWITTER = "com.twitter.android"

/** 桌面上能打开的应用（排除总控自己）。 */
private fun launchableApps(pm: PackageManager, self: String): List<AppChoice> = try {
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != self }
        .map { ai ->
            val icon = try {
                pm.getApplicationIcon(ai).toBitmap(48, 48, Bitmap.Config.ARGB_8888).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            AppChoice(ai.packageName, pm.getApplicationLabel(ai).toString(), icon)
        }
} catch (e: Exception) {
    emptyList()
}
