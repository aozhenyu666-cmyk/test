package com.yishou.app.window

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.round.AnswerRules
import com.yishou.app.round.RoundEngine
import com.yishou.app.look.ScreenLookService
import com.yishou.app.system.AttachmentController
import com.yishou.app.system.Reminders
import com.yishou.app.gate.GateService
import com.yishou.app.speech.Heard
import com.yishou.app.speech.ListenPhase
import com.yishou.app.speech.Spoken
import com.yishou.app.speech.VoiceCommand
import com.yishou.app.speech.VoiceCommands
import com.yishou.app.stats.DayStats
import com.yishou.app.system.Notifications
import kotlinx.coroutines.isActive
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

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
    val breakpoint: Breakpoint? = null,
    val coachMove: String? = null,
    val moveLoading: Boolean = false,
    val moveError: String? = null,
    val answer: String = "",
    val submitting: Boolean = false,
    val hint: String? = null,
    val judgeError: String? = null,
    val lastRound: Round? = null,
    /** “先停”之后，等休息结束或用户点“提前回来” */
    val paused: Boolean = false,
    /** 休息到什么时候；到点自动回到下一轮 */
    val pauseUntil: Long = 0,
    /** 这一问已经提醒了几次（最多 2 次） */
    val reminders: Int = 0,
    val noResponse: Boolean = false,
    val effectiveCount: Int = 0,
    val ttsProblem: String? = null,
    /** 刚完成第几组（今天的有效手数凑满一组时出现），等用户选“歇一下”或“接着来” */
    val setDone: Int? = null,
    val restMinutes: Int = 2,
    /** 语音陪练：读完题自动听你说 */
    val voiceOn: Boolean = true,
    val listen: ListenPhase = ListenPhase.IDLE,
    /** 陪练正在说话 */
    val speaking: Boolean = false,
    /** 听写好了，几秒后自动发出；0 表示没有在倒数 */
    val autoSendLeft: Int = 0,
    /** 语音这边的一句提示：没听到、听写失败等 */
    val voiceNote: String? = null,
    /** 需要请求麦克风权限 */
    val needMic: Boolean = false,
    /** 这一手已经把你拉回来几次 */
    val pulled: Int = 0,
) {
    val answerChars: Int get() = AnswerRules.countChars(answer)
}

/**
 * 陪练窗口：倒计时内一轮接一轮。每轮出题后朗读问题（口语版），语音陪练开着时读完就听你说，
 * 听写好了倒数几秒自动发出；判定后先说一句结果，再说下一手。
 * 没有动静时按 [IdleLadder] 升级：朗读卡点和起手提示两次，再把窗口页拉回前台。
 */
class WindowViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()
    private val engine = yishou.engine
    private val speaker = Speaker(app)
    private val zone get() = ZoneId.systemDefault()

    val attachment = AttachmentController(yishou, viewModelScope)

    private val _state = MutableStateFlow(WindowState())
    val state: StateFlow<WindowState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var reminderJob: Job? = null
    private var spokenMove: String? = null
    private var bpJob: Job? = null
    private var listenJob: Job? = null
    private var autoSendJob: Job? = null
    private val voice = yishou.voice

    /** 最后一次有动静（出题、打字、说话、点“我在想”）的时间，无回应梯子从这里算 */
    private var lastActivity = System.currentTimeMillis()
    /** 梯子已经走到第几级 */
    private var ladder = 0
    /** 判定之后先说的那一句（有效/还不算 + 反馈），说完再说下一手 */
    private var pendingVerdict: String? = null

    /** 当前这一手配的起手式 */
    val starters = engine.starters

    init {
        viewModelScope.launch { speaker.problem.collect { p -> _state.update { it.copy(ttsProblem = p) } } }
        viewModelScope.launch { speaker.speaking.collect { v -> _state.update { it.copy(speaking = v) } } }
        viewModelScope.launch { voice.phase.collect { v -> _state.update { it.copy(listen = v) } } }
        viewModelScope.launch {
            yishou.settings.app.collect { p -> _state.update { it.copy(voiceOn = p.voiceMode) } }
        }
    }

    /** 音量（0–1），画波纹用 */
    val level = voice.level

    /** 重新判断现在是否在窗口里，并加载任务。 */
    fun refresh() {
        viewModelScope.launch {
            val prefs = yishou.settings.app.value
            val now = System.currentTimeMillis()
            val span = WindowClock.active(now, prefs, zone)
            val task = dao.getCurrentTask()
            val bp = task?.let { dao.getBreakpoint(it.id) }
            val count = span?.let { dao.countEffective(RoundSource.WINDOW, it.start, it.end) } ?: 0
            _state.update {
                it.copy(
                    loading = false,
                    span = span,
                    ended = false,
                    remainingMs = span?.let { s -> s.end - now } ?: 0,
                    nextStart = WindowClock.nextScheduledStart(now, prefs, zone),
                    windowMinutes = prefs.windowMinutes,
                    task = task,
                    breakpoint = bp,
                    coachMove = bp?.pendingCoachMove?.takeIf { m -> m.isNotBlank() },
                    effectiveCount = count,
                )
            }
            if (span != null) {
                WindowService.start(yishou)
                startTicker(span)
                if (task != null) {
                    observeBreakpoint(task.id)
                    ensureMove(task)
                }
            }
        }
    }

    /** 断点可能被“看一眼”改掉（换了一手），页面要跟着变。 */
    private fun observeBreakpoint(taskId: Long) {
        bpJob?.cancel()
        bpJob = viewModelScope.launch {
            dao.observeBreakpoint(taskId).collect { bp ->
                val move = bp?.pendingCoachMove?.takeIf { it.isNotBlank() }
                val changed = move != null && move != _state.value.coachMove && !_state.value.submitting
                _state.update { it.copy(breakpoint = bp, coachMove = if (it.submitting) it.coachMove else move ?: it.coachMove) }
                if (changed) presentMove()
            }
        }
    }

    /** 掷骰换一手：出一手专练掷出的那一面（新的一手出来后照常朗读、计时）。 */
    fun roll(face: Int) = replaceMove { engine.rollFace(it, face) }

    /** 请陪练先示范这一面，再让我做。 */
    fun demo() = replaceMove { engine.demo(it) }

    private fun replaceMove(call: suspend (Task) -> RoundEngine.MoveResult) {
        val s = _state.value
        val task = s.task ?: return
        if (s.moveLoading || s.submitting || s.span == null || s.ended) return
        speaker.stop()
        stopVoice()
        _state.update { it.copy(moveLoading = true, moveError = null) }
        viewModelScope.launch {
            val r = call(task)
            _state.update { it.copy(moveLoading = false, moveError = (r as? RoundEngine.MoveResult.Failed)?.error?.message) }
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
                    if (yishou.settings.app.value.ttsEnabled) speaker.speak("休息结束，回到下一手。")
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
            val count = span?.let { dao.countEffective(RoundSource.WINDOW, it.start, it.end + 1) } ?: 0
            _state.update { it.copy(ended = true, remainingMs = 0, effectiveCount = count) }
        }
    }

    private val active get() = _state.value.let { it.span != null && !it.ended && !it.paused }

    private suspend fun ensureMove(task: Task) {
        if (_state.value.coachMove != null) {
            presentMove()
            return
        }
        _state.update { it.copy(moveLoading = true, moveError = null) }
        val r = engine.currentMove(task)
        _state.update {
            when (r) {
                is RoundEngine.MoveResult.Ready -> it.copy(moveLoading = false, coachMove = r.coachMove)
                is RoundEngine.MoveResult.Failed -> it.copy(moveLoading = false, moveError = r.error.message)
            }
        }
        presentMove()
    }

    fun retryMove() {
        val task = _state.value.task ?: return
        viewModelScope.launch { ensureMove(task) }
    }

    /** 新的一手出现时：朗读问题，并从头开始无回应计时。 */
    private fun presentMove() {
        val move = _state.value.coachMove ?: return
        if (!active) return
        if (move != spokenMove) {
            spokenMove = move
            markActivity()
            _state.update { it.copy(pulled = 0) }
            // “看一眼”出的一手，看屏服务在页面不可见时已经读过了
            val alreadySpoken = move == ScreenLookService.lastLookMove && !WindowActivity.isVisible
            if (!alreadySpoken) speakMove() else maybeListen()
        }
        scheduleReminders()
    }

    /** 这一手的朗读版 */
    private fun spokenText(): String {
        val move = _state.value.coachMove.orEmpty()
        val say = engine.spoken.value?.takeIf { it.first == move }?.second
        return Spoken.of(move, say)
    }

    /** 读这一手（前面带上判定结果），读完就听你说。 */
    private fun speakMove() {
        val verdict = pendingVerdict
        pendingVerdict = null
        if (!yishou.settings.app.value.ttsEnabled) {
            maybeListen()
            return
        }
        val text = if (verdict != null) "$verdict 下一手：${spokenText()}" else spokenText()
        speaker.speak(text) { maybeListen() }
    }

    private fun markActivity() {
        lastActivity = System.currentTimeMillis()
        ladder = 0
        if (_state.value.reminders != 0 || _state.value.noResponse) _state.update { it.copy(reminders = 0, noResponse = false) }
    }

    /** 按无回应梯子提醒、拉回。每 5 秒看一次离最后一次动静多久了。 */
    private fun scheduleReminders() {
        reminderJob?.cancel()
        if (!active || _state.value.coachMove == null) return
        reminderJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val s = _state.value
                if (!active || s.submitting || s.moveLoading || s.setDone != null) continue
                // 正在听你说、在倒数发出、或者陪练正在说话，这一刻先不打断（但不重新计时）
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
        val stuck = _state.value.breakpoint?.stuck?.takeIf { it.isNotBlank() } ?: "还没有写下卡点"
        val starter = STARTERS[(n - 1).coerceIn(0, STARTERS.lastIndex)]
        _state.update { it.copy(reminders = n, noResponse = n >= 2) }
        if (yishou.settings.app.value.ttsEnabled) speaker.speak("当前卡点：$stuck。$starter") { maybeListen() }
    }

    /**
     * 拉回：两次提醒之后还没动静，把窗口页拉到前台（不管你在哪个应用），再把这一手读一遍。
     * 你在窗口允许的学习应用、相机、电话里，或者屏幕关着时，不拉，只朗读。
     */
    private fun pullBack(k: Int) {
        if (!active) return
        _state.update { it.copy(pulled = k, noResponse = true) }
        val power = yishou.getSystemService(PowerManager::class.java)
        val fg = GateService.foreground
        val canPull = power?.isInteractive == true && !WindowActivity.isVisible && GateService.mayPullFrom(fg)
        if (canPull) {
            val intent = Intent(yishou, WindowActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!GateService.bringToFront(intent)) {
                Notifications.showReminder(
                    yishou, Notifications.ID_WINDOW,
                    "这一手还在等你",
                    "已经提醒过两次。点开回到陪练窗口，从这一手接着走。",
                    WindowActivity::class.java,
                )
            }
            yishou.stats.event(DayStats.PULL_BACK)
        }
        if (yishou.settings.app.value.ttsEnabled) speaker.speak("回来。这一手还在等你：${spokenText()}") { maybeListen() }
    }

    // ---------- 语音 ----------

    /** 读完一句之后：语音陪练开着、页面在前台、有麦克风权限，就听你说。 */
    private fun maybeListen() {
        val s = _state.value
        if (!s.voiceOn || !active || s.coachMove == null || s.submitting || s.moveLoading || s.setDone != null) return
        if (!WindowActivity.isVisible || s.autoSendLeft > 0 || voice.phase.value != ListenPhase.IDLE) return
        if (!voice.hasPermission()) {
            _state.update { it.copy(needMic = true) }
            return
        }
        listenJob?.cancel()
        listenJob = viewModelScope.launch { onHeard(voice.listen()) }
    }

    /** 点麦克风：陪练在说就打断它；正在听就当说完了；否则开始听。 */
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
            it.copy(needMic = false, voiceNote = if (granted) null else "没有麦克风权限，语音陪练用不了。可以在系统设置里给一手开麦克风。")
        }
        if (granted) maybeListen()
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
            is Heard.Failed -> _state.update { it.copy(voiceNote = h.message) }
            is Heard.Text -> {
                markActivity()
                _state.update { it.copy(voiceNote = null) }
                when (VoiceCommands.parse(h.text)) {
                    VoiceCommand.DONT_KNOW -> dontKnow()
                    VoiceCommand.REPEAT -> speakMove()
                    VoiceCommand.PAUSE -> pause(5)
                    VoiceCommand.DEMO -> demo()
                    VoiceCommand.ROLL -> roll(Random.nextInt(1, 7))
                    VoiceCommand.THINKING -> {
                        thinking()
                        speaker.speak("好，想好了点麦克风直接说。")
                    }
                    VoiceCommand.CANCEL -> _state.update { it.copy(answer = "", hint = null) }
                    null -> heardAnswer(h.text)
                }
            }
        }
    }

    /** 听到的回答接在已写的后面；够长就倒数自动发出，不够就请你再说一步。 */
    private fun heardAnswer(text: String) {
        val joined = _state.value.answer.trimEnd().let { if (it.isEmpty()) text else "$it，$text" }
        _state.update { it.copy(answer = joined, hint = null) }
        if (!AnswerRules.isLongEnough(AnswerRules.compose(joined, attachment.readyText))) {
            _state.update { it.copy(hint = "再多说一步：得到了什么、依据是什么") }
            if (yishou.settings.app.value.ttsEnabled) speaker.speak("再多说一步：你得到了什么，依据是什么？") { maybeListen() }
            return
        }
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

    /** 倒数中点“改一下”或者动了输入框：不自动发了，留给你改。 */
    fun cancelAutoSend() {
        autoSendJob?.cancel()
        autoSendJob = null
        if (_state.value.autoSendLeft != 0) _state.update { it.copy(autoSendLeft = 0) }
    }

    fun onAnswerChange(text: String) {
        cancelAutoSend()
        markActivity()
        _state.update { it.copy(answer = text, hint = null) }
    }

    /** “我在想”：重新计时，这段时间不提醒。 */
    fun thinking() {
        if (!active) return
        speaker.stop()
        markActivity()
        scheduleReminders()
    }

    /** “我不知道”：判为无效，请陪练给一个更具体的支架。 */
    fun dontKnow() = submitText(DONT_KNOW, skipLengthCheck = true)

    fun submit() {
        if (attachment.busy) return
        submitText(AnswerRules.compose(_state.value.answer, attachment.readyText), skipLengthCheck = false)
    }

    fun attach(uri: Uri) {
        val s = _state.value
        attachment.attach(uri, s.task?.title, s.coachMove)
    }

    private fun submitText(text: String, skipLengthCheck: Boolean) {
        val s = _state.value
        val task = s.task ?: return
        val move = s.coachMove ?: return
        if (s.submitting || s.span == null || s.ended) return
        if (!skipLengthCheck && !AnswerRules.isLongEnough(text)) {
            _state.update { it.copy(hint = "再多写一步：写下你得到了什么、依据是什么") }
            return
        }
        _state.update { it.copy(submitting = true, hint = null, judgeError = null, paused = false) }
        speaker.stop()
        listenJob?.cancel()
        voice.cancel()
        cancelAutoSend()
        viewModelScope.launch {
            when (val r = engine.answer(task, move, text, RoundSource.WINDOW, skipLengthCheck = skipLengthCheck)) {
                RoundEngine.AnswerResult.TooShort -> _state.update { it.copy(submitting = false, hint = "再多写一步") }
                is RoundEngine.AnswerResult.Failed -> {
                    _state.update { it.copy(submitting = false, judgeError = "暂时无法判定：${r.error.message}") }
                    scheduleReminders()
                }
                is RoundEngine.AnswerResult.Judged -> {
                    val bp = dao.getBreakpoint(task.id)
                    val span = _state.value.span
                    val count = span?.let { dao.countEffective(RoundSource.WINDOW, it.start, it.end) } ?: 0
                    _state.update {
                        it.copy(
                            submitting = false,
                            lastRound = r.round,
                            breakpoint = bp,
                            coachMove = bp?.pendingCoachMove,
                            answer = if (skipLengthCheck) it.answer else "",
                            effectiveCount = count,
                        )
                    }
                    if (!skipLengthCheck) attachment.clear()
                    if (_state.value.voiceOn) pendingVerdict = Spoken.verdict(r.round.effective, r.round.feedback)
                    // 凑满一组时先问要不要歇一下，选了“接着来”再出下一手
                    if (!(r.round.effective && checkSetDone())) presentMove()
                }
            }
        }
    }

    /** 今天的有效手数正好凑满一组时，问一句要不要歇一下。问了返回 true。 */
    private suspend fun checkSetDone(): Boolean {
        val prefs = yishou.settings.app.value
        val start = WindowClock.startOfDay(WindowClock.today(System.currentTimeMillis(), zone), zone)
        val effective = dao.roundsBetween(start, System.currentTimeMillis() + 1).count { it.effective }
        if (effective > 0 && effective % prefs.setSize == 0) {
            val n = effective / prefs.setSize
            _state.update { it.copy(setDone = n, restMinutes = prefs.restMinutes) }
            reminderJob?.cancel()
            pendingVerdict = null
            if (prefs.ttsEnabled) speaker.speak("第 $n 组完成了。歇 ${prefs.restMinutes} 分钟，还是接着来？")
            return true
        }
        return false
    }

    /** 组间：歇一下（就是“先停”）或接着来。 */
    fun restAfterSet() {
        val minutes = _state.value.restMinutes
        _state.update { it.copy(setDone = null) }
        pause(minutes)
    }

    fun continueAfterSet() {
        _state.update { it.copy(setDone = null) }
        speaker.stop()
        presentMove()
    }

    /**
     * “先停”：断点已在每轮结束时保存；结束本轮，休息 minutes 分钟，窗口计时继续。
     * 休息时间到会朗读并自动回到下一轮；应用在后台时用通知叫你回来。
     */
    fun pause(minutes: Int) {
        if (_state.value.span == null || _state.value.ended) return
        reminderJob?.cancel()
        speaker.stop()
        stopVoice()
        attachment.clear()
        val until = System.currentTimeMillis() + minutes * 60_000L
        Reminders.schedulePauseEnd(yishou, until)
        // 记进设置：休息期间开局规则不拦（无障碍服务读这里）
        yishou.settings.updateApp { it.copy(windowPauseUntil = until) }
        _state.update { it.copy(paused = true, pauseUntil = until, answer = "", hint = null, judgeError = null) }
    }

    /** 继续下一轮：重新朗读这一手，从头计时。 */
    fun resume() {
        Reminders.cancelPauseEnd(yishou)
        yishou.settings.updateApp { it.copy(windowPauseUntil = 0) }
        _state.update { it.copy(paused = false, pauseUntil = 0) }
        spokenMove = null
        pendingVerdict = null
        presentMove()
    }

    override fun onCleared() {
        stopVoice()
        speaker.shutdown()
        super.onCleared()
    }

    companion object {
        const val DONT_KNOW = "我不知道"

        /** 无回应时朗读的起手提示，第一次和第二次各一句 */
        val STARTERS = listOf(
            "可以先说一句：我目前确定的是什么。",
            "可以先说一句：如果……那么……，哪怕不确定也行。",
        )
    }
}
