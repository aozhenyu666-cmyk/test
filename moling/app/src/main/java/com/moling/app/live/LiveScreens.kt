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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.layout.width
import com.moling.app.ui.MolingFace
import com.moling.app.ui.art.InkHourglass
import com.moling.app.ui.art.InkRipple
import com.moling.app.ui.art.InkThinking
import com.moling.app.ui.art.WashBlue
import com.moling.app.ui.MolingTheme
import com.moling.app.ui.ChessBait
import com.moling.app.ui.ChessFreeInput
import com.moling.app.ui.ChessHeader
import com.moling.app.ui.ChessScore
import com.moling.app.ui.Paper
import com.moling.app.ui.Seal
import com.moling.app.ui.Speaker
import com.moling.core.live.Mood
import com.moling.app.ui.art.inkEnter
import com.moling.core.live.Quiz
import com.moling.core.spar.Chess
import com.moling.core.spar.Chesses
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
        val choices = nextChoices
        nextChoices = emptyList()
        val i = Intent(context, GateActivity::class.java)
            .putExtra(EXTRA_MOOD, mood.name).putExtra(EXTRA_LINE, line).putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_CHOICES, choices.joinToString("\u0001"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch(context, i, "墨灵拦住你了", line)
    }

    /**
     * 这次出现带的点选项（破雾等）。心跳在 [Appear.show] 之前放进来，[peek] 带走并清空——
     * 这样各种出现方式不用都改签名（桌宠 / 拦人页的选项见 DEEPSEEK-6 的 D 组）。
     */
    @Volatile
    var nextChoices: List<String> = emptyList()

    fun peek(context: Context, mood: Mood, line: String, modality: com.moling.core.live.Modality = com.moling.core.live.Modality.PEEK) {
        val choices = nextChoices
        nextChoices = emptyList()
        val i = Intent(context, PeekActivity::class.java)
            .putExtra(EXTRA_MOOD, mood.name).putExtra(EXTRA_LINE, line).putExtra(EXTRA_MODALITY, modality.name)
            .putExtra(EXTRA_CHOICES, choices.joinToString("\u0001"))
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
    const val EXTRA_MODALITY = "modality"
    const val EXTRA_CHOICES = "choices"

    fun choices(i: Intent?): List<String> = i?.getStringExtra(EXTRA_CHOICES).orEmpty().split('\u0001').filter { it.isNotBlank() }

    fun modality(i: Intent?): com.moling.core.live.Modality =
        runCatching { com.moling.core.live.Modality.valueOf(i?.getStringExtra(EXTRA_MODALITY).orEmpty()) }.getOrDefault(com.moling.core.live.Modality.PEEK)

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
    /** D1 破雾选项：点一下就能回，不用打字。 */
    private var gateChoices by mutableStateOf<List<String>>(emptyList())

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
        gateChoices = LiveScreens.choices(i)
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
        // B1 捉虫题：动一下脑子，答对了她再听你说。
        var gateQuiz by remember { mutableStateOf<Quiz?>(null) }
        var quizBusy by remember { mutableStateOf(false) }
        // D1 破雾：他刷懵了，默认收起打字框，只让他点。
        val fog = remember { BrainHost.state.value.gate?.fog == true }
        var showInput by remember { mutableStateOf(!fog) }
        val startedAt = remember { BrainHost.state.value.gate?.startedAt ?: System.currentTimeMillis() }
        // 一次拦人只记一笔 GATE：放行后不再重复记账（F11-4）。
        var gateReported by remember { mutableStateOf(false) }
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

        /** 放行时只记一次 GATE；点“先不聊了 / 别管我”不算回复，不记 REPLIED。 */
        fun noteGate(reaction: com.moling.core.live.Reaction, isExit: Boolean) {
            if (isExit || gateReported) return
            gateReported = true
            BrainHost.react(
                com.moling.core.live.Modality.GATE,
                reaction,
                System.currentTimeMillis() - startedAt,
            )
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
                if (turn.release) {
                    released = true
                    noteGate(com.moling.core.live.Reaction.REPLIED, isExit = false)
                }
                busy = false
            }
        }

        Column(
            Modifier.fillMaxSize().background(Paper).padding(20.dp).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val left = ((maxMs - (now - startedAt)).coerceAtLeast(0) / 1000).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!released) InkHourglass(left * 1000f / maxMs, Modifier.size(34.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (released) "放行了" else "墨灵拦在你面前${if (label.isNotBlank()) " · $label" else ""} · 最多再 ${left / 60}:${"%02d".format(left % 60)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(contentAlignment = Alignment.Center) {
                // 她身后一圈淡淡的涟漪：她刚“落”到你面前
                InkRipple(Modifier.size(230.dp), color = if (mood == Mood.POUT) Seal else WashBlue)
                MolingFace(mood, Modifier.size(170.dp))
            }
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 2.dp, color = Color.White) {
                Text(line, Modifier.padding(16.dp).fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
            }
            // D1 破雾选项：大一点的按钮，两两一行，手指好点。
            if (!released && gateChoices.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gateChoices.chunked(2).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEachIndexed { ci, c ->
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        lifecycleScope.launch {
                                            busy = true
                                            val t = runCatching { BrainHost.tap(c, com.moling.core.live.Modality.GATE) }.getOrNull()
                                            busy = false
                                            if (t == null) {
                                                Toast.makeText(this@GateActivity, "没接上，再点一次", Toast.LENGTH_SHORT).show()
                                                return@launch
                                            }
                                            mood = t.mood
                                            line = t.line
                                            gateChoices = t.choices
                                            if (BrainHost.speakOn()) Speaker.speak(t.line)
                                            if (t.release) {
                                                released = true
                                                val isExit = c == com.moling.core.live.Live.EXIT || c == "别管我"
                                                noteGate(com.moling.core.live.Reaction.REPLIED, isExit)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).padding(vertical = 6.dp).inkEnter(order = ci, distance = 8.dp),
                                ) { Text(c, style = MaterialTheme.typography.bodyLarge) }
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
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
                if (showInput) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(400) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (fog) "不用打字，点上面的选项就行" else "回她：卡在哪 / 几点停 / 为什么现在要刷") },
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
                    if (busy) InkThinking("她在想…") else Button(enabled = text.isNotBlank(), onClick = { send() }) { Text("回她") }
                }
                } else if (fog) {
                    TextButton(onClick = { showInput = true }) { Text("想打字也行", style = MaterialTheme.typography.labelMedium) }
                }
                if (gateQuiz == null) {
                    TextButton(
                        enabled = !busy && !quizBusy,
                        onClick = {
                            quizBusy = true
                            lifecycleScope.launch {
                                val q = runCatching { BrainHost.quiz() }.getOrNull()
                                quizBusy = false
                                if (q == null) {
                                    Toast.makeText(this@GateActivity, "现在出不了题", Toast.LENGTH_SHORT).show()
                                } else {
                                    gateQuiz = q
                                }
                            }
                        },
                    ) { Text(if (quizBusy) "在出题…" else "做一道捉虫题") }
                } else {
                    val q = gateQuiz
                    if (q != null) {
                        Surface(shape = MaterialTheme.shapes.medium, color = Color.White) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                QuizParts(q) { ok, _ ->
                                    if (ok) {
                                        gateQuiz = null
                                        lifecycleScope.launch {
                                            val turn = BrainHost.gateReply("我刚答对了你的捉虫题：原来是「" + q.fix + "」")
                                            mood = turn.mood
                                            line = turn.line
                                            if (BrainHost.speakOn()) Speaker.speak(turn.line)
                                            if (turn.release) {
                                                released = true
                                                noteGate(com.moling.core.live.Reaction.REPLIED, isExit = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
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

    /** 这次是以哪种方式出现的（报反应用）；出现的时刻；报过没有。 */
    private val shownAt = System.currentTimeMillis()
    private var reported = false

    private fun report(r: com.moling.core.live.Reaction) {
        if (reported) return
        reported = true
        BrainHost.react(LiveScreens.modality(intent), r, System.currentTimeMillis() - shownAt)
    }

    private fun dismissed() = report(
        if (System.currentTimeMillis() - shownAt < 3_000) com.moling.core.live.Reaction.DISMISSED_FAST else com.moling.core.live.Reaction.DISMISSED,
    )

    override fun onDestroy() {
        // 一直没碰就走了 = 没理
        if (!isChangingConfigurations) report(com.moling.core.live.Reaction.IGNORED)
        super.onDestroy()
    }

    @Composable
    private fun Peek(firstMood: Mood, first: String) {
        var mood by remember { mutableStateOf(firstMood) }
        var line by remember { mutableStateOf(first) }
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var touched by remember { mutableStateOf(false) }
        // 点一下就能回的选项（破雾）：点了她接一句，最多三下
        var choices by remember { mutableStateOf(LiveScreens.choices(intent)) }
        // D3：这一轮是不是盲盒 / 小剧场 / 赌（只影响样子）。
        var kind by remember { mutableStateOf(BrainHost.state.value.lastPlay?.kind.orEmpty()) }
        // E1 三手棋：第几手 / 收官分数 / 暴论 / “我自己说一句”。
        var chess by remember { mutableStateOf(BrainHost.state.value.chess) }
        var chessScore by remember { mutableIntStateOf(-1) }
        var chessConclusion by remember { mutableStateOf("") }
        var freeMode by remember { mutableStateOf(false) }
        var closeSoon by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (BrainHost.speakOn()) Speaker.speak(first)
            delay(15_000)
            if (!touched) finish()
        }
        LaunchedEffect(closeSoon) {
            if (closeSoon) { delay(3_500); finish() }
        }

        fun runTap(c: String) {
            busy = true
            touched = true
            // 点“先不聊了 / 别管我”不是回复：不报 REPLIED，也不让 onDestroy 再补 IGNORED。
            val exit = c == com.moling.core.live.Live.EXIT || c == "别管我"
            if (exit) reported = true else report(com.moling.core.live.Reaction.REPLIED)
            lifecycleScope.launch {
                val t = BrainHost.tap(c, LiveScreens.modality(intent))
                line = t.line
                mood = t.mood
                choices = t.choices
                kind = ""
                if (t.chess) {
                    val s = BrainHost.state.value
                    chess = s.chess
                    if (s.chess == null) {
                        val last = s.chessDone.lastOrNull()
                        chessScore = last?.score ?: -1
                        chessConclusion = last?.conclusion.orEmpty()
                    }
                }
                if (BrainHost.speakOn()) Speaker.speak(t.line)
                busy = false
                if (t.choices.isEmpty()) closeSoon = true
            }
        }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                .clickable(remember { MutableInteractionSource() }, null) { dismissed(); finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                Modifier.fillMaxWidth().padding(12.dp).imePadding()
                    .clickable(remember { MutableInteractionSource() }, null) { touched = true },
                shape = MaterialTheme.shapes.extraLarge,
                color = Paper,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(16.dp).inkEnter(distance = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ch = chess
                    if (ch != null || chessScore >= 0) {
                        ChessHeader(turn = ch?.turn ?: 3, done = ch == null)
                    }
                    if (ch != null && ch.moves.isEmpty()) {
                        ChessBait(ch.bait)
                    }
                    if (ch == null && chessScore >= 0) {
                        ChessScore(chessScore, chessConclusion) {
                            startActivity(
                                Intent(this@PeekActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_TAB, 2)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MolingFace(mood, Modifier.size(72.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (kind == "blindbox" || kind == "bet") {
                                KindTag(if (kind == "blindbox") "盲盒" else "她下注了")
                            }
                            if (kind == "drama") {
                                line.lines().filter { it.isNotBlank() }.forEach { one ->
                                    Text("· " + one, style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                Text(line, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (choices.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        choices.forEachIndexed { i, c ->
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { if (c == Chesses.FREE) freeMode = true else runTap(c) },
                                modifier = Modifier.inkEnter(order = i, distance = 8.dp),
                            ) { Text(c) }
                        }
                    }
                    if (freeMode) ChessFreeInput(onSend = { said -> freeMode = false; runTap(said) })
                    if (choices.isEmpty() && !freeMode) OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(400); touched = true },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("回她一句（可以不回）") },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            dismissed()
                            BrainHost.answeredLater()
                            finish()
                        }) { Text("知道了") }
                        TextButton(onClick = {
                            report(com.moling.core.live.Reaction.REPLIED)
                            startActivity(Intent(this@PeekActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }) { Text("去聊") }
                        Button(enabled = !busy && text.isNotBlank(), onClick = {
                            val t = text.trim()
                            busy = true
                            touched = true
                            text = ""
                            lifecycleScope.launch {
                                report(if (com.moling.core.live.Live.perfunctory(t)) com.moling.core.live.Reaction.PERFUNCTORY else com.moling.core.live.Reaction.REPLIED)
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

/** D3：卡片上的小标签（盲盒 / 她下注了）。 */
@Composable
private fun KindTag(text: String) {
    Box(
        Modifier
            .background(Seal.copy(alpha = 0.10f), RoundedCornerShape(50))
            .border(0.6.dp, Seal.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(text, color = Seal, style = MaterialTheme.typography.labelSmall) }
}