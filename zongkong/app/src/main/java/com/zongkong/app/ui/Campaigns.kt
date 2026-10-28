package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.agent.Campaign

/** 部门页上的“开一场战役”。 */
@Composable
fun CampaignCard(c: Campaign, nav: NavHostController, askTopic: Boolean = false) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val config by app.store.config.collectAsStateWithLifecycle()
    val log by app.chat.log.collectAsStateWithLifecycle()
    val mode by app.chat.mode.collectAsStateWithLifecycle()
    var topic by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val color = when (c) {
        Campaign.GRAND_PLAN, Campaign.PROJECT -> sig.plan
        Campaign.GRAND_THINK, Campaign.DIGEST -> sig.think
        Campaign.INTAKE -> sig.info
    }
    Panel(accent = color, modifier = Modifier.testTag("campaign-${c.name}")) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            when (c) {
                Campaign.GRAND_PLAN, Campaign.PROJECT -> Lattice(0.8f, color, Modifier.fillMaxSize())
                Campaign.GRAND_THINK, Campaign.DIGEST -> LeafVein(1f, color, Modifier.fillMaxSize())
                Campaign.INTAKE -> TideLines(0.6f, color, Modifier.fillMaxSize())
            }
        }
        Text(c.title, style = MaterialTheme.typography.titleLarge.merge(SerifTitle), color = color)
        Hint(c.what)
        if (askTopic) OutlinedTextField(topic, { topic = it }, label = { Text(if (c == Campaign.PROJECT) "哪件事（比如：国考）" else "想透哪个问题（可空：让总控从卡着的事里挑）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 战役挂在对话段上：这里只看当前模式这一段的，不会把另一种对话里的战役当成“正在跑”
            val running = log.threadOf(mode)?.campaign == c.name
            Button(
                onClick = {
                    if (running) nav.navigate("chat")
                    else if (app.chat.begin(c, topic)) nav.navigate("chat")
                    else msg = if (!config.ai.ready) "要先接 AI（我 → AI 接口）" else "总控正在忙，稍等"
                },
                colors = ButtonDefaults.buttonColors(containerColor = color),
                modifier = Modifier.testTag("campaign-go-${c.name}"),
            ) { Text(if (running) "继续" else "开始（15–40 分钟）") }
            if (log.campaignLog.any { it.startsWith(c.title) }) TextButton(onClick = { msg = log.campaignLog.lastOrNull { it.startsWith(c.title) } }) { Text("上一次", color = sig.muted) }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sig.ai) }
    }
}

/** 对话页顶上：正在进行的战役，可以收工。 */
@Composable
fun CampaignBanner() {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val log by app.chat.log.collectAsStateWithLifecycle()
    val mode by app.chat.mode.collectAsStateWithLifecycle()
    // 横幅只报当前这条对话线自己的战役；切到另一条线时它就该消失
    val running = log.threadOf(mode) ?: return
    val c = Campaign.of(running.campaign) ?: return
    val now = rememberNow(60_000)
    if (now - running.campaignAt > 3 * 3600_000L) return
    Panel(accent = sig.ai, modifier = Modifier.testTag("campaign-banner")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${c.title}进行中 · ${(now - running.campaignAt) / 60_000} 分钟" + if (running.campaignTopic.isNotBlank()) " · ${running.campaignTopic}" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { app.chat.stopCampaign() }) { Text("收工", color = sig.muted) }
        }
    }
}
