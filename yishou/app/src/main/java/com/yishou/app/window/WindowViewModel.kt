package com.yishou.app.window

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.card.Card
import com.yishou.app.card.CardSession
import com.yishou.app.card.Conf
import com.yishou.app.card.Progress
import com.yishou.app.card.SessionState
import com.yishou.app.card.Step
import com.yishou.app.card.VoiceAnswer
import com.yishou.app.data.CardMode
import com.yishou.app.data.SetStatus
import com.yishou.app.gate.GateService
import com.yishou.app.settings.AppZone
import com.yishou.app.speech.Heard
import com.yishou.app.speech.ListenPhase
import com.yishou.app.speech.Spoken
import com.yishou.app.speech.VoiceCommand
import com.yishou.app.speech.VoiceCommands
import com.yishou.app.stats.DayStats
import com.yishou.app.system.Haptics
import com.yishou.app.system.Notifications
import com.yishou.app.system.Reminders
import com.yishou.app.ui.CardActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WindowState(
    val loading: Boolean = true,
    /** 正在进行的窗口；null 表示现在不在窗口里 */
    val span: WindowClock.Span? = null,
    /** 页面打开期间窗口结束了 */
    val ended: Boolean = false,
    val remainingMs: Long = 0,
    val nextStart: Long? = null,
    val windowMinutes: Int = 45,
    /** “先停”之后，等休息结束或用户点“提前回来” */
    val paused: Boolean = false,
    val pauseUntil: Long = 0,
    /** 这一步已经提醒了几次 */
    val reminders: Int = 0,
    val noResponse: Boolean = false,
    /** 本窗口练完的组数 */
    val setsDone: Int = 0,
    val ttsProblem: String? = null,
    val restMinutes: Int = 2,
    /** 正在热身（出题同时在跑） */
    val warmingUp: Boolean = false,
    /** 语音陪练：每一步读一句、听你说 */
    val voiceOn: Boolean = true,
    val listen: ListenPhase = ListenPhase.IDLE,
    val speaking: Boolean = false,
    /** 语音这边的一句提示：没听到、听写失败、还差什么 */
    val voiceNote: String? = null,
    val needMic: Boolean = false,
    /** 这一步已经把你拉回来几次 */
    val pulled: Int = 0,
)

/**
 * 陪练窗口：倒计时内一组接一组题卡。语音开着时像面对面：每一步只说一句短问话
 * （“选哪个，把握多大？”“依据哪一句？”“再查一个，错在哪？”），你直接开口答，
 * 说漏了什么就只追问那一项。没有动静时按 [IdleLadder] 提醒、震动、拉回。
 */
class WindowViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val speaker = Speaker(app)
    private val zone get() = AppZone.zone
    private val session: CardSession = yishou.newSession()
    val card: StateFlow<SessionState> = session.state

    private val _state = MutableStateFlow(WindowState())
    val state: StateFlow<WindowState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var reminderJob: Job? = null
    private var listenJob: Job? = null
    private val voice = yishou.voice

    /** 已经开口问过的那一步（题卡 id + 步骤 + 多查了几项） */
    private var promptedKey = ""
    private var lastActivity = System.currentTimeMillis()
    private var ladder = 0
    /** 下注时只说了选项、还没说把握 */
    private var partialOption: Int? = null

    val level = voice.level

    init {
        viewModelScope.launch { speaker.problem.collect { p -> _state.update { it.copy(ttsProblem = p) } } }
        viewModelScope.launch { speaker.speaking.collect { v -> _state.update { it.copy(speaking = v) } } }
        viewModelScope.launch { voice.phase.collect { v -> _state.update { it.copy(listen = v) } } }
        viewModelScope.launch { yishou.settings.app.collect { p -> _state.update { it.copy(voiceOn = p.voiceMode) } } }
        // 每到新的一步（新题、下一步），开口问一句
        viewModelScope.launch { session.state.collect { onCardState(it) } }
        // “看一眼”看到的题：作为下一张
        viewModelScope.launch {
            yishou.incomingCard.collect { d ->
                if (d != null && session.state.value.active) {
                    session.useDealt(d, checks = Progress.checks(yishou.cardEngine.history()))
                    yishou.incomingCard.value = null
                }
            }
        }
    }

    val actions = CardActions(
        bet = { o, c -> act { session.bet(o, c) } },
        evidence = { e -> act { session.evidence(e) } },
        check = { o, f -> act { session.check(o, f) } },
        change = { o -> act { session.change(o) } },
        hint = { act { session.hint() } },
        cause = { c -> act { session.cause(c) } },
        key = { o -> act { session.officialKey(o) } },
        hide = { session.hide() },
        note = { session.note(it) },
        predict = { session.predict(it) },
    )

    private fun act(block: suspend () -> Unit) {
        markActivity()
        speaker.stop()
        listenJob?.cancel()
        voice.cancel()
        viewModelScope.launch {
            val before = session.state.value.results.size
            block()
            val s = session.state.value
            if (s.results.size > before) {
                Haptics.play(yishou, if (s.results.last().firstCorrect) Haptics.Kind.PASS else Haptics.Kind.REDO)
                yishou.prefetchCards()
            }
        }
    }

    // ---------- 窗口 ----------

    fun refresh() {
        viewModelScope.launch {
            val prefs = yishou.settings.app.value
            val now = System.currentTimeMillis()
            val span = WindowClock.active(now, prefs, zone)
            _state.update {
                it.copy(
                    loading = false,
                    span = span,
                    ended = false,
                    remainingMs = span?.let { s -> s.end - now } ?: 0,
                    nextStart = WindowClock.nextScheduledStart(now, prefs, zone),
                    windowMinutes = prefs.windowMinutes,
                    setsDone = span?.let { s -> setsDoneSince(s.start) } ?: 0,
                    restMinutes = prefs.restMinutes,
                )
            }
            if (span != null) {
                WindowService.start(yishou)
                startTicker(span)
                if (!session.state.value.active) startSet(warm = prefs.warmup) else prompt(force = true)
            }
        }
    }

    private suspend fun setsDoneSince(start: Long): Int =
        yishou.database.coach().recentSets(50).count { it.status == SetStatus.DONE && (it.endedAt ?: 0) >= start }

    fun startSet(warm: Boolean) {
        _state.update { it.copy(warmingUp = warm) }
        promptedKey = ""
        if (warm) speak("先热身。吸气四秒，呼气六秒，跟着圈走。")
        viewModelScope.launch { session.start(CardMode.WINDOW, null, yishou.settings.app.value.setCards) }
    }

    fun warmupDone() {
        _state.update { it.copy(warmingUp = false) }
        promptedKey = ""
        onCardState(session.state.value)
    }

    fun startNow() {
        yishou.settings.updateApp { it.copy(manualWindowStart = System.currentTimeMillis(), windowPauseUntil = 0) }
        refresh()
    }

    fun stopEarly() {
        yishou.settings.updateApp { it.copy(windowStoppedAt = System.currentTimeMillis()) }
        onEnded()
    }

    private fun startTicker(span: WindowClock.Span) {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                val left = span.end - System.currentTimeMillis()
                if (left <= 0) {
                    onEnded()
                    break
                }
                _state.update { it.copy(remainingMs = left) }
                val s = _state.value
                if (s.paused && s.pauseUntil in 1..System.currentTimeMillis()) {
                    speak("休息结束，回来接着做。")
                    delay(2_500)
                    resume()
                }
                delay(1_000)
            }
        }
    }

    private fun onEnded() {
        tickJob?.cancel()
        WindowService.stop(yishou)
        // 结束窗口时立即释放投屏，不能等下一次分钟级巡检。
        yishou.stopService(Intent(yishou, com.yishou.app.look.ScreenLookService::class.java))
        Reminders.cancelPauseEnd(yishou)
        yishou.settings.updateApp { it.copy(windowPauseUntil = 0) }
        reminderJob?.cancel()
        speaker.stop()
        stopVoice()
        val span = _state.value.span
        viewModelScope.launch {
            val done = span?.let { setsDoneSince(it.start) } ?: 0
            _state.update { it.copy(ended = true, remainingMs = 0, setsDone = done) }
        }
    }

    private val active get() = _state.value.let { it.span != null && !it.ended && !it.paused && !it.warmingUp }

    // ---------- 每一步问一句 ----------

    private fun onCardState(c: SessionState) {
        val r = c.round
        if (c.done) {
            if (promptedKey != "done:${c.startedAt}") {
                promptedKey = "done:${c.startedAt}"
                reminderJob?.cancel()
                viewModelScope.launch {
                    val span = _state.value.span
                    _state.update { it.copy(setsDone = span?.let { s -> setsDoneSince(s.start) } ?: it.setsDone) }
                }
                val right = c.results.count { it.firstCorrect && it.help == 0 }
                speak("这组做完了，自己做对 $right 张。歇 ${_state.value.restMinutes} 分钟，还是再来一组？")
            }
            return
        }
        if (r == null) return
        val key = "${c.dealt?.row?.id}:${r.step}:${r.checks.size}:${c.hidden}:${c.needsKey}"
        if (key == promptedKey) return
        promptedKey = key
        markActivity()
        _state.update { it.copy(pulled = 0) }
        if (r.step == Step.BET && r.checks.isEmpty() && !c.hidden) Haptics.play(yishou, Haptics.Kind.NEW_MOVE)
        prompt(force = false)
        scheduleReminders()
    }

    /** 这一步的问话：短、口语、只问一件事 */
    private fun stepPrompt(c: SessionState): String? {
        val r = c.round ?: return null
        val card = r.card
        return when {
            c.needsKey -> "这是拍下的题，书上的答案是哪个？"
            r.step == Step.BET && c.level >= 2 && !c.hidden -> "先记材料，收起以后再答。"
            r.step == Step.BET -> "${Spoken.of(card.question, null)} 选哪个，把握多大？"
            r.step == Step.EVIDENCE -> "依据是哪一句？"
            r.step == Step.CHECK -> "再查一个：你没选的哪一项，错在哪？"
            r.step == Step.CHANGE -> "查完了，还选 ${Card.letter(r.bet ?: 0)} 吗？"
            else -> buildString {
                append("答案 ${Card.letter(card.answer)}。")
                append(if (r.firstCorrect) "首答对。" else "首答错。")
                append(if (r.evidenceOk) "依据也对。" else "依据是第 ${card.evidence.sorted().joinToString("、")} 句。")
                card.explain.split('。').firstOrNull()?.takeIf { it.isNotBlank() }?.let { append(it).append("。") }
            }
        }
    }

    private fun prompt(force: Boolean) {
        if (!active) return
        val c = session.state.value
        val text = stepPrompt(c) ?: return
        val reveal = c.round?.step == Step.REVEAL && !c.needsKey
        speak(text) {
            if (reveal) {
                // 语音陪练时：揭晓读完，停一下，自动下一张（不开语音就等你点“下一张”）
                if (_state.value.voiceOn) viewModelScope.launch {
                    delay(2_500)
                    if (active && session.state.value.round?.step == Step.REVEAL && !session.state.value.done) next()
                }
            } else {
                maybeListen()
            }
        }
        if (force) scheduleReminders()
    }

    fun next() {
        markActivity()
        viewModelScope.launch { session.next() }
    }

    fun retry() {
        viewModelScope.launch { session.deal() }
    }

    private fun speak(text: String, then: (() -> Unit)? = null) {
        if (yishou.settings.app.value.ttsEnabled) speaker.speak(text, then) else then?.invoke()
    }

    // ---------- 提醒梯子 ----------

    private fun markActivity() {
        lastActivity = System.currentTimeMillis()
        ladder = 0
        if (_state.value.reminders != 0 || _state.value.noResponse) _state.update { it.copy(reminders = 0, noResponse = false) }
    }

    private fun scheduleReminders() {
        reminderJob?.cancel()
        if (!active) return
        reminderJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val s = _state.value
                val c = session.state.value
                if (!active || c.loading || c.done || c.round == null) continue
                if (s.listen != ListenPhase.IDLE || s.speaking) continue
                val p = yishou.settings.app.value
                val lvl = IdleLadder.level(
                    System.currentTimeMillis() - lastActivity,
                    p.remindFirstMinutes, p.remindSecondMinutes, p.pullBackMinutes, p.pullBackEnabled,
                )
                if (lvl > ladder) {
                    ladder = lvl
                    if (IdleLadder.isPull(lvl)) pullBack(lvl - IdleLadder.FIRST_PULL_LEVEL + 1) else remind(lvl)
                }
            }
        }
    }

    private fun remind(n: Int) {
        if (!active) return
        _state.update { it.copy(reminders = n, noResponse = n >= 2) }
        Haptics.play(yishou, Haptics.Kind.REMIND)
        val ask = stepPrompt(session.state.value).orEmpty()
        speak("还在这一步。$ask 不会就说“提示”。") { maybeListen() }
    }

    private fun pullBack(k: Int) {
        if (!active) return
        _state.update { it.copy(pulled = k, noResponse = true) }
        Haptics.play(yishou, Haptics.Kind.PULL)
        val power = yishou.getSystemService(PowerManager::class.java)
        val canPull = power?.isInteractive == true && !WindowActivity.isVisible && GateService.mayPullFrom(GateService.foreground)
        if (canPull) {
            val intent = Intent(yishou, WindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!GateService.bringToFront(intent)) {
                Notifications.showReminder(yishou, Notifications.ID_WINDOW, "这张题还在等你", "已经提醒过两次。点开回到陪练窗口。", WindowActivity::class.java)
            }
            yishou.stats.event(DayStats.PULL_BACK)
        }
        speak("回来。${stepPrompt(session.state.value).orEmpty()}") { maybeListen() }
    }

    // ---------- 语音 ----------

    private fun maybeListen() {
        val s = _state.value
        val c = session.state.value
        if (!s.voiceOn || !active || c.round == null || c.loading || c.done) return
        if (c.round.step == Step.REVEAL && !c.needsKey) return
        if (!WindowActivity.isVisible || voice.phase.value != ListenPhase.IDLE) return
        if (!voice.hasPermission()) {
            _state.update { it.copy(needMic = true) }
            return
        }
        listenJob?.cancel()
        listenJob = viewModelScope.launch { onHeard(voice.listen()) }
    }

    fun overlayPrompt(): String? {
        val c = session.state.value
        if (c.mustPredict && c.predicted == null) return c.round?.card?.question?.let { "$it\n先说你的结论和理由，之后再选选项。" }
        return stepPrompt(c)
    }
    fun overlayAnswer(text: String) {
        if (text.isBlank() || !active) return
        val c = session.state.value
        if (c.mustPredict && c.predicted == null) session.predict(text)
        else onHeard(Heard.Text(text))
    }

    fun micTap() {
        val s = _state.value
        when {
            s.listen == ListenPhase.LISTENING -> voice.finish()
            s.listen == ListenPhase.TRANSCRIBING -> {}
            !voice.hasPermission() -> _state.update { it.copy(needMic = true) }
            else -> {
                speaker.stop()
                _state.update { it.copy(voiceNote = null) }
                listenJob?.cancel()
                listenJob = viewModelScope.launch { onHeard(voice.listen()) }
            }
        }
    }

    fun onMicPermission(granted: Boolean) {
        _state.update { it.copy(needMic = false, voiceNote = if (granted) null else "没有麦克风权限。可以直接点屏幕作答。") }
        if (granted) viewModelScope.launch {
            delay(400)
            maybeListen()
        }
    }

    fun onHidden() {
        if (voice.phase.value == ListenPhase.LISTENING && listenJob?.isActive == true) {
            listenJob?.cancel()
            voice.cancel()
        }
    }

    fun setVoice(on: Boolean) {
        yishou.settings.updateApp { it.copy(voiceMode = on) }
        _state.update { it.copy(voiceOn = on) }
        if (on) maybeListen() else stopVoice()
    }

    private fun stopVoice() {
        listenJob?.cancel()
        voice.cancel()
    }

    /** 说的话按这一步拆：缺什么只追问什么 */
    private fun onHeard(h: Heard) {
        val text = when (h) {
            Heard.Nothing -> {
                _state.update { it.copy(voiceNote = "没听到。想好了点麦克风直接说，或者点屏幕。") }
                return
            }
            is Heard.Failed -> {
                _state.update { it.copy(voiceNote = "${h.message}。可以直接点屏幕作答。") }
                return
            }
            is Heard.Text -> h.text
        }
        markActivity()
        _state.update { it.copy(voiceNote = "听到：$text") }
        when (VoiceCommands.parse(text)) {
            VoiceCommand.DONT_KNOW, VoiceCommand.HINT, VoiceCommand.EXPLAIN, VoiceCommand.DEMO -> {
                actions.hint()
                viewModelScope.launch {
                    delay(300)
                    speak(session.state.value.round?.hintText() ?: "看解析。") { maybeListen() }
                }
                return
            }
            VoiceCommand.REPEAT -> {
                promptedKey = ""
                onCardState(session.state.value)
                return
            }
            VoiceCommand.PAUSE -> {
                pause(5)
                return
            }
            VoiceCommand.THINKING -> {
                speak("好，想好了点麦克风直接说。")
                return
            }
            else -> {}
        }
        val c = session.state.value
        val r = c.round ?: return
        val a = VoiceAnswer.parse(text)
        fun ask(q: String) = speak(q) { maybeListen() }
        when {
            c.needsKey -> a.option?.let { actions.key(it) } ?: ask("书上的答案是 A、B、C 还是 D？")
            r.step == Step.BET -> {
                val option = a.option ?: partialOption
                when {
                    option == null -> ask("选哪一项？")
                    a.conf == null -> {
                        partialOption = option
                        ask("${Card.letter(option)}。把握呢：高、中还是低？")
                    }
                    else -> {
                        partialOption = null
                        actions.bet(option, a.conf)
                    }
                }
            }
            r.step == Step.EVIDENCE -> if (a.sentences.isNotEmpty()) actions.evidence(a.sentences) else ask("说第几句。")
            r.step == Step.CHECK -> {
                val option = a.option?.takeIf { it in r.checkable }
                when {
                    option == null -> ask("查哪一项？要是你没选的。")
                    a.flaw == null -> ask("${Card.letter(option)} 错在哪？比如无中生有、绝对化、偷换概念，或者没毛病。")
                    else -> actions.check(option, a.flaw)
                }
            }
            r.step == Step.CHANGE -> when {
                a.keep -> actions.change(null)
                a.option != null -> actions.change(a.option)
                else -> ask("不改，还是改成哪一项？")
            }
            else -> if ("下一" in text || "继续" in text) next()
        }
    }

    /** “先停”：休息 minutes 分钟，窗口计时继续，到点叫你回来 */
    fun pause(minutes: Int) {
        if (_state.value.span == null || _state.value.ended) return
        reminderJob?.cancel()
        speaker.stop()
        stopVoice()
        val until = System.currentTimeMillis() + minutes * 60_000L
        Reminders.schedulePauseEnd(yishou, until)
        yishou.settings.updateApp { it.copy(windowPauseUntil = until) }
        _state.update { it.copy(paused = true, pauseUntil = until) }
    }

    fun resume() {
        Reminders.cancelPauseEnd(yishou)
        yishou.settings.updateApp { it.copy(windowPauseUntil = 0) }
        _state.update { it.copy(paused = false, pauseUntil = 0) }
        promptedKey = ""
        val c = session.state.value
        if (!c.active || c.done) startSet(warm = false) else onCardState(c)
    }

    /** 组末：歇一下 / 再来一组 */
    fun restAfterSet() = pause(_state.value.restMinutes)

    fun againAfterSet() = startSet(warm = false)

    fun thinking() {
        if (!active) return
        speaker.stop()
        markActivity()
        scheduleReminders()
    }

    override fun onCleared() {
        stopVoice()
        speaker.shutdown()
        super.onCleared()
    }
}
