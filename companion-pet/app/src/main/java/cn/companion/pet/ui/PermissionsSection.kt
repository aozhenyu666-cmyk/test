package cn.companion.pet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cn.companion.pet.MainActivity
import cn.companion.pet.a11y.PerceptionHub
import cn.companion.pet.ui.components.GroupTitle
import cn.companion.pet.ui.components.SettingRow

/** 还没弄好的设置，今天页只显示一张小卡；点哪一项就打开设置里对应的分区。 */
data class SetupGap(val label: String, val settingsGroup: Int)

/** 权限/系统设置：只在“设置 → 权限”里出现，今天页不再铺开。每一行都能直接跳到系统对应页面。 */
@Composable
fun PermissionsSection(activity: MainActivity, resumeTick: Int) {
    val overlay = remember(resumeTick) { Permissions.overlay(activity) }
    val a11y = remember(resumeTick) { Permissions.accessibility(activity) }
    val mic = remember(resumeTick) { Permissions.mic(activity) }
    val notif = remember(resumeTick) { Permissions.notifications(activity) }
    val battery = remember(resumeTick) { runCatching { Permissions.batteryUnrestricted(activity) }.getOrDefault(false) }
    val a11yConnected by PerceptionHub.connected.collectAsState()

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
    Step("不限制电池", "关掉电池优化，后台才不容易被杀。无障碍显示“此服务出现故障”、桌宠自己消失，多半是被系统清理了，先开这一项。", battery) {
        activity.startActivity(Permissions.batteryIntent(activity))
    }

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
