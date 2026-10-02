package com.behaviordept.app.training

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.behaviordept.app.ui.theme.Paper
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

/** 趋势折线（Vico）：纵轴是 0–100%，横轴是每周或每次考试。 */
@Composable
fun TrendChart(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    val p = Paper.colors
    val model = remember(points) {
        CartesianChartModel(
            LineCartesianLayerModel.build {
                series(x = points.indices.toList(), y = points.map { it.value * 100 })
            },
        )
    }
    val labels = points.map { it.label }
    val xFormatter = remember(labels) {
        CartesianValueFormatter { _, value, _ -> labels.getOrNull(value.roundToInt()) ?: "" }
    }
    val yFormatter = remember { CartesianValueFormatter { _, value, _ -> "${value.roundToInt()}%" } }
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
            rangeProvider = remember(points.size) {
                CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = (points.size - 1).coerceAtLeast(1).toDouble(), minY = 0.0, maxY = 100.0)
            },
        ),
        startAxis = VerticalAxis.rememberStart(
            label = rememberAxisLabelComponent(color = p.ink2),
            line = null,
            tick = null,
            guideline = rememberAxisGuidelineComponent(fill = fill(p.grid)),
            valueFormatter = yFormatter,
            itemPlacer = remember { VerticalAxis.ItemPlacer.step({ 25.0 }) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberAxisLabelComponent(color = p.ink2),
            line = null,
            tick = null,
            guideline = null,
            valueFormatter = xFormatter,
        ),
    )
    CartesianChartHost(
        chart = chart,
        model = model,
        modifier = modifier.fillMaxWidth().height(180.dp),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
    )
}
