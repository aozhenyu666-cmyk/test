package cn.companion.pet.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventVisibility
import cn.companion.pet.service.PetService
import cn.companion.pet.ui.components.CutAction
import cn.companion.pet.ui.components.HeroPanel
import cn.companion.pet.ui.components.OrbitArt
import cn.companion.pet.ui.components.WaveArt
import cn.companion.pet.ui.components.QuietAction
import cn.companion.pet.ui.components.EmptyNote
import cn.companion.pet.ui.components.GroupTitle
import cn.companion.pet.ui.components.PageHeader
import cn.companion.pet.ui.components.Panel
import cn.companion.pet.ui.components.SettingRow
import cn.companion.pet.ui.components.SourceTag
import cn.companion.pet.ui.theme.CompanionShapes
import cn.companion.pet.ui.theme.CompanionSpacing
import cn.companion.pet.ui.theme.LocalCompanionColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cn.companion.pet.net.CallStatus
import java.time.LocalDate

private data class StartSnapshot(
    val hasKey: Boolean = false,
    val used: Int = 0,
    val limit: Int = 0,
    val recent: List<String> = emptyList(),
    val lastCall: CallStatus? = null,
    /** 最近一次异常退出（崩溃/卡死/被系统杀），只显示 24 小时内的。 */
    val lastExit: cn.companion.pet.data.Diagnostics.Exit? = null,
)

/**
 * 今天：状态、启动/暂停、快捷入口、最近交流。权限和设置不在这里铺开：
 * 还差什么只显示一张小卡，点一下打开设置里对应的分区。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartTab(activity: MainActivity, resumeTick: Int, openSettings: (Int) -> Unit, openMine: (Int) -> Unit) {
    val app = PetApp.instance
    val colors = LocalCompanionColors.current
    // resumeTick 变化时重新计算
    val overlay = remember(resumeTick) { Permissions.overlay(activity) }
    val a11y = remember(resumeTick) { Permissions.accessibility(activity) }
    var snapshot by remember { mutableStateOf(StartSnapshot()) }
    val hasKey = snapshot.hasKey
    var running by remember(resumeTick) { mutableStateOf(PetService.running) }
    var paused by remember(resumeTick) { mutableStateOf(app.settings.petPaused) }
    // 服务可能由看门狗/开机/升级在页面停留期间拉起，静态 running 不会触发重组。
    // 每秒对齐一次，避免出现“角色已在屏幕上，状态却写着已停止”。
    LaunchedEffect(resumeTick) {
        while (true) {
            if (running != PetService.running) running = PetService.running
            if (paused != app.settings.petPaused) paused = app.settings.petPaused
            snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    val eventSnapshot = app.events.all()
                    val visibility = EventVisibility(eventSnapshot, app.memory.tombstones())
                    StartSnapshot(
                        hasKey = app.apiKey() != null,
                        used = app.budget.used(LocalDate.now().toString()),
                        limit = app.settings.dailyLimit,
                        recent = eventSnapshot.filter { it.day == LocalDate.now().toString() }
                            .filter { it.kind == EventKind.EXCHANGE || it.kind == EventKind.LEGACY_LINE }
                            .filter { visibility.allows(it) }
                            .takeLast(3).reversed().map { it.text },
                        lastCall = app.callStatus.last(),
                        lastExit = cn.companion.pet.data.Diagnostics.lastAbnormal(app)
                            ?.takeIf { System.currentTimeMillis() - it.at < 24 * 60 * 60 * 1000L },
                    )
                }.getOrDefault(snapshot)
            }
            delay(1500)
        }
    }
    val recent = snapshot.recent
    val lastCall = snapshot.lastCall

    val status = when {
        paused -> "已暂停"
        running -> "正在陪伴"
        else -> "已停止"
    }
    val hint = when {
        !overlay -> "先开悬浮窗权限，它才能出现。"
        !hasKey -> "还没填模型的 API Key：它会出现，但不会开口。"
        snapshot.limit > 0 && snapshot.used >= snapshot.limit -> "今天的调用额度用完了。它会继续陪着你，明天恢复开口。"
        lastCall != null && !lastCall.ok -> "最近一次调用模型没成功：${lastCall.message}。修好之前它不会开口，可以到设置里点“测试连接”。"
        !a11y -> "没开无障碍：它不知道你在用什么 App，只能按普通场景搭话。"
        else -> "一切就绪。"
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = CompanionSpacing.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = { ChatActivity.open(activity) }) { Text("和同桌聊") }
        PageHeader("今天", hint.takeIf { it != "一切就绪。" })
        HeroPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("它现在", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                    Text("一个会观察、会等待的同伴", style = MaterialTheme.typography.bodySmall,
                        color = colors.paper.copy(alpha = 0.72f), modifier = Modifier.padding(top = 4.dp))
                }
                OrbitArt(size = 104.dp, accent = colors.accent, core = colors.warning)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.titleLarge, color = colors.paper, modifier = Modifier.weight(1f))
                SourceTag(if (running && !paused) "在线" else "离线", highlighted = running && !paused)
            }
            if (snapshot.limit > 0) {
                Text("今天已调用模型 " + snapshot.used + " / " + snapshot.limit + " 次", style = MaterialTheme.typography.bodySmall,
                    color = colors.paper.copy(alpha = 0.72f), modifier = Modifier.padding(top = 10.dp))
            }
            lastCall?.let { call ->
                val time = java.time.Instant.ofEpochMilli(call.at).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                Text(
                    if (call.ok) "上次调用模型：$time 成功" else "上次调用模型：$time 失败 · ${call.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (call.ok) colors.paper.copy(alpha = 0.72f) else colors.danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            snapshot.lastExit?.let { exit ->
                val time = java.time.Instant.ofEpochMilli(exit.at).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                Text("上次异常退出：$time · ${exit.reason}（设置 → 诊断 可复制报告）", style = MaterialTheme.typography.bodySmall,
                    color = colors.danger, modifier = Modifier.padding(top = 4.dp).clickable { openSettings(SETTINGS_DIAGNOSTICS) })
            }
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

        PartnerStatus(activity)

        // 还差的设置：一张小卡，点哪项就去设置里对应的分区。
        val gaps = buildList {
            if (!overlay) add(SetupGap("悬浮窗权限", SETTINGS_PERMISSIONS))
            if (!a11y) add(SetupGap("无障碍服务", SETTINGS_PERMISSIONS))
            if (!hasKey) add(SetupGap("模型 Key", SETTINGS_MODELS))
        }
        if (gaps.isNotEmpty()) {
            Panel {
                Text("还差 ${gaps.size} 项设置", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    gaps.forEach { gap ->
                        OutlinedButton(shape = CompanionShapes.button, onClick = { openSettings(gap.settingsGroup) }) { Text(gap.label + " →") }
                    }
                }
            }
        }

        WaveArt(Modifier.padding(top = 6.dp), height = 48.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickCard("看一眼", "截图问它", Modifier.weight(1f)) {
                Toast.makeText(activity, "到要看的 App 里点桌宠 → 看一眼", Toast.LENGTH_SHORT).show()
            }
            QuickCard("记念头", "一句话留下", Modifier.weight(1f)) {
                Toast.makeText(activity, "点桌宠 → 记个念头", Toast.LENGTH_SHORT).show()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickCard("技能", "一键重放操作", Modifier.weight(1f)) { openMine(MINE_SKILLS) }
            QuickCard("去 AI", "带上下文深聊", Modifier.weight(1f)) {
                Toast.makeText(activity, "点桌宠 → 去 " + (cn.companion.pet.core.session.AssistantApp.entries.firstOrNull { it.name == app.settings.assistant }?.label ?: "AI"), Toast.LENGTH_SHORT).show()
            }
        }

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
private fun QuickCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalCompanionColors.current
    androidx.compose.material3.Surface(
        modifier.clickable(onClick = onClick),
        color = colors.panel,
        shape = CompanionShapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.rule),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.ink)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
