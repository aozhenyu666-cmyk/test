package com.yishou.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yishou.app.coach.Warmup
import com.yishou.app.system.Haptics
import com.yishou.app.ui.theme.reducedMotion
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 热身：像运动员先用轻量的同一个动作把身体活动开。
 * 第一段一分钟呼吸（吸 4 秒、呼 6 秒，圈跟着胀缩，手机跟着轻震），第二段五步心算接龙。
 * ready 表示教练已经把这一组排好了。
 */
@Composable
fun WarmupPanel(ready: Boolean, onDone: (String?) -> Unit) {
    var phase by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("热身", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        when (phase) {
            0 -> Breathing(onDone = { phase = 1 })
            else -> ChainDrill(ready = ready, onDone = onDone)
        }
        TextButton(onClick = { if (phase == 0) phase = 1 else onDone(null) }) { Text(if (phase == 0) "跳过呼吸" else "跳过热身") }
        if (!ready) Text("教练在排这一组……", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Breathing(onDone: () -> Unit) {
    val context = LocalContext.current
    val still = reducedMotion()
    val scale = remember { Animatable(0.45f) }
    var inhale by remember { mutableStateOf(true) }
    var n by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        for (i in 1..Warmup.BREATHS) {
            n = i
            inhale = true
            Haptics.play(context, Haptics.Kind.INHALE)
            if (still) delay(Warmup.INHALE_MS) else scale.animateTo(1f, tween(Warmup.INHALE_MS.toInt(), easing = LinearEasing))
            inhale = false
            Haptics.play(context, Haptics.Kind.EXHALE)
            if (still) delay(Warmup.EXHALE_MS) else scale.animateTo(0.45f, tween(Warmup.EXHALE_MS.toInt(), easing = LinearEasing))
        }
        onDone()
    }
    val ink = MaterialTheme.colorScheme.onBackground
    val gold = MaterialTheme.colorScheme.tertiary
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            val r = size.minDimension / 2
            for (i in 1..3) drawCircle(ink.copy(alpha = 0.07f), r * i / 3f, style = Stroke(1f))
            drawCircle(gold.copy(alpha = 0.18f), r * scale.value)
            drawCircle(gold, r * scale.value, style = Stroke(3f))
        }
        Text(if (inhale) "吸气" else "呼气", style = MaterialTheme.typography.headlineSmall)
    }
    Text("第 $n / ${Warmup.BREATHS} 次。吸 4 秒，呼 6 秒，跟着圈走。", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ChainDrill(ready: Boolean, onDone: (String?) -> Unit) {
    val chain = remember { Warmup.chain(Random(System.currentTimeMillis())) }
    var i by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var right by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf<String?>(null) }
    val started = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val done = i >= chain.steps.size
    val value = if (i == 0) chain.start else chain.steps[i - 1].result

    Text("心算接龙：每一步都用上一步的结果。", style = MaterialTheme.typography.bodyMedium)
    if (!done) {
        Text("$value  ${chain.steps[i].op}  =  ?", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = input,
            onValueChange = { v -> input = v.filter { it.isDigit() }.take(3) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("第 ${i + 1} / ${chain.steps.size} 步") },
            modifier = Modifier.width(200.dp),
        )
        Button(onClick = {
            val ok = input.toIntOrNull() == chain.steps[i].result
            if (ok) right++
            last = if (ok) "对" else "是 ${chain.steps[i].result}，从它接着来"
            input = ""
            i++
        }, enabled = input.isNotEmpty()) { Text("接上") }
        last?.let { Text(it, color = if (it == "对") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error) }
    } else {
        val seconds = (System.currentTimeMillis() - started.longValue) / 1000
        val score = Warmup.score(right, chain.steps.size, seconds)
        Text(score, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Button(onClick = { onDone(score) }, enabled = ready, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (ready) "开始这一组" else "教练还在排……")
        }
    }
}

