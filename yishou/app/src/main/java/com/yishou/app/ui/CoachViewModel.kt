package com.yishou.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.coach.CoachState
import com.yishou.app.coach.CoachStats
import com.yishou.app.coach.SetEngine
import com.yishou.app.data.MoveVerdict
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.settings.AppZone
import com.yishou.app.system.AttachmentController
import com.yishou.app.system.Haptics
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 主页（教练）上，除了组本身以外的界面状态。 */
data class CoachUi(
    val answer: String = "",
    /** 正在热身：开组请求同时在后台跑 */
    val warmingUp: Boolean = false,
    /** 离开了多少秒、该先复述结论链了；null 表示不用 */
    val recallAway: Long? = null,
    val stats: CoachStats? = null,
    val suggestedFace: Int = 2,
)

/** 领域预设：不用填表，点一下就能开组 */
val DOMAIN_PRESETS = listOf("行测 判断", "行测 言语", "资料分析", "生活安排")

class CoachViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()
    private val engine: SetEngine = yishou.coachEngine

    val coach: StateFlow<CoachState> = engine.state
    val tasks: StateFlow<List<Task>> = dao.observeAllTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val attachment = AttachmentController(yishou, viewModelScope)

    private val _ui = MutableStateFlow(CoachUi())
    val ui: StateFlow<CoachUi> = _ui.asStateFlow()

    private var hiddenAt = 0L

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            val since = WindowClock.startOfDay(WindowClock.today(System.currentTimeMillis(), AppZone.zone).minusDays(14), AppZone.zone)
            val c = yishou.database.coach()
            val sets = c.recentSets(200).filter { it.startedAt >= since }
            val stats = CoachStats.of(sets, c.movesSince(since), c.recallsSince(since))
            _ui.update { it.copy(stats = stats, suggestedFace = engine.suggestFace()) }
        }
    }

    /**
     * 开一组：领域没有就新建（只要一个名字，不用填表），设为当前任务。
     * 要热身时，开组请求在热身期间跑，热身完正好排好。
     */
    fun open(domain: String, face: Int, material: String?, warmup: Boolean) {
        val text = listOfNotNull(material?.takeIf { it.isNotBlank() }, attachment.readyText).joinToString("\n").ifBlank { null }
        _ui.update { it.copy(warmingUp = warmup) }
        viewModelScope.launch {
            val task = ensureTask(domain)
            engine.open(task, face, text, warmup = "")
            attachment.clear()
        }
    }

    /** 热身做完：把成绩记到这一组上 */
    fun warmupDone(score: String?) {
        _ui.update { it.copy(warmingUp = false) }
        if (score == null) return
        viewModelScope.launch {
            val set = engine.state.value.set ?: return@launch
            yishou.database.coach().updateSet(set.copy(warmup = score))
        }
    }

    private suspend fun ensureTask(domain: String): Task {
        val name = domain.trim().ifBlank { DOMAIN_PRESETS.first() }
        val all = dao.allTasks()
        val existing = all.firstOrNull { it.title == name }
        if (existing != null) {
            if (!existing.isCurrent) dao.switchCurrentTask(existing.id)
            return existing.copy(isCurrent = true)
        }
        val now = System.currentTimeMillis()
        val t = Task(title = name, goal = "", material = null, isCurrent = true, createdAt = now)
        val id = dao.createCurrentTask(t, known = "", stuck = "", nextQuestion = "", now = now)
        return t.copy(id = id)
    }

    fun onAnswerChange(text: String) = _ui.update { it.copy(answer = text) }

    fun attach(uri: Uri) = attachment.attach(uri, engine.state.value.set?.goal, engine.state.value.current?.question)

    fun submit() {
        val text = listOfNotNull(_ui.value.answer.takeIf { it.isNotBlank() }, attachment.readyText).joinToString("\n")
        if (text.isBlank() || attachment.busy) return
        viewModelScope.launch {
            val r = engine.answer(text, RoundSource.HOME)
            if (r is SetEngine.Outcome.Judged) {
                _ui.update { it.copy(answer = "") }
                attachment.clear()
                Haptics.play(yishou, if (r.verdict == MoveVerdict.PASS) Haptics.Kind.PASS else Haptics.Kind.REDO)
                if (r.ended != null) refreshStats()
            }
        }
    }

    fun help(explain: Boolean = false, demo: Boolean = false) {
        viewModelScope.launch { engine.help(explain, demo) }
    }

    fun drop() {
        viewModelScope.launch {
            engine.drop()
            refreshStats()
        }
    }

    fun saveLesson(text: String) {
        viewModelScope.launch {
            engine.saveLesson(text)
            refreshStats()
        }
    }

    fun dismissEnded() = engine.dismissEnded()

    // ---------- 离开回来先复述 ----------

    fun onHidden() {
        hiddenAt = System.currentTimeMillis()
    }

    fun onVisible() {
        val away = if (hiddenAt == 0L) 0 else (System.currentTimeMillis() - hiddenAt) / 1000
        hiddenAt = 0
        val s = engine.state.value
        if (away >= RECALL_AFTER_SECONDS && s.set != null && s.chain.isNotEmpty() && s.busy == null) {
            _ui.update { it.copy(recallAway = away) }
        }
    }

    fun recallDone(recalled: Int?) {
        val away = _ui.value.recallAway ?: return
        _ui.update { it.copy(recallAway = null) }
        if (recalled == null) return
        viewModelScope.launch {
            engine.recordRecall(engine.state.value.chain.size, recalled, away)
            refreshStats()
        }
    }

    companion object {
        /** 离开多久回来要先复述 */
        const val RECALL_AFTER_SECONDS = 60L
    }
}
