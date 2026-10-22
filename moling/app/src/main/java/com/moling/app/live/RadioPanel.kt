package com.moling.app.live

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.live.Radio
import com.moling.core.live.RadioState
import com.moling.core.live.SegmentKind
import com.moling.core.workspace.Focus
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.WorkbenchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 第十一批 R2：“陪我听一会”面板。
 * 只播案板里已有的材料 / 稿子正文；显示当前段、来源、进度和醒目的停止。他不回也不会被追问。
 * 状态全部来自 [BrainHost.radio]（StateFlow），不在 Compose 里重复 start。
 */
@Composable
fun RadioPanel(ws: WorkbenchState, focus: Focus? = ws.active()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by BrainHost.radio.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val s = session
    LaunchedEffect(s?.id, s?.state) {
        while (s?.active == true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    // 离开面板就停：不把电台留在后台一个人念（音频焦点 / 拔耳机 / 来电 / 熄屏在 RadioHost 里处理）。
    DisposableEffect(Unit) {
        onDispose { BrainHost.radioStopAll() }
    }

    fun toast(msg: String) = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()

    if (s == null || !s.active) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("陪我听一会", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            s?.state == RadioState.FINISHED -> "讲完了：" + s.stopReason.ifBlank { "这一段到这儿" }
                            s?.state == RadioState.STOPPED -> "已停止" + if (s.stopReason.isNotBlank()) "（" + s.stopReason + "）" else ""
                            focus == null -> "先开一件案板，或贴一段材料"
                            else -> "只念案板里已有的材料和稿子，不联网；你不回也不会被追问。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    enabled = focus != null,
                    onClick = {
                        val f = focus ?: return@TextButton
                        scope.launch {
                            when (val r = BrainHost.radioStart(f.id, f.materialIds)) {
                                is PortResult.Ok -> Unit
                                is PortResult.Failed -> toast(RadioHost.errorText(r.error))
                            }
                        }
                    },
                ) { Text("开始") }
            }
        }
        return
    }

    val seg = s.current
    val left = (Radio.remainingMs(s, now) / 1000).toInt()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("陪我听一会 · " + s.state.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "第 " + (s.index + 1) + "/" + s.segments.size + " 段" + when {
                            s.state == RadioState.PAUSED -> " · 已暂停（继续从这一段开头念）"
                            left > 0 -> " · 还剩 " + left + " 秒"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { scope.launch { runCatching { BrainHost.radioStop(s.id) } } },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("停止") }
            }
            Box(
                Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier.fillMaxWidth(Radio.progress(s, now).coerceIn(0f, 1f)).height(4.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            }
            if (seg != null) {
                Text(
                    when (seg.kind) {
                        SegmentKind.QUOTE -> "引用"
                        SegmentKind.IDEA -> "她的整理（可能不全对）"
                        SegmentKind.QUESTION -> "她留的话"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(seg.text, style = MaterialTheme.typography.bodyMedium)
                val src = seg.sourceIds.map { id -> RadioHost.sourceLabel(ws, id) }.filter { it.isNotBlank() }.distinct()
                if (src.isNotEmpty()) Text("来源：" + src.joinToString("、"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(s.stopReason.ifBlank { "这一段到这儿" }, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        if (s.state == RadioState.PAUSED) runCatching { BrainHost.radioResume(s.id) }
                        else runCatching { BrainHost.radioPause(s.id) }
                    }
                }) { Text(if (s.state == RadioState.PAUSED) "继续" else "暂停") }
                OutlinedButton(onClick = { scope.launch { runCatching { BrainHost.radioSkip(s.id) } } }) { Text("跳过") }
            }
            // 这是“让她安静两小时”，不是电台控件：挪出暂停/跳过/停止那一排，名字写明白。
            TextButton(onClick = { scope.launch { runCatching { BrainHost.stopLive() } } }) { Text("这两小时别主动找我") }
            Text(
                "只听不回：不扣分，也不会追着问。停止后这一段不会再自动恢复；暂停后继续从这一段开头重新念。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
