@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yishou.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.speech.Heard
import com.yishou.app.speech.ListenPhase
import com.yishou.app.ui.theme.reducedMotion
import kotlinx.coroutines.launch

/**
 * 声音的样子：一颗子落进水里的涟漪。
 * 陪练说话时，金色的环从子上慢慢荡开；在听你说时，环跟着你的音量起伏；
 * 在听写时，环收拢成一圈慢慢转的弧。什么都不做时只剩几圈很淡的静环。
 */
@Composable
fun VoiceRings(level: Float, phase: ListenPhase, speaking: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val still = reducedMotion()
    val moving = !still && (speaking || phase != ListenPhase.IDLE)
    val t = if (moving) {
        rememberInfiniteTransition(label = "rings").animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (speaking) 2_400 else 1_600, easing = LinearEasing), RepeatMode.Restart),
            label = "t",
        ).value
    } else 0f
    // 音量平滑一下，不让环抖
    val smooth = remember { Animatable(0f) }
    LaunchedEffect(level) { smooth.animateTo(level, tween(120)) }
    val ink = scheme.onBackground
    val gold = scheme.tertiary
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2
        val stone = maxR * 0.16f
        val rings = 7
        when {
            phase == ListenPhase.TRANSCRIBING -> {
                for (i in 0 until 3) {
                    drawArc(
                        ink.copy(alpha = 0.5f - i * 0.12f),
                        startAngle = t * 360f + i * 120f, sweepAngle = 70f, useCenter = false,
                        topLeft = Offset(c.x - stone * 2.2f, c.y - stone * 2.2f),
                        size = androidx.compose.ui.geometry.Size(stone * 4.4f, stone * 4.4f),
                        style = Stroke(width = 3f),
                    )
                }
            }
            phase == ListenPhase.LISTENING || speaking -> {
                val color = if (speaking) gold else ink
                val amp = if (speaking) 0.55f else 0.25f + 0.75f * smooth.value
                for (i in 0 until rings) {
                    val f = ((i.toFloat() / rings) + t) % 1f
                    val r = stone + (maxR - stone) * f * amp.coerceAtLeast(0.3f)
                    drawCircle(color.copy(alpha = (1f - f) * 0.55f), r, c, style = Stroke(width = 1.6f))
                }
            }
            else -> {
                for (i in 1..3) drawCircle(ink.copy(alpha = 0.08f), stone + (maxR - stone) * i / 4f, c, style = Stroke(width = 1f))
            }
        }
        drawCircle(if (speaking) gold else scheme.primary, stone, c)
        drawCircle(scheme.onPrimary.copy(alpha = 0.18f), stone * 0.32f, Offset(c.x - stone * 0.3f, c.y - stone * 0.3f))
    }
}

/**
 * 输入框旁边的麦克风：点一下开始听，说完停一下自动结束（也可以再点一下结束），
 * 听写出的字交给 onText。第一次用时请求麦克风权限。
 */
@Composable
fun MicButton(onText: (String) -> Unit, enabled: Boolean = true, onNote: (String?) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as YishouApp
    val voice = app.voice
    val phase by voice.phase.collectAsStateWithLifecycle()
    val level by voice.level.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var mine by remember { mutableStateOf(false) }
    // 听写回来时用最新的回调（期间输入框可能变了）
    val currentOnText by rememberUpdatedState(onText)
    val currentOnNote by rememberUpdatedState(onNote)
    fun start() {
        mine = true
        currentOnNote(null)
        scope.launch {
            when (val h = voice.listen()) {
                is Heard.Text -> currentOnText(h.text)
                Heard.Nothing -> currentOnNote("没听到，再点一下说")
                is Heard.Failed -> currentOnNote(h.message)
            }
            mine = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else onNote("没有麦克风权限。可以在系统设置里给一手开麦克风。")
    }
    val listening = mine && phase == ListenPhase.LISTENING
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
        if (listening) VoiceRings(level, phase, speaking = false, modifier = Modifier.size(52.dp))
        FilledTonalIconButton(
            onClick = {
                when {
                    listening -> voice.finish()
                    mine -> {}
                    !voice.hasPermission() -> permission.launch(Manifest.permission.RECORD_AUDIO)
                    else -> start()
                }
            },
            enabled = enabled && (mine || phase == ListenPhase.IDLE),
            colors = if (listening) IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            else IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.size(if (listening) 40.dp else 44.dp).semantics { contentDescription = if (listening) "说完了" else "说话作答" },
        ) {
            if (mine && phase == ListenPhase.TRANSCRIBING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                MicGlyph(active = listening)
            }
        }
    }
}

/** 麦克风图标（material-icons-core 里没有，画一个） */
@Composable
fun MicGlyph(active: Boolean = false, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val color = if (active) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bodyW = w * 0.36f
        drawRoundRect(
            color,
            topLeft = Offset((w - bodyW) / 2, h * 0.06f),
            size = androidx.compose.ui.geometry.Size(bodyW, h * 0.56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyW / 2),
        )
        drawArc(
            color, 0f, 180f, false,
            topLeft = Offset(w * 0.18f, h * 0.22f),
            size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.56f),
            style = Stroke(width = w * 0.08f),
        )
        drawLine(color, Offset(w / 2, h * 0.78f), Offset(w / 2, h * 0.94f), strokeWidth = w * 0.08f)
    }
}

/** 一行小字：语音状态 */
@Composable
fun VoiceNote(text: String?) {
    text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** 输入框加一个麦克风：说的话接在已写的后面。预判本、回响这些地方用。 */
@Composable
fun VoiceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    enabled: Boolean = true,
) {
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.Column(modifier) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                minLines = minLines,
                enabled = enabled,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            MicButton(
                onText = { t -> onValueChange(value.trimEnd().let { if (it.isEmpty()) t else "$it，$t" }) },
                enabled = enabled,
                onNote = { note = it },
            )
        }
        VoiceNote(note)
    }
}
