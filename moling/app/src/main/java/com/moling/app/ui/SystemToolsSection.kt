package com.moling.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moling.app.BrainHost
import com.moling.app.system.SystemPermissions
import com.moling.app.system.SystemToolKind
import com.moling.core.mind.BEIJING
import com.moling.core.system.DndMode
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * B5 设置页·系统工具：日历 / 勿扰 / 歌单，三项各自独立开关。
 *
 * 每个动作都把结果和错误原样显示；不把“打开了系统页面 / 发出了 Intent”写成已完成。
 * 新权限只在用户点开对应开关时申请（日历是运行时权限；勿扰是系统特殊访问）。
 */
@Composable
fun SystemToolsSection() {
    val context = LocalContext.current
    val host = BrainHost.systemTools
    val state by host.state.collectAsState()
    val scope = rememberCoroutineScope()
    val sys = state.config

    var msg by remember { mutableStateOf<String?>(null) }
    var msgOk by remember { mutableStateOf(false) }
    var calendarOk by remember { mutableStateOf(SystemPermissions.calendarGranted(context)) }
    var dndAccess by remember { mutableStateOf(notificationPolicyAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                calendarOk = SystemPermissions.calendarGranted(context)
                dndAccess = notificationPolicyAccess(context)
                host.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun show(ok: Boolean, text: String) {
        msgOk = ok
        msg = text
    }

    // 日历权限：只有他点开日历时才申请。拒绝就这一项降级，别的照常。
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarOk = granted
        host.refresh()
        if (granted) {
            scope.launch {
                val r = host.calendars()
                show(r.ok, if (r.ok) "给了日历权限：读到 ${r.value?.size ?: 0} 个日历，选一个" else r.message)
            }
        } else {
            show(false, "没给日历权限：只降级日历这一项，聊天、案板、陪练照常")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "三项互相独立：关掉某一项不影响另外两项，也不影响案板/聊天/陪练。没给权限时只降级这一项，原因写在这里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------------------------------------------------------- 日历
        Text("日历", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("读忙闲 / 打开系统日程编辑页")
                Text(
                    "只读你选中的那个日历；写日程走系统编辑页，由你在那里保存。墨灵不知道你保存没有。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = sys.calendarOn,
                onCheckedChange = { on ->
                    host.setCalendarOn(on)
                    if (on) {
                        if (!calendarOk) {
                            askCalendar.launch(Manifest.permission.READ_CALENDAR)
                        } else {
                            scope.launch {
                                val r = host.calendars()
                                show(r.ok, if (r.ok) "读到 ${r.value?.size ?: 0} 个日历" else r.message)
                            }
                        }
                    } else {
                        show(true, "日历这一项关掉了；不读也不写")
                    }
                },
            )
        }
        state.capability(SystemToolKind.CALENDAR)?.let { cap ->
            Text(
                "状态：${cap.state.label}${if (cap.detail.isNotBlank()) " · ${cap.detail}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sys.calendarOn && !calendarOk) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }.onFailure { show(false, "打不开系统设置：请手动在系统设置里给日历权限") }
            }) { Text("去系统设置里给日历权限") }
        }
        if (sys.calendarOn && calendarOk) {
            if (state.calendars.isEmpty()) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = host.calendars()
                        show(r.ok, if (r.ok) "读到 ${r.value?.size ?: 0} 个日历" else r.message)
                    }
                }) { Text("读日历列表") }
            } else {
                Text("选一个日历", style = MaterialTheme.typography.bodySmall)
                state.calendars.take(8).forEach { choice ->
                    FilterChip(
                        selected = sys.calendarId == choice.id,
                        onClick = { host.setCalendar(choice) },
                        label = { Text(if (choice.account.isBlank()) choice.name else "${choice.name}（${choice.account}）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = sys.calendarId.isNotBlank(),
                        onClick = {
                            scope.launch {
                                val r = host.readBusyToday(System.currentTimeMillis())
                                show(r.ok, if (r.ok) "读到 ${r.value?.size ?: 0} 段忙闲，排计划会绕开" else r.message)
                            }
                        },
                    ) { Text("读今天忙闲") }
                    OutlinedButton(onClick = { scope.launch { host.calendars() } }) { Text("刷新列表") }
                }
                if (state.syncedAt > 0) {
                    Text(
                        "最近同步：${hm(state.syncedAt)} · ${state.busy.size} 段忙闲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.busy.take(3).forEach { b ->
                    Text("· ${b.title}  ${hm(b.start)}–${hm(b.end)}", style = MaterialTheme.typography.bodySmall)
                }
                var title by remember { mutableStateOf("墨灵记下的事") }
                var startText by remember { mutableStateOf("20:00") }
                var endText by remember { mutableStateOf("21:00") }
                Text("写一条日程（打开系统编辑页，不直接写）", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(60) },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it.take(5) },
                        label = { Text("开始") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it.take(5) },
                        label = { Text("结束") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = host.insertEvent(title, startText, endText, System.currentTimeMillis())
                        show(r.ok, r.message)
                    }
                }) { Text("打开系统日程编辑页") }
                Text(
                    "只算“已打开编辑页”；写没写进去以你在系统日历里点保存为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------------------------------------------------------- 勿扰
        Text("勿扰", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("改系统勿扰（只恢复自己改过的）")
                Text(
                    "勿扰是系统的“通知策略访问”，不是普通权限。墨灵只恢复自己这一次确实改过、且你期间没手动改过的档位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = sys.dndOn,
                onCheckedChange = { on ->
                    host.setDndOn(on)
                    dndAccess = notificationPolicyAccess(context)
                    if (on && !dndAccess) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        }.onFailure { show(false, "打不开系统勿扰权限页：请手动在系统设置里给") }
                    }
                },
            )
        }
        state.capability(SystemToolKind.DND)?.let { cap ->
            Text(
                "状态：${cap.state.label}${if (cap.detail.isNotBlank()) " · ${cap.detail}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sys.dndOn) {
            if (!dndAccess) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }.onFailure { show(false, "打不开系统勿扰权限页：请手动在系统设置里给") }
                }) { Text("去给通知策略访问") }
            } else {
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = host.dndApply(DndMode.PRIORITY, System.currentTimeMillis())
                        show(r.ok, r.message)
                        dndAccess = notificationPolicyAccess(context)
                    }
                }) { Text("切到仅优先（压住消息）") }
                val change = state.dndChange
                if (change != null) {
                    Text(
                        "墨灵上次改过：${change.original.label} → ${change.applied.label}（${hm(change.at)}）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            val r = host.dndRestore(System.currentTimeMillis())
                            show(r.ok, r.message)
                            dndAccess = notificationPolicyAccess(context)
                        }
                    }) { Text("恢复成墨灵改之前的档位") }
                } else {
                    Text(
                        "没有墨灵留下的勿扰记录：不会去动你现在的设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- 歌单
        Text("歌单", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显式 Intent 打开你选的歌单")
                Text(
                    "选应用包名 + 可验证 URI；墨灵只把打开交给系统，不确认是否在播、也不能替你暂停。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = sys.playlistOn,
                onCheckedChange = { on ->
                    host.setPlaylistOn(on)
                    if (on) show(true, "歌单这一项开了：填好应用和 URI 再点打开")
                },
            )
        }
        state.capability(SystemToolKind.PLAYLIST)?.let { cap ->
            Text(
                "状态：${cap.state.label}${if (cap.detail.isNotBlank()) " · ${cap.detail}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sys.playlistOn) {
            var pkg by remember(sys.playlistPackage) { mutableStateOf(sys.playlistPackage) }
            var uri by remember(sys.playlistUri) { mutableStateOf(sys.playlistUri) }
            Text("音乐应用（包名）", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = pkg,
                onValueChange = { pkg = it.take(120) },
                label = { Text("例如 com.netease.cloudmusic") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "com.netease.cloudmusic" to "网易云",
                    "com.tencent.qqmusic" to "QQ音乐",
                    "com.kugou.android" to "酷狗",
                    "com.spotify.music" to "Spotify",
                ).forEach { (p, name) ->
                    FilterChip(
                        selected = pkg == p,
                        onClick = { pkg = p; uri = "" },
                        label = { Text(name) },
                    )
                }
            }
            OutlinedTextField(
                value = uri,
                onValueChange = { uri = it.take(500) },
                label = { Text("可验证的 URI（例如 spotify:playlist:… 或 https://…）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    host.setPlaylist(pkg, uri)
                    val cap = host.state.value.capability(SystemToolKind.PLAYLIST)
                    show(cap?.usable == true, "配置已保存：${cap?.state?.label ?: ""} ${cap?.detail.orEmpty()}")
                }) { Text("保存配置") }
                OutlinedButton(onClick = {
                    host.setPlaylist(pkg, uri)
                    scope.launch {
                        val r = host.openPlaylist(System.currentTimeMillis())
                        show(r.ok, r.message)
                    }
                }) { Text("打开歌单") }
            }
        }

        // ---------------------------------------------------------------- 结果与最近动作
        msg?.let { text ->
            Surface(
                color = if (msgOk) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.actions.isNotEmpty()) {
            Text("最近动作（新的在上）", style = MaterialTheme.typography.bodySmall)
            state.actions.take(5).forEach { a ->
                Text(
                    "${hm(a.at)} · ${a.kind.label} · ${if (a.ok) "成功" else "没成"} · ${a.message}" +
                        if (a.undo.isNotBlank()) "（${a.undo}）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun notificationPolicyAccess(context: android.content.Context): Boolean = runCatching {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm?.isNotificationPolicyAccessGranted == true
}.getOrDefault(false)

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

private fun hm(at: Long): String = Instant.ofEpochMilli(at).atZone(BEIJING).format(HHMM)
