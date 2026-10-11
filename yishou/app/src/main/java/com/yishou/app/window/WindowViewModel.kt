package com.yishou.app.window

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.coach.CoachState
import com.yishou.app.coach.SetEngine
import com.yishou.app.data.MoveVerdict
import com.yishou.app.data.RoundSource
import com.yishou.app.data.SetStatus
import com.yishou.app.data.Task
import com.yishou.app.gate.GateService
import com.yishou.app.settings.AppZone
import com.yishou.app.speech.Heard
import com.yishou.app.speech.ListenPhase
import com.yishou.app.speech.Spoken
import com.yishou.app.speech.VoiceCommand
import com.yishou.app.speech.VoiceCommands
import com.yishou.app.stats.DayStats
import com.yishou.app.system.AttachmentController
import com.yishou.app.system.Haptics
import com.yishou.app.system.Notifications
import com.yishou.app.system.Reminders
import com.yishou.app.ui.DOMAIN_PRESETS
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
    val task: Task? = null,
    /** 当前这一问（教练组里等着回答的那一手） */
    val coachMove: String? = null,
    val answer: String = "",
    /** “先停”之后，等休息结束或用户点“提前回来” */
    val paused: Boolean = false,
    /** 休息到什么时候；到点自动回到下一问 */
    val pauseUntil: Long = 0,
    /** 这一问已经提醒了几次 */
    val reminders: Int = 0,
    val noResponse: Boolean = false,
    /** 本窗口练完的组数 */
    val setsDone: Int = 0,
    val ttsProblem: String? = null,
    val restMinutes: Int = 2,
    /** 正在热身（开组请求同时在跑） */
    val warmingUp: Boolean = false,
    /** 离开了多少秒、该先复述结论链；null 表示不用 */
    val recallAway: Long? = null,
    /** 语音陪练：读完题自动听你说 */
    val voiceOn: Boolean = true,
    val listen: ListenPhase = ListenPhase.IDLE,
    /** 教练正在说话 */
    val speaking: Boolean = false,
    /** 听写好了，几秒后自动发出；0 表示没有在倒数 */
    val autoSendLeft: Int = 0,
    /** 语音这边的一句提示：没听到、听写失败等 */
    val voiceNote: String? = null,
    /** 需要请求麦克风权限 */
    val needMic: Boolean = false,
    /** 这一问已经把你拉回来几次 */
    val pulled: Int = 0,
)

/**
 * 陪练窗口：倒计时内一组接一组。每一问读口语版，语音陪练开着时读完就听你说，
 * 听写好了倒数几秒自动发出；判完先说一句结果（过了 / 再改一下），再说下一问。
 * 没有动静时按 [IdleLadder] 升级：朗读两次，再把窗口页拉回前台；每一步都有自己的震法。
 */
class WindowViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()
    private val engine: SetEngine = yishou.coachEngine
    private val speaker = Speaker(app)
    private val zone get() = AppZone.zone

    val attachment = AttachmentController(yishou, viewModelScope)

    private val _state = MutableStateFlow(WindowState())
    val state: StateFlow<WindowState> = _state.asStateFlow()

    /** 教练组的状态，页面直接画 */
    val coach: StateFlow<CoachState> = engine.state

    private var tickJob: Job? = null
    private var reminderJob: Job? = null
    private var listenJob: Job? = null
    private var autoSendJob: Job? = null
    private val voice = yishou.voice

    /** 已经读过的那一问（SetMove.id） */
    private var spokenMoveId = -1L
    /** 最后一次有动静的时间，无回应梯子从这里算 */
    private var lastActivity = System.currentTimeMillis()
    private var ladder = 0
    /** 判完以后先说的那一句，说完再说下一问 */
    private var pendingVerdict: String? = null
    private var hiddenAt = 0L

    init {
        viewModelScope.launch { speaker.problem.collect { p -> _state.update { it.copy(ttsProblem = p) } } }
        viewModelScope.launch { speaker.speaking.collect { v -> _state.update { it.copy(speaking = v) } } }
        viewModelScope.launch { voice.phase.collect { v -> _state.update { it.copy(listen = v) } } }
        viewModelScope.launch { yishou.settings.app.collect { p -> _state.update { it.copy(voiceOn = p.voiceMode) } } }
        // 教练那边出了新的一问（开组、接力、重做、看屏开组）：页面跟着变，读出来
        viewModelScope.launch {
            engine.state.collect { c ->
                val m = c.current
                _state.update { it.copy(coachMove = m?.question) }
                if (m != null && c.busy == null && m.id != spokenMoveId) presentMove()
            }
        }
    }

    /** 音量（0–1），画波纹用 */
    val level = voice.level

    /** 重新判断现在是否在窗口里；在窗口里又没有在练的组，就按当前领域自动开一组。 */
    fun refresh() {
        viewModelScope.launch {
            val prefs = yishou.settings.app.value
            val now = System.currentTimeMillis()
            val span = WindowClock.active(now, prefs, zone)
            val task = dao.getCurrentTask()
            _state.update {
                it.copy(
                    loading = false,
                    span = span,
                    ended = false,
                    remainingMs = span?.let { s -> s.end - now } ?: 0,
                    nextStart = WindowClock.nextScheduledStart(now, prefs, zone),
                    windowMinutes = prefs.windowMinutes,
                    task = task,
                    setsDone = span?.let { s -> setsDoneSince(s.start) } ?: 0,
                    restMinutes = prefs.restMinutes,
                )
            }
            if (span != null) {
                WindowService.start(yishou)
                startTicker(span)
                val c = engine.state.value
                if (c.set == null && c.ended == null && c.busy == null) openSet(warm = prefs.warmup)
                else presentMove()
            }
        }
    }

    private suspend fun setsDoneSince(start: Long): Int =
        yishou.database.coach().recentSets(50).count { it.status == SetStatus.DONE && (it.endedAt ?: 0) >= start }

    /** 开一组：当前领域（没有就用第一个预设），教练建议的主练面，教练出材料。 */
    fun openSet(warm: Boolean, material: String? = null, face: Int? = null, domain: String? = null) {
        viewModelScope.launch {
            val task = ensureTask(domain)
            _state.update { it.copy(task = task, warmingUp = warm) }
            if (warm) speak("先热身。吸气四秒，呼气六秒，跟着圈走。")
            engine.open(task, face ?: engine.suggestFace(), material)
        }
    }

    private suspend fun ensureTask(domain: String?): Task {
        val current = dao.getCurrentTask()
        if (domain == null && current != null) return current
        val name = domain?.trim()?.ifBlank { null } ?: DOMAIN_PRESETS.first()
        dao.allTasks().firstOrNull { it.title == name }?.let {
            if (!it.isCurrent) dao.switchCurrentTask(it.id)
            return it.copy(isCurrent = true)
        }
        val now = System.currentTimeMillis()
        val t = Task(title = name, goal = "", material = null, isCurrent = true, createdAt = now)
        return t.copy(id = dao.createCurrentTask(t, "", "", "", now))
    }

    fun warmupDone(score: String?) {
        _state.update { it.copy(warmingUp = false) }
        viewModelScope.launch {
            val set = engine.state.value.set
            if (score != null && set != null) yishou.database.coach().updateSet(set.copy(warmup = score))
            spokenMoveId = -1
            presentMove()
        }
    }

    /** 手动开始一个窗口（时长按设置）。 */
    fun startNow() {
        yishou.settings.updateApp { it.copy(manualWindowStart = System.currentTimeMillis(), windowPauseUntil = 0) }
        refresh()
    }

    /** 提前结束当前窗口。 */
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
                    speak("休息结束，回到这一问。")
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

    private val active get() = _state.value.let { it.span != null && !it.ended && !it.paused && !it.warmingUp && it.recallAway == null }

    // ---------- 出题、朗读、提醒梯子 ----------

    /** 新的一问出现时：先说判定结果（如果有），再读这一问，从头计时。 */
    private fun presentMove() {
        val m = engine.state.value.current ?: return
        if (!active) return
        if (m.id != spokenMoveId) {
            spokenMoveId = m.id
            markActivity()
            _state.update { it.copy(pulled = 0) }
            Haptics.play(yishou, Haptics.Kind.NEW_MOVE)
            speakMove()
        }
        scheduleReminders()
    }

    private fun spokenText(): String {
        val m = engine.state.value.current ?: return ""
        return Spoken.of(m.question, m.say)
    }

    private fun speakMove() {
        val verdict = pendingVerdict
        pendingVerdict = null
        if (!yishou.settings.app.value.ttsEnabled) {
            maybeListen()
            return
        }
        val text = if (verdict != null) "$verdict 下一问：${spokenText()}" else spokenText()
        speaker.speak(text) { maybeListen() }
    }

    private fun speak(text: String, then: (() -> Unit)? = null) {
        if (yishou.settings.app.value.ttsEnabled) speaker.speak(text, then) else then?.invoke()
    }

    private fun markActivity() {
        lastActivity = System.currentTimeMillis()
        ladder = 0
        if (_state.value.reminders != 0 || _state.value.noResponse) _state.update { it.copy(reminders = 0, noResponse = false) }
    }

    private fun scheduleReminders() {
        reminderJob?.cancel()
        if (!active || engine.state.value.current == null) return
        reminderJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val s = _state.value
                val c = engine.state.value
                if (!active || c.busy != null || c.current == null) continue
                if (s.listen != ListenPhase.IDLE || s.autoSendLeft > 0 || s.speaking) continue
                val p = yishou.settings.app.value
                val level = IdleLadder.level(
                    System.currentTimeMillis() - lastActivity,
                    p.remindFirstMinutes, p.remindSecondMinutes, p.pullBackMinutes, p.pullBackEnabled,
                )
                if (level > ladder) {
                    ladder = level
                    if (IdleLadder.isPull(level)) pullBack(level - IdleLadder.FIRST_PULL_LEVEL + 1) else remind(level)
                }
            }
        }
    }

    private fun remind(n: Int) {
        if (!active) return
        val starter = STARTERS[(n - 1).coerceIn(0, STARTERS.lastIndex)]
        _state.update { it.copy(reminders = n, noResponse = n >= 2) }
        Haptics.play(yishou, Haptics.Kind.REMIND)
        speak("这一问还在等你：${spokenText()} $starter") { maybeListen() }
    }

    /**
     * 拉回：两次提醒之后还没动静，把窗口页拉到前台（不管你在哪个应用），再读一遍这一问。
     * 你在窗口允许的学习应用、相机、电话里，或者屏幕关着时，不拉，只朗读和震动。
     */
    private fun pullBack(k: Int) {
        if (!active) return
        _state.update { it.copy(pulled = k, noResponse = true) }
        Haptics.play(yishou, Haptics.Kind.PULL)
        val power = yishou.getSystemService(PowerManager::class.java)
        val canPull = power?.isInteractive == true && !WindowActivity.isVisible && GateService.mayPullFrom(GateService.foreground)
        if (canPull) {
            val intent = Intent(yishou, WindowActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!GateService.bringToFront(intent)) {
                Notifications.showReminder(
                    yishou, Notifications.ID_WINDOW,
                    "这一问还在等你",
                    "已经提醒过两次。点开回到陪练窗口，从这一问接着走。",
                    WindowActivity::class.java,
                )
            }
            yishou.stats.event(DayStats.PULL_BACK)
        }
        speak("回来。这一问还在等你：${spokenText()}") { maybeListen() }
    }

    // ---------- 语音 ----------

    private fun maybeListen() {
        val s = _state.value
        val c = engine.state.value
        if (!s.voiceOn || !active || c.current == null || c.busy != null) return
        if (!WindowActivity.isVisible || s.autoSendLeft > 0 || voice.phase.value != ListenPhase.IDLE) return
        if (!voice.hasPermission()) {
            _state.update { it.copy(needMic = true) }
            return
        }
        listenJob?.cancel()
        listenJob = viewModelScope.launch { onHeard(voice.listen()) }
    }

    /** 点麦克风：教练在说就打断它；正在听就当说完了；否则开始听。 */
    fun micTap() {
        val s = _state.value
        when {
            s.listen == ListenPhase.LISTENING -> voice.finish()
            s.listen == ListenPhase.TRANSCRIBING -> {}
            !voice.hasPermission() -> _state.update { it.copy(needMic = true) }
            else -> {
                speaker.stop()
                cancelAutoSend()
                _state.update { it.copy(voiceNote = null) }
                listenJob?.cancel()
                listenJob = viewModelScope.launch { onHeard(voice.listen()) }
            }
        }
    }

    fun onMicPermission(granted: Boolean) {
        _state.update {
            it.copy(needMic = false, voiceNote = if (granted) null else "没有麦克风权限，语音陪练用不了。可以直接用输入法上的麦克风说。")
        }
        if (granted) viewModelScope.launch {
            delay(400)
            maybeListen()
        }
    }

    /** 窗口页退到后台：正在听就不听了；记下离开的时间，回来先复述结论链。 */
    fun onHidden() {
        hiddenAt = System.currentTimeMillis()
        if (voice.phase.value == ListenPhase.LISTENING && listenJob?.isActive == true) {
            listenJob?.cancel()
            voice.cancel()
        }
    }

    fun onVisible() {
        val away = if (hiddenAt == 0L) 0 else (System.currentTimeMillis() - hiddenAt) / 1000
        hiddenAt = 0
        val c = engine.state.value
        val s = _state.value
        if (away >= RECALL_AFTER_SECONDS && c.set != null && c.chain.isNotEmpty() && s.span != null && !s.ended && !s.warmingUp) {
            _state.update { it.copy(recallAway = away) }
            reminderJob?.cancel()
            speak("回来了。先别看，说出刚才的结论链。")
        }
    }

    fun recallDone(recalled: Int?) {
        val away = _state.value.recallAway ?: return
        _state.update { it.copy(recallAway = null) }
        viewModelScope.launch {
            if (recalled != null) engine.recordRecall(engine.state.value.chain.size, recalled, away)
            spokenMoveId = -1
            presentMove()
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
        cancelAutoSend()
    }

    private fun onHeard(h: Heard) {
        when (h) {
            Heard.Nothing -> _state.update { it.copy(voiceNote = "没听到。想好了点麦克风直接说。") }
            is Heard.Failed -> _state.update { it.copy(voiceNote = "${h.message}。可以直接用输入法上的麦克风说。") }
            is Heard.Text -> {
                markActivity()
                _state.update { it.copy(voiceNote = null) }
                when (VoiceCommands.parse(h.text)) {
                    VoiceCommand.DONT_KNOW, VoiceCommand.HINT -> help()
                    VoiceCommand.EXPLAIN -> help(explain = true)
                    VoiceCommand.DEMO -> help(demo = true)
                    VoiceCommand.REPEAT -> speakMove()
                    VoiceCommand.PAUSE -> pause(5)
                    VoiceCommand.ROLL -> speak("换主练要先练完这一组，或者在页面上点“换一组”。") { maybeListen() }
                    VoiceCommand.THINKING -> {
                        thinking()
                        speak("好，想好了点麦克风直接说。")
                    }
                    VoiceCommand.CANCEL -> _state.update { it.copy(answer = "") }
                    null -> heardAnswer(h.text)
                }
            }
        }
    }

    /** 听到的回答接在已写的后面，倒数几秒自动发出（0 秒就等你点发送）。 */
    private fun heardAnswer(text: String) {
        val joined = _state.value.answer.trimEnd().let { if (it.isEmpty()) text else "$it，$text" }
        _state.update { it.copy(answer = joined) }
        val seconds = yishou.settings.app.value.voiceAutoSendSeconds
        if (seconds <= 0) return
        autoSendJob?.cancel()
        autoSendJob = viewModelScope.launch {
            for (i in seconds downTo 1) {
                _state.update { it.copy(autoSendLeft = i) }
                delay(1_000)
            }
            _state.update { it.copy(autoSendLeft = 0) }
            submit()
        }
    }

    fun cancelAutoSend() {
        autoSendJob?.cancel()
        autoSendJob = null
        if (_state.value.autoSendLeft != 0) _state.update { it.copy(autoSendLeft = 0) }
    }

    // ---------- 作答、帮助 ----------

    fun onAnswerChange(text: String) {
        cancelAutoSend()
        markActivity()
        _state.update { it.copy(answer = text) }
    }

    /** “我在想”：重新计时，这段时间不提醒。 */
    fun thinking() {
        if (!active) return
        speaker.stop()
        markActivity()
        scheduleReminders()
    }

    fun attach(uri: Uri) {
        val c = engine.state.value
        attachment.attach(uri, c.set?.goal, c.current?.question)
    }

    fun submit() {
        if (attachment.busy) return
        val text = listOfNotNull(_state.value.answer.takeIf { it.isNotBlank() }, attachment.readyText).joinToString("\n")
        if (text.isBlank() || !active) return
        speaker.stop()
        listenJob?.cancel()
        voice.cancel()
        cancelAutoSend()
        viewModelScope.launch {
            when (val r = engine.answer(text, RoundSource.WINDOW)) {
                is SetEngine.Outcome.Judged -> {
                    _state.update { it.copy(answer = "") }
                    attachment.clear()
                    val f = r.feedback
                    Haptics.play(yishou, if (r.verdict == MoveVerdict.PASS) Haptics.Kind.PASS else Haptics.Kind.REDO)
                    pendingVerdict = when (r.verdict) {
                        MoveVerdict.PASS -> "过了。${f.gap}"
                        MoveVerdict.REDO -> "再改一下。${f.gap}"
                        MoveVerdict.SHOWN -> "这一步应该是：${f.correctStep}"
                        else -> f.gap
                    }
                    if (r.ended != null) onSetEnded()
                }
                is SetEngine.Outcome.Failed -> scheduleReminders()
                else -> {}
            }
        }
    }

    private fun onSetEnded() {
        reminderJob?.cancel()
        val verdict = pendingVerdict.orEmpty()
        pendingVerdict = null
        viewModelScope.launch {
            val span = _state.value.span
            _state.update { it.copy(setsDone = span?.let { s -> setsDoneSince(s.start) } ?: it.setsDone) }
        }
        speak("$verdict 这组练完了。用一句话说说练会了什么，或者歇 ${_state.value.restMinutes} 分钟。")
    }

    /** 组末：记下“练会了什么”，接着开下一组 */
    fun saveLessonAndNext(lesson: String?) {
        viewModelScope.launch {
            if (lesson.isNullOrBlank()) engine.dismissEnded() else engine.saveLesson(lesson)
            spokenMoveId = -1
            openSet(warm = false)
        }
    }

    /** 组末：歇一下 */
    fun restAfterSet(lesson: String?) {
        viewModelScope.launch {
            if (lesson.isNullOrBlank()) engine.dismissEnded() else engine.saveLesson(lesson)
            pause(_state.value.restMinutes)
        }
    }

    /** 卡住：线索 → 半步 → 示范；没看懂材料；帮助到了就读出来。 */
    fun help(explain: Boolean = false, demo: Boolean = false) {
        speaker.stop()
        markActivity()
        viewModelScope.launch {
            if (engine.help(explain, demo) is SetEngine.Outcome.Ok) {
                val text = engine.state.value.current?.helpText?.lines()?.lastOrNull().orEmpty()
                speak(text) { maybeListen() }
            }
        }
    }

    fun drop() {
        viewModelScope.launch {
            engine.drop()
            spokenMoveId = -1
            openSet(warm = false)
        }
    }

    /**
     * “先停”：每一手判完都已保存；休息 minutes 分钟，窗口计时继续。
     * 休息时间到会朗读并自动回到这一问；应用在后台时用通知叫你回来。
     */
    fun pause(minutes: Int) {
        if (_state.value.span == null || _state.value.ended) return
        reminderJob?.cancel()
        speaker.stop()
        stopVoice()
        attachment.clear()
        val until = System.currentTimeMillis() + minutes * 60_000L
        Reminders.schedulePauseEnd(yishou, until)
        yishou.settings.updateApp { it.copy(windowPauseUntil = until) }
        _state.update { it.copy(paused = true, pauseUntil = until, answer = "") }
    }

    fun resume() {
        Reminders.cancelPauseEnd(yishou)
        yishou.settings.updateApp { it.copy(windowPauseUntil = 0) }
        _state.update { it.copy(paused = false, pauseUntil = 0) }
        spokenMoveId = -1
        val c = engine.state.value
        if (c.set == null && c.ended == null && c.busy == null) openSet(warm = false) else presentMove()
    }

    override fun onCleared() {
        stopVoice()
        speaker.shutdown()
        super.onCleared()
    }

    companion object {
        const val RECALL_AFTER_SECONDS = 60L

        /** 无回应时朗读的起手提示，第一次和第二次各一句 */
        val STARTERS = listOf(
            "可以先说一句：我目前确定的是什么。",
            "可以先说一句：如果……那么……，哪怕不确定也行。",
        )
    }
}
