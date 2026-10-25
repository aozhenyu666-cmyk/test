package cn.companion.pet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.companion.pet.PetApp
import cn.companion.pet.data.Stats
import cn.companion.pet.ui.components.EmptyNote
import cn.companion.pet.ui.components.PageHeader
import cn.companion.pet.ui.components.Panel
import cn.companion.pet.ui.components.Rule
import cn.companion.pet.ui.theme.CompanionSpacing
import cn.companion.pet.ui.theme.LocalCompanionColors

@Composable
fun StatsTab(resumeTick: Int) {
    val stats = PetApp.instance.stats
    val colors = LocalCompanionColors.current
    val days = remember(resumeTick) { stats.days() }
    val kinds = listOf(
        Stats.Kind.SPOKE, Stats.Kind.REPLIED, Stats.Kind.IGNORED,
        Stats.Kind.GEMINI_TEXT, Stats.Kind.GEMINI_LIVE, Stats.Kind.SESSIONS,
        Stats.Kind.PULLBACKS, Stats.Kind.API_CALLS,
    )
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = CompanionSpacing.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader("统计", "最近 " + (if (days.isEmpty()) 0 else days.size) + " 天。没有可靠 token/定价，不算精确费用。")
        if (days.isEmpty()) {
            EmptyNote("还没有数据。")
            return@Column
        }
        val recent = days.takeLast(7)
        Panel {
            Text("最近七天", style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text("橙：开口　墨：回应", style = MaterialTheme.typography.labelSmall, color = colors.ink2,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            val max = recent.maxOfOrNull { maxOf(stats.get(Stats.Kind.SPOKE, it), stats.get(Stats.Kind.REPLIED, it)) }?.coerceAtLeast(1) ?: 1
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val slot = size.width / recent.size
                val barW = slot * 0.22f
                recent.forEachIndexed { i, day ->
                    val x = slot * i + slot * 0.28f
                    val spoke = stats.get(Stats.Kind.SPOKE, day)
                    val replied = stats.get(Stats.Kind.REPLIED, day)
                    val h1 = size.height * spoke / max
                    val h2 = size.height * replied / max
                    drawRect(colors.accent.copy(alpha = 0.85f), Offset(x, size.height - h1), Size(barW, h1))
                    drawRect(colors.ink.copy(alpha = 0.75f), Offset(x + barW + 4f, size.height - h2), Size(barW, h2))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                recent.forEach { d -> Text(d.toString().substring(5), style = MaterialTheme.typography.labelSmall, color = colors.ink2) }
            }
        }
        Text("回应率低：先改人设和钩子。回应了但没拉住：再调开口频率。", style = MaterialTheme.typography.bodySmall, color = colors.ink2)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                Cell("日期", 92, bold = true)
                kinds.forEach { Cell(it.label, 64, bold = true) }
                Cell("回应率", 64, bold = true)
            }
            Rule()
            days.forEach { d ->
                Row {
                    Cell(d.toString().substring(5), 92)
                    kinds.forEach { Cell(stats.get(it, d).toString(), 64) }
                    val spoke = stats.get(Stats.Kind.SPOKE, d)
                    val rate = (stats.get(Stats.Kind.REPLIED, d) * 100 / spoke).toString() + "%"
                    Cell(if (spoke == 0) "—" else rate, 64)
                }
                Rule()
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: Int, bold: Boolean = false) {
    Text(
        text,
        Modifier.width(width.dp).padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (bold) LocalCompanionColors.current.ink else LocalCompanionColors.current.ink2,
        fontWeight = if (bold) FontWeight.Bold else null,
        textAlign = TextAlign.Center,
    )
}
