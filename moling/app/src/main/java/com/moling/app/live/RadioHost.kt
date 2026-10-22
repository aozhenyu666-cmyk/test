package com.moling.app.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.media3.common.Player
import com.moling.app.ui.Speaker
import com.moling.core.live.Radio
import com.moling.core.live.RadioContent
import com.moling.core.live.RadioEngine
import com.moling.core.live.RadioSession
import com.moling.core.live.RadioState
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
 * 电台的 App 控制器（R2；第十五批 B6 起声音走 Media3）：一份会话、一个 StateFlow，界面只读它，不靠重组重复 start。
 *
 *  - 只播案板里已有的材料 / 稿子正文（[RadioContent]），播放过程中不联网（云端语音模型除外，它只是把字变成声音）；
 *  - 每一段先合成成音频文件（[Speaker.renderToFile]），再交给 [RadioPlayer]（ExoPlayer）：音频焦点、拔耳机暂停、
 *    来电 / 导航让路、暂停后从原位置接着播，都由播放器负责；通知栏 / 锁屏控制在 [RadioService]；
 *  - 状态机（core 的 [RadioEngine]）仍然说了算：合成 / 播完的回调带 session / segment / generation，对不上就丢，停了不会自己又开播；
 *  - 播放器因为任何原因停在半路（他在通知栏点暂停、来电、拔耳机），状态机跟着暂停；播放器接着放了，状态机跟着继续；
 *  - 熄屏 / 离开面板：默认停掉（和以前一样）；设置里打开“锁屏后继续播”才继续在后台放。
 */
object RadioHost {
    private val engine = RadioEngine()
    private val _state = MutableStateFlow<RadioSession?>(null)
    val state: StateFlow<RadioSession?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var appContext: Context? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    /** 已经交给播放器（或在合成中）的段（session:segment:generation），避免重组 / tick 重复开念。 */
    private var spokenKey = ""
    private var spoken: Triple<String, String, Long>? = null

    /** 设置：锁屏 / 离开面板后继续播（默认关）。 */
    private const val PREF_BACKGROUND = "radio_background"

    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext != null) return
        appContext = app
        Speaker.prepare(app)
        RadioPlayer.addListener(app, object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) segmentEnded(RadioPlayer.currentKey)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = followPlayer(isPlaying)
        })
        val off = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (!backgroundAllowed()) stopAll()
            }
        }
        screenOffReceiver = off
        runCatching {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(off, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(off, filter)
        }
    }

    fun backgroundAllowed(): Boolean =
        appContext?.getSharedPreferences("presence", Context.MODE_PRIVATE)?.getBoolean(PREF_BACKGROUND, false) ?: false

    fun setBackgroundAllowed(on: Boolean) {
        appContext?.getSharedPreferences("presence", Context.MODE_PRIVATE)?.edit()?.putBoolean(PREF_BACKGROUND, on)?.apply()
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
        appContext?.let { RadioService.start(it) }
        publish()
        syncSpeech()
        return r
    }

    fun pause(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.pause(sessionId, now)
        if (r is PortResult.Ok) RadioPlayer.pause()
        publish()
        return r
    }

    fun resume(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.resume(sessionId, now)
        publish()
        if (r is PortResult.Ok) {
            // 播放器里还是这一段：从停下的地方接着放；不是（比如暂停时还没合成完）就重新合成这一段
            if (!RadioPlayer.resumeIfLoaded(spokenKey)) {
                spokenKey = ""
                syncSpeech()
            }
        }
        return r
    }

    fun skip(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.skip(sessionId, now)
        if (r is PortResult.Ok) {
            RadioPlayer.stop()
            spokenKey = ""
        }
        publish()
        syncSpeech()
        finishIfOver()
        return r
    }

    fun stop(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.stop(sessionId, now)
        release()
        return r
    }

    /** 通知栏 / 锁屏的“下一段”。 */
    fun skipCurrent() {
        engine.current?.takeIf { it.active }?.let { skip(it.id) }
    }

    /** 通知栏的“停止”、划掉 App。 */
    fun stopCurrent(reason: String) {
        engine.current?.takeIf { it.active }?.let { engine.stop(it.id, System.currentTimeMillis(), reason) }
        release()
    }

    /**
     * 界面离开 / 熄屏：没开“锁屏后继续播”就停。
     * 只管电台自己：没有电台在播时什么都不做，也不碰她的耳语、对话朗读（第十四批复审修过的问题）。
     */
    fun stopAll() {
        if (engine.current?.active != true && RadioPlayer.currentKey.isEmpty() && tickJob == null) return
        if (backgroundAllowed() && engine.current?.active == true) return
        engine.current?.takeIf { it.active }?.let { engine.stop(it.id, System.currentTimeMillis()) }
        release()
    }

    private fun release() {
        RadioPlayer.stop()
        spokenKey = ""
        spoken = null
        publish()
        tickJob?.cancel()
        tickJob = null
        Speaker.clearRendered()
        appContext?.let { RadioService.stop(it) }
    }

    /** 播放器停在半路 / 又放起来了：状态机跟着它走（来电、拔耳机、通知栏暂停都会走到这里）。 */
    private fun followPlayer(isPlaying: Boolean) {
        val s = engine.current ?: return
        if (spokenKey.isEmpty() || RadioPlayer.currentKey != spokenKey) return
        val now = System.currentTimeMillis()
        // 只认“停在半路”：还在加载 / 已经放完的不算暂停
        if (!isPlaying && s.state == RadioState.PLAYING && playerState() == Player.STATE_READY) {
            engine.pause(s.id, now)
            publish()
        } else if (isPlaying && s.state == RadioState.PAUSED) {
            engine.resume(s.id, now)
            publish()
        }
    }

    private fun playerState(): Int = appContext?.let { RadioPlayer.player(it).playbackState } ?: Player.STATE_IDLE

    /** 一段播完：状态机认这一段才往下走。 */
    private fun segmentEnded(key: String) {
        if (key.isEmpty() || key != spokenKey) return
        val (sessionId, segmentId, generation) = spoken ?: return
        val now = System.currentTimeMillis()
        if (engine.onSegmentDone(sessionId, segmentId, generation, now)) {
            publish()
            syncSpeech()
            finishIfOver()
        }
    }

    /** 讲完 / 到 5 分钟：释放播放器和后台服务。 */
    private fun finishIfOver() {
        if (engine.current?.active != true) release()
    }

    /** 定时推进（1 秒一次，只在有活动会话时跑）。正在出声的那一段放完以前不按估计时长往下跳。 */
    private fun ensureTick() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                if (RadioPlayer.isPlaying(spokenKey)) {
                    publish()
                    continue
                }
                val s = engine.tick(now) ?: break
                publish()
                syncSpeech()
                if (!s.active) break
            }
            tickJob = null
            finishIfOver()
        }
    }

    /**
     * 念当前这一段：只有正在播、且这一段还没交出去才开始。先合成成文件，回来时状态机还认才交给播放器；
     * 合成不了就只出文字（段按估计时长往下走），不硬外放、不卡住。
     */
    private fun syncSpeech() {
        val s = engine.current ?: return
        if (s.state != RadioState.PLAYING) return
        val seg = s.current ?: return
        if (seg.text.isBlank()) return
        val key = "${s.id}:${seg.id}:${s.generation}"
        if (key == spokenKey) return
        spokenKey = key
        spoken = Triple(s.id, seg.id, s.generation)
        val sessionId = s.id
        val segmentId = seg.id
        val generation = s.generation
        ensureTick()
        if (!engine.accepts(sessionId, segmentId, generation)) return
        val ctx = appContext ?: return
        Speaker.renderToFile(seg.text, "${sessionId}-${segmentId}-$generation") { file ->
            // 合成回来时已经停止 / 跳过 / 换了会话：不播
            if (file == null || spokenKey != key || !engine.accepts(sessionId, segmentId, generation)) return@renderToFile
            RadioPlayer.play(ctx, file, key, seg.text.take(24))
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
