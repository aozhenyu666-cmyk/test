package cn.companion.pet.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.companion.pet.MainActivity
import cn.companion.pet.PetApp
import cn.companion.pet.a11y.PerceptionHub
import cn.companion.pet.service.PetService

/** 首次引导 + 当前状态。每一项都能直接跳到系统里对应的设置页。 */
@Composable
fun StartTab(activity: MainActivity, resumeTick: Int) {
    val app = PetApp.instance
    // resumeTick 变化时重新计算
    val overlay = remember(resumeTick) { Permissions.overlay(activity) }
    val a11y = remember(resumeTick) { Permissions.accessibility(activity) }
    val mic = remember(resumeTick) { Permissions.mic(activity) }
    val notif = remember(resumeTick) { Permissions.notifications(activity) }
    val battery = remember(resumeTick) { Permissions.batteryUnrestricted(activity) }
    val hasKey = remember(resumeTick) { app.apiKey() != null }
    var running by remember(resumeTick) { mutableStateOf(PetService.running) }
    val a11yConnected by PerceptionHub.connected.collectAsState()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(if (running) "桌宠正在屏幕边上" else "桌宠没在运行", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !overlay -> "先开悬浮窗权限，它才能出现。"
                        !hasKey -> "还没填 DeepSeek 的 API Key：它会出现，但不会开口。到“设置”里填。"
                        !a11y -> "没开无障碍：它不知道你在用什么 App，只能按普通场景搭话。"
                        else -> "一切就绪。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = overlay, onClick = {
                        val ok = PetService.startIfAllowed(activity, userAction = true)
                        running = ok
                        if (!ok) Toast.makeText(activity, "启动失败，检查悬浮窗权限", Toast.LENGTH_SHORT).show()
                    }) { Text(if (running) "重新唤出" else "启动桌宠") }
                    OutlinedButton(onClick = {
                        app.settings.userStopped = true
                        activity.stopService(android.content.Intent(activity, PetService::class.java))
                        running = false
                    }) { Text("停止") }
                }
            }
        }

        Text("必需", style = MaterialTheme.typography.titleSmall)
        Step("悬浮窗", "让角色显示在其他 App 上面。", overlay) { activity.startActivity(Permissions.overlayIntent(activity)) }
        Step(
            "无障碍服务",
            "只用来知道前台是哪个 App、有没有在滚动；学习场景开口前读一次屏幕文字。" +
                "如果开关是灰的：到“应用详情 → 右上角 ⋮ → 允许受限制的设置”，再回来开。" +
                if (a11y && !a11yConnected) "（已打开，但服务还没连上，可以关掉再打开一次）" else "",
            a11y,
        ) { activity.startActivity(Permissions.accessibilityIntent()) }

        Text("推荐", style = MaterialTheme.typography.titleSmall)
        Step("麦克风和通知", "按住说话要用麦克风；通知用来常驻和快速暂停。", mic && notif) { activity.requestMic() }
        Step("不限制电池", "关掉电池优化，后台才不容易被杀。", battery) { activity.startActivity(Permissions.batteryIntent(activity)) }

        Text(if (Permissions.isVivo()) "vivo / iQOO 还需要" else "国产系统还需要", style = MaterialTheme.typography.titleSmall)
        ManualStep(
            "允许自启动 / 后台高耗电",
            "i 管家 → 应用管理 → 权限管理 → 自启动，打开“桌宠”；电池 → 后台耗电管理，选“允许后台高耗电”。",
            "去设置",
        ) {
            if (!Permissions.tryStart(activity, Permissions.vivoAutoStartIntents())) activity.startActivity(Permissions.appDetails(activity))
        }
        ManualStep(
            "后台弹出界面",
            "应用详情 → 权限 → 后台弹出界面 → 允许。不开的话，点“去 Gemini”时可能拉不起来。",
            "应用详情",
        ) { activity.startActivity(Permissions.appDetails(activity)) }
        ManualStep(
            "在最近任务里锁定",
            "打开最近任务，按住“桌宠”的卡片往下拉（或点锁形图标），锁住后一键清理不会杀掉它。",
            null,
        ) {}
    }
}

@Composable
private fun Step(title: String, desc: String, done: Boolean, onFix: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (done) "✓ " else "○ ") + title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            if (!done) TextButton(onClick = onFix) { Text("去开启") }
        }
    }
}

@Composable
private fun ManualStep(title: String, desc: String, action: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            if (action != null) TextButton(onClick = onClick) { Text(action) }
        }
    }
}
