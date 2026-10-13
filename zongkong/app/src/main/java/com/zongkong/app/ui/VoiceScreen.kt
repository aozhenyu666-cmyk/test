package com.zongkong.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.system.PetView
import com.zongkong.app.system.Voice
import com.zongkong.app.zk
import com.zongkong.core.ApiResult
import com.zongkong.core.Policy
import com.zongkong.core.VoiceConfig
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.boss.BossConfig
import kotlinx.coroutines.launch

/** 桌面宠物（Compose 里画）：和桌面上那只同一个画法。 */
@Composable
fun PetFigure(mood: PetView.Mood, modifier: Modifier = Modifier, kind: PetView.Kind? = null) {
    val store = LocalContext.current.zk.store
    val d by store.dossier.collectAsStateWithLifecycle()
    val cfg by store.config.collectAsStateWithLifecycle()
    val stance = remember(d) { store.stance() }
    val k = kind ?: PetView.kindOf(cfg.boss.petKind)
    val t = if (LocalReduceMotion.current) 0.4f else rememberInfiniteTransition(label = "pet").animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)), label = "t").value
    Canvas(modifier) {
        drawIntoCanvas { c -> PetView.paint(c.nativeCanvas, size.width, size.height, t, mood, stance, 1f, k) }
    }
}

/**
 * 对讲：按住说话，松手发给总控，它的回答念出来。没配语音接口时用手机自带的语音输入和朗读。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceScreen(nav: NavHostController, openSettings: Boolean = false) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val config by app.store.config.collectAsStateWithLifecycle()
    val log by app.chat.log.collectAsStateWithLifecycle()
    val busy by app.chat.busy.collectAsStateWithLifecycle()
    val vstate by Voice.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(openSettings) }
    var sentAt by remember { mutableStateOf(0L) }
    val visible = log.messages.filter { !it.hidden && !it.error && it.content.isNotBlank() && it.role != ChatMsg.TOOL }
    val lastUser = visible.lastOrNull { it.role == ChatMsg.USER }
    val lastReply = visible.lastOrNull { it.role == ChatMsg.ASSISTANT }
    val (replyBody, options) = remember(lastReply) { lastReply?.let { Agent.options(it.content) } ?: ("" to emptyList()) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) hint = "没有麦克风权限，用不了按住说话" }
    val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said ->
            sentAt = System.currentTimeMillis()
            app.chat.send(said)
        }
    }
    fun systemListen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "跟总控说")
        if (i.resolveActivity(context.packageManager) != null) recognizer.launch(i) else hint = "这台手机没有自带语音输入：在下面填一个语音接口"
    }
    // 回答到了：念出来；问完了就接着听（只用手机自带的语音输入时）
    LaunchedEffect(lastReply?.at, busy) {
        val r = lastReply ?: return@LaunchedEffect
        if (busy || r.at < sentAt || sentAt == 0L) return@LaunchedEffect
        sentAt = 0L
        if (config.voice.speakReplies) Voice.speak(context, replyBody) {
            if (config.voice.listenAfterAsk && !config.voice.apiReady && (replyBody.contains('？') || options.isNotEmpty())) systemListen()
        }
    }

    val mood = when {
        vstate == Voice.State.LISTENING -> PetView.Mood.LISTEN
        busy || vstate == Voice.State.THINKING -> PetView.Mood.THINK
        vstate == Voice.State.SPEAKING -> PetView.Mood.TALK
        else -> PetView.Mood.IDLE
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("对讲", { Voice.cancelRecording(); Voice.stopSpeaking(); nav.popBackStack() }) {
            TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "收起设置" else "语音设置", color = sig.muted) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Ripple(mood == PetView.Mood.LISTEN, sig.ai, Modifier.fillMaxSize())
                PetFigure(mood, Modifier.size(150.dp).testTag("voice-pet"))
            }
            Text(
                when (mood) {
                    PetView.Mood.LISTEN -> "在听…松手发出去"
                    PetView.Mood.THINK -> "在想…"
                    PetView.Mood.TALK -> "在说"
                    else -> if (config.voice.apiReady) "按住下面的圆说话" else "点下面的圆说话（手机自带的语音输入）"
                },
                style = MaterialTheme.typography.titleMedium, color = sig.muted,
            )
            lastUser?.let { Text("你：${it.content}", style = MaterialTheme.typography.bodyMedium, color = sig.muted, textAlign = TextAlign.Center) }
            if (replyBody.isNotBlank()) Panel(modifier = Modifier.testTag("voice-reply")) {
                Text(replyBody, style = MaterialTheme.typography.titleMedium.merge(SerifTitle))
                if (options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o -> QuickChip(o, sig.ai) { sentAt = System.currentTimeMillis(); app.chat.send(o) } }
                }
                TextButton(onClick = { Voice.speak(context, replyBody) }) { Text("再念一遍", color = sig.ai) }
            }
            // 按住说话的大圆
            Box(
                Modifier.size(120.dp).background(if (mood == PetView.Mood.LISTEN) sig.strict else sig.ai, CircleShape).testTag("voice-hold")
                    .pointerInput(config.voice.apiReady) {
                        detectTapGestures(
                            onTap = { if (!config.voice.apiReady) systemListen() },
                            onPress = {
                                if (!config.voice.apiReady) return@detectTapGestures
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    mic.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                if (!Voice.startRecording(context)) { hint = "录音失败"; return@detectTapGestures }
                                val released = tryAwaitRelease()
                                if (!released) { Voice.cancelRecording(); return@detectTapGestures }
                                scope.launch {
                                    when (val r = Voice.stopAndTranscribe(context)) {
                                        is ApiResult.Err -> hint = r.message
                                        is ApiResult.Ok -> { hint = null; sentAt = System.currentTimeMillis(); app.chat.send(r.value) }
                                    }
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (mood == PetView.Mood.LISTEN) "松手" else "说", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
            hint?.let { Text(it, color = sig.warn, style = MaterialTheme.typography.bodySmall) }
            if (!config.ai.ready) Hint("还没接 AI：总控只能用固定的话回你。去 我 → AI 接口。")

            if (showSettings) VoiceSettings(config.voice, Policy.desiredBoss(config))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSettings(v: VoiceConfig, boss: BossConfig) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(v.baseUrl) }
    var key by remember { mutableStateOf(v.apiKey) }
    var tts by remember { mutableStateOf(v.ttsModel) }
    var voice by remember { mutableStateOf(v.ttsVoice) }
    var stt by remember { mutableStateOf(v.sttModel) }
    var result by remember { mutableStateOf<String?>(null) }
    fun save(next: VoiceConfig) = app.store.updateConfig { it.copy(voice = next) }
    Panel(modifier = Modifier.testTag("voice-now")) {
        Text("现在它用什么声音", style = MaterialTheme.typography.titleSmall)
        Text(
            if (v.apiReady) "语音接口：${v.baseUrl.substringAfter("://").substringBefore('/')} · ${v.ttsVoice.substringAfterLast(':')}（自然人声）"
            else "手机自带的朗读（机械音）——还没填语音接口",
            style = MaterialTheme.typography.bodyMedium, color = if (v.apiReady) sig.free else sig.warn,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Voice.speak(context, "你好，我是总控。今天的主线做了吗？") }) { Text("试听") }
            if (!v.apiReady) TextButton(onClick = {
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("换手机朗读引擎") }
        }
        Hint(
            "默认那个机械音是手机自带的“文字转语音”。想要像人说话：在下面选一家服务商、贴上密钥、点“保存并试听”，三步。" +
                "不想花钱：点“换手机朗读引擎”，装个好一点的引擎（比如讯飞、Google），也会自然很多。",
        )
    }
    Panel(modifier = Modifier.testTag("voice-settings")) {
        Text("语音接口（OpenAI 兼容）", style = MaterialTheme.typography.titleSmall)
        Text("① 选一家", style = MaterialTheme.typography.labelLarge, color = sig.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VOICE_PRESETS.forEach { p ->
                QuickChip(p.name, sig.ai) { url = p.url; tts = p.tts; voice = p.voice; stt = p.stt; result = p.note }
            }
            val ai = app.store.config.value.ai
            if (ai.baseUrl.isNotBlank()) QuickChip("和 AI 接口同一家", sig.ai) {
                url = ai.baseUrl; key = ai.apiKey
                VOICE_PRESETS.firstOrNull { ai.baseUrl.contains(it.url.substringAfter("://").substringBefore('/')) }?.let { tts = it.tts; voice = it.voice; stt = it.stt }
                result = "地址和密钥用了 AI 接口那份；这家要支持语音才行。"
            }
        }
        Text("② 填密钥 ③ 保存并试听", style = MaterialTheme.typography.labelLarge, color = sig.muted)
        Hint("说话用 /audio/speech，听写用 /audio/transcriptions。不填就用手机自带的朗读和语音输入。录音只发给这里填的接口。")
        OutlinedTextField(url, { url = it }, label = { Text("接口地址（例：https://api.openai.com/v1）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(tts, { tts = it }, label = { Text("说话模型") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(voice, { voice = it }, label = { Text("音色") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        VOICE_PRESETS.firstOrNull { it.tts == tts }?.voices?.let { list ->
            Text("音色", style = MaterialTheme.typography.labelLarge, color = sig.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { (id, name) -> QuickChip(name + if (voice == id) " ✓" else "", sig.info) { voice = id } }
            }
        }
        OutlinedTextField(stt, { stt = it }, label = { Text("听写模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            save(v.copy(baseUrl = url.trim(), apiKey = key.trim(), ttsModel = tts.trim(), ttsVoice = voice.trim(), sttModel = stt.trim()))
            result = "测试中…"
            scope.launch { result = Voice.test(context); Voice.speak(context, "你好，我是总控。") }
        }) { Text("保存并试听") }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        SwitchLine("主动找你时开口说（睡觉时段不说）", v.speakProactive) { save(v.copy(speakProactive = it)) }
        SwitchLine("对讲里的回答念出来", v.speakReplies) { save(v.copy(speakReplies = it)) }
        SwitchLine("它问完自动听你回答（手机自带语音输入时）", v.listenAfterAsk) { save(v.copy(listenAfterAsk = it)) }
    }
    Panel {
        Text("桌面宠物和主动程度", style = MaterialTheme.typography.titleSmall)
        fun setBoss(b: BossConfig) {
            var msg = ""
            app.store.updateConfig { c -> Policy.setBoss(c, b, System.currentTimeMillis()).also { msg = it.message }.config }
            com.zongkong.app.system.Pet.refresh(context)
            result = msg
        }
        Text("形象", style = MaterialTheme.typography.labelLarge, color = sig.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pet-kinds")) {
            PetView.Kind.entries.forEach { k ->
                val on = PetView.kindOf(boss.petKind) == k
                Column(
                    Modifier.weight(1f).background(if (on) sig.ai.copy(alpha = 0.12f) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { setBoss(boss.copy(petKind = k.name)) }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PetFigure(PetView.Mood.IDLE, Modifier.size(64.dp), kind = k)
                    Text(k.label + if (on) " ✓" else "", style = MaterialTheme.typography.labelLarge, color = if (on) sig.ai else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Hint(PetView.kindOf(boss.petKind).what + "。腮红、项圈、花盆的颜色跟着态度变；严格时会皱眉。")
        SwitchLine("放在桌面上（点它、按住它说话）", boss.pet) { setBoss(boss.copy(pet = it)) }
        Text("主动程度", style = MaterialTheme.typography.labelLarge, color = sig.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BossConfig.INITIATIVE.forEachIndexed { i, name -> QuickChip(name + if (boss.initiative == i) " ✓" else "", sig.ai) { setBoss(boss.copy(initiative = i)) } }
        }
        Hint("安静：只在出事时找你。标准：一天三次问候（上午、下午、晚上），加上抽查和回访。黏人：每一个半小时问你一次。调低、收起宠物 24 小时后生效；换形象马上生效。")
        Hint("桌面小部件：长按桌面 → 小组件 → 总控。")
    }
}

/** 语音服务商预设：点一下填好地址、模型、音色，剩下只要贴密钥。 */
private data class VoicePreset(val name: String, val url: String, val tts: String, val voice: String, val stt: String, val note: String, val voices: List<Pair<String, String>>)

private val VOICE_PRESETS = listOf(
    VoicePreset(
        "硅基流动", "https://api.siliconflow.cn/v1", "FunAudioLLM/CosyVoice2-0.5B", "FunAudioLLM/CosyVoice2-0.5B:anna", "FunAudioLLM/SenseVoiceSmall",
        "国内直连，中文自然。去 cloud.siliconflow.cn 注册 → API 密钥 → 新建，贴到下面。",
        listOf("anna" to "沉稳女声", "claire" to "温柔女声", "bella" to "激情女声", "diana" to "欢快女声", "alex" to "沉稳男声", "benjamin" to "低沉男声", "charles" to "磁性男声", "david" to "欢快男声")
            .map { (id, n) -> "FunAudioLLM/CosyVoice2-0.5B:$id" to n },
    ),
    VoicePreset(
        "OpenAI", "https://api.openai.com/v1", "tts-1", "alloy", "whisper-1",
        "要能访问 OpenAI。platform.openai.com → API keys → 新建，贴到下面。",
        listOf("alloy" to "alloy 中性", "nova" to "nova 女声", "shimmer" to "shimmer 女声", "echo" to "echo 男声", "onyx" to "onyx 低沉", "fable" to "fable 英式"),
    ),
)

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
