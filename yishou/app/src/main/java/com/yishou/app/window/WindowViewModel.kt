package com.yishou.app.window

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.round.AnswerRules
import com.yishou.app.round.RoundEngine
import com.yishou.app.system.AttachmentController
import com.yishou.app.system.Reminders
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
) {
    val answerChars: Int get() = AnswerRules.countChars(answer)
}

/**
 * 陪练窗口：倒计时内一轮接一轮。每轮出题后朗读问题；
 * 没有提交时按设置的间隔朗读“当前卡点 + 起手提示”，同一问题最多 2 次。
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

    init {
        viewModelScope.launch { speaker.problem.collect { p -> _state.update { it.copy(ttsProblem = p) } } }
        refresh()
    }

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
                startTicker(span)
                if (task != null) ensureMove(task)
            }
        }
    }

    /** 手动开始一个窗口（时长按设置）。 */
    fun startNow() {
        yishou.settings.updateApp { it.copy(manualWindowStart = System.currentTimeMillis()) }
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
        Reminders.cancelPauseEnd(yishou)
        reminderJob?.cancel()
        speaker.stop()
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
            _state.update { it.copy(reminders = 0, noResponse = false) }
            if (yishou.settings.app.value.ttsEnabled) speaker.speak(move)
        }
        scheduleReminders()
    }

    private fun scheduleReminders() {
        reminderJob?.cancel()
        val s = _state.value
        if (!active || s.coachMove == null || s.noResponse) return
        val prefs = yishou.settings.app.value
        reminderJob = viewModelScope.launch {
            if (_state.value.reminders == 0) {
                delay(prefs.remindFirstMinutes * 60_000L)
                remind(1)
            }
            if (_state.value.reminders == 1) {
                delay(prefs.remindSecondMinutes * 60_000L)
                remind(2)
            }
        }
    }

    private fun remind(n: Int) {
        if (!active) return
        val stuck = _state.value.breakpoint?.stuck?.takeIf { it.isNotBlank() } ?: "还没有写下卡点"
        val starter = STARTERS[(n - 1).coerceIn(0, STARTERS.lastIndex)]
        if (yishou.settings.app.value.ttsEnabled) speaker.speak("当前卡点：$stuck。$starter")
        _state.update { it.copy(reminders = n, noResponse = n >= 2) }
    }

    fun onAnswerChange(text: String) {
        _state.update { it.copy(answer = text, hint = null) }
    }

    /** “我在想”：重新计时，这段时间不提醒。 */
    fun thinking() {
        if (!active) return
        speaker.stop()
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
                    presentMove()
                }
            }
        }
    }

    /**
     * “先停”：断点已在每轮结束时保存；结束本轮，休息 minutes 分钟，窗口计时继续。
     * 休息时间到会朗读并自动回到下一轮；应用在后台时用通知叫你回来。
     */
    fun pause(minutes: Int) {
        if (_state.value.span == null || _state.value.ended) return
        reminderJob?.cancel()
        speaker.stop()
        attachment.clear()
        val until = System.currentTimeMillis() + minutes * 60_000L
        Reminders.schedulePauseEnd(yishou, until)
        _state.update { it.copy(paused = true, pauseUntil = until, answer = "", hint = null, judgeError = null) }
    }

    /** 继续下一轮：重新朗读这一手，从头计时。 */
    fun resume() {
        Reminders.cancelPauseEnd(yishou)
        _state.update { it.copy(paused = false, pauseUntil = 0) }
        spokenMove = null
        presentMove()
    }

    override fun onCleared() {
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
