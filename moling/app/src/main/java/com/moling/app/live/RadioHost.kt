package com.moling.app.live

import android.content.Context
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

    /** 已经念过的段（session:segment:generation），避免重组 / tick 重复开念。 */
    private var spokenKey = ""

    fun init(context: Context) {
        appContext = context.applicationContext
        Speaker.prepare(context.applicationContext)
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
        }
        publish()
        syncSpeech()
        return r
    }

    fun stop(sessionId: String, now: Long = System.currentTimeMillis()): PortResult<RadioSession> {
        val r = engine.stop(sessionId, now)
        Speaker.stop()
        spokenKey = ""
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
