package com.moling.app.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moling.app.system.AndroidCalendar
import com.moling.app.system.AndroidDnd
import com.moling.app.system.AndroidPlaylist
import com.moling.app.system.ToolsStore

/**
 * 系统工具（第十五批 B5）：日历 / 勿扰 / 歌单，各自一个开关，默认都关。
 * 打开日历才向他要“读日历”权限；勿扰的权限要他自己去系统里给；歌单只在主河道开始时发通知，点了才打开。
 */
@Composable
fun ToolsSettings() {
    val context = LocalContext.current
    val store = remember { ToolsStore(context) }
    val cal = remember { AndroidCalendar(context, store) }
    val dnd = remember { AndroidDnd(context, store) }
    val playlist = remember { AndroidPlaylist(context) }
    var rev by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rev++ }
        lifecycle.lifecycle.addObserver(o)
        onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) store.calendarOn = false
        rev++
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("每样一个开关，默认都关。没开的那样什么都不做，也不影响案板、聊天和陪练。", style = MaterialTheme.typography.bodySmall)

        // ---------- 日历 ----------
        var calOn by remember(rev) { mutableStateOf(store.calendarOn) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("排水道时看日历", style = MaterialTheme.typography.titleSmall)
                Text("读你选的日历里今天忙的时段，水道绕开它们。只读，不改你的日历。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(calOn, onCheckedChange = {
                calOn = it
                store.calendarOn = it
                if (it && !cal.permitted()) askCalendar.launch(Manifest.permission.READ_CALENDAR) else rev++
            })
        }
        if (calOn) {
            val c = remember(rev) { cal.capability() }
            Text("状态：${c.state.label}${if (c.detail.isNotBlank()) "（${c.detail}）" else ""}", style = MaterialTheme.typography.labelSmall)
            if (cal.permitted()) {
                var ids by remember(rev) { mutableStateOf(store.calendarIds) }
                remember(rev) { cal.calendars() }.forEach { (id, name) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        ids = if (id in ids) ids - id else ids + id
                        store.calendarIds = ids
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Checkbox(id in ids, onCheckedChange = {
                            ids = if (it) ids + id else ids - id
                            store.calendarIds = ids
                        })
                    }
                }
                if (store.lastCalendarSync > 0) {
                    Text("上次读日历：${java.time.Instant.ofEpochMilli(store.lastCalendarSync).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalTime().withNano(0)}", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                TextButton(onClick = { askCalendar.launch(Manifest.permission.READ_CALENDAR) }) { Text("给“读日历”权限") }
            }
        }

        // ---------- 勿扰 ----------
        var dndOn by remember(rev) { mutableStateOf(store.dndOn) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("主河道时开勿扰", style = MaterialTheme.typography.titleSmall)
                Text("进主河道时把勿扰调成“仅优先”，出来时恢复。只在原来是“关”时才调；你期间自己改过就不动。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(dndOn, onCheckedChange = { dndOn = it; store.dndOn = it; rev++ })
        }
        if (dndOn) {
            val d = remember(rev) { dnd.capability() }
            Text("状态：${d.state.label}${if (d.detail.isNotBlank()) "（${d.detail}）" else ""}", style = MaterialTheme.typography.labelSmall)
            if (!dnd.permitted()) {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("去系统里给“勿扰权限”") }
            }
        }

        // ---------- 歌单 ----------
        var plOn by remember(rev) { mutableStateOf(store.playlistOn) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("主河道开始时提醒放歌单", style = MaterialTheme.typography.titleSmall)
                Text("只发一条通知，你点了才打开音乐 App；墨灵不会自己在后台开别的应用。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(plOn, onCheckedChange = { plOn = it; store.playlistOn = it; rev++ })
        }
        if (plOn) {
            var uri by remember { mutableStateOf(store.playlistUri) }
            OutlinedTextField(
                uri, { uri = it.trim(); store.playlistUri = uri },
                label = { Text("歌单链接（从音乐 App 里“分享 → 复制链接”）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            val apps = remember(uri) { playlist.handlers(uri) }
            var pkg by remember(rev) { mutableStateOf(store.playlistPkg) }
            if (uri.isNotBlank() && apps.isEmpty()) Text("手机上没有能打开这个链接的 App。", style = MaterialTheme.typography.labelSmall)
            apps.forEach { (p, label) ->
                Row(Modifier.fillMaxWidth().clickable { pkg = p; store.playlistPkg = p }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Checkbox(pkg == p, onCheckedChange = { pkg = p; store.playlistPkg = p })
                }
            }
            val pc = remember(uri, pkg) { playlist.capability(pkg, uri) }
            Text("状态：${pc.state.label}${if (pc.detail.isNotBlank()) "（${pc.detail}）" else ""}", style = MaterialTheme.typography.labelSmall)
            if (pc.usable) TextButton(onClick = { playlist.open(pkg, uri, System.currentTimeMillis()) }) { Text("试着打开一次") }
        }
    }
}
