package com.yishou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.log.RunLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行日志：哪里出过错。只在本机；点“复制全部”可以贴给别人帮你看。 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val log = RunLog.current
    val versionFlow = remember { log?.version ?: kotlinx.coroutines.flow.MutableStateFlow(0) }
    val version by versionFlow.collectAsStateWithLifecycle()
    val entries = remember(version) { log?.entries().orEmpty() }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat("M月d日 HH:mm:ss", Locale.CHINA) }

    Scaffold(topBar = { BackTopBar("运行日志", onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "这里记下请求失败、朗读和听写出错、服务出错和崩溃，最近 300 条，只存在本机。" +
                        "遇到“怎么没反应”时先来这里看一眼。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(RunLog.format(entries)))
                            copied = true
                        },
                        enabled = entries.isNotEmpty(),
                    ) { Text(if (copied) "已复制" else "复制全部") }
                    OutlinedButton(onClick = { log?.clear() }, enabled = entries.isNotEmpty()) { Text("清空") }
                }
            }
            if (entries.isEmpty()) {
                item { Text("还没有出过错。", style = MaterialTheme.typography.bodyLarge) }
            }
            items(entries) { e ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(e.area, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            Text(time.format(Date(e.time)), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(e.message, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
}
