package com.yishou.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从已安装应用里多选。选中的排在前面。 */
@Composable
fun AppPickerDialog(
    title: String,
    selected: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) {
                    Text("正在读取应用列表……", modifier = Modifier.padding(top = 12.dp))
                } else {
                    val shown = list
                        .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                        .sortedByDescending { it.pkg in selected }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(shown, key = { it.pkg }) { a ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { picked = if (a.pkg in picked) picked - a.pkg else picked + a.pkg },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = a.pkg in picked,
                                    onCheckedChange = { on -> picked = if (on) picked + a.pkg else picked - a.pkg },
                                )
                                a.icon?.let {
                                    Image(it, contentDescription = null, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(a.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 包名列表显示成应用名（读不到名字就显示包名）。 */
@Composable
fun rememberAppLabels(pkgs: Set<String>): String {
    val context = LocalContext.current
    return remember(pkgs) {
        if (pkgs.isEmpty()) "（未选）"
        else pkgs.joinToString("、") { pkg ->
            try {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
        }
    }
}
