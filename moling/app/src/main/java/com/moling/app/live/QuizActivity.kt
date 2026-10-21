package com.moling.app.live

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.app.ui.Ink
import com.moling.app.ui.MolingFace
import com.moling.app.ui.MolingTheme
import com.moling.app.ui.Paper
import com.moling.app.ui.Seal
import com.moling.core.live.Modality
import com.moling.core.live.Mood
import com.moling.core.live.Quiz
import com.moling.core.live.Reaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * B1 = 原 Z5 捉虫题：把一条论断改错一处、切成几段，他点出被改错的那段。
 *
 * 要动一下脑子才答得出，所以能把“自动驾驶”的注意力拉回手动挡。不碰“一手”，是墨灵自己的小题。
 * 答对 / 答错都算“他动脑了”（报 REPLIED）；点外面划掉、15 秒没碰照实报。
 * 拦人页也用同一套片段（[QuizParts]）。
 */

class QuizActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_MOOD = "mood"
        private const val EXTRA_LINE = "line"
        private const val EXTRA_PARTS = "parts"
        private const val EXTRA_WRONG = "wrong"
        private const val EXTRA_FIX = "fix"
        private const val EXTRA_SOURCE = "source"

        fun open(context: Context, mood: Mood, line: String, quiz: Quiz) {
            val i = Intent(context, QuizActivity::class.java)
                .putExtra(EXTRA_MOOD, mood.name)
                .putExtra(EXTRA_LINE, line)
                .putStringArrayListExtra(EXTRA_PARTS, ArrayList(quiz.parts))
                .putExtra(EXTRA_WRONG, quiz.wrong)
                .putExtra(EXTRA_FIX, quiz.fix)
                .putExtra(EXTRA_SOURCE, quiz.source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { context.startActivity(i) }
        }

        fun quiz(intent: Intent?): Quiz? {
            val i = intent ?: return null
            val parts = i.getStringArrayListExtra(EXTRA_PARTS)?.filter { it.isNotBlank() } ?: return null
            if (parts.size !in 3..5) return null
            val wrong = i.getIntExtra(EXTRA_WRONG, -1)
            if (wrong !in parts.indices) return null
            return Quiz(parts, wrong, i.getStringExtra(EXTRA_FIX).orEmpty(), i.getStringExtra(EXTRA_SOURCE).orEmpty())
        }

        fun mood(intent: Intent?): Mood =
            runCatching { Mood.valueOf(intent?.getStringExtra(EXTRA_MOOD).orEmpty()) }.getOrDefault(Mood.CURIOUS)
    }

    private val shownAt = System.currentTimeMillis()
    private var reported = false

    private fun report(reaction: Reaction) {
        if (reported) return
        reported = true
        BrainHost.react(Modality.QUIZ, reaction, System.currentTimeMillis() - shownAt)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val quiz = quiz(intent)
        if (quiz == null) {
            finish()
            return
        }
        val mood = mood(intent)
        val line = intent.getStringExtra("line").orEmpty()
        setContent { MolingTheme { Sheet(mood, line, quiz) } }
    }

    @Composable
    private fun Sheet(mood0: Mood, line: String, quiz: Quiz) {
        var mood by remember { mutableStateOf(mood0) }
        var answered by remember { mutableStateOf(false) }
        var correct by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun dismiss() {
            report(if (System.currentTimeMillis() - shownAt < 3_000) Reaction.DISMISSED_FAST else Reaction.DISMISSED)
            finish()
        }

        // 15 秒没碰：算没理她，收起来。
        LaunchedEffect(Unit) {
            delay(15_000)
            if (!answered) {
                report(Reaction.IGNORED)
                finish()
            }
        }
        // 答对之后停两秒，让他看清“原来是什么”。
        LaunchedEffect(answered, correct) {
            if (answered && correct) {
                delay(2_000)
                finish()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Ink.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismiss() },
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = MaterialTheme.shapes.large,
                color = Paper,
                shadowElevation = 8.dp,
            ) {
                Column(
                    Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MolingFace(if (correct) Mood.HAPPY else mood, Modifier.size(56.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    QuizParts(quiz) { ok, revealed ->
                        if (answered) return@QuizParts
                        if (ok) {
                            answered = true
                            correct = true
                            mood = Mood.HAPPY
                            report(Reaction.REPLIED)
                            scope.launch { runCatching { BrainHost.quizResult(true) } }
                        } else if (revealed) {
                            answered = true
                            report(Reaction.REPLIED)
                            scope.launch { runCatching { BrainHost.quizResult(false) } }
                        }
                    }
                    TextButton(onClick = { dismiss() }, modifier = Modifier.align(Alignment.End)) {
                        Text(if (correct) "好" else "知道了")
                    }
                }
            }
        }
    }
}

/**
 * 捉虫题的片段：一句切成几段，点出错的那段。
 * 拦人页和 [QuizActivity] 共用；[onPicked] 在每次点击后回调（correct = 点对；revealed = 已经揭晓）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuizParts(quiz: Quiz, onPicked: (correct: Boolean, revealed: Boolean) -> Unit) {
    var chosen by remember(quiz) { mutableIntStateOf(-1) }
    var wrongPicks by remember(quiz) { mutableStateOf(listOf<Int>()) }
    val revealed = chosen >= 0 || wrongPicks.size >= 2
    val shake = remember { Animatable(0f) }
    val lastWrong = wrongPicks.lastOrNull() ?: -1

    LaunchedEffect(wrongPicks.size) {
        if (wrongPicks.isNotEmpty() && !revealed) {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(60))
            shake.animateTo(-1f, tween(60))
            shake.animateTo(0f, tween(60))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("这句话有一处被我改错了，点出来：", style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            quiz.parts.forEachIndexed { i, part ->
                val isWrong = i == quiz.wrong
                val showRed = revealed && isWrong
                val showGray = i in wrongPicks && !showRed
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        showRed -> Seal.copy(alpha = 0.12f)
                        showGray -> Ink.copy(alpha = 0.06f)
                        else -> Paper
                    },
                    border = BorderStroke(0.8.dp, if (showRed) Seal else Ink.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .then(
                            if (i == lastWrong && !revealed) {
                                Modifier.offset { IntOffset((shake.value * 6).dp.toPx().toInt(), 0) }
                            } else {
                                Modifier
                            },
                        )
                        .clickable(enabled = !revealed) {
                            if (isWrong) {
                                chosen = i
                                onPicked(true, true)
                            } else {
                                wrongPicks = wrongPicks + i
                                onPicked(false, wrongPicks.size >= 2)
                            }
                        },
                ) {
                    Text(
                        part,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (showRed) Seal else Ink,
                        textDecoration = if (showRed) TextDecoration.LineThrough else null,
                        fontWeight = if (showRed) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        when {
            revealed -> Text("原来是：" + quiz.fix, style = MaterialTheme.typography.bodySmall, color = Seal)
            wrongPicks.isNotEmpty() -> Text("不是这段", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}