package com.yishou.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.round.AnswerRules
import com.yishou.app.round.RoundEngine
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

/** 主页棋盘的界面状态。 */
data class BoardState(
    val loaded: Boolean = false,
    val configured: Boolean = false,
    val task: Task? = null,
    val breakpoint: Breakpoint? = null,
    /** 正在请求开局 */
    val loadingMove: Boolean = false,
    val moveError: String? = null,
    val answer: String = "",
    val submitting: Boolean = false,
    /** “再多写一步”之类的输入提示 */
    val hint: String? = null,
    /** 判定请求失败的原因；回答保留在输入框里，可以直接重试 */
    val judgeError: String? = null,
    /** 上一轮的判定结果 */
    val lastRound: Round? = null,
) {
    /** 当前要回答的一手，就是断点里还没被回答的那一手 */
    val coachMove: String? get() = breakpoint?.pendingCoachMove?.takeIf { it.isNotBlank() }
    val answerChars: Int get() = AnswerRules.countChars(answer)
}

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()
    private val engine = yishou.engine

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
                            lastRound = if (taskChanged) null else s.lastRound,
                            answer = if (taskChanged) "" else s.answer,
                            judgeError = if (taskChanged) null else s.judgeError,
                            moveError = if (taskChanged) null else s.moveError,
                        )
                    }
                    maybeAutoOpen()
                }
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

    /** 应一手：提交回答并等待判定。 */
    fun submit() {
        val s = _state.value
        val task = s.task ?: return
        val move = s.coachMove ?: return
        if (s.submitting) return
        if (!AnswerRules.isLongEnough(s.answer)) {
            _state.update { it.copy(hint = "再多写一步") }
            return
        }
        _state.update { it.copy(submitting = true, judgeError = null, hint = null) }
        viewModelScope.launch {
            when (val r = engine.answer(task, move, s.answer, RoundSource.HOME)) {
                RoundEngine.AnswerResult.TooShort ->
                    _state.update { it.copy(submitting = false, hint = "再多写一步") }
                is RoundEngine.AnswerResult.Failed ->
                    _state.update { it.copy(submitting = false, judgeError = "暂时无法判定：${r.error.message}") }
                is RoundEngine.AnswerResult.Judged ->
                    _state.update { it.copy(submitting = false, answer = "", lastRound = r.round) }
            }
        }
    }
}
