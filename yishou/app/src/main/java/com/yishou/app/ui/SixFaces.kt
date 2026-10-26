package com.yishou.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yishou.app.data.DrillRow
import com.yishou.app.data.Material
import com.yishou.app.llm.Faces
import com.yishou.app.think.Classics
import com.yishou.app.think.DrillStats
import com.yishou.app.think.Lens
import com.yishou.app.think.Recite

/**
 * 六骰子：六种思考动作并排，选一面（或者掷一下），下面列出这一面能练的几种。
 * 点一种直接开练：素材用你展开的那段，没有就用教练备好的新素材。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SixFaces(face: Int, drills: List<DrillRow>, due: Int, onFace: (Int) -> Unit, onLens: (Lens) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("六面", style = MaterialTheme.typography.titleMedium)
                Text("选一面，或者掷一下", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DiceRoller(enabled = true, shake = false, onRolled = onFace, size = 40.dp)
        }
        // 3 × 2 的骰面
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (col in 0 until 3) {
                    val n = row * 3 + col + 1
                    FaceTile(n, selected = n == face, avg = DrillStats.face(drills, n), onClick = { onFace(n) }, modifier = Modifier.weight(1f))
                }
            }
        }
        val f = Faces.of(face)
        if (f != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${f.number} · ${f.name}", style = MaterialTheme.typography.titleSmall)
                    Text("最小动作：${f.action}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Lens.ofFace(face).forEach { lens ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onLens(lens) },
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(lens.label, style = MaterialTheme.typography.titleMedium)
                                    Text(lens.short, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (lens == Lens.RECITE && due > 0) {
                                    Text("$due 段到期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceTile(n: Int, selected: Boolean, avg: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val f = Faces.of(n) ?: return
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer, label = "bg")
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, label = "scale")
    Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = modifier.scale(scale).clickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DieFace(n, 26.dp)
            Text(f.short, style = MaterialTheme.typography.labelLarge)
            Text(avg?.let { "$it 分" } ?: "—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 背什么：到期该再背的、展开的那段素材切成的小段、最近的素材、一手自带的古诗文。
 * “看着骨架背”会在复述时给每句开头两个字。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReciteChooser(
    due: List<Recite.Due>,
    material: Material?,
    recent: List<Material>,
    onPick: (Material?, String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var skeleton by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("背哪一段") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("先看一会儿，时间到原文就收起来，然后复述。逐字对照在手机上算，不用模型。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !skeleton, onClick = { skeleton = false }, label = { Text("全收起") })
                    FilterChip(selected = skeleton, onClick = { skeleton = true }, label = { Text("留骨架提示") })
                }
                if (due.isNotEmpty()) {
                    Section("到期该再背的")
                    due.take(5).forEach { d -> Pick(d.title, d.text, "上次 ${d.lastScore} 分") { onPick(null, d.title, d.text, skeleton) } }
                }
                if (material != null) {
                    Section("展开的这段：${material.title}")
                    Recite.chunks(material.text).take(6).forEachIndexed { i, c -> Pick("第 ${i + 1} 小段", c, null) { onPick(material, "${material.title} · ${i + 1}", c, skeleton) } }
                }
                val others = recent.filter { it.id != material?.id }
                if (others.isNotEmpty()) {
                    Section("最近的素材（第一小段）")
                    others.forEach { m ->
                        val c = Recite.chunks(m.text).firstOrNull() ?: return@forEach
                        Pick(m.title, c, null) { onPick(m, m.title, c, skeleton) }
                    }
                }
                Section("自带的古诗文")
                Classics.ALL.forEach { p -> Pick(p.title, p.text, null) { onPick(null, p.title, p.text, skeleton) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("算了") } },
    )
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun Pick(title: String, text: String, note: String?, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
