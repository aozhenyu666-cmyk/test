package com.moling.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moling.app.ui.art.InkMountain
import com.moling.app.ui.art.WashGold
import com.moling.app.ui.art.WashViolet
import com.moling.app.ui.art.inkEnter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import com.moling.core.atlas.AtlasMap
import com.moling.core.atlas.Island
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 体系图沙盘（Z7 / B2）：每个主题是一座岛。
 *   淡墨圈 = 这个主题；圈里的点 = 论断（亮的实心墨点 + WashGold 光晕，雾里的淡墨空心点）；
 *   小黑洞 = 缺口（深墨实心圈 + 外面一圈虚线）。
 * 岛下面一行 AtlasMap.line(island)，再一根细进度线（island.progress）。
 * 静态画，不做动画（省电）；不需要力导向，按论断数排成两列错开的群岛。
 * 只读：点岛只回调 onOpenTopic(topicId)，切主题由 AtlasScreen 走原来的入口。
 */

/** 沙盘：两列错开的群岛；没有主题时是一座山 + 一句话。 */
@Composable
fun AtlasMapView(
    islands: List<Island>,
    onOpenTopic: (String) -> Unit,
    edges: List<com.moling.core.atlas.Edge> = emptyList(),
) {
    if (islands.isEmpty()) {
        // 空态：还没有任何主题，一座山 + 一句话，居中放着。
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                InkMountain(Modifier.size(120.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "还没有体系图。在对话里说‘记下’，或者分享一段文字进来。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 每行两座岛；单数行整体往下沉一点，看起来像群岛而不是表格。
        islands.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (rowIndex % 2 == 1) 28.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                pair.forEachIndexed { pairIndex, island ->
                    val ids = island.dots.map { it.id }.toSet()
                    IslandCard(
                        island = island,
                        modifier = Modifier.weight(1f).inkEnter(order = rowIndex * 2 + pairIndex, distance = 14.dp),
                        onOpenTopic = onOpenTopic,
                        edges = edges.filter { it.from in ids && it.to in ids },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 一座岛：圈（点 + 黑洞）+ AtlasMap.line + 细进度线；整块可点。 */
@Composable
private fun IslandCard(
    island: Island,
    modifier: Modifier,
    onOpenTopic: (String) -> Unit,
    edges: List<com.moling.core.atlas.Edge> = emptyList(),
) {
    // 岛的大小按论断数长：0 条半径 26dp，10 条及以上 56dp。
    val radiusDp = 26f + 30f * (island.dots.size.coerceIn(0, 10) / 10f)
    Column(
        modifier = modifier
            .clickable { onOpenTopic(island.topicId) }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 画布比圈大一圈，给缺口外面的虚线留位置。
        IslandCanvas(island, edges, Modifier.size((radiusDp * 2f + 24f).dp))
        Spacer(Modifier.height(8.dp))
        Text(
            AtlasMap.line(island),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        ProgressLine(
            progress = island.progress,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(3.dp),
        )
    }
}

/** 岛的样子：淡墨圈、论断点、缺口黑洞。全部按 [island] 静态画。 */
@Composable
private fun IslandCanvas(island: Island, edges: List<com.moling.core.atlas.Edge>, modifier: Modifier) {
    val ring = Ink.copy(alpha = 0.24f)
    val march by rememberInfiniteTransition(label = "edgeMarch").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "edgeMarchPhase",
    )
    val faintDot = Ink.copy(alpha = 0.32f)
    val litDot = Ink.copy(alpha = 0.88f)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f - 12.dp.toPx()
        // 淡墨圈：外面一圈细线，里面一层很淡的墨色，像水晕。
        drawCircle(Ink.copy(alpha = 0.05f), r, c)
        drawCircle(ring, r, c, style = Stroke(0.9.dp.toPx()))

        // 论断：按黄金角撒在圈里，条数越多铺得越开。
        val n = island.dots.size
        val dots = island.dots.mapIndexed { i, _ ->
            val a = i * 2.399963f
            val d = if (n <= 1) 0f else r * 0.62f * sqrt((i + 0.5f) / n.toFloat())
            c + Offset(cos(a) * d, sin(a) * d)
        }
        // E6：她推出来的线（note 以「她连的：」开头）用虚线连在两颗点之间。
        val ids = island.dots.map { it.id }
        edges.filter { it.note.startsWith("她连的：") }.forEach { e ->
            val from = dots[ids.indexOf(e.from)]
            val to = dots[ids.indexOf(e.to)]
            drawLine(
                WashViolet.copy(alpha = 0.7f),
                from,
                to,
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), phase = -march * 6.dp.toPx()),
            )
        }
        island.dots.forEachIndexed { i, dot ->
            val p = dots[i]
            if (dot.lit) {
                // 亮的：一层 WashGold 光晕 + 实心墨点。
                drawCircle(WashGold.copy(alpha = 0.30f), 6.dp.toPx(), p)
                drawCircle(litDot, 2.6.dp.toPx(), p)
            } else {
                // 雾里的：淡墨空心点。
                drawCircle(faintDot, 2.dp.toPx(), p, style = Stroke(0.9.dp.toPx()))
            }
        }

        // 缺口：最多画 8 个，免得挤成一团；真实个数写在下面的 AtlasMap.line 里。
        val gaps = island.gaps.take(8)
        gaps.forEachIndexed { j, _ ->
            val a = -PI.toFloat() / 2f + (j / gaps.size.toFloat()) * 2f * PI.toFloat()
            val p = c + Offset(cos(a), sin(a)) * (r * 0.86f)
            drawCircle(Ink.copy(alpha = 0.9f), 5.dp.toPx(), p)
            drawCircle(
                Ink.copy(alpha = 0.4f),
                8.5.dp.toPx(),
                p,
                style = Stroke(
                    width = 0.8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }
    }
}

/** 细进度线：底下一根淡墨线，前面用印泥色画出亮了几成。 */
@Composable
private fun ProgressLine(progress: Float, modifier: Modifier) {
    val p = progress.coerceIn(0f, 1f)
    Canvas(modifier) {
        val y = size.height / 2f
        val stroke = 1.2.dp.toPx()
        drawLine(Ink.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), stroke, cap = StrokeCap.Round)
        if (p > 0f) {
            drawLine(Seal.copy(alpha = 0.75f), Offset(0f, y), Offset(size.width * p, y), stroke, cap = StrokeCap.Round)
        }
        // 起点一个小墨点当刻度。
        drawCircle(Ink.copy(alpha = 0.45f), 1.6.dp.toPx(), Offset(0f, y))
    }
}
