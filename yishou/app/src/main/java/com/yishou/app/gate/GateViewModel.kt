package com.yishou.app.gate

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.llm.CoachMessages
import com.yishou.app.round.AnswerRules
import com.yishou.app.system.AttachmentController
import com.yishou.app.round.RoundEngine
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** 思考页的界面状态。 */
data class GateState(
    val loading: Boolean = true,
    /** 触发的应用包名；从权限页“测试思考页”打开时为 null */
    val triggerPackage: String? = null,
    val appLabel: String = "",
    val group: String = "",
    val task: Task? = null,
    val breakpoint: Breakpoint? = null,
    /** 陪练窗口进行中：不发放行 */
    val inWindow: Boolean = false,
    val coachMove: String? = null,
    val moveLoading: Boolean = false,
    /** 出题失败的原因；此时用断点里的“下一问”当题目 */
    val moveError: String? = null,
    val answer: String = "",
    val submitting: Boolean = false,
    val hint: String? = null,
    val lastRound: Round? = null,
    /** 判定失败的原因，此时可以“保存并通过” */
    val judgeError: String? = null,
    val offlineLeft: Int = 0,
    val offlineMinutes: Int = 5,
    /** 已放行的分钟数；非 null 表示这一手有效、可以回到原应用 */
    val passMinutes: Int? = null,
    /** 隔了很久才回来时的上一轮，用于“回到局面” */
    val resumeFrom: Round? = null,
) {
    val answerChars: Int get() = AnswerRules.countChars(answer)
    val isTest: Boolean get() = triggerPackage == null
}

class GateViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()
    private val engine = yishou.engine

    val attachment = AttachmentController(yishou, viewModelScope)

    private val _state = MutableStateFlow(GateState())
    val state: StateFlow<GateState> = _state.asStateFlow()

    private var startedFor: Pair<String?, String>? = null

    /** 每次思考页被（重新）打开时调用。同一个应用重复打开不会清掉正在写的回答。 */
    fun start(pkg: String?, group: String, label: String) {
        if (startedFor == pkg to group && !_state.value.loading && _state.value.passMinutes == null) return
        startedFor = pkg to group
        attachment.clear()
        _state.value = GateState(loading = true, triggerPackage = pkg, appLabel = label, group = group)
        viewModelScope.launch {
            val prefs = yishou.settings.app.value
            val now = System.currentTimeMillis()
            val inWindow = pkg != null && WindowClock.active(now, prefs, ZoneId.systemDefault()) != null
            val task = dao.getCurrentTask()
            val bp = task?.let { dao.getBreakpoint(it.id) }
            val last = dao.lastRound()
            val resume = last?.takeIf { (now - it.createdAt) / 60_000 >= CoachMessages.RESUME_GAP_MINUTES }
            _state.update {
                it.copy(
                    loading = false,
                    resumeFrom = resume,
                    task = task,
                    breakpoint = bp,
                    inWindow = inWindow,
                    coachMove = bp?.pendingCoachMove?.takeIf { m -> m.isNotBlank() },
                    offlineMinutes = prefs.offlinePassMinutes,
                )
            }
            if (task != null && !inWindow) loadMove(task)
        }
    }

    private suspend fun loadMove(task: Task) {
        if (_state.value.coachMove != null) return
        _state.update { it.copy(moveLoading = true, moveError = null) }
        val r = engine.currentMove(task)
        val bp = dao.getBreakpoint(task.id)
        _state.update {
            when (r) {
                is RoundEngine.MoveResult.Ready -> it.copy(moveLoading = false, coachMove = r.coachMove, breakpoint = bp)
                is RoundEngine.MoveResult.Failed -> it.copy(
                    moveLoading = false,
                    moveError = r.error.message,
                    // 出不了题时仍然可以作答：用断点里的“下一问”，没有就用通用的问题
                    coachMove = bp?.nextQuestion?.takeIf { q -> q.isNotBlank() } ?: FALLBACK_QUESTION,
                    breakpoint = bp,
                )
            }
        }
    }

    fun onAnswerChange(text: String) {
        _state.update { it.copy(answer = text, hint = null) }
    }

    fun attach(uri: Uri) {
        val s = _state.value
        attachment.attach(uri, s.task?.title, s.coachMove)
    }

    private fun composedAnswer(): String = AnswerRules.compose(_state.value.answer, attachment.readyText)

    fun submit() {
        val s = _state.value
        val task = s.task ?: return
        val move = s.coachMove ?: return
        if (s.submitting || s.passMinutes != null || attachment.busy) return
        val text = composedAnswer()
        if (!AnswerRules.isLongEnough(text)) {
            _state.update { it.copy(hint = "再多写一步：写下你得到了什么、依据是什么") }
            return
        }
        _state.update { it.copy(submitting = true, hint = null, judgeError = null) }
        viewModelScope.launch {
            when (val r = engine.answer(task, move, text, RoundSource.GATE, s.triggerPackage)) {
                RoundEngine.AnswerResult.TooShort -> _state.update { it.copy(submitting = false, hint = "再多写一步") }
                is RoundEngine.AnswerResult.Failed -> {
                    val left = offlineLeft()
                    _state.update {
                        it.copy(submitting = false, judgeError = "暂时无法判定：${r.error.message}", offlineLeft = left)
                    }
                }
                is RoundEngine.AnswerResult.Judged -> {
                    val bp = dao.getBreakpoint(task.id)
                    if (r.round.effective && !s.isTest && !s.inWindow) {
                        val minutes = yishou.settings.app.value.passMinutesFor(s.group)
                        engine.grantPass(s.group, minutes, r.round.id)
                        attachment.clear()
                        _state.update { it.copy(submitting = false, lastRound = r.round, breakpoint = bp, passMinutes = minutes) }
                    } else {
                        // 无效：显示反馈和理由，下一手已在断点里，可以再答一次
                        attachment.clear()
                        _state.update {
                            it.copy(
                                submitting = false,
                                lastRound = r.round,
                                breakpoint = bp,
                                answer = "",
                                coachMove = bp?.pendingCoachMove ?: it.coachMove,
                                moveError = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /** 离线“保存并通过”。 */
    fun saveOffline() {
        val s = _state.value
        val task = s.task ?: return
        val move = s.coachMove ?: return
        if (s.submitting || s.isTest || s.inWindow) return
        val text = composedAnswer()
        if (!AnswerRules.isLongEnough(text)) {
            _state.update { it.copy(hint = "再多写一步") }
            return
        }
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val left = offlineLeft()
            if (left <= 0) {
                _state.update { it.copy(submitting = false, offlineLeft = 0) }
                return@launch
            }
            val minutes = yishou.settings.app.value.offlinePassMinutes
            engine.saveOffline(task, move, text, s.triggerPackage, s.group, minutes)
            attachment.clear()
            _state.update { it.copy(submitting = false, passMinutes = minutes, offlineLeft = left - 1) }
        }
    }

    private suspend fun offlineLeft(): Int {
        val zone = ZoneId.systemDefault()
        val since = WindowClock.startOfDay(WindowClock.today(System.currentTimeMillis(), zone), zone)
        val used = engine.offlineUsedSince(since)
        return (yishou.settings.app.value.offlineDailyLimit - used).coerceAtLeast(0)
    }

    companion object {
        const val FALLBACK_QUESTION = "陪练暂时出不了题。写下你在当前任务上的下一步：你已经确定了什么，接下来要做哪一步、为什么？"
    }
}
