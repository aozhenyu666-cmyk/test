package com.zongkong.pc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zongkong.core.AiConfig
import com.zongkong.core.boss.Boss
import com.zongkong.pc.Cat
import com.zongkong.pc.Controller
import com.zongkong.pc.Decide
import com.zongkong.pc.PcPolicy
import com.zongkong.pc.PcRules
import com.zongkong.pc.PcTools
import com.zongkong.pc.SiteRule
import com.zongkong.pc.Usage
import com.zongkong.pc.Verdict

enum class Tab(val label: String) { TODAY("今天"), CHAT("总控"), RULES("规则"), LINK("连接与设置") }

/** 主窗口：左边一列标签，右边内容。 */
@Composable
fun MainScreen(c: Controller, tab: Tab, onTab: (Tab) -> Unit) {
    val sig = LocalSig.current
    val config by c.config.collectAsState()
    Row(Modifier.fillMaxSize().background(sig.paper)) {
        Column(Modifier.width(170.dp).fillMaxHeight().background(sig.wash).padding(vertical = 22.dp, horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                Seal("控", sig.ai)
                Column {
                    Text("总控", style = MaterialTheme.typography.titleMedium)
                    Text(Controller.VERSION, style = MaterialTheme.typography.labelSmall, color = sig.muted)
                }
            }
            Tab.entries.forEach { t ->
                val on = t == tab
                Box(
                    Modifier.fillMaxWidth().background(if (on) sig.card else Color.Transparent, RoundedCornerShape(10.dp)).clickable { onTab(t) }.padding(horizontal = 12.dp, vertical = 10.dp),
                ) { Text(t.label, style = MaterialTheme.typography.titleSmall, color = if (on) sig.ink else sig.muted) }
            }
            Spacer(Modifier.weight(1f))
            val link by c.link.collectAsState()
            Hint(if (config.link.ready) "手机：$link" else "没连手机")
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (tab) {
                Tab.TODAY -> TodayPage(c, onTab)
                Tab.CHAT -> FullChat(c)
                Tab.RULES -> RulesPage(c)
                Tab.LINK -> LinkPage(c)
            }
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}

// ---------- 今天 ----------

@Composable
fun TodayPage(c: Controller, onTab: (Tab) -> Unit) {
    val sig = LocalSig.current
    val now by c.now.collectAsState()
    val today by c.today.collectAsState()
    val config by c.config.collectAsState()
    val state by c.state.collectAsState()
    val phone by c.phone.collectAsState()
    val fg by c.fg.collectAsState()
    val ctx = c.ctx(now)
    val r = config.rules
    Page {
        Box(Modifier.fillMaxWidth().height(26.dp)) { FlowLines(sig.line, Modifier.fillMaxSize(), 3) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            val total = today.totalMinutes()
            Ring(if (r.totalMinutes > 0) total.toFloat() / r.totalMinutes else 0f, if (r.totalMinutes in 1..total) sig.strict else sig.plan, Modifier.size(132.dp), 9.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${total / 60}:${"%02d".format(total % 60)}", style = MaterialTheme.typography.headlineSmall.merge(Mono))
                    Text(if (r.totalMinutes > 0) "/ ${r.totalMinutes / 60}:${"%02d".format(r.totalMinutes % 60)}" else "不限", style = MaterialTheme.typography.labelMedium, color = sig.muted)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("今天在电脑上", style = MaterialTheme.typography.headlineMedium)
                fg?.let { (f, t) -> Hint("此刻：${t.label}（${t.cat.label}）${if (f.title.isNotBlank() && t.key.startsWith("web:")) "「${f.title.take(40)}」" else ""}") }
                val st = Boss.stance(state.dossier, emptyList(), now)
                Text("信用 ${Boss.credibility(state.dossier, emptyList(), now)} · ${st.label}", style = MaterialTheme.typography.labelLarge.merge(Mono), color = sig.ai)
                phone?.let { p -> if (p.main.isNotBlank()) Text("手机上今天的主线：${p.main}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        // 会话
        val session = state.activeSession(now)
        Panel(accent = if (session == null) sig.warn else null) {
            if (session != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("这次在做：${session.purpose}", style = MaterialTheme.typography.titleMedium)
                        Hint("到 ${Usage.hm(session.until, c.zone)}（还有 ${(session.until - now) / 60_000 + 1} 分钟）${if (session.byBoss) " · 总控批的" else ""}")
                    }
                    OutlinedButton(onClick = { c.endSession() }) { Text("做完了，结束") }
                }
            } else {
                Text("现在没有会话", style = MaterialTheme.typography.titleMedium)
                Hint(if (r.sessions) "下一次碰电脑会先问这次做什么。" else "会话制关着。")
            }
            state.activePasses(now).forEach { p -> Hint("放行中：${p.label} 到 ${Usage.hm(p.until, c.zone)}（${p.reason}）", color = sig.ai) }
            state.activeLock(now)?.let { l -> Hint("锁机到 ${Usage.hm(l.until, c.zone)}：${l.by}——${l.reason}", color = sig.strict) }
        }
        // 各类额度
        Panel {
            Text("额度", style = MaterialTheme.typography.titleSmall)
            listOf(Cat.FUN, Cat.SOCIAL, Cat.GAME, Cat.WORK, Cat.OTHER).forEach { cat ->
                val used = today.catMinutes(cat)
                val lim = r.limit(cat)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(cat.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
                    Box(Modifier.weight(1f)) { Meter(used, lim, sig.cat(cat)) }
                    Text(if (lim == null) "$used 分" else "$used / $lim 分", style = MaterialTheme.typography.labelLarge.merge(Mono), color = if (lim != null && used >= lim) sig.strict else sig.muted, modifier = Modifier.width(96.dp))
                }
            }
        }
        // 时间轴和最多的
        Panel {
            Text("今天的时间轴", style = MaterialTheme.typography.titleSmall)
            val dayStart = Usage.day(now, c.zone).atTime(4, 0).atZone(c.zone).toInstant().toEpochMilli()
            DayStrip(today.segs, dayStart, Modifier.fillMaxWidth().height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("4:00", "10:00", "16:00", "22:00", "4:00").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = sig.muted) } }
            today.top(8).forEach { (label, m, cat) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(sig.cat(cat))
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("$m 分", style = MaterialTheme.typography.labelLarge.merge(Mono), color = sig.muted)
                }
            }
            if (today.segs.isEmpty()) Hint("还没有记录。")
        }
        val hits = state.blocks.filter { Usage.day(it.at, c.zone) == Usage.day(now, c.zone) }
        if (hits.isNotEmpty() || state.gaps.isNotEmpty()) {
            Panel {
                Text("今天被拦 ${hits.size} 次", style = MaterialTheme.typography.titleSmall)
                hits.takeLast(6).asReversed().forEach { Hint("${Usage.hm(it.at, c.zone)} ${it.label}：${it.why}") }
                state.gaps.takeLast(3).forEach { g -> Hint("失联 ${Usage.hm(g.from, c.zone)}–${Usage.hm(g.to, c.zone)}：${g.why}", color = sig.strict) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onTab(Tab.CHAT) }, colors = ButtonDefaults.buttonColors(containerColor = sig.ai)) { Text("跟总控说") }
            OutlinedButton(onClick = { onTab(Tab.RULES) }) { Text("看规则") }
        }
        when (val v = com.zongkong.pc.Decide.machine(ctx)) {
            is Verdict.Machine -> Hint("（电脑现在是“${v.kind.title}”，锁屏盖在最上面）", color = sig.strict)
            else -> {}
        }
    }
}

// ---------- 规则 ----------

@Composable
fun RulesPage(c: Controller) {
    val sig = LocalSig.current
    val config by c.config.collectAsState()
    val base = PcTools.desired(config)
    var r by remember(base) { mutableStateOf(base) }
    var msg by remember { mutableStateOf<String?>(null) }
    var newSite by remember { mutableStateOf("") }
    var newDomain by remember { mutableStateOf("") }
    Page {
        Text("规则", style = MaterialTheme.typography.headlineMedium)
        Hint("收紧马上生效。放宽（额度调大、删掉网站、关掉开关）要写为什么，交给总控审：批了 24 小时后生效，驳回就作废、6 小时内不能再申请；没接 AI 要等 72 小时。AI 自己只能收紧。")
        config.pending?.let { p ->
            Panel(accent = sig.warn) {
                Text("排着的放宽：${p.summary}", color = sig.warn)
                if (p.why.isNotBlank()) Hint("你的理由：${p.why}")
                when {
                    p.reviewing -> Hint("总控在审…")
                    else -> Hint("总控：${p.verdict}。${Usage.hm(p.due, c.zone)} 生效（${if (p.due - p.at > PcPolicy.DELAY) "三天后" else "明天这个时候"}）。再改一次会以新的为准、重新审。")
                }
            }
        }
        config.denied?.takeIf { System.currentTimeMillis() - it.at < PcPolicy.RETRY_AFTER }?.let { d ->
            Panel(accent = sig.strict) {
                Text("驳回：${d.summary}", color = sig.strict)
                Hint("总控：${d.reason}。${Usage.hm(d.at + PcPolicy.RETRY_AFTER, c.zone)} 之后才能再申请。真要用一会儿，去“对话”申请一次性放行。")
            }
        }
        Panel {
            Text("每天的额度（分钟）", style = MaterialTheme.typography.titleSmall)
            listOf(Cat.FUN, Cat.SOCIAL, Cat.GAME).forEach { cat ->
                NumberRow("${cat.label}（0 = 一律要申请）", r.limits[cat.name] ?: 0) { r = r.copy(limits = r.limits + (cat.name to it)) }
            }
            NumberRow("电脑总时长（0 = 不限）", r.totalMinutes) { r = r.copy(totalMinutes = it) }
            NumberRow("总控一次最多放行", r.maxPass) { r = r.copy(maxPass = it) }
        }
        Panel {
            Text("会话和夜里", style = MaterialTheme.typography.titleSmall)
            SwitchRow("会话制：用电脑先说做什么、用多久，到点锁屏对账", r.sessions) { r = r.copy(sessions = it) }
            NumberRow("自己一次最多开几分钟", r.sessionMax) { r = r.copy(sessionMax = it) }
            SwitchRow("夜里锁机", r.curfew) { r = r.copy(curfew = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeField("从", r.curfewStart) { r = r.copy(curfewStart = it) }
                TimeField("到", r.curfewEnd) { r = r.copy(curfewEnd = it) }
            }
            NumberRow("连续用多久主动问一句（0 = 不问）", r.checkEvery) { r = r.copy(checkEvery = it) }
        }
        Panel {
            Text("怎么拦、怎么看", style = MaterialTheme.typography.titleSmall)
            SwitchRow("网站在网络层拦（系统代理，换浏览器也打不开）", r.netBlock) { r = r.copy(netBlock = it) }
            SwitchRow("让总控看屏幕（申请、抽查时截一张发给你自己的 AI；截图只存在这台电脑上）", r.screenLook) { r = r.copy(screenLook = it) }
            SwitchRow("开机自动启动", r.autostart) { r = r.copy(autostart = it) }
            SwitchRow("锁住 AI 接口（换模型 24 小时后生效）", r.modelLock) { r = r.copy(modelLock = it) }
        }
        Panel {
            Text("管的网站（${r.sites.size}）", style = MaterialTheme.typography.titleSmall)
            r.sites.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(Cat.of(s.cat).label, sig.cat(Cat.of(s.cat)))
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(90.dp))
                    Hint(s.domains.joinToString(" "), Modifier.weight(1f))
                    TextButton(onClick = { r = r.copy(sites = r.sites - s) }) { Text("删", color = sig.muted) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newSite, { newSite = it }, label = { Text("名字（虎扑）") }, singleLine = true, modifier = Modifier.width(150.dp))
                OutlinedTextField(newDomain, { newDomain = it }, label = { Text("域名（hupu.com）") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val d = newDomain.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
                    if (newSite.isNotBlank() && d.contains('.')) {
                        r = r.copy(sites = r.sites + SiteRule(newSite.trim(), Cat.FUN.name, listOf(d), listOf(newSite.trim())))
                        newSite = ""; newDomain = ""
                    }
                }) { Text("加（娱乐）") }
            }
        }
        Panel {
            Text("管的程序（${r.apps.size}）", style = MaterialTheme.typography.titleSmall)
            Hint(r.apps.joinToString("、") { "${it.label.ifBlank { it.exe }}（${Cat.of(it.cat).label}）" })
            Hint("由 Steam、WeGame、Epic、战网、米哈游启动器启动的程序都算游戏。")
        }
        val loosening = remember(r, config.rules) { PcPolicy.looser(PcPolicy.stricter(config.rules, r), r) }
        var why by remember(base) { mutableStateOf("") }
        if (loosening.isNotEmpty()) {
            Panel(accent = sig.warn) {
                Text("这次有放宽：${loosening.joinToString("、")}", style = MaterialTheme.typography.titleSmall, color = sig.warn)
                OutlinedTextField(why, { why = it }, label = { Text("为什么要放宽（至少 ${PcPolicy.MIN_WHY} 个字，总控会看理由和今天的记录）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { msg = c.setRules(r, why) }, enabled = r != base, colors = ButtonDefaults.buttonColors(containerColor = sig.plan)) { Text("保存") }
            TextButton(onClick = { r = base }) { Text("撤销没保存的", color = sig.muted) }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sig.ai) }
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedTextField(value.toString(), { t -> t.filter { it.isDigit() }.take(4).toIntOrNull()?.let(onChange) ?: if (t.isEmpty()) onChange(0) else Unit }, singleLine = true, modifier = Modifier.width(110.dp))
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
}

@Composable
private fun TimeField(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var t by remember(minutes) { mutableStateOf(Decide.hm(minutes)) }
    OutlinedTextField(t, { s ->
        t = s
        Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim())?.let { m ->
            val h = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            if (h in 0..23 && mm in 0..59) onChange(h * 60 + mm)
        }
    }, label = { Text(label) }, singleLine = true, modifier = Modifier.width(120.dp))
}

// ---------- 连接与设置 ----------

@Composable
fun LinkPage(c: Controller) {
    val sig = LocalSig.current
    val config by c.config.collectAsState()
    val phone by c.phone.collectAsState()
    val link by c.link.collectAsState()
    val now by c.now.collectAsState()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var url by remember(config.ai) { mutableStateOf(config.ai.baseUrl) }
    var key by remember(config.ai) { mutableStateOf(config.ai.apiKey) }
    var model by remember(config.ai) { mutableStateOf(config.ai.model) }
    var vision by remember(config.ai) { mutableStateOf(config.ai.visionModel) }
    var aiMsg by remember { mutableStateOf<String?>(null) }
    var quitting by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    Page {
        Text("连接与设置", style = MaterialTheme.typography.headlineMedium)
        Panel(accent = if (config.link.ready) null else sig.ai) {
            Text("和手机上的总控连起来", style = MaterialTheme.typography.titleSmall)
            Hint("手机上：我 → 电脑 → 复制配对码，发到电脑上（微信文件传输助手就行），粘贴到这里。连上以后：手机知道你电脑用了多久、在做什么；手机进总控时间，电脑跟着锁；手机上的总控可以锁电脑、拦网站、给电脑发话。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it }, label = { Text("配对码（ZKPC1:…）") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { msg = c.pair(code); code = "" }, enabled = code.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = sig.ai)) { Text("连接") }
            }
            msg?.let { Text(it, color = sig.ai, style = MaterialTheme.typography.bodySmall) }
            KeyValue("状态", if (config.link.ready) link else "没连")
            phone?.let { p ->
                KeyValue("手机最后同步", Usage.hm(p.at, c.zone))
                if (p.main.isNotBlank()) KeyValue("今天的主线", p.main)
                KeyValue("手机信用", "${p.credibility} · ${p.stance}")
                if (p.hqUntil > now) KeyValue("总控时间", "到 ${Usage.hm(p.hqUntil, c.zone)}", sig.strict)
            }
        }
        Panel {
            Text("AI 接口（OpenAI 兼容：DeepSeek、Kimi、通义、OpenAI……）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(url, { url = it }, label = { Text("接口地址（https://api.deepseek.com/v1）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(model, { model = it }, label = { Text("模型（deepseek-chat）") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(vision, { vision = it }, label = { Text("看图模型（可空）") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { aiMsg = c.setAi(AiConfig(url.trim(), key.trim(), model.trim(), visionModel = vision.trim())) }, colors = ButtonDefaults.buttonColors(containerColor = sig.plan)) { Text("保存") }
                aiMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sig.ai) }
            }
            config.pendingAi?.let { Hint("排着的换接口：${it.ai.model}，${Usage.hm(it.due, c.zone)} 生效（模型锁）", color = sig.warn) }
        }
        Panel {
            Text("声音", style = MaterialTheme.typography.titleSmall)
            SwitchRow("用 Windows 自带的声音念出来（拦截、问候、总控的回复）", config.speak) { c.setSpeak(it) }
        }
        Panel {
            Text("透明", style = MaterialTheme.typography.titleSmall)
            Hint("只读前台窗口的程序名和标题；开了“看屏幕”才会截屏（只存在这台电脑上，看的时候发给你自己填的 AI）。数据都在 ${c.dataDir.absolutePath}，记事本能看。图标、托盘、开始菜单都看得见，随时能退出、卸载——退出要写原因，手机会知道；被任务管理器结束，下次打开会记成“失联”报给手机。")
        }
        Panel(accent = sig.strict) {
            if (!quitting) TextButton(onClick = { quitting = true }) { Text("退出总控…", color = sig.strict) }
            else {
                OutlinedTextField(reason, { reason = it }, label = { Text("为什么退出（至少 10 个字，会告诉手机）") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.quit(reason)?.let { msg = it } ?: kotlin.system.exitProcess(0) }, colors = ButtonDefaults.buttonColors(containerColor = sig.strict)) { Text("退出") }
                    TextButton(onClick = { quitting = false }) { Text("算了") }
                }
            }
        }
    }
}

/** 右下角的小问候。 */
@Composable
fun CheckInCard(v: com.zongkong.pc.CheckInView, c: Controller) {
    val sig = LocalSig.current
    var text by remember(v.at) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(sig.card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Seal("控", sig.ai, 24.dp)
            Text("这 ${v.minutes} 分钟在做什么？", style = MaterialTheme.typography.titleMedium)
        }
        if (v.summary.isNotBlank()) Hint("记录：${v.summary}")
        OutlinedTextField(text, { text = it }, placeholder = { Text("一句话（或者点下面的）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("在学习", "在工作", "在查资料", "在娱乐").forEach { a -> Chip(a, if (a == "在娱乐") sig.warn else sig.plan) { c.answerCheckIn(a) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.answerCheckIn(text) }, enabled = text.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = sig.ai)) { Text("回答") }
            TextButton(onClick = { c.dismissCheckIn() }) { Text("不回（2 小时不回扣信用）", color = sig.muted, fontSize = 12.sp) }
        }
    }
}
