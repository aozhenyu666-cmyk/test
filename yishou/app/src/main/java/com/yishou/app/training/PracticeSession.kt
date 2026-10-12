package com.yishou.app.training

import android.net.Uri
import com.yishou.app.YishouApp
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.round.AnswerRules
import com.yishou.app.round.RoundEngine
import com.yishou.app.system.AttachmentController
import com.yishou.app.system.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PracticeState(
    val task: Task? = null,
    val breakpoint: Breakpoint? = null,
    val mode: PracticeMode = PracticeMode.THINK,
    val move: String? = null,
    val answer: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val last: Round? = null,
    val starters: List<String> = emptyList(),
    val history: List<Round> = emptyList(),
    val paused: Boolean = false,
)

/** 开放的一手：材料和回答是两种不同操作。主页、窗口、入口共用，原话接着记进 Round。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeSession(
    private val app: YishouApp,
    private val scope: CoroutineScope,
    private val source: String = RoundSource.HOME,
    private val triggerPackage: () -> String? = { null },
) {
    private val dao = app.database.dao()
    private val _state = MutableStateFlow(PracticeState())
    val state = _state.asStateFlow()
    val attachment = AttachmentController(app, scope)
    private var request: Job? = null
    private var automaticallyStart = false
    private var requestRevision = 0

    init {
        scope.launch {
            dao.observeCurrentTask().flatMapLatest { task ->
                if (task == null) flowOf(null to null)
                else dao.observeBreakpoint(task.id).combine(flowOf(task)) { bp, t -> t to bp }
            }.collect { (task, bp) ->
                val old = _state.value
                if (old.task != task) {
                    requestRevision++
                    request?.cancel()
                    val sameTask = task != null && old.task?.id == task.id
                    if (!sameTask) attachment.clear()
                    _state.value = PracticeState(
                        task = task, breakpoint = bp,
                        mode = task?.let { PracticeMode.of(it.practiceMode) } ?: old.mode,
                        move = bp?.pendingCoachMove,
                        answer = if (sameTask) old.answer else "",
                        last = if (sameTask) old.last else null,
                        history = if (sameTask) old.history else emptyList(),
                    )
                    if (automaticallyStart && task != null && bp?.pendingCoachMove.isNullOrBlank()) start()
                } else {
                    _state.update { it.copy(breakpoint = bp, move = bp?.pendingCoachMove) }
                }
            }
        }
        scope.launch {
            dao.observeRoundsSince(System.currentTimeMillis() - 7 * 86_400_000L).combine(state) { rounds, s ->
                rounds.filter { it.taskId == s.task?.id && !it.coachMove.startsWith("【题卡】") }.takeLast(8)
            }.collect { history ->
                if (_state.value.history != history) _state.update { it.copy(history = history) }
            }
        }
    }

    fun selectMode(mode: PracticeMode) {
        if (_state.value.busy) return
        scope.launch {
            val match = dao.allTasks().firstOrNull { PracticeMode.of(it.practiceMode) == mode }
            if (match != null) dao.switchCurrentTask(match.id) else dao.clearCurrentFlag()
            _state.update { it.copy(mode = mode) }
        }
    }

    fun selectTask(task: Task) {
        if (!_state.value.busy) scope.launch { dao.switchCurrentTask(task.id) }
    }

    /** 没有自己的材料也可以开始，但这是练习情境，不能冒充用户现实处境。 */
    fun startScenario() {
        if (_state.value.busy) return
        scope.launch {
            automaticallyStart = true
            dao.createCurrentTask(Task(title = "日常判断训练", goal = PracticeMode.THINK.goal, material = null, isCurrent = true, createdAt = System.currentTimeMillis()), "", "", "", System.currentTimeMillis())
        }
    }

    fun start() {
        automaticallyStart = true
        _state.update { it.copy(paused = false) }
        val task = _state.value.task ?: return
        runRequest {
            when (val r = app.engine.currentMove(task)) {
                is RoundEngine.MoveResult.Failed -> fail(r.error.message)
                is RoundEngine.MoveResult.Ready -> ready(r.coachMove)
            }
        }
    }

    fun answer(text: String) = _state.update { it.copy(answer = text, error = null) }
    fun attach(uri: Uri) = attachment.attach(uri, _state.value.task?.title, _state.value.move)
    fun clearAttachment() = attachment.clear()

    fun submit() {
        val s = _state.value
        val task = s.task ?: return
        val move = s.move ?: return
        if (attachment.busy) return
        val text = AnswerRules.compose(s.answer.trim(), attachment.readyText)
        if (text.isBlank()) { fail("说出一个判断、依据或具体结果即可。") ; return }
        runRequest {
            when (val r = app.engine.answer(task, move, text, source, triggerPackage())) {
                is RoundEngine.AnswerResult.Failed -> fail(r.error.message)
                RoundEngine.AnswerResult.TooShort -> fail("先说出这一步的判断或结果。")
                is RoundEngine.AnswerResult.Judged -> {
                    attachment.clear()
                    _state.update { it.copy(answer = "", last = r.round) }
                    ready(r.judgement.nextCoachMove)
                    Haptics.play(app, if (r.round.effective) Haptics.Kind.PASS else Haptics.Kind.REDO)
                }
            }
        }
    }

    /** 添加材料不判分；存入任务并清除旧问题，再围绕新内容接一手。 */
    fun addMaterial() {
        val s = _state.value
        val task = s.task ?: return
        if (attachment.busy) return
        val text = AnswerRules.compose(s.answer.trim(), attachment.readyText).trim()
        if (text.isEmpty()) { fail("先粘贴文字，或提供一张截图。") ; return }
        runRequest {
            val material = listOfNotNull(task.material?.takeIf { it.isNotBlank() }, text).joinToString("\n\n")
            if (material.length > 24_000) { fail("材料较长，请到编辑任务中精简后保存。") ; return@runRequest }
            val bp = dao.getBreakpoint(task.id)
            // 保留断点。编辑后由任务观察流重新开局；不会把导入材料记成有效回答。
            attachment.clear()
            _state.update { it.copy(answer = "") }
            automaticallyStart = true
            dao.editTask(task.copy(material = material), bp?.known.orEmpty(), bp?.stuck.orEmpty(), bp?.nextQuestion.orEmpty(), System.currentTimeMillis())
        }
    }

    fun demo() {
        val task = _state.value.task ?: return
        runRequest {
            when (val r = app.engine.demo(task)) {
                is RoundEngine.MoveResult.Ready -> ready(r.coachMove)
                is RoundEngine.MoveResult.Failed -> fail(r.error.message)
            }
        }
    }

    fun roll(face: Int) {
        val task = _state.value.task ?: return
        runRequest {
            when (val r = app.engine.rollFace(task, face)) {
                is RoundEngine.MoveResult.Ready -> ready(r.coachMove)
                is RoundEngine.MoveResult.Failed -> fail(r.error.message)
            }
        }
    }

    fun stop() {
        automaticallyStart = false
        requestRevision++
        request?.cancel()
        _state.update { it.copy(busy = false, paused = true) }
    }

    private fun runRequest(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        val revision = ++requestRevision
        request = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                com.yishou.app.log.RunLog.e("开放陪练", "未能完成当前操作", e)
                if (revision == requestRevision) fail("没有完成这一步：${e.message ?: "请重试"}")
            }
            finally { if (revision == requestRevision) _state.update { it.copy(busy = false) } }
        }
    }

    private fun ready(move: String) {
        _state.update { it.copy(move = move, error = null, starters = app.engine.starters.value?.takeIf { p -> p.first == move }?.second.orEmpty()) }
    }
    private fun fail(message: String) = _state.update { it.copy(error = message) }
}
