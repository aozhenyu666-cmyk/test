package com.zongkong.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/** 动效画廊：总控里所有线条母题，点一下雷霆会劈。 */
@Composable
fun GalleryScreen(nav: NavHostController) {
    val sig = LocalSignals.current
    val (strike, hit) = rememberStrike()
    Column(Modifier.fillMaxSize()) {
        BackBar("线条动效", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Hint("细墨线、留白、慢。每个部门、每种状态一个母题。系统里关了动画就静止。")
            MOTIFS.forEach { m ->
                Panel(onClick = if (m.name == "雷霆") hit else null) {
                    Box(Modifier.fillMaxWidth().height(110.dp)) {
                        when (m.name) {
                            "潮汐" -> TideLines(0.55f, sig.info, Modifier.fillMaxSize())
                            "叶脉" -> LeafVein(1f, sig.think, Modifier.fillMaxSize())
                            "几何矩阵" -> Lattice(0.5f, sig.plan, Modifier.fillMaxSize())
                            "雷霆" -> Lightning(strike, sig.strict, Modifier.fillMaxSize(), ambient = strike == 0)
                            "风暴漩涡" -> Vortex(sig.ai, Modifier.fillMaxSize(), intensity = 1.5f)
                            "涟漪" -> Ripple(true, sig.ai, Modifier.fillMaxSize())
                            "年轮" -> TreeRings(9, 0.7f, sig.think, Modifier.fillMaxSize())
                            "流线" -> FlowLines(sig.hq, Modifier.fillMaxSize())
                            "月相" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf(0.15f, 0.4f, 0.65f, 0.95f).forEach { MoonPhase(it, sig.plan, Modifier.width(44.dp).fillMaxHeight()) }
                            }
                            "星图" -> StarMap(listOf(4, 2, 5), sig.think, Modifier.fillMaxSize())
                            "轨道" -> Orbit(listOf("收", "谋", "筹", "行", "验"), sig.ai, Modifier.fillMaxSize(), active = 3)
                            "漩涡 → 秩序" -> { val o by rememberInfiniteTransition(label = "order").animateFloat(0f, 1f, infiniteRepeatable(tween(4000), RepeatMode.Reverse), label = "order"); Vortex(sig.strict, Modifier.fillMaxSize(), order = o) }
                            else -> BranchGrowth(1f, sig.ai, Modifier.fillMaxSize())
                        }
                    }
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Text(m.what, style = MaterialTheme.typography.bodySmall)
                    Text("用在：" + m.where, style = MaterialTheme.typography.labelMedium, color = sig.muted)
                }
            }
        }
    }
}
