package com.yishou.app.partner

import android.os.SystemClock
import com.yishou.app.YishouApp
import com.yishou.app.data.EchoKind
import com.yishou.app.data.PartnerSession
import com.yishou.app.llm.ChatClient
import com.yishou.app.llm.LlmDirector
import com.yishou.app.log.RunLog
import com.yishou.app.speech.Heard
import com.yishou.app.voice.HttpStreamingTextModel
import com.yishou.app.window.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 一手自己的语音伙伴（路线 B）：听写 → 文字模型 → 朗读，全在一手里。
 * 这里把纯逻辑的 [PartnerController] 接到手机上：麦克风听、朗读说、结果写库。
 * 一问一答轮流说：它说话时不开麦（免得把自己的声音当成你在说），要打断就点“停”。
 */
class PartnerVoice(private val app: YishouApp) {

    data class Running(val plan: PartnerPlan, val live: LiveState)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    private var controller: PartnerController? = null
    private var scope: CoroutineScope? = null
    private var speaker: Speaker? = null
    private var plan: PartnerPlan? = null
    private var startedWall = 0L
    private var closed = false

    val active: Boolean get() = controller != null

    /** 开一场；不能开时返回原因 */
    suspend fun start(plan: PartnerPlan): String? {
        if (controller != null) return "已经在聊了"
        if (!app.settings.llm.value.isComplete) return "先在设置里填好文字模型接口（地址、密钥、模型名）。"
        if (!app.voice.hasPermission()) return "需要麦克风权限。"
        if (app.partner.pendingClose() != null) return "上一场还没收线，先在下面收线。"
        val dao = app.database.partners()
        val last = dao.latestClosed()
        val memory = app.database.echoes().recent()
            .filter { it.kind == EchoKind.LAW || it.kind == EchoKind.RULE || it.kind == EchoKind.CONCLUSION }
            .take(5).map { it.text }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val sp = Speaker(app)
        val mouth = Mouth { text ->
            // 朗读不可用时不能卡死在这一句：按字数给个上限
            withTimeoutOrNull(4_000L + text.length * 400L) {
                suspendCancellableCoroutine { cont ->
                    sp.speak(text) { if (cont.isActive) cont.resume(Unit) }
                    cont.invokeOnCancellation { sp.stop() }
                }
            } ?: sp.stop()
        }
        val chat = { ChatClient(config = { app.settings.llm.value }, http = ChatClient.defaultHttpClient(45), usage = app.stats) }
        val c = PartnerController(
            plan = plan,
            talker = HttpStreamingTextModel(chat()),
            director = LlmDirector(chat()),
            mouth = mouth,
            scope = sc,
            clock = SystemClock::elapsedRealtime,
            lastClose = last?.closeLine,
            lastNext = last?.nextStep,
            memory = memory,
            onClosed = { line, next, _ -> save(line, next) },
        )
        this.plan = plan
        controller = c
        scope = sc
        speaker = sp
        startedWall = System.currentTimeMillis()
        closed = false
        sc.launch { c.state.collect { st -> _running.value = Running(plan, st) } }
        // 它一开口就把麦关了；你一出声，等回答的计时先停
        sc.launch {
            c.state.collect { st -> if (st.phase != Phase.LISTENING) app.voice.cancel() }
        }
        sc.launch {
            app.voice.level.collect { lv -> if (lv > 0.35f) c.activity() }
        }
        sc.launch { listenLoop(c) }
        c.start()
        return null
    }

    private suspend fun listenLoop(c: PartnerController) {
        while (scope?.isActive == true) {
            val st = c.state.value
            if (st.stage == Stage.ENDED) {
                delay(1_500)
                stop()
                return
            }
            if (st.phase != Phase.LISTENING) {
                delay(150)
                continue
            }
            when (val h = app.voice.listen()) {
                is Heard.Text -> c.heard(h.text)
                is Heard.Failed -> {
                    RunLog.e("语音伙伴", h.message)
                    delay(2_000)
                }
                Heard.Nothing -> delay(100)
            }
        }
    }

    fun interrupt() {
        app.voice.cancel()
        controller?.interrupt()
    }

    fun end() = controller?.end()

    fun setGear(gear: Gear) = controller?.setGear(gear)

    /** 关掉这一场。没收线的留一条待收线记录，下次在伴读页或入口页补上。 */
    fun stop() {
        val c = controller ?: return
        controller = null
        c.stop()
        app.voice.cancel()
        speaker?.shutdown()
        speaker = null
        val p = plan
        val wasClosed = closed
        val started = startedWall
        val sc = scope
        scope = null
        _running.value = null
        app.appScopeLaunch {
            if (!wasClosed && p != null) {
                app.database.partners().insert(
                    PartnerSession(
                        material = p.material.name, goal = p.goal.trim(), minutes = p.minutes, gear = p.gear.name,
                        startedAt = started, endedAt = System.currentTimeMillis(),
                        presentMs = System.currentTimeMillis() - started, route = ROUTE,
                    ),
                )
                app.partner.reload()
            }
        }
        sc?.cancel()
    }

    private suspend fun save(line: String, next: String) {
        val p = plan ?: return
        closed = true
        app.database.partners().insert(
            PartnerSession(
                material = p.material.name, goal = p.goal.trim(), minutes = p.minutes, gear = p.gear.name,
                startedAt = startedWall, endedAt = System.currentTimeMillis(),
                presentMs = System.currentTimeMillis() - startedWall,
                closeLine = line.trim(), nextStep = next.trim(), route = ROUTE,
            ),
        )
        app.echo(line, EchoKind.CONCLUSION, "partner")
    }

    companion object {
        const val ROUTE = "api"
    }
}
