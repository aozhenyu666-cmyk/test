package com.yishou.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.yishou.app.data.Round
import com.yishou.app.llm.CoachMessages
import com.yishou.app.round.AnswerRules
import com.yishou.app.system.Attachment
import kotlinx.coroutines.delay
import java.io.File

// ---------- 对话气泡 ----------

/** 陪练的一手：左侧气泡。footer 放计时等小字。 */
@Composable
fun CoachBubble(text: String, footer: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                if (footer != null) {
                    Spacer(Modifier.size(6.dp))
                    footer()
                }
            }
        }
    }
}

/** 用户的一手：右侧气泡。图片转写只显示一行提示。 */
@Composable
fun UserBubble(text: String) {
    val mark = text.indexOf(AnswerRules.IMAGE_MARK)
    val body = if (mark >= 0) text.substring(0, mark).trim() else text
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyLarge)
                if (mark >= 0) Text("📷 附图片", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 落子：有效是一颗黑子，不算是一颗空心子，出现时带一点弹性。 */
@Composable
fun Stone(filled: Boolean, color: Color, animateKey: Any) {
    val scale = remember(animateKey) { Animatable(0f) }
    LaunchedEffect(animateKey) {
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
    }
    Canvas(
        Modifier
            .size(16.dp)
            .graphicsLayer(scaleX = scale.value, scaleY = scale.value),
    ) {
        val r = size.minDimension / 2
        if (filled) {
            drawCircle(color, r)
            drawCircle(Color.White.copy(alpha = 0.25f), r * 0.35f, center = Offset(r * 0.7f, r * 0.7f))
        } else {
            drawCircle(color, r - 1.5f, style = Stroke(width = 3f))
        }
    }
}

/** 判定结果：一行标签 + 反馈。 */
@Composable
fun VerdictLine(round: Round) {
    val color = when {
        !round.judged -> MaterialTheme.colorScheme.outline
        round.effective -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val label = when {
        !round.judged -> "离线保存，未判定"
        round.effective -> "有效 · ${round.moveType}"
        else -> "这一手不算"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stone(filled = round.effective, color = color, animateKey = round.id to round.createdAt)
            Spacer(Modifier.size(8.dp))
            Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        if (round.feedback.isNotBlank()) Text(round.feedback, style = MaterialTheme.typography.bodyMedium)
        if (!round.effective && round.reason.isNotBlank()) {
            Text(round.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 陪练正在想：三个点轮流亮起。 */
@Composable
fun TypingBubble(label: String = "陪练在想") {
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "phase")
    CoachBubble("$label ") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(8.dp)
                        .alpha(if (phase.toInt() == i) 1f else 0.3f)
                        .background(MaterialTheme.colorScheme.onSecondaryContainer, CircleShape),
                )
            }
        }
    }
}

/** 一直在走的计时：这一手出来以后过了多久还没应。 */
@Composable
fun ElapsedClock(since: Long, prefix: String = "这一手已等你") {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val sec = ((now - since) / 1000).coerceAtLeast(0)
    val text = when {
        sec < 3600 -> "%d:%02d".format(sec / 60, sec % 60)
        else -> CoachMessages.formatGap(sec / 60)
    }
    val color = when {
        sec >= 10 * 60 -> MaterialTheme.colorScheme.error
        sec >= 3 * 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Text("⏱ $prefix $text", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
}

/** 隔了很久回来时，先帮你找回局面。 */
@Composable
fun ResumeCard(gapMinutes: Long, lastAnswer: String?, stuck: String?) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("隔了 ${CoachMessages.formatGap(gapMinutes)}，先回到局面", fontWeight = FontWeight.Bold)
            lastAnswer?.takeIf { it.isNotBlank() }?.let {
                Text("你上次说：「${it.substringBefore(AnswerRules.IMAGE_MARK).trim().ifEmpty { "（图片作答）" }}」", maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            stuck?.takeIf { it.isNotBlank() }?.let { Text("当时卡在：$it") }
        }
    }
}

// ---------- 输入区 ----------

/** 拍照用的临时文件（缓存目录，识别完就没用了）。 */
private fun newPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File(dir, "answer.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

/**
 * 作答输入区：文字 + 拍照/相册 + 发送。图片会先被识图模型转写，转写结果显示出来，
 * 和文字一起作为这一手的原话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerComposer(
    answer: String,
    starters: List<String> = emptyList(),
    onAnswerChange: (String) -> Unit,
    attachment: Attachment?,
    onAttach: (Uri) -> Unit,
    onClearAttachment: () -> Unit,
    submitting: Boolean,
    onSubmit: () -> Unit,
    hint: String? = null,
    error: String? = null,
    submitLabel: String = "应一手",
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let(onAttach)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onAttach)
    }
    val imageText = (attachment as? Attachment.Ready)?.text
    val chars = AnswerRules.countChars(AnswerRules.compose(answer, imageText))

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (attachment) {
                null -> {}
                Attachment.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("正在识别图片……", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClearAttachment) { Text("取消") }
                }
                is Attachment.Ready -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "📷 ${attachment.text}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onClearAttachment) { Text("移除") }
                    }
                }
                is Attachment.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(attachment.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearAttachment) { Text("关闭") }
                }
            }
            if (starters.isNotEmpty() && !submitting) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    starters.forEach { st ->
                        SuggestionChip(
                            onClick = {
                                val head = st.removeSuffix("……").removeSuffix("...").removeSuffix("…")
                                onAnswerChange(if (answer.isBlank()) head else answer.trimEnd() + head)
                            },
                            label = { Text(st, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
            hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = onAnswerChange,
                    enabled = !submitting,
                    placeholder = { Text("你的一手：得到了什么、依据是什么") },
                    minLines = 1,
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                FilledIconButton(
                    onClick = onSubmit,
                    enabled = !submitting && attachment != Attachment.Working,
                    modifier = Modifier.size(52.dp),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = submitLabel)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = {
                        val uri = newPhotoUri(context)
                        photoUri = uri
                        camera.launch(uri)
                    },
                    label = { Text("拍照") },
                    enabled = !submitting,
                )
                AssistChip(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    label = { Text("相册") },
                    enabled = !submitting,
                )
                actions()
                Spacer(Modifier.weight(1f))
                Text(
                    if (submitting) "判定中…" else "$chars/${AnswerRules.MIN_CHARS} 字",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chars >= AnswerRules.MIN_CHARS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
