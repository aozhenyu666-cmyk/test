package com.zongkong.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.system.Usage
import com.zongkong.app.zk
import com.zongkong.core.Policy
import com.zongkong.core.boss.Boss
import com.zongkong.core.boss.BossConfig
import com.zongkong.core.boss.Charter
import com.zongkong.core.boss.ClaimVerdict
import com.zongkong.core.boss.CommitStatus
import com.zongkong.core.boss.Stance

/** 态度的颜色。 */
@Composable
fun stanceColor(s: Stance): Color {
    val sig = LocalSignals.current
    return when (s) {
        Stance.TRUST -> sig.free
        Stance.NORMAL -> sig.ai
        Stance.WARY -> sig.warn
        Stance.STRICT -> sig.strict
    }
}

/** 信用的半圆表：进场时从 0 走到当前分数。 */
@Composable
fun CredGauge(score: Int, color: Color, modifier: Modifier = Modifier) {
    val sig = LocalSignals.current
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(if (reduce) score / 100f else 0f) }
    LaunchedEffect(score) { if (reduce) a.snapTo(score / 100f) else a.animateTo(score / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.width * 0.07f
            val d = minOf(size.width, size.height * 2) - stroke
            val topLeft = Offset((size.width - d) / 2, stroke / 2)
            drawArc(sig.line, 180f, 180f, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 180f, 180f * a.value, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            // 40、60、80 三道刻度：严格 / 审慎 / 正常 / 信任
            listOf(0.4f, 0.6f, 0.8f).forEach { f ->
                val ang = Math.toRadians(180.0 + 180.0 * f)
                val r = d / 2
                val c = Offset(topLeft.x + r, topLeft.y + r)
                val p1 = Offset(c.x + (r - stroke) * Math.cos(ang).toFloat(), c.y + (r - stroke) * Math.sin(ang).toFloat())
                val p2 = Offset(c.x + (r + stroke) * Math.cos(ang).toFloat(), c.y + (r + stroke) * Math.sin(ang).toFloat())
                drawLine(sig.muted.copy(alpha = 0.5f), p1, p2, strokeWidth = 2f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(a.value * 100).toInt()}", style = MaterialTheme.typography.displayMedium.merge(Mono), color = color, modifier = Modifier.testTag("cred-score"))
            Text("信用", style = MaterialTheme.typography.labelMedium, color = sig.muted)
        }
    }
}

/**
 * 档案：总控者怎么看你。信用和态度、未结的约定、它排的回访、你的说法和它的判定、信用明细、它的眼睛、宪章。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DossierScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val store = app.store
    val sig = LocalSignals.current
    val d by store.dossier.collectAsStateWithLifecycle()
    val hq by store.hq.collectAsStateWithLifecycle()
    val config by store.config.collectAsStateWithLifecycle()
    val work by store.work.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    val cred = remember(d, hq, work, now) { Boss.credibility(d, hq.sessions, now, work.events) }
    val stance = remember(d, hq, work, now) { Boss.stance(d, hq.sessions, now, work.events) }
    val ledger = remember(d, hq, work, now) { Boss.ledger(d, hq.sessions, now, work.events) }
    var showCharter by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackBar("档案", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------- 年轮：最近 14 天一天一圈，信用越高圈越圆；严格时一道雷 ----------
            Box(Modifier.fillMaxWidth().height(110.dp)) {
                val days = remember(ledger) { (ledger.map { java.time.Instant.ofEpochMilli(it.at).atZone(store.zone).toLocalDate() }.distinct().size + 3).coerceAtMost(14) }
                TreeRings(days, cred / 100f, stanceColor(stance), Modifier.fillMaxSize())
                if (stance == Stance.STRICT) Lightning(cred, sig.strict, Modifier.fillMaxSize(), ambient = true)
            }

            // ---------- 信用和态度 ----------
            Panel(accent = stanceColor(stance), modifier = Modifier.enter(0).testTag("dossier-head")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CredGauge(cred, stanceColor(stance), Modifier.size(width = 150.dp, height = 96.dp))
                    Column(Modifier.weight(1f)) {
                        Text("态度：${stance.label}", style = MaterialTheme.typography.titleLarge, color = stanceColor(stance))
                        Hint(stance.rule)
                    }
                }
                d.escalation?.takeIf { it.until > now }?.let {
                    Text("总控自己收紧到“${it.stance.label}”，到 ${clock(it.until)}：${it.reason}", style = MaterialTheme.typography.bodySmall, color = sig.strict)
                }
                Hint("起点 ${Boss.BASE}，看最近 ${Boss.WINDOW_DAYS} 天：说法属实 +3、对不上 −12、说不清 −4；约定做到 +4、没做到 −10；总控时间做完 +3、提前退出 −5；不理也有代价：总控主动问了 2 小时不回 −3（一天最多 4 次），15 分钟内回 +1，到点提醒后 45 分钟没开始也没推迟 −2（一天最多 3 次）；记下做完一步 +1（一天最多 4 次）。低于 40 是“严格”：不给紧急放行、不接受口头说法。")
                Text("强度：" + com.zongkong.core.boss.Intensity.describe(config.boss, stance), style = MaterialTheme.typography.labelLarge, color = stanceColor(stance), modifier = Modifier.testTag("intensity"))
                if (cred < 60) Hint("怎么爬回来：它问你，15 分钟内回 +1；做完一步 +1（各一天最多 4 次）；说法被核实 +3；守住约定 +4；做完总控时间 +3。最快的是今天就守住一条约定、做完一次总控时间。")
            }

            // ---------- 约定 ----------
            val open = d.commitments.filter { it.status == CommitStatus.OPEN }.sortedBy { it.due }
            SectionLabel("未结的约定（${open.size}）")
            if (open.isEmpty()) Hint("没有。你答应总控的事会记在这里，到点它会回头查。")
            open.forEach { c ->
                Panel(accent = if (c.due < now) sig.strict else null, modifier = Modifier.testTag("commit-${c.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.what, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Tag(if (c.due < now) "已过期" else "${clock(c.due)} 前", if (c.due < now) sig.strict else sig.ai)
                    }
                    if (c.check.isNotBlank()) Hint("检查：${c.check}")
                }
            }
            val pending = d.checks.filter { !it.done && it.due > now }.sortedBy { it.due }
            if (pending.isNotEmpty()) {
                SectionLabel("总控排的回访")
                Panel {
                    pending.take(6).forEach { k ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(clock(k.due), style = MaterialTheme.typography.bodyMedium.merge(Mono), color = sig.ai)
                            Text(k.about, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }

            // ---------- 说法和判定 ----------
            val claims = d.claims.takeLast(12).reversed()
            SectionLabel("你的说法和总控的判定")
            if (claims.isEmpty()) Hint("还没有。你跟总控说“我在做 X”“我做了 Y 小时”，它会记下来，对照手机记录下结论。")
            claims.forEach { c ->
                val color = when (c.verdict) {
                    ClaimVerdict.VERIFIED -> sig.free
                    ClaimVerdict.CONTRADICTED -> sig.strict
                    ClaimVerdict.VAGUE -> sig.warn
                    else -> sig.muted
                }
                Panel(modifier = Modifier.testTag("claim-${c.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("“${c.text}”", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Tag(c.verdict.label, color)
                    }
                    if (c.evidence.isNotBlank()) Hint("依据：${c.evidence}")
                    Text(clock(c.at), style = MaterialTheme.typography.labelSmall, color = sig.muted)
                }
            }
            val closed = d.commitments.filter { it.status != CommitStatus.OPEN }.takeLast(6).reversed()
            if (closed.isNotEmpty()) {
                SectionLabel("结了的约定")
                Panel {
                    closed.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.what, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                            Tag(c.status.label, when (c.status) { CommitStatus.KEPT -> sig.free; CommitStatus.BROKEN -> sig.strict; else -> sig.muted })
                        }
                    }
                }
            }
            if (ledger.isNotEmpty()) {
                SectionLabel("信用明细")
                Panel {
                    ledger.takeLast(10).reversed().forEach {
                        Row {
                            Text((if (it.delta > 0) "+" else "") + it.delta, style = MaterialTheme.typography.bodyMedium.merge(Mono), color = if (it.delta > 0) sig.free else sig.strict, modifier = Modifier.padding(end = 10.dp))
                            Text(it.why, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }

            // ---------- 眼睛 ----------
            SectionLabel("总控的眼睛")
            EyesPanel(Policy.desiredBoss(config), config.boss, Usage.granted(context)) { message = it }
            ShotStrip(store.shots(now - 24 * 3600_000L, now).takeLast(8)) { message = it }

            // ---------- 宪章 ----------
            SectionLabel("宪章")
            Panel(onClick = { showCharter = !showCharter }, modifier = Modifier.testTag("charter")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Charter.VERSION, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(if (showCharter) "收起" else "展开", color = sig.muted, style = MaterialTheme.typography.labelLarge)
                }
                Hint("总控者的设定：写在 App 里，每次对话都放在最前面。换模型、换接口它都不变；对话里谁也改不了。")
                if (showCharter) Text(Charter.TEXT, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    OutcomeDialog(message) { message = null }
}

/** 眼睛的设置：多看立即生效，少看等 24 小时。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EyesPanel(boss: BossConfig, current: BossConfig, usage: Boolean, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val store = context.zk.store
    val sig = LocalSignals.current
    fun save(b: BossConfig) {
        var msg = ""
        store.updateConfig { c -> Policy.setBoss(c, b, System.currentTimeMillis()).also { msg = it.message }.config }
        onMessage(msg)
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("使用情况访问", style = MaterialTheme.typography.titleSmall)
                Hint(if (usage) "已授权：总控没在运行的时段也查得到你用了什么" else "没授权：只有总控自己记的，服务被关掉的时段是空白")
            }
            if (!usage) TextButton(onClick = { safeStart(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("去打开") }
            else Tag("已开", sig.free)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("截图抽查", style = MaterialTheme.typography.titleSmall)
                Hint(
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) "要 Android 11 以上"
                    else "亮屏时不定时截一张（电话、总控不截），只存在手机上，留 ${boss.keepDays} 天；总控要看时才发给你自己的 AI 接口。",
                )
            }
            Switch(checked = boss.shots, onCheckedChange = { save(boss.copy(shots = it)) }, modifier = Modifier.testTag("eyes-shots"))
        }
        if (boss.shots) {
            Text("每小时大约", style = MaterialTheme.typography.labelLarge, color = sig.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 6).forEach { n -> QuickChip(if (boss.shotsPerHour == n) "$n 张 ✓" else "$n 张", sig.ai) { save(boss.copy(shotsPerHour = n)) } }
            }
        }
        Text("每天抽查（总控自己看一眼，有事才找你）", style = MaterialTheme.typography.labelLarge, color = sig.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 2, 3, 5).forEach { n -> QuickChip((if (n == 0) "不抽查" else "$n 次") + if (boss.spotChecks == n) " ✓" else "", sig.ai) { save(boss.copy(spotChecks = n)) } }
        }
        Text("娱乐连续多久，总控找你", style = MaterialTheme.typography.labelLarge, color = sig.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(20, 30, 40, 60, 0).forEach { n -> QuickChip((if (n == 0) "不管" else "$n 分钟") + if (boss.bingeMinutes == n) " ✓" else "", sig.ai) { save(boss.copy(bingeMinutes = n)) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("模型锁", style = MaterialTheme.typography.titleSmall)
                Hint("现在的 AI 接口能用时，换地址 / 密钥 / 模型要等 24 小时——免得不清醒的时候换一个听话的。")
            }
            Switch(checked = boss.modelLock, onCheckedChange = { save(boss.copy(modelLock = it)) })
        }
        if (boss != current) Text("排队中：${current.looser(boss).joinToString("、")}——24 小时后生效", style = MaterialTheme.typography.bodySmall, color = sig.warn)
        Hint("看得更多、查得更勤立即生效；关掉、调少、解开模型锁 24 小时后生效。")
    }
}

/** 最近的抽查截图（只在手机上）。 */
@Composable
private fun ShotStrip(shots: List<com.zongkong.core.boss.Shot>, onMessage: (String) -> Unit) {
    if (shots.isEmpty()) return
    val context = LocalContext.current
    val sig = LocalSignals.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近 24 小时的抽查截图（${shots.size}）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                context.zk.store.shotsDir.listFiles()?.forEach { it.delete() }
                onMessage("截图都删掉了。")
            }) { Text("全部删除", color = sig.muted) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shots.forEach { s ->
                val bmp = remember(s.path) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(s.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
                    }.getOrNull()
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    bmp?.let { Image(it, null, modifier = Modifier.size(width = 64.dp, height = 128.dp), contentScale = ContentScale.Crop) }
                    Text(clock(s.at), style = MaterialTheme.typography.labelSmall, color = sig.muted, fontSize = 10.sp)
                }
            }
        }
    }
}

/** 对话页顶上的一行：信用、态度，点开看档案。 */
@Composable
fun StanceLine(onClick: () -> Unit) {
    val store = LocalContext.current.zk.store
    val d by store.dossier.collectAsStateWithLifecycle()
    val hq by store.hq.collectAsStateWithLifecycle()
    val work by store.work.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    val cred = remember(d, hq, work, now) { Boss.credibility(d, hq.sessions, now, work.events) }
    val st = remember(d, hq, work, now) { Boss.stance(d, hq.sessions, now, work.events) }
    val open = d.commitments.count { it.status == CommitStatus.OPEN }
    Row(Modifier.clickable(onClick = onClick).testTag("stance-line"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("信用 $cred", style = MaterialTheme.typography.labelMedium.merge(Mono), color = stanceColor(st), fontWeight = FontWeight.Bold)
        Text("· ${st.label}" + if (open > 0) " · 约定 $open" else "", style = MaterialTheme.typography.labelMedium, color = LocalSignals.current.muted, maxLines = 1)
    }
}
