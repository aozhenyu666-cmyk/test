package com.yishou.app.ui

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
import com.yishou.app.round.RoundEngine
import com.yishou.app.system.AttachmentController
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** 主页棋盘的界面状态。 */
data class BoardState(
    val loaded: Boolean = false,
    val configured: Boolean = false,
    val task: Task? = null,
    val breakpoint: Breakpoint? = null,
    /** 今天的全部轮次，按时间排成对话 */
    val todayRounds: List<Round> = emptyList(),
    /** 隔了很久才回来时的上一轮（用于“回到局面”）；不需要时为 null */
    val resumeFrom: Round? = null,
    /** 正在请求开局 */
    val loadingMove: Boolean = false,
    val moveError: String? = null,
    val answer: String = "",
    val submitting: Boolean = false,
    /** “再多写一步”之类的输入提示 */
    val hint: String? = null,
    /** 判定请求失败的原因；回答保留在输入框里，可以直接重试 */
    val judgeError: String? = null,
) {
    /** 当前要回答的一手，就是断点里还没被回答的那一手 */
    val coachMove: String? get() = breakpoint?.pendingCoachMove?.takeIf { it.isNotBlank() }
    val effectiveToday: Int get() = todayRounds.count { it.effective }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()
    private val engine = yishou.engine
    val attachment = AttachmentController(yishou, viewModelScope)

    private val _state = MutableStateFlow(BoardState())
    val state: StateFlow<BoardState> = _state.asStateFlow()

    /** 已经自动请求过开局的（任务, 断点版本, 接口配置），避免失败后反复自动重试 */
    private var autoOpenedFor: Triple<Long, Long, Int>? = null
    private var configVersion = 0

    init {
        val taskAndBreakpoint = dao.observeCurrentTask().flatMapLatest { task ->
            if (task == null) flowOf<Pair<Task?, Breakpoint?>>(null to null)
            else dao.observeBreakpoint(task.id).map { task to it }
        }
        viewModelScope.launch {
            combine(taskAndBreakpoint, yishou.settings.llm) { (task, bp), cfg -> Triple(task, bp, cfg) }
                .collect { (task, bp, cfg) ->
                    configVersion = cfg.hashCode()
                    _state.update { s ->
                        val taskChanged = s.task?.id != task?.id
                        s.copy(
                            loaded = true,
                            configured = cfg.isComplete,
                            task = task,
                            breakpoint = bp,
                            answer = if (taskChanged) "" else s.answer,
                            judgeError = if (taskChanged) null else s.judgeError,
                            moveError = if (taskChanged) null else s.moveError,
                        )
                    }
                    maybeAutoOpen()
                }
        }
        val zone = ZoneId.systemDefault()
        val todayStart = WindowClock.startOfDay(WindowClock.today(System.currentTimeMillis(), zone), zone)
        viewModelScope.launch {
            dao.observeRoundsSince(todayStart).collect { rounds -> _state.update { it.copy(todayRounds = rounds) } }
        }
        viewModelScope.launch {
            val last = dao.lastRound()
            val gap = last?.let { (System.currentTimeMillis() - it.createdAt) / 60_000 } ?: 0
            if (last != null && gap >= CoachMessages.RESUME_GAP_MINUTES) _state.update { it.copy(resumeFrom = last) }
        }
    }

    /** 有任务、已配置、但还没有待回答的一手时，自动发一次开局请求。 */
    private fun maybeAutoOpen() {
        val s = _state.value
        val task = s.task ?: return
        if (!s.configured || s.coachMove != null || s.loadingMove) return
        val key = Triple(task.id, s.breakpoint?.updatedAt ?: 0L, configVersion)
        if (autoOpenedFor == key) return
        autoOpenedFor = key
        requestMove()
    }

    /** 请求陪练出一手（开局）。失败时显示原因和“重试”。 */
    fun requestMove() {
        val task = _state.value.task ?: return
        if (_state.value.loadingMove) return
        _state.update { it.copy(loadingMove = true, moveError = null) }
        viewModelScope.launch {
            val result = engine.currentMove(task)
            _state.update {
                it.copy(
                    loadingMove = false,
                    moveError = (result as? RoundEngine.MoveResult.Failed)?.error?.message,
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

    /** 应一手：提交回答（文字 + 图片转写）并等待判定。 */
    fun submit() {
        val s = _state.value
        val task = s.task ?: return
        val move = s.coachMove ?: return
        if (s.submitting || attachment.busy) return
        val text = AnswerRules.compose(s.answer, attachment.readyText)
        if (!AnswerRules.isLongEnough(text)) {
            _state.update { it.copy(hint = "再多写一步：写下你得到了什么、依据是什么") }
            return
        }
        _state.update { it.copy(submitting = true, judgeError = null, hint = null) }
        viewModelScope.launch {
            when (val r = engine.answer(task, move, text, RoundSource.HOME)) {
                RoundEngine.AnswerResult.TooShort ->
                    _state.update { it.copy(submitting = false, hint = "再多写一步") }
                is RoundEngine.AnswerResult.Failed ->
                    _state.update { it.copy(submitting = false, judgeError = "暂时无法判定：${r.error.message}") }
                is RoundEngine.AnswerResult.Judged -> {
                    attachment.clear()
                    _state.update { it.copy(submitting = false, answer = "", resumeFrom = null) }
                }
            }
        }
    }
}
