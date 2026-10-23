package cn.companion.pet.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.companion.pet.MainActivity
import cn.companion.pet.PetApp
import cn.companion.pet.a11y.PerceptionHub
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.service.PetService
import cn.companion.pet.ui.components.CutAction
import cn.companion.pet.ui.components.EmptyNote
import cn.companion.pet.ui.components.GroupTitle
import cn.companion.pet.ui.components.PageHeader
import cn.companion.pet.ui.components.Panel
import cn.companion.pet.ui.components.SettingRow
import cn.companion.pet.ui.components.SourceTag
import cn.companion.pet.ui.theme.CompanionShapes
import cn.companion.pet.ui.theme.CompanionSpacing
import cn.companion.pet.ui.theme.LocalCompanionColors
import java.time.LocalDate

/** 首次引导 + 当前状态。每一行都能直接跳到系统里对应的设置页。 */
@Composable
fun StartTab(activity: MainActivity, resumeTick: Int) {
    val app = PetApp.instance
    val colors = LocalCompanionColors.current
    // resumeTick 变化时重新计算
    val overlay = remember(resumeTick) { Permissions.overlay(activity) }
    val a11y = remember(resumeTick) { Permissions.accessibility(activity) }
    val mic = remember(resumeTick) { Permissions.mic(activity) }
    val notif = remember(resumeTick) { Permissions.notifications(activity) }
    val battery = remember(resumeTick) { Permissions.batteryUnrestricted(activity) }
    val hasKey = remember(resumeTick) { app.apiKey() != null }
    var running by remember(resumeTick) { mutableStateOf(PetService.running) }
    var paused by remember(resumeTick) { mutableStateOf(app.settings.petPaused) }
    val a11yConnected by PerceptionHub.connected.collectAsState()
    val recent = remember(resumeTick) {
        app.events.forDay(LocalDate.now())
            .filter { it.kind == EventKind.EXCHANGE || it.kind == EventKind.LEGACY_LINE }
            .map { it.text }
            .takeLast(3)
            .reversed()
    }

    val status = when {
        paused -> "已暂停"
        running -> "正在陪伴"
        else -> "已停止"
    }
    val hint = when {
        !overlay -> "先开悬浮窗权限，它才能出现。"
        !hasKey -> "还没填模型的 API Key：它会出现，但不会开口。"
        !a11y -> "没开无障碍：它不知道你在用什么 App，只能按普通场景搭话。"
        else -> "一切就绪。"
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = CompanionSpacing.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PageHeader("屏幕边上的同伴", hint)
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("状态", style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
                SourceTag(status, highlighted = running && !paused)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CutAction(
                    text = if (running) "重新唤出" else "启动桌宠",
                    highlighted = true,
                    enabled = overlay,
                    onClick = {
                        val ok = PetService.startIfAllowed(activity, userAction = true)
                        running = ok
                        if (!ok) Toast.makeText(activity, "启动失败，检查悬浮窗权限", Toast.LENGTH_SHORT).show()
                    },
                )

                OutlinedButton(
                    shape = CompanionShapes.button,
                    onClick = {
                        paused = !paused
                        app.settings.petPaused = paused
                    },
                ) { Text(if (paused) "继续" else "暂停") }
                OutlinedButton(
                    shape = CompanionShapes.button,
                    onClick = {
                        app.settings.userStopped = true
                        activity.stopService(android.content.Intent(activity, PetService::class.java))
                        running = false
                    },
                ) { Text("停止") }
            }
        }

        GroupTitle("必需")
        Step("悬浮窗", "让角色显示在其他 App 上面。", overlay) { activity.startActivity(Permissions.overlayIntent(activity)) }
        Step(
            "无障碍服务",
            "只用来知道前台是哪个 App、有没有在滚动；学习场景开口前读一次屏幕文字。" +
                "如果开关是灰的：到“应用详情 → 右上角 ⋮ → 允许受限制的设置”，再回来开。" +
                if (a11y && !a11yConnected) "（已打开，但服务还没连上，可以关掉再打开一次）" else "",
            a11y,
        ) { activity.startActivity(Permissions.accessibilityIntent()) }

        GroupTitle("推荐")
        Step("麦克风和通知", "按住说话要用麦克风；通知用来常驻和快速暂停。", mic && notif) { activity.requestMic() }
        Step("不限制电池", "关掉电池优化，后台才不容易被杀。", battery) { activity.startActivity(Permissions.batteryIntent(activity)) }

        GroupTitle(if (Permissions.isVivo()) "vivo / iQOO 还需要" else "国产系统还需要")
        SettingRow(
            "允许自启动 / 后台高耗电",
            "i 管家 → 应用管理 → 权限管理 → 自启动，打开“桌宠”；电池 → 后台耗电管理，选“允许后台高耗电”。",
            value = "去设置",
            onClick = {
                if (!Permissions.tryStart(activity, Permissions.vivoAutoStartIntents())) {
                    activity.startActivity(Permissions.appDetails(activity))
                }
            },
        )
        SettingRow(
            "后台弹出界面",
            "应用详情 → 权限 → 后台弹出界面 → 允许。不开的话，点“去 Gemini”时可能拉不起来。",
            value = "应用详情",
            onClick = { activity.startActivity(Permissions.appDetails(activity)) },
        )
        SettingRow(
            "在最近任务里锁定",
            "打开最近任务，按住“桌宠”的卡片往下拉（或点锁形图标），锁住后一键清理不会杀掉它。",
        )

        GroupTitle("最近交流")
        if (recent.isEmpty()) {
            EmptyNote("还没有交流记录。")
        } else {
            Panel {
                recent.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = colors.ink2, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Step(title: String, desc: String, done: Boolean, onFix: () -> Unit) {
    SettingRow(
        title = title,
        description = desc,
        value = if (done) "已开启" else "未开启",
        onClick = if (done) null else onFix,
    )
}