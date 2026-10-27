package com.yishou.app.gate

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.card.*
import com.yishou.app.data.*
import com.yishou.app.judge.*
import com.yishou.app.reflex.*
import com.yishou.app.reflex.Main as GateMain
import com.yishou.app.settings.AppZone
import com.yishou.app.stats.DayStats
import com.yishou.app.thread.*
import com.yishou.app.ui.StrandWork
import com.yishou.app.ui.TurnReview
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class GateState(
    val loading: Boolean = true, val triggerPackage: String? = null, val appLabel: String = "", val group: String = "",
    val inWindow: Boolean = false, val partnerPending: Boolean = false, val followUp: String? = null,
    val exit: String = "", val passMinutes: Int? = null, val wrongInRow: Int = 0,
    val waiting: Boolean = false, val waitRemainingMs: Long = WAIT_MS,
    val mode: GateMain = GateMain.WAIT, val revision: Int = 0, val busy: Boolean = false, val error: String? = null,
) { val isTest get() = triggerPackage == null && group != "早上" }

/** 挑题、作答与放行分开；失败只换路，不直接发通行。 */
class GateViewModel(app: Application) : AndroidViewModel(app) {
    val yishou = app as YishouApp
    private val dao = yishou.database.strands()
    private val session = yishou.newSession()
    val card = session.state
    val work = StrandWork(yishou)
    private val issuer = PassIssuer(yishou.database.dao())
    private val fallback = WaitFallback { SystemClock.elapsedRealtime() }
    private var waitJob: Job? = null
    private var operation: Job? = null
    private var startedFor: Pair<String?, String>? = null
    private val _state = MutableStateFlow(GateState())
    val state = _state.asStateFlow()
    var probe: GateFlow? = null; private set
    var review: TurnReview? = null; private set
    var triggerSaved = false; private set
    var recall: Reflex? = null; private set
    var recallFeedback: String? = null; private set
    var recallingEcho = false; private set
    private var echoChecked = false
    private var recallTries = 0
    private var judgeDown = false
    private val skippedCards = mutableSetOf<Long>()
    private var bankQuestion: LockedQ? = null
    var askingFirst = false; private set
    var feed: List<String> = emptyList(); private set
    var ice: Ice? = null; private set
    private var fade = FadeState()
    private var kind = ""
    private var fadeRecorded = false

    fun changed() { _state.update { it.copy(revision = it.revision + 1) } }
    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        operation = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { com.yishou.app.log.RunLog.e("入口", "这一手操作失败", e); _state.update { it.copy(error = "这步没能完成，请重试") } }
            finally { _state.update { it.copy(busy = false, loading = false, revision = it.revision + 1) } }
        }
    }
    fun start(pkg: String?, group: String, label: String) {
        if (startedFor == (pkg to group) && _state.value.passMinutes == null) return
        operation?.cancel(); cancelWait(); startedFor = pkg to group
        judgeDown = false; skippedCards.clear(); echoChecked = false; recallTries = 0
        val prefs = yishou.settings.app.value
        val now = System.currentTimeMillis()
        _state.value = GateState(triggerPackage = pkg, group = group, appLabel = label,
            inWindow = group == GateActivity.WINDOW_GROUP || (pkg != null && WindowClock.active(now, prefs, AppZone.zone) != null),
            followUp = prefs.pendingExit.takeIf { it.isNotBlank() && now >= prefs.pendingExitAt })
        run { refreshPartnerCloseNow(); if (!_state.value.inWindow) pick() }
    }
    fun refreshPartnerClose() { viewModelScope.launch { refreshPartnerCloseNow() } }
    private suspend fun refreshPartnerCloseNow() { _state.update { it.copy(partnerPending = yishou.partner.pendingClose() != null) } }

    private suspend fun availableBank(): List<Dealt> = yishou.database.cards().unusedBank().mapNotNull { row ->
        if (!row.verified || row.id in skippedCards) null else runCatching {
            val c = Card.fromJson(row.json)
            if (LockedQuestions.fromCard(row.id, c) == null) null else Dealt(row, c, false)
        }.getOrNull()
    }
    private suspend fun pick() {
        probe = null; review = null; triggerSaved = false; recall = null; recallFeedback = null; ice = null
        session.stop()
        val open = StrandStore(dao).open()
        val bank = availableBank()
        val refs = dao.reflexes().map { it.value() }
        val todayRefs = availableReflexes(refs)
        val mode = GatePlan.pick(GateInput(open != null && (open.pending != null || !judgeDown), bank.isNotEmpty(),
            yishou.settings.llm.value.isComplete && !judgeDown, todayRefs.isNotEmpty() && recallTries < 3, _state.value.wrongInRow))
        _state.update { it.copy(mode = mode) }
        when (mode) {
            GateMain.STRAND -> {
                work.load(open!!)
                // 外部总结在主动页面粘贴；收费站回到当前项正常出题。
                if (work.strand?.pending?.kind == MoveKind.EXTERNAL) {
                    val s = work.strand!!
                    work.load(StrandStore(dao).save(s.copy(pending = null)))
                }
                probe = work.flow; review = work.review
                if (probe == null && review == null) { judgeDown = true; pick(); return }
            }
            GateMain.BANK -> {
                val d = bank.first()
                session.start(CardMode.GATE, null, 1, deal = false); session.useDealt(d, 0)
                bankQuestion = LockedQuestions.fromCard(d.row.id, d.card)!!
                kind = d.card.skill.name; fade = dao.fade(kind)?.value() ?: FadeState(); fadeRecorded = false
                prepareBank()
                ice = bank.drop(1).randomOrNull()?.let { LockedQuestions.fromCard(it.row.id, it.card) }?.let { Icebreaker.from(it, kotlin.random.Random.nextLong()) }
            }
            GateMain.LOW_ENERGY -> recall = Reflexes.pick(todayRefs, System.currentTimeMillis())
            GateMain.WAIT -> Unit
        }
        if (!echoChecked && (mode == GateMain.STRAND || mode == GateMain.BANK)) {
            echoChecked = true
            refs.filter { it.dueAt <= System.currentTimeMillis() && it.id !in recalledToday() }.minByOrNull { it.dueAt }?.let {
                recall = it; recallingEcho = true
            }
        }
    }
    private fun prepareBank() {
        val q = bankQuestion ?: return
        askingFirst = fade.level == FadeLevel.ASK_FIRST
        feed = if (fade.level == FadeLevel.FEED) Fade.feedHints(q) else emptyList()
        probe = GateFlow(if (askingFirst) Fade.askFirstQ(q) else q) { SystemClock.elapsedRealtime() }
    }
    fun finishIce() { ice = null; changed() }
    fun showProbe(flow: GateFlow) { if (probe === flow) { flow.shown(); changed() } }
    fun editProbe(text: String, voice: Boolean = false) { probe?.edit(text, voice); changed() }
    fun voiceProbeStart() { probe?.voiceStart() }
    private suspend fun recordFade(ok: Boolean) {
        if (fadeRecorded) return
        fadeRecorded = true
        val n = fade.after(ok); dao.saveFade(FadeRow(kind, n.wins, n.misses))
    }
    fun submitProbe() = run {
        val f = probe ?: return@run
        if (_state.value.mode == GateMain.STRAND) {
            work.submit(); review = work.review
            if (f.unavailable) { judgeDown = true; pick() }
            else if (review?.verdict?.passed == false) _state.update { it.copy(wrongInRow = it.wrongInRow + 1) }
        } else {
            // 看过的退坡提示也交给判卷核对，不能照抄提示通关。
            val judge = object : Judge {
                override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String>) = yishou.judge.judge(q, answer, trace, seen + feed)
            }
            f.submit(judge)
            if (f.unavailable) { judgeDown = true; pick(); return@run }
            if (f.canSelect() && askingFirst) {
                askingFirst = false
                probe = GateFlow(bankQuestion!!) { SystemClock.elapsedRealtime() }
            } else if (f.canSelect()) f.verdict?.let(session::approveGate)
            else if (f.exhausted) {
                recordFade(false)
                review = TurnReview(f.question, f.answer, f.trace!!, f.verdict!!)
                _state.update { it.copy(wrongInRow = it.wrongInRow + 1) }
                session.state.value.dealt?.let { skippedCards += it.row.id }
            }
        }
    }
    fun selectProbeOption(option: Int) = run {
        val f = probe ?: return@run
        if (!f.canSelect() || askingFirst || session.state.value.done || review != null) return@run
        session.bet(option, Conf.MID)
        val correct = session.state.value.round?.finalCorrect == true
        recordFade(correct)
        review = TurnReview(f.question, f.answer, f.trace!!, f.verdict!!.copy(passed = correct, note = if (correct) "答案和要点都过了" else "选项没对上"))
        if (!correct) _state.update { it.copy(wrongInRow = it.wrongInRow + 1) }
        session.state.value.dealt?.let { skippedCards += it.row.id }
    }
    fun retry() = run {
        if (_state.value.mode == GateMain.STRAND && work.review != null) work.next()
        pick()
    }
    fun retryUnavailable() = run { judgeDown = false; pick() }
    fun saveTrigger(signal: String, action: String) = run {
        val rev = review ?: return@run
        if (!rev.verdict.passed || rev.verdict.unavailable) return@run
        Reflexes.check(signal, action, rev.q.prompt)?.let { why -> _state.update { it.copy(error = why) }; return@run }
        if (!triggerSaved) {
            dao.insertReflex(ReflexRow.from(Reflexes.create(signal, action, rev.q.id, System.currentTimeMillis())))
            triggerSaved = true
        }
    }
    fun onExitChange(text: String) { _state.update { it.copy(exit = text) } }
    fun answerFollowUp(done: Boolean) {
        yishou.stats.event(if (done) DayStats.EXIT_KEPT else DayStats.EXIT_MISSED)
        yishou.settings.updateApp { it.copy(pendingExit = "", pendingExitAt = 0) }
        _state.update { it.copy(followUp = null) }
    }
    private fun mayGrant() = !_state.value.let { it.isTest || it.inWindow || it.partnerPending || it.passMinutes != null || it.loading }
    fun pass() = run {
        val rev = review ?: return@run
        if (!mayGrant() || !triggerSaved || !rev.verdict.passed || rev.verdict.unavailable) return@run
        val pass = if (_state.value.mode == GateMain.STRAND) {
            // 不让同一份复盘在下次入口再换一次通行。
            work.acknowledge()
            issuer.issueVerified(_state.value.group, GatePlan.FULL_PASS_MIN, rev.verdict)
        } else issuer.issue(session.state.value, _state.value.group)
        pass?.let { finishGrant(it, DayStats.GATE_PASS) }
    }
    private fun finishGrant(pass: Pass, event: String) {
        yishou.stats.event(event)
        val s = _state.value
        if (s.exit.isNotBlank()) yishou.settings.updateApp { it.copy(pendingExit = s.exit.trim(), pendingExitAt = pass.endAt) }
        _state.update { it.copy(passMinutes = ((pass.endAt - pass.startAt) / 60_000L).toInt(), waiting = false) }
    }
    fun startWait() {
        if (_state.value.mode != GateMain.WAIT || _state.value.waiting || _state.value.busy || _state.value.inWindow || _state.value.partnerPending) return
        fallback.start(); _state.update { it.copy(waiting = true) }
        waitJob = viewModelScope.launch {
            while (_state.value.waiting) {
                val left = fallback.remainingMs(); _state.update { it.copy(waitRemainingMs = left) }
                if (left == 0L) break
                delay(minOf(left, 1_000L))
            }
        }
    }
    fun cancelWait() { waitJob?.cancel(); fallback.leave(); _state.update { it.copy(waiting = false, waitRemainingMs = WAIT_MS) } }
    fun passAfterWait() = run {
        if (!mayGrant() || !_state.value.waiting) return@run
        issuer.issueAfterWait(_state.value.group, fallback)?.let { finishGrant(it, DayStats.WAIT_PASS) }
    }

    private fun recalledToday(): Set<Long> = yishou.settings.app.value.let { prefs ->
        if (prefs.recallDay == java.time.LocalDate.now(AppZone.zone).toEpochDay()) prefs.recallIds else emptySet()
    }
    private fun availableReflexes(all: List<Reflex>) = all.filter { it.id !in recalledToday() }
    fun recallSource(): String = recall?.source.orEmpty()
    fun submitRecall(answer: String) = run {
        val r = recall ?: return@run
        val ok = Reflexes.recalled(r.action, answer)
        dao.updateReflex(ReflexRow.from(Reflexes.afterRecall(r, ok, System.currentTimeMillis())))
        val day = java.time.LocalDate.now(AppZone.zone).toEpochDay()
        yishou.settings.updateApp { it.copy(recallDay = day, recallIds = recalledToday() + r.id) }
        recallFeedback = if (ok) "记起来了" else "原来的动作：${r.action}"
        if (recallingEcho) return@run
        recallTries++
        if (ok && mayGrant()) issuer.issueRecall(_state.value.group, r, answer)?.let { finishGrant(it, DayStats.LOW_PASS) }
        if (ok && _state.value.isTest) recallFeedback = "默写通过；测试页不放行"
    }
    fun nextRecall() = run {
        if (recallingEcho) { recallingEcho = false; recall = null; recallFeedback = null }
        else pick()
    }
}
