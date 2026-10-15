package com.zongkong.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.system.HqRunner
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.boss.Boss
import com.zongkong.core.boss.BossConfig
import com.zongkong.core.boss.CommitStatus
import com.zongkong.core.hq.Hq
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.Focus
import com.zongkong.core.work.TimeParse
import java.time.Instant

/**
 * 总控怎么运转：一张图（收→谋→筹→行→验）、今天它会主动做的事（几点、做什么）、它怎么判你、常见问题。
 * 只是把已经在跑的逻辑摆出来给你看，不改任何东西。
 */
@Composable
fun FlowScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val store = app.store
    val sig = LocalSignals.current
    val config by store.config.collectAsStateWithLifecycle()
    val work by store.work.collectAsStateWithLifecycle()
    val d by store.dossier.collectAsStateWithLifecycle()
    val hq by store.hq.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    val zone = store.zone
    val cred = remember(d, hq, work, now) { Boss.credibility(d, hq.sessions, now, work.events) }
    val stance = remember(d, hq, work, now) { Boss.stance(d, hq.sessions, now, work.events) }
    val schedule = remember(config, work, d, now, stance) { today(config, work, d, now, zone, stance) }

    Column(Modifier.fillMaxSize()) {
        BackBar("总控怎么运转", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("flow"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 一张图 ----------
            Panel {
                Box(Modifier.fillMaxWidth().height(200.dp)) {
                    Orbit(listOf("收", "谋", "筹", "行", "验"), sig.ai, Modifier.fillMaxSize(), active = 4, textColor = MaterialTheme.colorScheme.onSurface)
                }
                STAGES.forEach { (name, what) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(name, style = MaterialTheme.typography.labelLarge, color = sig.ai, modifier = Modifier.width(64.dp))
                        Text(what, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                Hint("总控是最后那一环：验收。它不替你做事，只核对你说的和记录对不对得上，对得上就认、对不上就扣信用，然后决定下一步管多紧。")
            }

            // ---------- 今天它会主动做的事 ----------
            SectionLabel("今天它会主动找你")
            Panel(modifier = Modifier.testTag("flow-today")) {
                Text(
                    "强度：${com.zongkong.core.boss.Intensity.describe(config.boss, stance)}" +
                        (if (!config.hq.proactive) " · 主动消息已关" else "") +
                        (if (!config.ai.ready) " · 没接 AI（只发固定的话）" else ""),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (schedule.isEmpty()) Hint("今天没有排定的主动消息。")
                schedule.forEach { e ->
                    val past = e.at in 1 until now
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (e.at == 0L) "随时" else clock(e.at, zone),
                            style = MaterialTheme.typography.labelLarge.merge(Mono),
                            color = if (past) sig.muted else sig.ai, modifier = Modifier.width(48.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(e.what, style = MaterialTheme.typography.bodyMedium, color = if (past) sig.muted else Color.Unspecified)
                            if (e.why.isNotBlank()) Text(e.why, style = MaterialTheme.typography.bodySmall, color = sig.muted)
                        }
                    }
                }
                Hint("抽查每天 ${com.zongkong.core.boss.Intensity.spotChecks(config.boss, stance)} 次，时间每天不一样、不告诉你：它看一眼最近 2 小时的手机使用（开了截图就看截图），没事不出声，有事才找你。娱乐应用连续 ${com.zongkong.core.boss.Intensity.bingeMinutes(config.boss, stance)} 分钟会直接找你。" + if (config.boss.shots) "截图抽查每小时约 ${com.zongkong.core.boss.Intensity.shotsPerHour(config.boss, stance)} 张（锁屏时攒着，解锁后补）。" else "")
                TextButton(onClick = { nav.navigate("voice/settings") }) { Text("调主动程度 →", color = sig.ai) }
            }

            // ---------- 它怎么判你 ----------
            SectionLabel("它怎么判你")
            Panel(onClick = { nav.navigate("dossier") }, modifier = Modifier.testTag("flow-cred")) {
                Text("现在：信用 $cred · ${stance.label}", style = MaterialTheme.typography.titleSmall, color = stanceColor(stance))
                Text(stance.rule, style = MaterialTheme.typography.bodySmall)
                RULES.forEach { (delta, what) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(delta, style = MaterialTheme.typography.labelLarge.merge(Mono), color = if (delta.startsWith("+")) sig.free else sig.strict, modifier = Modifier.width(48.dp))
                        Text(what, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                Hint("起点 ${Boss.BASE}，只看最近 ${Boss.WINDOW_DAYS} 天。80 以上信任，60–79 正常，40–59 审慎（说什么都要证据），40 以下严格（不给紧急放行、不认口头说法）。点这里看每一笔。")
            }

            // ---------- 常见问题 ----------
            SectionLabel("常见问题")
            FAQ.forEach { (q, a) -> Faq(q, a) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { nav.navigate("gallery") }) { Text("看所有线条动效", color = sig.muted) }
                TextButton(onClick = { nav.navigate("settings/perm") }) { Text("权限是否齐了", color = sig.muted) }
            }
        }
    }
}

@Composable
private fun Faq(q: String, a: String) {
    var open by remember { mutableStateOf(false) }
    val sig = LocalSignals.current
    Panel(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(q, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (open) "−" else "+", style = MaterialTheme.typography.titleMedium, color = sig.muted)
        }
        if (open) Text(a, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class Planned(val at: Long, val what: String, val why: String = "")

/** 今天（北京时间）总控排定的主动动作，按时间排。 */
private fun today(config: com.zongkong.core.Config, work: com.zongkong.core.work.Work, d: com.zongkong.core.boss.Dossier, now: Long, zone: java.time.ZoneId, stance: com.zongkong.core.boss.Stance): List<Planned> {
    val date = DayClock.date(now, zone)
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    fun at(min: Int) = dayStart + min * 60_000L
    val out = mutableListOf<Planned>()
    val hqc = config.hq
    if (hqc.proactive) {
        out += Planned(at(hqc.morningAt), "早上第一条：今天的主线是什么", "没定就让你现在挑一个")
        HqRunner.checkInSlots(com.zongkong.core.boss.Intensity.level(config.boss, stance), config.activeStart, config.activeEnd, date).forEach {
            if (!Hq.quiet(hqc, at(it), zone)) out += Planned(at(it), "问候：挑一件最该问的事问你", "主线到哪了 / 快到点的约定 / 没提炼的收集 / 该验证的假设。2 小时不回，信用 −3")
        }
        out += Planned(at(13 * 60 + 30), "午后：主线还没动就追问", "13:30–17:00 之间，今天计划内的时间还是 0 时")
    }
    work.actions().filter { it.actionStatus == ActionStatus.TODO || it.actionStatus == ActionStatus.RESCHEDULED }.forEach { a ->
        val s = a.plan?.takeIf { it.hasTime }?.let { TimeParse.startMillis(it, zone) } ?: return@forEach
        if (DayClock.date(s, zone) == date) out += Planned(s, "到点提醒：${a.title}", "${Focus.FOLLOW_MINUTES} 分钟后没开始再追一次；45 分钟没任何动静，信用 −2")
    }
    d.checks.filter { !it.done && DayClock.date(it.due, zone) == date }.forEach { out += Planned(it.due, "回访：${it.about.take(40)}", "它自己排的，到点先查证据再找你") }
    d.commitments.filter { it.status == CommitStatus.OPEN && DayClock.date(it.due, zone) == date }.forEach {
        out += Planned(it.due, "约定到点：${it.what.take(40)}", "做到 +4，没做到 −10；过期一天不回应按没做到算")
    }
    if (hqc.enabled) out += Planned(at(Hq.minutesOf(hqc.at)), "总控时间（${hqc.minutes} 分钟）", if (hqc.autoLock) "到点不进就自动锁：只能用总控和白名单" else "到点叫你")
    if (date.dayOfWeek == java.time.DayOfWeek.SUNDAY && config.ai.ready) out += Planned(at(Hq.minutesOf(hqc.at - 60)), "周复盘：看这周的账，改 1–2 条规则", "收紧马上生效，放宽等 24 小时")
    return out.sortedBy { it.at }
}

private val STAGES = listOf(
    "收 · 信息" to "看到的、想到的先扔进收集箱，再提炼成一句“对我有什么用”。",
    "谋 · 思考" to "脉络：想法长成节点，假设要验证、结论要回填。",
    "筹 · 规划" to "周计划（2–3 个方向）、长期目标挂在首页上方、倒推排步骤、估时校准。",
    "行 · 行为" to "事项下的行动：到点提醒 → 开始 → 做完写结果；拦截娱乐、总控时间。",
    "验 · 总控" to "核对你说的和记录：时间线、截图、照片、产出。认、扣、追问、收紧。",
)

private val RULES = listOf(
    "+3" to "说法属实（有依据）",
    "−12" to "说法对不上记录",
    "−4" to "说不清（追问三轮还模糊）",
    "+4 / −10" to "约定做到 / 没做到",
    "+3 / −5" to "总控时间做完 / 提前退出",
    "−3" to "它主动问你，2 小时没回（一天最多扣 4 次）",
    "+1" to "它问你，15 分钟内回了（一天最多 4 次）",
    "−2" to "到点提醒后 45 分钟没开始、没推迟、没说卡住（一天最多 3 次）",
    "+1" to "做完一步、达到完成依据（一天最多 4 次）",
)

private val FAQ = listOf(
    "行动怎么用？做完要怎么标？" to
        "行动 = 事项下面一步具体的事（有时间、有“完成依据”）。在事项页，每条行动下面有“▶ 开始”和“✓ 做完”。\n" +
        "· 开始：计时，可以顺手开专注锁；中途离开记一个断点。\n" +
        "· 做完：写一句结果，选“达到 / 部分达到 / 没达到”完成依据。达到 → 变成“完成”；部分 → “待确认”；没达到 → “卡住”，会问你卡在哪。\n" +
        "结果会回到行动上，同时在“记录”里多一条“结果”，事项的断点和下一步跟着更新；事项页顶上的本周星图会多一颗星，信用 +1。",
    "“记录”是什么？为什么只看到一部分字？" to
        "记录是一件事的档案袋：GPT 交接来的材料和问题、你随手记的念头、每次做完的结果、卡点、断点。上面可以按类型筛。\n" +
        "从 Notion 导入的记录，同步只带回标题和属性；GPT 常把长内容写在 Notion 页面的正文里——点开一条，再点“读 Notion 正文”就能看到全文。",
    "它的声音为什么是机械音？" to
        "没填语音接口时用的是手机自带的“文字转语音”。去 我 → 对讲、语音、桌面宠物：选“硅基流动”（国内直连，中文自然）或 OpenAI，贴上密钥，点“保存并试听”。不想花钱就点“换手机朗读引擎”。",
    "求职、考试这种长期的事放哪？" to
        "首页最上面的“＋ 远方”。挂上以后是一弯月亮，从新月长到满月 = 时间走了多少，剩 7 天变色。和总控说一句，它会关联事项，周计划里提醒你给它留一步。",
    "不理它会怎样？" to
        "以前不会怎样，现在会：主动问你 2 小时不回 −3，提醒了 45 分钟没动静 −2。信用掉到 60 以下它会要证据，40 以下进入严格。回它一句就不算“没回”。",
    "桌面宠物怎么换？" to
        "我 → 对讲、语音、桌面宠物 → 形象：夜枭（猫头鹰）、团子（橘猫）、小葵（向日葵）。点一下马上换。",
)
