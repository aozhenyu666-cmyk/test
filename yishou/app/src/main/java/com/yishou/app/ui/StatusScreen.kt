package com.yishou.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.data.FaceCount
import com.yishou.app.data.Round
import com.yishou.app.llm.Faces
import com.yishou.app.look.ScreenLookService
import com.yishou.app.stats.DayStats
import com.yishou.app.window.WindowClock
import java.time.ZoneId

/** 盘点：今天和近 7 天，各类请求的 token、失败、耗时，以及拦截、放行、轮次。 */
@Composable
fun StatusScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val version by app.stats.version.collectAsStateWithLifecycle()
    var resumeKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeKey++ }
    val days = remember(version, resumeKey) { app.stats.lastDays(7) }
    val today = days.first()
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val llm by app.settings.llm.collectAsStateWithLifecycle()
    val vision by app.settings.vision.collectAsStateWithLifecycle()
    val lookOn by ScreenLookService.running.collectAsStateWithLifecycle()
    val rounds by produceState(emptyList<Round>(), version, resumeKey) {
        val zone = ZoneId.systemDefault()
        val start = WindowClock.startOfDay(WindowClock.today(System.currentTimeMillis(), zone), zone)
        value = app.database.dao().roundsBetween(start, System.currentTimeMillis() + 1)
    }

    val faces by produceState(emptyList<FaceCount>(), version, resumeKey) {
        value = app.database.dao().faceCounts(0)
    }

    Scaffold(topBar = { BackTopBar("盘点", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Block("今天的棋") {
                val effective = rounds.count { it.effective }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Big("${rounds.size}", "手")
                    Big("$effective", "有效")
                    Big(if (rounds.isEmpty()) "–" else "${effective * 100 / rounds.size}%", "有效率")
                }
                Line("思考页弹出", "${today.event(DayStats.GATE_SHOWN)} 次")
                Line("答对放行", "${today.event(DayStats.GATE_PASS)} 次")
                Line("离线放行", "${today.event(DayStats.OFFLINE_PASS)} / ${prefs.offlineDailyLimit} 次")
                Line("窗口里被拉回", "${today.event(DayStats.WINDOW_BLOCK)} 次")
                Line("陪练看屏", "${today.event(DayStats.LOOK)} 次")
            }

            Block("六面练得怎样") {
                FacePanel(faces)
            }

            Block("今天的用量") {
                val t = today.total
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Big(formatTokens(t.tokens), "token")
                    Big("${t.calls}", "次请求")
                    Big("${t.fails}", "次失败")
                }
                if (today.kinds.isEmpty()) {
                    Text("今天还没有请求。", style = MaterialTheme.typography.bodySmall)
                }
                today.kinds.entries.sortedByDescending { it.value.tokens }.forEach { (kind, k) ->
                    Line(
                        kind,
                        "${k.calls} 次，${formatTokens(k.tokens)} token，平均 ${"%.1f".format(k.avgMillis / 1000.0)} 秒" +
                            if (k.fails > 0) "，失败 ${k.fails} 次" else "",
                    )
                }
            }

            Block("近 7 天") {
                days.forEach { d ->
                    val t = d.total
                    Line(d.date.substring(5), "${t.calls} 次请求，${formatTokens(t.tokens)} token，拦下 ${d.event(DayStats.GATE_SHOWN) + d.event(DayStats.WINDOW_BLOCK)} 次")
                }
            }

            Block("上下文会不会越来越长") {
                val judge = today.kinds["判定"] ?: days.mapNotNull { it.kinds["判定"] }.firstOrNull()
                Text(
                    "不会。每次请求只带：任务、断点（已知 / 卡点 / 下一问）、最近 5 条规则和这一轮的原话，" +
                        "不带全部聊天记录。断点就是压缩过的上下文，每轮结束时更新。",
                    style = MaterialTheme.typography.bodySmall,
                )
                judge?.let { Line("一次判定请求平均", "${it.avgRequestChars} 字") }
            }

            Block("运行状态") {
                Line("陪练模型", llm.model.ifBlank { "未配置" })
                Line("识图模型", vision.model.ifBlank { "未配置" })
                Line("入口思考页", if (PermissionStatus.accessibility(context)) "已开启" else "未开启")
                Line("看屏", if (lookOn) "已开启" else "未开启")
                Line("开局规则", if (prefs.windowStrict) "开（学习应用 ${prefs.windowAllowed.size} 个）" else "关")
            }
        }
    }
}

/** 六面各练了多少、有效率多少；练过 3 手以上里有效率最低的一面，就是眼下最该练的。 */
@Composable
private fun FacePanel(counts: List<FaceCount>) {
    val byFace = counts.associateBy { it.face }
    val max = (1..6).maxOf { byFace[it]?.total ?: 0 }.coerceAtLeast(1)
    Faces.ALL.forEach { f ->
        val c = byFace[f.number]
        val total = c?.total ?: 0
        val eff = c?.effective ?: 0
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            DieFace(f.number, 22.dp)
            Text(f.short, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).width(40.dp))
            Box(Modifier.weight(1f).height(10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(5.dp)),
                )
                if (total > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth(total.toFloat() / max)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), RoundedCornerShape(5.dp)),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(eff.toFloat() / max)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(5.dp)),
                    )
                }
            }
            Text(
                if (total == 0) "没练过" else "$eff/$total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).width(48.dp),
            )
        }
    }
    val weakest = counts.filter { it.face in 1..6 && it.total >= 3 }.minByOrNull { it.effective.toFloat() / it.total }
    val untouched = Faces.ALL.filter { (byFace[it.number]?.total ?: 0) == 0 }
    Text(
        when {
            weakest != null -> "最该练的是${Faces.of(weakest.face)!!.name}：有效率最低。下次可以掷骰时专门找它，或点“看个示范”。"
            untouched.isNotEmpty() -> "还没练过：${untouched.joinToString("、") { it.short }}。掷骰换一手试试。"
            else -> "六面都练过了，继续让陪练按眼前的题选面。"
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Big(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}
