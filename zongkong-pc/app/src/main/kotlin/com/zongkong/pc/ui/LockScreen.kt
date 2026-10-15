package com.zongkong.pc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zongkong.pc.Controller
import com.zongkong.pc.LockKind
import com.zongkong.pc.Usage
import com.zongkong.pc.Verdict

/**
 * 整台电脑锁着时盖在所有窗口上面的页。
 *  没说用途 / 会话到点：写这次做什么、选时长，自己就能开；到点的先答“做完了吗”。
 *  夜里、总时长用完：自己开不了，只能向总控申请（右边就是对话）。
 *  锁机、总控时间：等时间到；真急用紧急解锁（一天一次、十分钟、要写清楚，手机会知道）。
 */
@Composable
fun LockScreen(v: Verdict.Machine, c: Controller) {
    val sig = LocalSig.current
    val now by c.now.collectAsState()
    val config by c.config.collectAsState()
    val state by c.state.collectAsState()
    val phone by c.phone.collectAsState()
    val today by c.today.collectAsState()
    val selfOk = v.kind == LockKind.NO_SESSION || v.kind == LockKind.SESSION_END
    var purpose by remember(v.kind) { mutableStateOf("") }
    var minutes by remember(v.kind) { mutableIntStateOf(minOf(45, config.rules.sessionMax)) }
    var err by remember { mutableStateOf<String?>(null) }
    var reviewed by remember(v.kind, state.session?.start) { mutableStateOf(v.kind != LockKind.SESSION_END) }
    var emergency by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf("") }
    val dark = v.kind == LockKind.CURFEW || v.kind == LockKind.HQ || v.kind == LockKind.LOCK
    val bg = if (dark) sig.night else sig.paper
    val fg = if (dark) Color(0xFFE8E6E1) else sig.ink
    val mutedC = if (dark) Color(0xFF9A9892) else sig.muted

    Row(Modifier.fillMaxSize().background(bg)) {
        // ---------- 左：为什么锁、自己能做什么 ----------
        Column(Modifier.weight(1.1f).fillMaxHeight().padding(56.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Seal("控", sig.ai, 34.dp)
                Text("总控", style = MaterialTheme.typography.titleLarge, color = fg)
                Spacer(Modifier.weight(1f))
                Text(Usage.hm(now, c.zone), style = MaterialTheme.typography.headlineMedium.merge(Mono), color = mutedC)
            }
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.CenterStart) {
                Breath(if (dark) sig.ai else sig.plan, Modifier.size(150.dp))
            }
            Text(v.kind.title, style = MaterialTheme.typography.displayMedium.copy(fontSize = 44.sp), color = fg)
            Text(v.reason, style = MaterialTheme.typography.bodyLarge, color = mutedC)
            if (v.until > now) Text("到 ${Usage.hm(v.until, c.zone)} 解开（还有 ${(v.until - now) / 60_000 + 1} 分钟）", style = MaterialTheme.typography.titleMedium.merge(Mono), color = sig.ai)
            phone?.takeIf { it.main.isNotBlank() }?.let { Text("今天的主线：${it.main}", style = MaterialTheme.typography.titleMedium, color = fg) }
            Text("今天电脑已用 ${today.totalMinutes() / 60} 小时 ${today.totalMinutes() % 60} 分", style = MaterialTheme.typography.bodyMedium, color = mutedC)

            if (selfOk && !reviewed) {
                val s = state.session
                Text("上一段说好做「${s?.purpose.orEmpty()}」。做完了吗？", style = MaterialTheme.typography.titleMedium, color = fg)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("做完了", sig.free) { c.review(true, ""); reviewed = true }
                    Chip("没做完", sig.warn) { c.review(false, ""); reviewed = true }
                }
            } else if (selfOk) {
                OutlinedTextField(purpose, { purpose = it; err = null }, label = { Text("这次开电脑做什么（具体：写论文第二章 / 查期末安排）") }, singleLine = true, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth())
                // 打不了字也能开：点一个用途
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull(
                        phone?.main?.takeIf { it.isNotBlank() }?.let { "做主线：$it" },
                        "学习（看书、刷题）", "写作业 / 论文", "工作 / 项目", "查资料",
                    ).take(5).forEach { p -> Chip(p.take(16), sig.plan, selected = purpose == p) { purpose = p; err = null } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60, 90, 120).filter { it <= config.rules.sessionMax }.forEach { m -> Chip("$m 分钟", sig.plan, selected = minutes == m) { minutes = m } }
                }
                Button(onClick = { err = c.startSession(purpose, minutes) }, colors = ButtonDefaults.buttonColors(containerColor = sig.plan)) { Text("开始") }
                Hint("到点会回到这一页对账。娱乐、游戏、社交照样按额度管。要更久，向右边的总控申请。", color = mutedC)
            }
            err?.let { Text(it, color = sig.strict) }

            Spacer(Modifier.weight(1f))
            if (!emergency) {
                TextButton(onClick = { emergency = true }) { Text("真有急事？", color = mutedC) }
            } else {
                OutlinedTextField(why, { why = it; err = null }, label = { Text("为什么急（至少 30 字；一天一次、十分钟，手机会知道）") }, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { err = c.emergency(why) }, colors = ButtonDefaults.buttonColors(containerColor = sig.strict)) { Text("紧急解锁 10 分钟") }
                    TextButton(onClick = { emergency = false }) { Text("算了", color = mutedC) }
                }
            }
        }
        // ---------- 右：向总控申请 ----------
        if (v.kind != LockKind.HQ) {
            Box(Modifier.weight(0.9f).fillMaxHeight().background(if (dark) Color(0xFF1C2028) else sig.wash).padding(32.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("向总控申请", style = MaterialTheme.typography.titleLarge, color = fg)
                    Hint("说清楚做什么、要多久、用完回去做什么。它会对着你今天的记录和手机上的计划判断。", color = mutedC)
                    Box(Modifier.background(sig.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(14.dp)) {
                        ChatPanel(c, Modifier.fillMaxWidth().height(560.dp), compact = true)
                    }
                }
            }
        }
    }
}

/** 其他显示器上：只是一块暗色，写着“锁着”。 */
@Composable
fun LockShade(v: Verdict.Machine) {
    Box(Modifier.fillMaxSize().background(Color(0xFF14171D)), contentAlignment = Alignment.Center) {
        Text("总控 · ${v.kind.title}", color = Color(0xFF9A9892), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
    }
}

/** 拦截页：这一样现在关着。可以不去了，也可以向总控申请。 */
@Composable
fun GateScreen(g: com.zongkong.pc.GateView, c: Controller) {
    val sig = LocalSig.current
    val phone by c.phone.collectAsState()
    var asking by remember(g.shownAt) { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().background(sig.paper)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(56.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Seal("控", sig.ai, 34.dp)
                Text("总控", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(30.dp))
            Tag(g.target.cat.label, sig.cat(g.target.cat))
            Text("${g.target.label} 现在关着", style = MaterialTheme.typography.displayMedium.copy(fontSize = 44.sp))
            if (g.title.isNotBlank() && g.title != g.target.label) Hint("「${g.title.take(80)}」")
            Text(g.reason, style = MaterialTheme.typography.bodyLarge, color = sig.muted)
            if (g.limit > 0) {
                Meter(g.used, g.limit, sig.cat(g.target.cat), Modifier.widthIn(max = 420.dp))
                Hint("今天${g.target.cat.label} ${g.used} / ${g.limit} 分钟")
            }
            phone?.takeIf { it.main.isNotBlank() }?.let { Text("今天的主线：${it.main}——做了吗？", style = MaterialTheme.typography.titleMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { c.dismissGate() }, colors = ButtonDefaults.buttonColors(containerColor = sig.plan)) { Text("不去了") }
                Button(onClick = { asking = true }, colors = ButtonDefaults.buttonColors(containerColor = sig.ai)) { Text("向总控申请") }
            }
            Hint("窗口已经收到任务栏。网页在网络这一层也打不开，换浏览器也一样。")
        }
        if (asking) {
            Box(Modifier.width(520.dp).fillMaxHeight().background(sig.wash).padding(28.dp)) {
                ChatPanel(c, Modifier.fillMaxSize(), starter = "我想用${g.target.label}：", compact = true)
            }
        } else {
            Box(Modifier.weight(0.8f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Breath(sig.cat(g.target.cat), Modifier.size(320.dp))
            }
        }
    }
}
