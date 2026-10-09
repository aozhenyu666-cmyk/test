package com.yishou.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yishou.app.llm.Faces
import com.yishou.app.ui.theme.reducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.random.Random

/** 中国骰子的老规矩：一点和四点是红的。 */
private val PipRed = Color(0xFFB3261E)

/** 一个骰面：圆角方块加点数。 */
@Composable
fun DieFace(face: Int, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val body = scheme.surface
    val edge = scheme.onSurface.copy(alpha = 0.55f)
    val pip = scheme.onSurface
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(body, size = Size(s, s), cornerRadius = CornerRadius(s * 0.2f))
        drawRoundRect(edge, size = Size(s, s), cornerRadius = CornerRadius(s * 0.2f), style = Stroke(width = s * 0.05f))
        val c = s / 2
        val o = s * 0.26f
        val r = if (face == 1) s * 0.13f else s * 0.085f
        val color = if (face == 1 || face == 4) PipRed else pip
        val points = when (face) {
            1 -> listOf(Offset(c, c))
            2 -> listOf(Offset(c - o, c - o), Offset(c + o, c + o))
            3 -> listOf(Offset(c - o, c - o), Offset(c, c), Offset(c + o, c + o))
            4 -> listOf(Offset(c - o, c - o), Offset(c + o, c - o), Offset(c - o, c + o), Offset(c + o, c + o))
            5 -> listOf(Offset(c - o, c - o), Offset(c + o, c - o), Offset(c, c), Offset(c - o, c + o), Offset(c + o, c + o))
            else -> listOf(
                Offset(c - o, c - o), Offset(c + o, c - o), Offset(c - o, c), Offset(c + o, c), Offset(c - o, c + o), Offset(c + o, c + o),
            )
        }
        points.forEach { drawCircle(color, r, it) }
    }
}

/**
 * 掷骰：点一下，或者摇一摇手机。骰子翻滚约 0.8 秒后停在某一面，回调 onRolled。
 * 系统关了动画时直接出结果。
 */
@Composable
fun DiceRoller(
    enabled: Boolean,
    shake: Boolean,
    onRolled: (Int) -> Unit,
    size: Dp = 44.dp,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val still = reducedMotion()
    var shown by rememberSaveable { mutableIntStateOf(Random.nextInt(1, 7)) }
    var rolling by remember { mutableStateOf(false) }
    val spin = remember { Animatable(0f) }
    val lift = remember { Animatable(1f) }
    val latestOnRolled by rememberUpdatedState(onRolled)
    val canRoll by rememberUpdatedState(enabled && !rolling)

    fun roll() {
        if (!canRoll) return
        rolling = true
        scope.launch {
            val result = Random.nextInt(1, 7)
            if (!still) {
                launch { lift.animateTo(1.25f, tween(180)); lift.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 300f)) }
                launch { spin.animateTo(spin.value + 720f, tween(760, easing = FastOutSlowInEasing)) }
                repeat(9) {
                    shown = Random.nextInt(1, 7)
                    delay(70L + it * 8L)
                }
            }
            shown = result
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            rolling = false
            latestOnRolled(result)
        }
    }

    if (shake) ShakeDetector { roll() }

    DieFace(
        shown,
        size,
        Modifier
            .graphicsLayer(rotationZ = spin.value, scaleX = lift.value, scaleY = lift.value, alpha = if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled) { roll() },
    )
}

/** 摇一摇：加速度超过约 2.5g 算一次，1.5 秒内只算一次。不需要任何权限。 */
@Composable
private fun ShakeDetector(onShake: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onShake)
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                if (g > 2.5f && now - last > 1_500) {
                    last = now
                    latest()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
}

/**
 * 这一手练哪一面。点开看最小动作和怎样算数——让“我在练什么”看得见。
 */
@Composable
fun FaceChip(face: Int) {
    val f = Faces.of(face) ?: return
    var open by rememberSaveable(face) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .animateContentSize()
            .clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DieFace(face, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("这一手练${f.name}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            }
            if (open) {
                Text("你要做的：${f.action}", style = MaterialTheme.typography.bodySmall)
                Text("怎样算数：${f.check}", style = MaterialTheme.typography.bodySmall)
                Text("标准句式：${f.frame}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}
