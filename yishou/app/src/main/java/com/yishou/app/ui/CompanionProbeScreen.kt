package com.yishou.app.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.companion.android.CompanionProbe
import com.yishou.app.companion.android.PlaybackCaptureProbe
import com.yishou.app.companion.core.Capability
import com.yishou.app.companion.core.ProbeVerdict

/**
 * 陪练探针（调试页）：把 T1 要验的三件事摆在一页上——
 * 内录有没有真声音、画面有没有真变化、暂停是不是真的停住了；另外加一栏资源互斥。
 *
 * 这一页只观察、不提问，也不发模型请求，所以不花模型钱。
 */
@Composable
fun CompanionProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val probe = remember { CompanionProbe(context) }
    val report by probe.report.collectAsStateWithLifecycle()
    val status by probe.status.collectAsStateWithLifecycle()
    val note by probe.note.collectAsStateWithLifecycle()
    val level by probe.audioLevel.collectAsStateWithLifecycle()
    val audioSummary by probe.audioSummary.collectAsStateWithLifecycle()
    val frameSummary by probe.frameSummary.collectAsStateWithLifecycle()
    val playerNote by probe.playerNote.collectAsStateWithLifecycle()
    var hasNotificationAccess by remember { mutableStateOf(probe.notificationAccess()) }
    var sessionText by remember { mutableStateOf(probe.sessionDescription()) }

    DisposableEffect(Unit) {
        onDispose { probe.shutdown() }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            probe.startCapture(r.resultCode, data)?.let { probe.say(it) }
        } else {
            probe.say("没有同意投屏，这次没开始观察。")
        }
    }

    Scaffold(topBar = { BackTopBar("陪练探针", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "这一页只验证能不能拿到东西，不提问、不调模型。结论按“有证据才算可用”记。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("一、音画观察（20 秒）", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                consent.launch(
                                    (context.getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent(),
                                )
                            },
                            enabled = !status.running,
                        ) { Text("开始观察") }
                        OutlinedButton(onClick = { probe.finishCapture() }, enabled = status.running) { Text("现在就出结论") }
                        TextButton(onClick = { probe.stopAll() }) { Text("全部停掉") }
                    }
                    Text(status.sound, style = MaterialTheme.typography.bodySmall)
                    Text("音量条：" + "▮".repeat((level * 12).toInt().coerceIn(0, 12)).padEnd(12, '·'), style = MaterialTheme.typography.bodySmall)
                    Text(status.frames, style = MaterialTheme.typography.bodySmall)
                    if (status.running) Text("已观察 ${status.elapsedSec} 秒", style = MaterialTheme.typography.bodySmall)
                    Text(audioSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(frameSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "这一页只数帧：不录屏、不存图、也不会给你看画面——它只回答「能不能拿到」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        PlaybackCaptureProbe.limitationNote() + "要用你真在用的视频 App 来验这一项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 开了却什么都没读到：把最可能的三个原因直接摆出来，别让人对着空白猜
                    if (status.running && status.elapsedSec >= 5 && level <= 0.001f && !status.frames.contains("帧 /")) {
                        Text(
                            "还没读到声音，也没收到画面。按顺序确认：① 目标 App 真的在出声（不是静音、不是耳机）；" +
                                "② 系统弹窗里点的是「立即开始」而不是返回；③ 下拉通知栏里系统投屏还在跑。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("二、暂停 / 恢复", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hasNotificationAccess) "通知使用权已开：可以读别的 App 的播放会话" else "通知使用权没开：读不到别的 App 的播放会话",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("去开通知使用权") }
                        OutlinedButton(onClick = {
                            hasNotificationAccess = probe.notificationAccess()
                            sessionText = probe.sessionDescription()
                        }) { Text("刷新会话") }
                    }
                    Text(sessionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("自控播放器（用一手自己的声音验这条路径）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { probe.startSelfPlayer() }) { Text("开") }
                        OutlinedButton(onClick = { probe.pauseSelfPlayer() }) { Text("测暂停") }
                        TextButton(onClick = { probe.stopSelfPlayer() }) { Text("关") }
                    }
                    Text(playerNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        hasNotificationAccess = probe.notificationAccess()
                        probe.pauseTargetApp()
                    }) { Text("直接测你正在放的那个 App") }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("三、结论", fontWeight = FontWeight.SemiBold)
                    Capability.entries.forEach { cap ->
                        val r = report.of(cap)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(cap.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.28f))
                            Text(
                                r?.verdict?.label ?: "还没验",
                                style = MaterialTheme.typography.bodyMedium,
                                color = verdictColor(r?.verdict),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(0.22f),
                            )
                            Text(
                                r?.evidence ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.5f),
                            )
                        }
                    }
                    if (report.results.isEmpty()) {
                        Text(
                            "还没有结论。先点上面的“开始观察”，并让目标 App 真的放出声音。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            note?.let {
                Card(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                "真机没测的部分：目标 App 允许/禁止被采集、投屏旋转、来电与耳机切换。这些要在你手机上逐项看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun verdictColor(v: ProbeVerdict?): Color = when (v) {
    ProbeVerdict.OK -> Color(0xFF2E7D32)
    ProbeVerdict.SILENT -> Color(0xFFB26A00)
    ProbeVerdict.DENIED, ProbeVerdict.FAILED -> MaterialTheme.colorScheme.error
    ProbeVerdict.UNSUPPORTED -> MaterialTheme.colorScheme.error
    ProbeVerdict.UNKNOWN, null -> MaterialTheme.colorScheme.onSurfaceVariant
}
