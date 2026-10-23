package com.yishou.app.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.companion.android.CompanionService
import com.yishou.app.companion.core.CompanionChecks

/**
 * 视频陪练：选目标、开一场、看胶囊、答题。
 *
 * 采集和会话在 [CompanionService] 里跑（前台服务），所以切到视频 App 也不会断；
 * 这一页只是看状态和发指令。
 */
@Composable
fun CompanionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view by CompanionService.view.collectAsStateWithLifecycle()
    var goal by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf(25) }
    var gear by rememberSaveable { mutableStateOf("ACCOMPANY") }
    var answer by rememberSaveable { mutableStateOf("") }

    val yishou = context.applicationContext as com.yishou.app.YishouApp
    val llmReady by yishou.settings.llm.collectAsStateWithLifecycle()
    val speech by yishou.settings.speech.collectAsStateWithLifecycle()
    val ready = CompanionChecks.startup(
        llmReady = llmReady.isComplete,
        sttReady = speech.sttReady,
        canRecord = yishou.voice.hasPermission(),
        notifyAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            // 目标应用的包名先留空：从无障碍那侧拿当前前台更准，接上了再填
            CompanionService.start(context, r.resultCode, data, goal, minutes, gear, pkg = "")
        }
    }

    Scaffold(topBar = { BackTopBar("视频陪练", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这一场要弄懂什么", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text("比如：抽样为什么能代表整体") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !view.running,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("时长", style = MaterialTheme.typography.bodySmall)
                        listOf(10, 25, 45).forEach { m ->
                            FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m 分") }, enabled = !view.running)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("强度", style = MaterialTheme.typography.bodySmall)
                        listOf("ACCOMPANY" to "陪", "LURE" to "诱", "STRICT" to "严").forEach { (k, label) ->
                            FilterChip(selected = gear == k, onClick = { gear = k }, label = { Text(label) }, enabled = !view.running)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                consent.launch(
                                    context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
                                )
                            },
                            enabled = !view.running && ready.canStart,
                        ) { Text("开始（要同意看屏和内录）") }
                        OutlinedButton(onClick = { CompanionService.stop(context) }, enabled = view.running) { Text("停") }
                    }
                }
            }

            // 缺什么先说在前面：不然点了开始也只会安静地听
            ready.blockers.forEach { b ->
                Card {
                    Text(b, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            ready.notes.forEach { n ->
                Card {
                    Text(n, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (view.capsule.isNotBlank()) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(view.capsule, style = MaterialTheme.typography.bodyMedium)
                        if (view.phase.isNotBlank()) {
                            Text("现在：${view.phase}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            view.hint?.let { h ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(h, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { CompanionService.ready(context) }) { Text("我按好了，念问题") }
                    }
                }
            }

            view.question?.let { q ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("它问：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(q, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            label = { Text("说你的想法（这一步先用打字）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                CompanionService.answer(context, answer)
                                answer = ""
                            }) { Text("答") }
                            TextButton(onClick = { CompanionService.skip(context) }) { Text("跳过（不算答错）") }
                        }
                    }
                }
            }

            if (view.note.isNotBlank()) {
                Card {
                    Text(view.note, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                "内录只能听别的 App 的声音，一手自己放的朗读不会被录进去；目标 App 若禁止被采集，胶囊会写「声音缺失」。\n" +
                    "这一版先打字作答，语音作答和浮窗还没接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
