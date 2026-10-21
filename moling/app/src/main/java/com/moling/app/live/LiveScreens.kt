package com.moling.app.live

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.moling.app.BrainHost
import com.moling.app.MainActivity
import com.moling.app.Notify
import com.moling.app.R
import com.moling.app.ui.MolingFace
import com.moling.app.ui.MolingTheme
import com.moling.app.ui.Paper
import com.moling.app.ui.Seal
import com.moling.app.ui.Speaker
import com.moling.core.live.Mood
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 活的墨灵出现在他面前的两种样子（v5）：
 *  - [GateActivity]：拦住。整屏，返回键不走；回她一句（她觉得认真了就放），或点「急事」马上走；
 *    最多 5 分钟、最多 3 轮，到点自己放（这些都由大脑的硬规矩管，界面只照着做）。
 *  - [PeekActivity]：只是蹦出来说一句。底部一张卡，点外面、点「知道了」就走，也可以回她。
 * 两个都只在他已经给了“显示在其他应用上层”权限时才能从后台弹出来；没有就退回通知。
 */
object LiveScreens {
    private const val ID_LIVE = 3101

    fun gate(context: Context, mood: Mood, line: String, label: String) {
        val i = Intent(context, GateActivity::class.java)
            .putExtra(EXTRA_MOOD, mood.name).putExtra(EXTRA_LINE, line).putExtra(EXTRA_LABEL, label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch(context, i, "墨灵拦住你了", line)
    }

    fun peek(context: Context, mood: Mood, line: String) {
        val i = Intent(context, PeekActivity::class.java)
            .putExtra(EXTRA_MOOD, mood.name).putExtra(EXTRA_LINE, line)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch(context, i, "墨灵", line)
    }

    private fun launch(context: Context, i: Intent, head: String, line: String) {
        val ok = Settings.canDrawOverlays(context) && runCatching { context.startActivity(i) }.isSuccess
        if (ok) return
        // 没权限从后台弹出来：发一条会响的通知，点开就是同一个界面
        Notify.ensureChannels(context)
        val pi = android.app.PendingIntent.getActivity(
            context, ID_LIVE, i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Notify.CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(head)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID_LIVE, n) }
    }

    const val EXTRA_MOOD = "mood"
    const val EXTRA_LINE = "line"
    const val EXTRA_LABEL = "label"

    fun mood(i: Intent?): Mood = runCatching { Mood.valueOf(i?.getStringExtra(EXTRA_MOOD).orEmpty()) }.getOrDefault(Mood.CALM)
}

/** 一句对话（界面上显示这一次来回）。 */
private data class Said(val who: String, val text: String)

class GateActivity : ComponentActivity() {
    companion object {
        /** 拦人的界面开着没（心跳据此决定要不要再弹一次）。 */
        @Volatile
        var visible = false
    }

    private var mood by mutableStateOf(Mood.POUT)
    private var line by mutableStateOf("")
    private var label by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.prepare(this)
        read(intent)
        onBackPressedDispatcher.addCallback(this) {
            Toast.makeText(this@GateActivity, "回她一句，或者点下面的「急事」", Toast.LENGTH_SHORT).show()
        }
        setContent { MolingTheme { Gate() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(i: Intent?) {
        mood = LiveScreens.mood(i)
        line = i?.getStringExtra(LiveScreens.EXTRA_LINE).orEmpty()
        label = i?.getStringExtra(LiveScreens.EXTRA_LABEL).orEmpty()
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    @Composable
    private fun Gate() {
        val said = remember { mutableStateListOf<Said>() }
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var released by remember { mutableStateOf(false) }
        val startedAt = remember { BrainHost.state.value.gate?.startedAt ?: System.currentTimeMillis() }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val maxMs = BrainHost.liveConfig().gateMaxMin * 60_000L
        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it }
        }

        LaunchedEffect(Unit) {
            // 打开时已经放行了（比如重复弹出），直接走
            if (BrainHost.state.value.gate == null) { finish(); return@LaunchedEffect }
            if (BrainHost.speakOn()) Speaker.speak(line)
        }
        // 计时：到点自己放（安全阀），界面上一直写着还剩多久
        LaunchedEffect(released) {
            while (!released) {
                now = System.currentTimeMillis()
                if (now - startedAt >= maxMs) {
                    BrainHost.gateEscape("超时")
                    mood = Mood.SAD
                    line = "到点了，先放你走。下次回我一句好不好。"
                    released = true
                }
                delay(1_000)
            }
            delay(4_000)
            finish()
        }

        fun send() {
            val t = text.trim()
            if (busy || released || t.isBlank()) return
            busy = true
            said += Said("墨灵", line)
            said += Said("你", t)
            text = ""
            lifecycleScope.launch {
                val turn = BrainHost.gateReply(t)
                mood = turn.mood
                line = turn.line
                if (BrainHost.speakOn()) Speaker.speak(turn.line)
                if (turn.release) released = true
                busy = false
            }
        }

        Column(
            Modifier.fillMaxSize().background(Paper).padding(20.dp).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val left = ((maxMs - (now - startedAt)).coerceAtLeast(0) / 1000).toInt()
            Text(
                if (released) "放行了" else "墨灵拦在你面前${if (label.isNotBlank()) " · $label" else ""} · 最多再 ${left / 60}:${"%02d".format(left % 60)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            MolingFace(mood, Modifier.size(170.dp))
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 2.dp, color = Color.White) {
                Text(line, Modifier.padding(16.dp).fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
            }
            if (said.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    said.takeLast(6).forEach { s ->
                        Text(
                            "${s.who}：${s.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (!released) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(400) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("回她：卡在哪 / 几点停 / 为什么现在要刷") },
                    minLines = 2,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        runCatching {
                            voice.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"),
                            )
                        }.onFailure { Toast.makeText(this@GateActivity, "语音不可用，直接打字吧", Toast.LENGTH_SHORT).show() }
                    }) { Text("说话") }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = !busy && text.isNotBlank(), onClick = { send() }) { Text(if (busy) "她在想…" else "回她") }
                }
                // 急事：永远在，点了马上走（记一笔）
                TextButton(enabled = !busy, onClick = {
                    lifecycleScope.launch {
                        BrainHost.gateEscape("急事")
                        finish()
                    }
                }) { Text("急事，先放我走", color = Seal, fontWeight = FontWeight.Bold) }
            } else {
                Button(onClick = { finish() }) { Text("好") }
            }
        }
    }
}

/** 她只是蹦出来说一句：底部一张卡，不挡路。 */
class PeekActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.prepare(this)
        val first = intent.getStringExtra(LiveScreens.EXTRA_LINE).orEmpty()
        val firstMood = LiveScreens.mood(intent)
        setContent { MolingTheme { Peek(firstMood, first) } }
    }

    @Composable
    private fun Peek(firstMood: Mood, first: String) {
        var mood by remember { mutableStateOf(firstMood) }
        var line by remember { mutableStateOf(first) }
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var touched by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (BrainHost.speakOn()) Speaker.speak(first)
            delay(15_000)
            if (!touched) finish()
        }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                .clickable(remember { MutableInteractionSource() }, null) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                Modifier.fillMaxWidth().padding(12.dp).imePadding()
                    .clickable(remember { MutableInteractionSource() }, null) { touched = true },
                shape = MaterialTheme.shapes.extraLarge,
                color = Paper,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MolingFace(mood, Modifier.size(72.dp))
                        Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(400); touched = true },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("回她一句（可以不回）") },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            BrainHost.answeredLater()
                            finish()
                        }) { Text("知道了") }
                        TextButton(onClick = {
                            startActivity(Intent(this@PeekActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }) { Text("去聊") }
                        Button(enabled = !busy && text.isNotBlank(), onClick = {
                            val t = text.trim()
                            busy = true
                            touched = true
                            text = ""
                            lifecycleScope.launch {
                                val r = BrainHost.chat(t, System.currentTimeMillis())
                                line = r.text
                                mood = Mood.CALM
                                if (BrainHost.speakOn()) Speaker.speak(r.text)
                                BrainHost.answeredLater()
                                busy = false
                            }
                        }) { Text(if (busy) "…" else "回她") }
                    }
                }
            }
        }
    }
}
