package cn.companion.pet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.companion.pet.PetApp
import cn.companion.pet.health.AndroidLockGateway
import cn.companion.pet.health.HealthStatus
import cn.companion.pet.ui.components.GroupTitle
import cn.companion.pet.ui.components.SettingRow
import cn.companion.pet.ui.theme.LocalCompanionColors

/**
 * 监管与锁屏（v2）。
 *
 * 界面纪律（规范 §7、§10）：宁可显示「未知」，也不涂绿。
 *  - 未接入 ≠ 健康；证据不足 = 未知。
 *  - 权限状态逐项分开写（设备管理员 / 无障碍 / 是否设置安全锁屏）。
 *  - 紧急暂停必须在这里一眼能找到，并且本地、立即生效，不问理由、不等冷静期。
 *  - 明确写清边界：普通 APK 做不到的事不宣称（强制停止后无法自我恢复等）。
 */
@Composable
fun GuardSection() {
    val context = LocalContext.current
    val app = PetApp.instance
    var tick by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    var lastStopNote by remember { mutableStateOf("") }

    val rt = remember(tick) { app.guardRuntime }
    val st = remember(tick) { runCatching { rt.status() }.getOrNull() }

    GroupTitle("监管与锁屏（v2）")

    // 权限状态：一项一行，不合并。
    val cap = st?.capability
    SettingRow(
        "设备管理员（锁屏）",
        "本轮只用 force-lock：到点锁屏一次。不读、不改你的锁屏密码，也不防卸载，" +
            "不要求设备所有者。没有它时只能走无障碍锁屏通道。",
        value = if (cap?.adminActive == true) "已激活" else "未激活",
        onClick = if (cap?.adminActive == true) null else {
            { runCatching { context.startActivity(AndroidLockGateway(context).requestAdminIntent()) } }
        },
    )
    SettingRow(
        "系统安全锁屏",
        "没设置 PIN／图案／密码时，锁屏调用可能只是让屏幕休眠，不会报成安全锁定。",
        value = when (cap?.secureLockSet) {
            true -> "已设置"
            false -> "未设置"
            else -> "未知"
        },
    )

    // 紧急暂停：与「正常提前结束监管」分开，这里不问理由、不联网、立刻生效。
    val emergency = st?.emergency
    if (emergency?.stopped == true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "已紧急暂停：本轮自动锁屏停止了" +
                    "（" + (emergency.reason.ifBlank { "用户手动" }) + "）。" +
                    if (emergency.sentinelAckAtWall > 0) "哨兵已确认。" else "哨兵尚未确认：暂停未确认。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { rt.emergencyResume(); tick++ }) { Text("重新开启监管") }
        }
    } else {
        Button(
            onClick = { confirmStop = true },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text("紧急暂停（立刻停止本轮自动锁屏）") }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("现在停止自动锁屏？") },
            text = {
                Text("只停本轮的自动锁屏，不改你的数据、不删规则。之后可以在这一页重新开启。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val outcome = rt.emergencyStop("用户在设置页点了紧急暂停")
                    // 写盘失败必须当面说清，不能显示成「已经停了」。
                    lastStopNote = if (outcome.ok) "" else "没能确认停止：" + outcome.note
                    confirmStop = false
                    tick++
                }) { Text("立刻停止") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } },
        )
    }

    if (lastStopNote.isNotBlank()) {
        Text(lastStopNote, style = MaterialTheme.typography.bodySmall, color = LocalCompanionColors.current.danger)
    }

    // 本地规则与哨兵连接：说真话，不渲染成「三体系已就绪」。
    GroupTitle("本地规则与哨兵")
    val lines = buildList {
        if (st == null) {
            add("读不到运行器状态。")
        } else {
            add("本地规则 ${st.policies} 条，生效中 ${st.effective} 条，版本 ${st.revision}")
            val link = st.sentinel
            add(
                when {
                    link.lastAtMono <= 0L -> "哨兵：还没有回应（未接入或未在岗）"
                    link.registered -> "哨兵：已注册；最近一次调用" + (if (link.lastOk) "成功" else "失败（" + link.lastError + "）")
                    else -> "哨兵：未接入（" + (link.lastError.ifBlank { "没有注册过" }) + "）"
                },
            )
            if (link.acceptedRevision >= 0 && link.lastAtMono > 0L) {
                add("哨兵已接受的规则版本：${link.acceptedRevision}")
            }
            add("自己（真实执行线程）：" + rt.lastSelfProbe)
            if (st.lastPlanReason.isNotBlank()) add("本地兜底：" + st.lastPlanReason)
            st.lastAttempt?.let { a ->
                add("最近一次锁屏：${a.state.name}（${a.note}）")
            }
            if (st.storeError.isNotBlank()) add("规则存储：" + st.storeError)
        }
        add("边界：本应用被系统强制停止后无法自我恢复；消息里也不宣称普通 APK 无法绕过。")
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { tick++ }) { Text("刷新") }
}

/** 健康协议状态在界面上的四句话（规范 §7，禁止涂绿）。 */
fun healthStatusLabel(status: HealthStatus): String = when (status) {
    HealthStatus.UNREGISTERED -> "未接入"
    HealthStatus.HEALTHY -> "在响应"
    HealthStatus.SUSPECT -> "可疑：连续失败"
    HealthStatus.DISABLED -> "无障碍未开启"
    HealthStatus.UNRESPONSIVE -> "确认失联"
    HealthStatus.RECOVERING -> "正在重连"
    HealthStatus.UNKNOWN -> "未知"
    HealthStatus.PAUSED -> "已暂停"
}
