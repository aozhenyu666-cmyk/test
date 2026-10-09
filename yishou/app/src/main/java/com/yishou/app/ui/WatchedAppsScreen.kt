package com.yishou.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.settings.AppGroup
import com.yishou.app.settings.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val pkg: String, val label: String, val icon: ImageBitmap?)

/** 列出桌面上能打开的应用（AndroidManifest 里用 <queries> 声明，不申请读取全部应用的权限）。 */
private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName to it }
        .distinctBy { it.first }
        .filter { it.first != context.packageName }
        .map { (pkg, info) ->
            val icon = try {
                info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            InstalledApp(pkg, info.loadLabel(pm).toString(), icon)
        }
        .sortedBy { it.label }
}

@Composable
fun WatchedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as YishouApp).settings
    val prefs by settings.app.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var newGroup by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Scaffold(topBar = { BackTopBar("关注的应用", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    Text("应用组", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("同一组的应用共用一次放行。放行时长 ${AppPrefs.MIN_PASS_MINUTES}–${AppPrefs.MAX_PASS_MINUTES} 分钟。", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(prefs.groups, key = { "g:" + it.name }) { g ->
                GroupRow(
                    group = g,
                    count = prefs.watched.count { it.value == g.name },
                    canDelete = prefs.groups.size > 1,
                    onMinutes = { m ->
                        settings.updateApp { p -> p.copy(groups = p.groups.map { if (it.name == g.name) it.copy(passMinutes = m) else it }) }
                    },
                    onDelete = {
                        settings.updateApp { p ->
                            val rest = p.groups.filter { it.name != g.name }
                            p.copy(groups = rest, watched = p.watched.mapValues { (_, v) -> if (v == g.name) rest.first().name else v })
                        }
                    },
                )
            }
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newGroup,
                            onValueChange = { newGroup = it },
                            label = { Text("新组名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val name = newGroup.trim()
                            if (name.isNotEmpty() && prefs.groups.none { it.name == name }) {
                                settings.updateApp { it.copy(groups = it.groups + AppGroup(name, AppPrefs.DEFAULT_PASS_MINUTES)) }
                                newGroup = ""
                            }
                        }) { Text("添加") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("已安装的应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            val list = apps
            if (list == null) {
                item { Text("正在读取应用列表……") }
            } else {
                val shown = list
                    .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) || it.pkg.contains(query.trim()) }
                    .sortedByDescending { it.pkg in prefs.watched }
                items(shown, key = { "a:" + it.pkg }) { a ->
                    AppRow(
                        app = a,
                        group = prefs.watched[a.pkg],
                        groups = prefs.groups,
                        onToggle = { on ->
                            settings.updateApp { p ->
                                p.copy(watched = if (on) p.watched + (a.pkg to p.groups.first().name) else p.watched - a.pkg)
                            }
                        },
                        onGroup = { g -> settings.updateApp { p -> p.copy(watched = p.watched + (a.pkg to g)) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: AppGroup, count: Int, canDelete: Boolean, onMinutes: (Int) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.name, fontWeight = FontWeight.Bold)
                Text("$count 个应用", style = MaterialTheme.typography.bodySmall)
            }
            Stepper(
                value = group.passMinutes,
                range = AppPrefs.MIN_PASS_MINUTES..AppPrefs.MAX_PASS_MINUTES,
                unit = "分钟",
                onChange = onMinutes,
            )
            if (canDelete) TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    group: String?,
    groups: List<AppGroup>,
    onToggle: (Boolean) -> Unit,
    onGroup: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(group == null) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = group != null, onCheckedChange = onToggle)
        if (app.icon != null) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(app.label)
            Text(app.pkg, style = MaterialTheme.typography.bodySmall)
        }
        if (group != null) {
            Box {
                TextButton(onClick = { menu = true }) { Text(group) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    groups.forEach { g ->
                        DropdownMenuItem(text = { Text(g.name) }, onClick = { menu = false; onGroup(g.name) })
                    }
                }
            }
        }
    }
}

/** “− 数值 +”的小控件。 */
@Composable
fun Stepper(value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
        Text("$value $unit")
        TextButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
    }
}
