package com.zongkong.app.ui

import android.content.Intent
import android.os.SystemClock
import android.telecom.TelecomManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zongkong.app.guard.AiFocusComponents
import com.zongkong.app.guard.SentinelClient
import com.zongkong.app.zk
import com.zongkong.core.AiFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AiFocusScreen(onBack: () -> Unit, onPermissions: () -> Unit, blockedLabel: String? = null) {
    val context = LocalContext.current
    val focus = context.zk.aiFocus
    val session by focus.session.collectAsStateWithLifecycle()
    val now = rememberNow(1_000)
    val left = session?.remaining(SystemClock.elapsedRealtime()) ?: 0L
    val active = left > 0
    val released = SentinelClient.released(now)
    val sig = LocalSignals.current
    var minutes by remember { mutableStateOf(20) }
    var wechat by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf(false) }
    val pickers by produceState<Set<String>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { AiFocusComponents.pickers(context) }.getOrDefault(emptySet()) }
    }
    val healthy = PermissionStatus.accessibility(context) && PermissionStatus.guardRunning(context)
    BackHandler(active && !released) { /* A system Back must not reveal another control screen. */ }

    fun open(pkg: String, start: Boolean = false) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { message = "没有找到这个应用，请先安装官方 App。"; return }
        if (start) {
            if (!healthy) { message = "先让无障碍服务恢复真实响应，再开始。"; return }
            if (context.zk.store.hq.value.active != null) { message = "请先完成当前的总控时间，再进入 AI 专注。"; return }
            val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage.orEmpty()
            if (!focus.start(minutes, wechat, pickers ?: return, dialer)) { message = "未能开始：已有一段专注，或本机保存失败。"; return }
        }
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { message = "应用未能打开；专注计时仍保留，可重试另一个 AI。" }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("ai-focus"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("AI 专注", if (active) "这段时间，只把问题聊透。" else "用一段完整时间和 GPT / Gemini 沟通。")
        if (focus.restoreFailed && !active) Text("上次的专注记录无法读取，未恢复保护；请重新开始一段。", color = sig.warn)
        if (active) {
            Panel(accent = sig.think) {
                Text("%02d:%02d".format((left + 999) / 60_000, ((left + 999) / 1_000) % 60), style = MaterialTheme.typography.displayLarge.merge(Mono), modifier = Modifier.testTag("ai-focus-remaining"))
                Hint(if (released) "哨兵的全局解除闸门正在生效；倒计时继续。" else "倒计时结束自动恢复原规则；返回、重开总控不会提前结束。")
                blockedLabel?.let { Text("「$it」在这段专注中不可用。", color = sig.warn) }
            }
            Button(onClick = { open(AiFocus.GPT) }, modifier = Modifier.fillMaxWidth()) { Text("打开 GPT") }
            Button(onClick = { open(AiFocus.GEMINI) }, modifier = Modifier.fillMaxWidth()) { Text("打开 Gemini") }
            OutlinedButton(onClick = { open(AiFocus.NEKOBOX) }, modifier = Modifier.fillMaxWidth()) { Text("打开 NekoBox") }
            if (session?.wechat == true) OutlinedButton(onClick = { open(AiFocus.WECHAT) }, modifier = Modifier.fillMaxWidth()) { Text("打开微信") }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL)) }.onFailure { message = "没有找到拨号组件。" }
            }, modifier = Modifier.fillMaxWidth()) { Text("通话") }
            Hint("发图 / 发文件：在 GPT 或 Gemini 的附件按钮里选。图库、文件管理器主页不会放行，选择页面最多连续使用 5 分钟。")
            if (!healthy) {
                Text("拦截服务未确认在响应：${PermissionStatus.healthText(context)}。计时保留，但当前不能保证拦截。", color = sig.warn)
                OutlinedButton(onClick = onPermissions) { Text("修复无障碍权限") }
            }
        } else {
            Hint(if (session != null) "上一段专注已结束，原来的总控规则已恢复。" else "开启后采用独立白名单，娱乐、浏览器、系统设置和其他应用都会被现有拦截页挡住。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 20, 40, 60).forEach { m -> QuickChip("$m 分" + if (minutes == m) " ✓" else "", sig.think) { minutes = m } }
            }
            Row {
                Checkbox(checked = wechat, onCheckedChange = { wechat = it })
                Text("保留微信通信", modifier = Modifier.padding(top = 12.dp))
            }
            Hint("始终保留通话、当前输入法、权限弹窗和 NekoBox。微信放行整个 App，也包含其中的朋友圈、视频号；想更严格，可在开始前关闭微信。")
            Text("拦截服务：${PermissionStatus.healthText(context)}", color = if (healthy) sig.free else sig.warn)
            if (!healthy) OutlinedButton(onClick = onPermissions) { Text("先检查权限与运行状态") }
            Button(onClick = { open(AiFocus.GPT, start = true) }, enabled = healthy && pickers != null, modifier = Modifier.fillMaxWidth().testTag("ai-focus-start-gpt")) { Text("专注 $minutes 分钟 · 打开 GPT") }
            Button(onClick = { open(AiFocus.GEMINI, start = true) }, enabled = healthy && pickers != null, modifier = Modifier.fillMaxWidth()) { Text("专注 $minutes 分钟 · 打开 Gemini") }
            Hint("到点解除这一层限制；今天欠交的节律、总控时间等原有规则仍然有效。重启后接着完成上次保存的剩余时间。")
            TextButton(onClick = onBack) { Text("返回总控") }
        }
        message?.let { Text(it, color = sig.warn) }
        TextButton(onClick = { diagnostics = !diagnostics }) { Text("图片 / 文件选择器诊断") }
        if (diagnostics) Panel {
            val knownPickers = if (active) session?.pickerComponents.orEmpty() else pickers.orEmpty()
            Hint("${if (active) "本段已固定" else "本机识别到"} ${knownPickers.size} 个系统选择组件。只读包名和窗口类名，不读图片或文件内容。")
            knownPickers.sorted().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Hint("iQOO 系统版本可能使用不同组件。若选图被拦，结束后到「我 → 无障碍诊断」查看 ai_focus_block 中的包名 / 类名；未识别的页面默认拦截。")
        }
    }
}
