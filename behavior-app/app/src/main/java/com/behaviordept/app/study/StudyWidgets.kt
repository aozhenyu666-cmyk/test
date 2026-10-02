package com.behaviordept.app.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.behaviordept.app.data.Rating
import com.behaviordept.app.data.Review
import com.behaviordept.app.ui.components.TianZiGeCell
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.ui.theme.SerifSC
import com.behaviordept.app.util.Time
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.continuous
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlin.math.roundToInt

/** 步骤格：方框数字，已完成红框、当前墨水实底、未开始灰框。 */
@Composable
fun StepGrid(current: UnitStep, modifier: Modifier = Modifier) {
    val p = Paper.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        UnitStep.steps.forEach { step ->
            val done = current == UnitStep.DONE || step.number < current.number
            val isCurrent = step == current
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                val box = Modifier.size(36.dp)
                Box(
                    when {
                        isCurrent -> box.background(p.ink)
                        done -> box.border(1.5.dp, p.red)
                        else -> box.border(1.dp, p.divider)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        step.number.toString(),
                        fontFamily = SerifSC,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            isCurrent -> p.page
                            done -> p.red
                            else -> p.ink2
                        },
                    )
                }
                Text(
                    step.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) p.ink else p.ink2,
                    modifier = Modifier.height(20.dp),
                )
            }
        }
    }
}

/**
 * 间隔时间线：每次自测是一个格，标 ✓ △ ✗；下次自测的位置高亮；后面几级间隔用淡格提示。
 */
@Composable
fun IntervalTimeline(finished: List<Review>, nextReviewAt: Long?, level: Int, modifier: Modifier = Modifier) {
    val p = Paper.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        finished.forEach { r ->
            TimelineCell(label = "${r.intervalDays}天", sub = Time.md(r.finishedAt ?: r.createdAt)) {
                TianZiGeCell(done = true, mark = Rating.mark(r.rating), size = 40.dp)
            }
        }
        if (nextReviewAt != null) {
            TimelineCell(label = "下次", sub = Time.md(nextReviewAt), strong = true) {
                TianZiGeCell(done = false, highlight = true, size = 40.dp)
            }
            val upcoming = ((level + 1)..Spacing.INTERVALS.lastIndex).map { Spacing.INTERVALS[it] }
            upcoming.forEach { d ->
                TimelineCell(label = "+${d}天", sub = "", faint = true) {
                    Box(Modifier.size(40.dp).border(1.dp, p.grid))
                }
            }
        }
    }
}

@Composable
private fun TimelineCell(
    label: String,
    sub: String,
    strong: Boolean = false,
    faint: Boolean = false,
    cell: @Composable () -> Unit,
) {
    val p = Paper.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        cell()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                strong -> p.ink
                faint -> p.ink2.copy(alpha = 0.5f)
                else -> p.ink2
            },
        )
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = p.ink2)
    }
}

/** 保持率：按间隔天数统计“记得”的占比。返回 (间隔下标, 百分比)。 */
fun retentionPoints(reviews: List<Review>): List<Pair<Int, Double>> =
    Spacing.INTERVALS.mapIndexedNotNull { index, days ->
        val rated = reviews.filter { it.rating != null && it.intervalDays == days }
        if (rated.isEmpty()) null
        else index to rated.count { it.rating == Rating.REMEMBER } * 100.0 / rated.size
    }

/** 保持率曲线：横轴间隔天数，纵轴“记得”占比。 */
@Composable
fun RetentionChart(points: List<Pair<Int, Double>>, modifier: Modifier = Modifier) {
    val p = Paper.colors
    val model = remember(points) {
        CartesianChartModel(
            LineCartesianLayerModel.build {
                series(x = points.map { it.first }, y = points.map { it.second })
            },
        )
    }
    val dayFormatter = remember {
        CartesianValueFormatter { _, value, _ ->
            val i = value.roundToInt().coerceIn(0, Spacing.INTERVALS.lastIndex)
            "${Spacing.INTERVALS[i]}天"
        }
    }
    val pctFormatter = remember { CartesianValueFormatter { _, value, _ -> "${value.roundToInt()}%" } }
    val line = LineCartesianLayer.rememberLine(
        fill = remember(p.red) { LineCartesianLayer.LineFill.single(fill(p.red)) },
        stroke = LineCartesianLayer.LineStroke.continuous(thickness = 2.dp),
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.point(rememberShapeComponent(fill(p.red), CorneredShape.Pill), 8.dp),
        ),
    )
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(line),
            rangeProvider = remember {
                CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = Spacing.INTERVALS.lastIndex.toDouble(), minY = 0.0, maxY = 100.0)
            },
        ),
        startAxis = VerticalAxis.rememberStart(
            label = rememberAxisLabelComponent(color = p.ink2),
            line = null,
            tick = null,
            guideline = rememberAxisGuidelineComponent(fill = fill(p.grid)),
            valueFormatter = pctFormatter,
            itemPlacer = remember { VerticalAxis.ItemPlacer.step({ 25.0 }) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberAxisLabelComponent(color = p.ink2),
            line = null,
            tick = null,
            guideline = null,
            valueFormatter = dayFormatter,
        ),
    )
    CartesianChartHost(
        chart = chart,
        model = model,
        modifier = modifier.fillMaxWidth().height(190.dp),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
    )
}
