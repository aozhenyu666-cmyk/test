package com.moling.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moling.app.BrainHost
import com.moling.app.HeartService
import com.moling.app.Usage
import com.moling.app.data.SettingsStore
import com.moling.app.ui.art.InkRing
import com.moling.core.Brain

/** 准备清单的一项。[essential] = 少了它她就不完整；[fix] 一键去改（null = 没法一键）。 */
data class ReadyItem(val title: String, val ok: Boolean, val detail: String, val essential: Boolean, val fixLabel: String = "", val fix: (() -> Unit)? = null)

/** 现在的准备情况（每次现算，不缓存）。[openSettings] 是“去设置页”。 */
fun readyItems(context: Context, openSettings: () -> Unit, openBridge: () -> Unit): List<ReadyItem> {
    val store = SettingsStore(context)
    val s = BrainHost.state.value
    val now = System.currentTimeMillis()
    fun go(intent: Intent) = { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }
    val pkg = context.packageName
    val zkFresh = s.lastStatus?.let { it.at > 0 && now - it.at < 10 * 60_000L } == true
    val notif = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val battery = runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(pkg) == true }.getOrDefault(false)
    val cfg = store.load()
    return listOf(
        ReadyItem("AI 接口", cfg.apiKey.isNotBlank(), "她想事情、说话都要它。DeepSeek 一天几毛钱。", true, "去填", openSettings),
        ReadyItem(
            "总控连上了", zkFresh,
            if (zkFresh) "授权书、严管、记录都看得到。" else "看不到总控：装了总控并打开过一次就会连上。",
            true, "打开总控",
            context.packageManager.getLaunchIntentForPackage("com.zongkong.app")?.let { go(it) },
        ),
        ReadyItem("看前台应用", Usage.allowed(context), "知道你在刷什么、刷了多久；她蹦出来、拦人都靠它。", true, "去打开", go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))),
        ReadyItem(
            "显示在其他应用上层", Settings.canDrawOverlays(context), "她要能从别的应用上面蹦出来、拦在你面前。", true, "去打开",
            go(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))),
        ),
        ReadyItem(
            "通知", notif, "她不在前台时靠通知找你。", true, "去打开",
            go(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)),
        ),
        ReadyItem(
            "后台陪着", HeartService.isRunning(), "离开墨灵也每分钟看一眼。", true, "现在开",
            { store.setBackgroundHeart(true); HeartService.start(context) },
        ),
        ReadyItem(
            "不被省电杀掉", battery,
            "vivo / 华为这类系统会把后台的她杀掉：在列表里找到墨灵，选“不优化”；vivo 还要在 i 管家 → 应用管理 → 权限 → 后台高耗电 里允许。",
            true, "去设置", go(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
        ),
        ReadyItem(
            "今天的水道", s.flow?.day == Brain.calendarDay(now), "排了，偏离时她才知道你本该在哪。", false,
        ),
        ReadyItem("和 GPT / Notion 贯通", store.notion().ready(), "她写黑板、GPT 回答、她来收。", false, "去设置", openBridge),
        ReadyItem("深想模型", cfg.hasDeep(), "夜里整理、收件箱用更强的模型（可以不开）。", false, "去设置", openBridge),
    )
}

/** 准备清单：一页看清她现在“活”到几成，每项一键去改。回到这页自动刷新。 */
@Composable
fun ReadyScreen(onDone: () -> Unit, openSettings: () -> Unit, openBridge: () -> Unit) {
    val context = LocalContext.current
    val state by BrainHost.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val items = remember(tick, state.lastStatus, state.flow) { readyItems(context, openSettings, openBridge) }
    val essential = items.filter { it.essential }
    val done = essential.count { it.ok }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("← 返回") }
            Text("她准备好了吗", style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkRing(done / essential.size.toFloat(), Modifier.size(72.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("$done / ${essential.size}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (done == essential.size) "都好了。她能看见、能出声、能拦你。" else "还差 ${essential.size - done} 步，她才能完整地活。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        InkTitleRow("必须的")
        essential.forEach { ReadyRow(it) }
        InkTitleRow("更好的")
        items.filterNot { it.essential }.forEach { ReadyRow(it) }
        TextButton(onClick = { tick++ }) { Text("刷新") }
    }
}

@Composable
private fun InkTitleRow(text: String) = com.moling.app.ui.art.InkTitle(text)

@Composable
private fun ReadyRow(item: ReadyItem) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (item.ok) Color.Transparent else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(0.7.dp, Ink.copy(alpha = if (item.ok) 0.12f else 0.25f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (item.ok) "●" else "○", color = if (item.ok) Seal else Ink.copy(alpha = 0.5f))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val fix = item.fix
            if (!item.ok && fix != null) OutlinedButton(onClick = fix) { Text(item.fixLabel) }
        }
    }
}
