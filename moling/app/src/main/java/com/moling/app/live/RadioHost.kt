package com.moling.app.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.moling.app.ui.Speaker
import com.moling.core.live.Radio
import com.moling.core.live.RadioContent
import com.moling.core.live.RadioEngine
import com.moling.core.live.RadioSegment
import com.moling.core.live.RadioSession
import com.moling.core.live.RadioState
import com.moling.core.live.SegmentKind
import com.moling.core.workspace.Focus
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 电台的 App 控制器（R2）：一份会话、一个 StateFlow，界面只读它，不靠重组重复 start。
 *
 * 只播案板里已有的材料 / 稿子正文（[RadioContent]），播放过程中不联网。
 * 声音走现有的 [Speaker]（系统朗读 / 设置里配的语音模型），停止立即掐断；
 * TTS 的迟到回调带 session / segment / generation，状态机对不上就丢掉，不会自己又开播。
 */
object RadioHost {
    private val engine = RadioEngine()
    private val _state = MutableStateFlow<RadioSession?>(null)
    val state: StateFlow<RadioSession?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var appContext: Context? = null

    // R6 音频焦点（本批只做最小正确版）：出声前要焦点；失焦 / 拔耳机 / 来电 / 熄屏 / 离开面板一律停。
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusGranted = false
    private var noisyReceiver: BroadcastReceiver? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    /** 已经念过的段（session:segment:generation），避免重组 / tick 重复开念。 */
    private var spokenKey = ""

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        Speaker.prepare(app)
        if (audio == null) {
            audio = app.getSystemService(AudioManager::class.java)
            // 照 Whisper 的写法请求焦点：来电 / 导航 / 别的应用要出声，系统收回焦点时马上停。
            focusListener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change < 0) stopAll()
            }
            val noisy = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = stopAll()
            }
            noisyReceiver = noisy
            register(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            val off = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = stopAll()
            }
            screenOffReceiver = off
            register(app, off, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    /** 动态注册系统广播：Android 13 起要写明不导出；系统广播照常收得到。 */
    private fun register(app: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
        }
    }

    /** 出声前拿一次音频焦点；拿不到就只出文字、不出声（不硬抢别人的声音）。 */
    private fun takeFocus(): Boolean {
        if (focusGranted) return true
        val am = audio ?: return false
        val listener = focusListener ?: return false
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
            .also { focusRequest = it }
        focusGranted = runCatching { am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
        return focusGranted
    }

    private fun dropFocus() {
        if (!focusGranted) return
        focusGranted = false
        val am = audio ?: return
        val request = focusRequest ?: return
        runCatching { am.abandonAudioFocusRequest(request) }
    }

    fun start(
        focus: Focus,
        ws: WorkbenchState,
        now: Long = System.currentTimeMillis(),
        sourceIds: List<String> = focus.materialIds,
    ): PortResult<RadioSession> {
        val segments = RadioContent.fromWorkbench(ws, focus, now)
        val r = engine.start(focus.id, sourceIds.ifEmpty { focus.materialIds }, segments, now)
        val s = (r as? PortResult.Ok)?.value ?: return r
        // 没有材料：不装成“准备中”，给一句能看懂的结束原因
        if (segments.isEmpty()) engine.onPrepared(s.id, s.generation, emptyList(), now)
        publish()
        syncSpeech()
        return r
    }

    fun pause(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.pause(sessionId, now)
        if (r is PortResult.Ok) {
            Speaker.stop()
            spokenKey = ""
            dropFocus()
        }
        publish()
        return r
    }

    fun resume(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.resume(sessionId, now)
        publish()
        syncSpeech()
        return r
    }

    fun skip(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.skip(sessionId, now)
        if (r is PortResult.Ok) {
            Speaker.stop()
            spokenKey = ""
            dropFocus()
        }
        publish()
        syncSpeech()
        return r
    }

    fun stop(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.stop(sessionId, now)
        Speaker.stop()
        spokenKey = ""
        dropFocus()
        publish()
        tickJob?.cancel()
        tickJob = null
        return r
    }

    /** 界面离开 / 进程结束：立刻停止并释放。 */
    fun stopAll() {
        engine.current?.let { engine.stop(it.id, System.currentTimeMillis()) }
        Speaker.stop()
        spokenKey = ""
        dropFocus()
        publish()
        tickJob?.cancel()
        tickJob = null
    }

    /** 定时推进（1 秒一次，只在有活动会话时跑）。 */
    private fun ensureTick() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val s = engine.tick(now) ?: break
                publish()
                syncSpeech()
                if (!s.active) break
            }
            dropFocus()
            tickJob = null
        }
    }

    /**
     * 念当前这一段。只有正在播、且这一段还没念过才开念；
     * 停止 / 暂停 / 跳过之后 spokenKey 被清掉，但 [syncSpeech] 看状态不会再开。
     */
    private fun syncSpeech() {
        val s = engine.current ?: return
        if (s.state != RadioState.PLAYING) return
        val seg = s.current ?: return
        if (seg.text.isBlank()) return
        val key = "${s.id}:${seg.id}:${s.generation}"
        if (key == spokenKey) return
        spokenKey = key
        val sessionId = s.id
        val segmentId = seg.id
        val generation = s.generation
        ensureTick()
        // 拿不到音频焦点就只出文字：段按 deadline 往下走，不硬外放。
        if (!takeFocus()) return
        Speaker.speak(seg.text) {
            // 迟到 / 已经停止的回调：状态机不认就不动
            val now = System.currentTimeMillis()
            if (engine.onSegmentDone(sessionId, segmentId, generation, now)) {
                publish()
                syncSpeech()
            }
        }
    }

    private fun publish() {
        _state.value = engine.current
    }

    /** 面板用：这段剩多少秒（暂停时是冻结的剩余）。 */
    fun remainingSeconds(now: Long): Int = engine.current?.let { (Radio.remainingMs(it, now) / 1000).toInt() } ?: 0

    /** 面板用：只给来源 id 对应的文字（找得到才显示，找不到不编）。 */
    fun sourceLabel(ws: WorkbenchState, id: String): String {
        ws.materials.firstOrNull { it.id == id }?.let { return it.title.ifBlank { it.text.take(20) } }
        if (id.startsWith("m-")) return "来源已删除"
        return id
    }

    fun errorText(e: PortError): String = when (e.code) {
        "NOT_FOUND" -> "这个电台已经结束了"
        "BAD_ACTION" -> "现在不能这么做"
        "QUIET" -> "现在是安静期"
        else -> e.message
    }
}
