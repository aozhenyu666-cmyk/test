package com.behaviordept.app.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.ai.AiAvailability
import com.behaviordept.app.ai.AiException
import com.behaviordept.app.ai.Grade
import com.behaviordept.app.ai.Parsers
import com.behaviordept.app.ai.Section
import com.behaviordept.app.data.JsonLists
import com.behaviordept.app.data.Rating
import com.behaviordept.app.data.Review
import com.behaviordept.app.data.SessionType
import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.study.Spacing
import com.behaviordept.app.study.UnitStep
import com.behaviordept.app.study.currentStep
import com.behaviordept.app.study.preQuestionList
import com.behaviordept.app.util.Time
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 专注模式里正在做的事。 */
sealed interface FocusTask {
    data object NewUnit : FocusTask
    data class Step(val step: UnitStep) : FocusTask
    data object Review : FocusTask
}

/** 一步做完后的结果页。 */
data class Completion(val title: String, val detail: String, val hasNext: Boolean)

/** 自测的进度。 */
enum class ReviewPhase { LOADING, ANSWERING, GRADING, RATING }

/**
 * 专注模式：一次专注 = 一个训练时段（Session）。进入就开始计时，
 * 每 30 秒写一次心跳；点“结束”写入时段和一条 SESSION 事件。
 * 当前要做的事在进入时算好：到期自测优先，否则是这个单元的下一步。
 */
class FocusViewModel(private val c: AppContainer, private val initialUnitId: Long) : ViewModel() {
    var task by mutableStateOf<FocusTask?>(null)
        private set
    var unit by mutableStateOf<StudyUnit?>(null)
        private set
    var startedAt by mutableLongStateOf(Time.now())
        private set
    var ended by mutableStateOf(false)
        private set
    var availability by mutableStateOf(AiAvailability.READY)
        private set

    // —— 输入 ——
    var newTitle by mutableStateOf("")
    var newMaterial by mutableStateOf("")
    val preQuestions = mutableStateListOf("", "", "")
    var explanation by mutableStateOf("")
    var example by mutableStateOf("")
    /** 对照资料自查时，自己写在各【小标题】下的内容。 */
    val selfNotes = mutableStateMapOf<String, String>()
    val answers = mutableStateListOf<String>()

    // —— 状态 ——
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** true = AI 不可用或你选了对照资料自查。 */
    var selfCheck by mutableStateOf(false)
        private set
    /** 自查模式下，写完之后才翻开资料对照。 */
    var materialRevealed by mutableStateOf(false)
        private set
    var critique by mutableStateOf<List<Section>?>(null)
        private set
    var questions by mutableStateOf<List<String>>(emptyList())
        private set
    var reviewPhase by mutableStateOf(ReviewPhase.LOADING)
        private set
    var grade by mutableStateOf<Grade?>(null)
        private set
    var completion by mutableStateOf<Completion?>(null)
        private set

    private var sessionId: Long? = null
    private var review: Review? = null
    private var heartbeat: Job? = null

    init {
        viewModelScope.launch { begin() }
    }

    private suspend fun begin() {
        c.sessions.closeAbandoned()
        availability = c.coach.availability()
        val u = if (initialUnitId > 0) c.study.unit(initialUnitId) else null
        unit = u
        val t = when {
            u == null -> FocusTask.NewUnit
            u.isReviewDue(Time.now()) -> FocusTask.Review
            u.currentStep() != UnitStep.DONE -> FocusTask.Step(u.currentStep())
            else -> FocusTask.Review // 四步都做完且没到期：提前自测一次
        }
        enter(t)
        val (type, title) = sessionMeta(t, u)
        startedAt = Time.now()
        sessionId = c.sessions.start(type, u?.id, title)
        heartbeat = viewModelScope.launch {
            while (isActive && !ended) {
                delay(30_000)
                sessionId?.let { c.sessions.heartbeat(it) }
            }
        }
    }

    private fun sessionMeta(t: FocusTask, u: StudyUnit?): Pair<String, String> = when (t) {
        FocusTask.NewUnit -> SessionType.NEW_UNIT to "新建学习单元"
        FocusTask.Review -> SessionType.REVIEW to "自测：${u?.title.orEmpty()}"
        is FocusTask.Step -> when (t.step) {
            UnitStep.PREVIEW -> SessionType.PREVIEW
            UnitStep.STUDY -> SessionType.STUDY
            UnitStep.EXPLAIN -> SessionType.EXPLAIN
            else -> SessionType.TRANSFER
        } to "${t.step.title}：${u?.title.orEmpty()}"
    }

    /** 切到一项任务，清掉上一项的临时状态。AI 不可用的环节直接进入对照资料自查。 */
    private fun enter(t: FocusTask) {
        task = t
        error = null
        critique = null
        grade = null
        completion = null
        materialRevealed = false
        selfNotes.clear()
        val needsAi = t == FocusTask.Review || t == FocusTask.Step(UnitStep.EXPLAIN) || t == FocusTask.Step(UnitStep.TRANSFER)
        selfCheck = needsAi && availability != AiAvailability.READY
        if (t == FocusTask.Review) loadQuestions()
    }

    val unavailableReason: String
        get() = when (availability) {
            AiAvailability.NO_KEY -> "还没配置 AI（设置里填 API Key），这次对照资料自查"
            AiAvailability.OFFLINE -> "现在没有网络，这次对照资料自查"
            AiAvailability.READY -> "改为对照资料自查"
        }

    fun switchToSelfCheck() {
        selfCheck = true
        error = null
        if (task == FocusTask.Review && questions.isEmpty()) loadQuestions()
    }

    private fun fail(e: Throwable) {
        error = when (e) {
            is AiException -> e.message ?: "AI 调用失败"
            else -> "出错了：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun reloadUnit(id: Long): StudyUnit {
        val u = c.study.unit(id) ?: throw IllegalStateException("单元不存在")
        unit = u
        return u
    }

    private fun nextAfter(u: StudyUnit): Boolean = u.currentStep() != UnitStep.DONE

    // —— 新建 ——
    val canCreate: Boolean get() = newTitle.isNotBlank() && newMaterial.trim().length >= 20

    fun createUnit() = launchBusy {
        val id = c.study.create(newTitle, newMaterial)
        reloadUnit(id)
        enter(FocusTask.Step(UnitStep.PREVIEW))
    }

    // —— 第 1 步：预习提问 ——
    val canSavePreview: Boolean get() = preQuestions.count { it.isNotBlank() } >= 2

    fun savePreview() = launchBusy {
        val u = unit ?: return@launchBusy
        c.study.savePreQuestions(u, preQuestions.filter { it.isNotBlank() })
        val nu = reloadUnit(u.id)
        completion = Completion("预习问题写好了", "接下来带着问题读资料", nextAfter(nu))
    }

    // —— 第 2 步：读资料 ——
    fun markStudied() = launchBusy {
        val u = unit ?: return@launchBusy
        c.study.markStudied(u)
        val nu = reloadUnit(u.id)
        completion = Completion("读完了", "下一步：合上资料，用自己的话讲一遍", nextAfter(nu))
    }

    // —— 第 3 步：合上讲一遍 ——
    val canSubmitExplanation: Boolean get() = explanation.trim().length >= 30

    fun submitExplanation() = launchBusy {
        val u = unit ?: return@launchBusy
        val text = c.coach.critiqueExplanation(u, explanation)
        critique = Parsers.sections(text)
        c.study.saveExplanation(u, explanation, text, "ai")
        finishExplain(u.id)
    }

    fun revealMaterial() {
        materialRevealed = true
    }

    fun submitSelfExplanation() = launchBusy {
        val u = unit ?: return@launchBusy
        val text = selfText(SELF_EXPLAIN_TITLES)
        critique = Parsers.sections(text)
        c.study.saveExplanation(u, explanation, text, "self")
        finishExplain(u.id)
    }

    private suspend fun finishExplain(id: Long) {
        val nu = reloadUnit(id)
        val next = nu.nextReviewAt?.let { Time.md(it) } ?: "明天"
        completion = Completion("讲完了一遍", "第一次自测排在 $next。下一步：举一反三", nextAfter(nu))
    }

    // —— 第 4 步：举一反三 ——
    val canSubmitExample: Boolean get() = example.trim().length >= 15

    fun submitExample() = launchBusy {
        val u = unit ?: return@launchBusy
        val text = c.coach.critiqueTransfer(u, example)
        critique = Parsers.sections(text)
        c.study.saveTransfer(u, example, text, "ai")
        finishTransfer(u.id)
    }

    fun submitSelfExample() = launchBusy {
        val u = unit ?: return@launchBusy
        val text = selfText(SELF_TRANSFER_TITLES)
        critique = Parsers.sections(text)
        c.study.saveTransfer(u, example, text, "self")
        finishTransfer(u.id)
    }

    private suspend fun finishTransfer(id: Long) {
        val nu = reloadUnit(id)
        val next = nu.nextReviewAt?.let { "下次自测：${Time.md(it)}（${Time.relativeDay(Time.dateOf(it))}）" } ?: ""
        completion = Completion("四步走完了", next, false)
    }

    // —— 间隔自测 ——
    fun loadQuestions() {
        val u = unit ?: return
        reviewPhase = ReviewPhase.LOADING
        launchBusy {
            // 中途退出过的自测接着做，不重新出题。
            val open = c.study.openReview(u.id)
            val qs: List<String>
            if (open != null) {
                review = open
                qs = JsonLists.decode(open.questions)
                if (open.mode == "self") selfCheck = true
            } else {
                qs = if (selfCheck) selfQuestions(u) else {
                    c.coach.generateQuestions(u, Spacing.days(u.intervalLevel), c.study.previousQuestions(u.id))
                }
                review = c.study.startReview(u, qs, if (selfCheck) "self" else "ai")
            }
            questions = qs
            answers.clear()
            val saved = review?.answers?.let { JsonLists.decode(it) }.orEmpty()
            qs.indices.forEach { answers.add(saved.getOrNull(it).orEmpty()) }
            reviewPhase = ReviewPhase.ANSWERING
        }
    }

    /** 离线时的自测题：预习问题 + 合上讲一遍。 */
    private fun selfQuestions(u: StudyUnit): List<String> =
        (u.preQuestionList().take(2) + "不看资料，把「${u.title}」的核心内容讲一遍").take(3)

    val canSubmitAnswers: Boolean get() = answers.isNotEmpty() && answers.all { it.isNotBlank() }

    fun submitAnswers() = launchBusy {
        val u = unit ?: return@launchBusy
        val r = review ?: return@launchBusy
        val list = answers.toList()
        if (selfCheck) {
            c.study.saveAnswers(r, list, "")
            materialRevealed = true
            reviewPhase = ReviewPhase.RATING
        } else {
            reviewPhase = ReviewPhase.GRADING
            val g = try {
                c.coach.gradeReview(u, questions, list)
            } catch (e: Exception) {
                reviewPhase = ReviewPhase.ANSWERING
                throw e
            }
            grade = g
            c.study.saveAnswers(r, list, Parsers.gradeToText(g))
            reviewPhase = ReviewPhase.RATING
        }
    }

    fun rate(rating: String) = launchBusy {
        val u = unit ?: return@launchBusy
        val r = review ?: return@launchBusy
        val critiqueText = grade?.let { Parsers.gradeToText(it) } ?: "【对照资料自查】\n- 已对照资料检查作答"
        c.study.finishReview(r, u, answers.toList(), critiqueText, rating)
        val nu = reloadUnit(u.id)
        val next = nu.nextReviewAt?.let { "${Time.md(it)}（${Time.relativeDay(Time.dateOf(it))}）" } ?: ""
        completion = Completion(
            "自测完成：${Rating.label(rating)}",
            "间隔调整为 ${Spacing.days(nu.intervalLevel)} 天，下次自测 $next",
            nextAfter(nu),
        )
    }

    // —— 接着做 / 结束 ——
    fun continueNext() {
        val u = unit ?: return
        val step = u.currentStep()
        if (step != UnitStep.DONE) enter(FocusTask.Step(step))
    }

    /** 结束专注，返回本次分钟数。 */
    fun end(onDone: (Int) -> Unit) {
        if (ended) return
        ended = true
        heartbeat?.cancel()
        viewModelScope.launch {
            val minutes = sessionId?.let { c.sessions.finish(it) } ?: 0
            onDone(minutes)
        }
    }

    override fun onCleared() {
        // 没点“结束”就离开（例如被系统回收）：在应用级作用域里收尾，时长按现在算。
        if (!ended) {
            val id = sessionId
            if (id != null) c.appScope.launch { c.sessions.finish(id) }
        }
        super.onCleared()
    }

    private fun selfText(titles: List<String>): String = titles.joinToString("\n") { t ->
        val body = selfNotes[t].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        "【$t】\n" + (body.ifEmpty { listOf("无") }).joinToString("\n") { "- $it" }
    }

    companion object {
        val SELF_EXPLAIN_TITLES = listOf("讲错了", "漏掉的关键点", "预习问题答到了吗")
        val SELF_TRANSFER_TITLES = listOf("成立的地方", "不成立的地方")
    }
}
