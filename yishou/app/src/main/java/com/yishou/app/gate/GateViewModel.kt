package com.yishou.app.gate

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.card.*
import com.yishou.app.data.CardMode
import com.yishou.app.data.FadeRow
import com.yishou.app.data.ReflexRow
import com.yishou.app.dsh.DshHandoff
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

/**
 * 入口这一次的状态。
 *
 * 没有 waiting / waitRemainingMs 这一组：原来“题库空了就等 10 分钟换 5 分钟、切屏就作废”
 * 把智力活干完之后又罚一次站，是这套流程最伤人的地方。题库改由循环池接回来，等待整条删掉。
 */
data class GateState(
    val loading: Boolean = true, val triggerPackage: String? = null, val appLabel: String = "", val group: String = "",
    val inWindow: Boolean = false, val partnerPending: Boolean = false, val followUp: String? = null,
    val exit: String = "", val passMinutes: Int? = null, val wrongInRow: Int = 0,
    val dshMode: Boolean = false, val dshSent: Boolean = false,
    val mode: GateMain = GateMain.LOW_ENERGY, val revision: Int = 0, val busy: Boolean = false, val error: String? = null,
) { val isTest get() = triggerPackage == null && group != "早上" }

/** 挑题、作答与放行分开；失败只换路，不直接发通行。 */
class GateViewModel(app: Application) : AndroidViewModel(app) {
    val yishou = app as YishouApp
    private val dao = yishou.database.strands()
    private val session = yishou.newSession()
    val card = session.state
    val work = StrandWork(yishou)
    private val issuer = PassIssuer(yishou.database.dao(), minutesFor = { g -> yishou.settings.app.value.passMinutesFor(g) })
    private var operation: Job? = null
    private var generation = 0
    private var startedFor: Pair<String?, String>? = null
    private var startedDshEventId: String? = null
    var dsh: DshHandoff.Request? = null; private set
    private val _state = MutableStateFlow(GateState())
    val state = _state.asStateFlow()
    var probe: GateFlow? = null; private set
    var review: TurnReview? = null; private set
    var triggerSaved = false; private set
    var recall: Reflex? = null; private set
    var recallFeedback: String? = null; private set
    var recallingEcho = false; private set
    private var echoChecked = false
    private var recallPrompt = ""
    private var recallTries = 0
    private var judgeDown = false
    /** 这一回入口里已经用过的题：只在还有别的题时让位，不会因此没题可做 */
    private val usedThisVisit = mutableSetOf<Long>()
    private var bankQuestion: LockedQ? = null
    var askingFirst = false; private set
    var feed: List<String> = emptyList(); private set
    private var fade = FadeState()
    private var kind = ""
    private var fadeRecorded = false
    /** 两次机会用完才亮选项的那一题：选对了给低能量时长 */
    private var revealedLast = false

    fun changed() { _state.update { it.copy(revision = it.revision + 1) } }
    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        val mine = generation
        operation = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { com.yishou.app.log.RunLog.e("入口", "这一手操作失败", e); _state.update { it.copy(error = "这步没能完成，请重试") } }
            finally { if (mine == generation) _state.update { it.copy(busy = false, loading = false, revision = it.revision + 1) } }
        }
    }
    fun start(pkg: String?, group: String, label: String, dshRequest: DshHandoff.Request? = null) {
        // 同一事件重建页面不能重发；同一个应用的新交接必须重新开题。
        if (startedFor == (pkg to group) && startedDshEventId == dshRequest?.eventId &&
            (_state.value.passMinutes == null || dshRequest != null)) return
        generation++; operation?.cancel(); startedFor = pkg to group
        startedDshEventId = dshRequest?.eventId; dsh = dshRequest
        judgeDown = false; usedThisVisit.clear(); echoChecked = false; recallTries = 0; revealedLast = false
        val prefs = yishou.settings.app.value
        val now = System.currentTimeMillis()
        _state.value = GateState(triggerPackage = pkg, group = group, appLabel = label, dshMode = dshRequest != null,
            inWindow = group == GateActivity.WINDOW_GROUP || (pkg != null && WindowClock.active(now, prefs, AppZone.zone) != null),
            followUp = prefs.pendingExit.takeIf { it.isNotBlank() && now >= prefs.pendingExitAt })
        run { refreshPartnerCloseNow(); if (!_state.value.inWindow) pick() }
    }
    fun refreshPartnerClose() { viewModelScope.launch { refreshPartnerCloseNow() } }
    private suspend fun refreshPartnerCloseNow() { _state.update { it.copy(partnerPending = yishou.partner.pendingClose() != null) } }

    /**
     * 这一回能出的题。循环池：没做过的先出，做过的不报废，按最久没做接回来。
     * 题库一道都进不了入口（没答案、解析拆不出要点）时返回空——这才是真没题，界面直说原因。
     */
    private suspend fun availableBank(): List<Dealt> {
        // 先把进不了入口的题剔掉，再在剩下的里面挑。
        // 反过来（先挑再校验）会让一道坏题把整个题库挡空——那正是“明明有题却说没题”。
        val usable = yishou.database.cards().bankCycle().mapNotNull { row ->
            if (!row.verified) return@mapNotNull null
            val card = runCatching { Card.fromJson(row.json) }.getOrNull() ?: return@mapNotNull null
            if (LockedQuestions.fromCard(row.id, card) == null) return@mapNotNull null
            Dealt(row, card, row.usedAt != null)
        }
        if (usable.isEmpty()) return emptyList()
        val slots = usable.map { BankSlot(it.row.id, it.row.usedAt, it.row.createdAt) }
        val picked = BankCycle.pick(slots, System.currentTimeMillis(), usedThisVisit) ?: return emptyList()
        return usable.filter { it.row.id == picked.id }
    }

    /** 题库里有没有一道能进入口的题（用来区分“题库空”和“判卷不在”） */
    private suspend fun bankHasAny(): Boolean = yishou.database.cards().bankCycle().any { row ->
        row.verified && runCatching {
            val c = Card.fromJson(row.json)
            LockedQuestions.fromCard(row.id, c) != null
        }.getOrDefault(false)
    }

    private suspend fun pick() {
        probe = null; review = null; triggerSaved = false; recall = null; recallFeedback = null; recallingEcho = false
        revealedLast = false
        session.stop()
        val open = StrandStore(dao).open()
        val bank = availableBank()
        val refs = dao.reflexes().map { it.value() }
        val todayRefs = availableReflexes(refs)
        val llm = yishou.settings.llm.value.isComplete
        val mode = GatePlan.pick(GateInput(open != null && (open.pending != null || !judgeDown), bank.isNotEmpty(),
            llm && !judgeDown, todayRefs.isNotEmpty() && recallTries < 3, _state.value.wrongInRow, freshReady = llm))
        _state.update { it.copy(mode = mode) }
        when (mode) {
            GateMain.STRAND -> {
                work.load(open!!)
                if (work.strand?.pending?.kind == MoveKind.EXTERNAL || work.review?.q?.allowPaste == true) {
                    if (work.review != null) work.acknowledge()
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
            }
            GateMain.FRESH -> {
                // 题库没有能用的题：现出一道锁好的题，并在后台再备一道
                val q = FreshQuestions.take(yishou)
                if (q == null) { judgeDown = true; pick(); return }
                bankQuestion = null; askingFirst = false; feed = emptyList()
                probe = GateFlow(q) { SystemClock.elapsedRealtime() }
                viewModelScope.launch { runCatching { FreshQuestions.prefetch(yishou) } }
            }
            GateMain.LOW_ENERGY -> recall = Reflexes.pick(todayRefs, System.currentTimeMillis())
        }
        if (!echoChecked && (mode == GateMain.STRAND || mode == GateMain.BANK || mode == GateMain.FRESH)) {
            echoChecked = true
            refs.filter { it.dueAt <= System.currentTimeMillis() && it.id !in recalledToday() }.minByOrNull { it.dueAt }?.let {
                recall = it; recallingEcho = true
            }
        }
        recallPrompt = recall?.let { sourcePrompt(it.source) }.orEmpty()
        // 一道题都进不了入口：说清是题库的问题，不是拿“等一等”糊过去
        if (mode != GateMain.STRAND && recall == null && !bankHasAny()) {
            _state.update { it.copy(error = "题库里还没有能出入口的题：导入的题要有答案，解析里要能拆出 2–4 个要点。") }
        }
    }
    private fun prepareBank() {
        val q = bankQuestion ?: return
        askingFirst = fade.level == FadeLevel.ASK_FIRST
        feed = BankOpening.hints(q, fade)
        probe = GateFlow(BankOpening.first(q, fade)) { SystemClock.elapsedRealtime() }
    }
    fun showProbe(flow: GateFlow) { if (probe === flow) { flow.shown(); changed() } }
    /** 槽里打的字：整句是拼出来的，不能当成粘贴 */
    fun editProbe(text: String) { probe?.slotEdit(text); changed() }
    /** 语音听写进来的整句 */
    fun editProbeVoice(text: String) { probe?.edit(text, voice = true); changed() }
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
        } else if (_state.value.mode == GateMain.FRESH) {
            // 现出的题没有选项：要点过了就进复盘和触发器，三次没过换路
            f.submit(yishou.judge)
            if (f.unavailable) { judgeDown = true; pick(); return@run }
            val v = f.verdict ?: return@run
            if (v.passed) review = TurnReview(f.question, f.answer, f.trace!!, v)
            else if (f.exhausted) {
                review = TurnReview(f.question, f.answer, f.trace!!, v)
                _state.update { it.copy(wrongInRow = it.wrongInRow + 1) }
            }
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
            } else if (f.canSelect() && f.verdict?.passed == true) {
                f.verdict?.let(session::approveGate)
            } else if (f.canSelect()) {
                // 两次没中：亮选项，给标准精简逻辑；选对算做完，不再拦
                revealedLast = true
                session.revealGate()
                recordFade(false)
                _state.update { it.copy(wrongInRow = it.wrongInRow + 1) }
            }
        }
    }
    fun selectProbeOption(option: Int) = run {
        val f = probe ?: return@run
        if (!f.canSelect() || askingFirst || session.state.value.done || review != null) return@run
        session.bet(option, Conf.MID)
        val correct = session.state.value.round?.finalCorrect == true
        recordFade(correct)
        val base = f.verdict
        review = if (base != null) TurnReview(f.question, f.answer, f.trace!!, base.copy(passed = correct, note = if (correct) "答案选对了" else "选项没对上"))
        else null
        // 选错了：这一题算用过（换一道），但不放行
        if (!correct) {
            _state.update { it.copy(wrongInRow = it.wrongInRow + 1) }
            session.state.value.dealt?.let { usedThisVisit += it.row.id }
        }
        // 选对了就放行：说中要点是正常时长，试满才选对是低能量时长。
        if (correct && mayGrant()) {
            // DSH 只认真正过判卷的作答，不能用低能量放行换凭证。
            val pass = (if (!_state.value.dshMode) issuer.issueRevealed(_state.value.group, session.state.value, revealedLast) else null)
                ?: issuer.issue(session.state.value, _state.value.group)
            pass?.let { finishGrant(it, if (revealedLast) DayStats.LOW_PASS else DayStats.GATE_PASS) }
            if (pass == null) _state.update { it.copy(error = "这一题按记录不足以放行，重来一道。") }
        }
    }
    fun retry() = run {
        if (_state.value.mode == GateMain.STRAND && work.review != null) work.next()
        pick()
    }
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

    /**
     * 线程那一手过了就放行。
     * 触发器不再是放行门槛：填触发器是加分项，不填也照样走——脑力活已经做完了，
     * 再拿一句表单把人挡在外面，只会让人觉得“过一关又冒出一关”。
     */
    fun pass() = run {
        val rev = review ?: return@run
        if (!mayGrant() || !GateCompletion.ready(rev.verdict, triggerSaved, rev.pullBack)) return@run
        val pass = if (_state.value.mode == GateMain.FRESH) {
            issuer.issueVerified(_state.value.group, rev.verdict)
        } else if (_state.value.mode == GateMain.STRAND) {
            // 不让同一份复盘在下次入口再换一次通行。
            work.acknowledge()
            issuer.issueVerified(_state.value.group, rev.verdict)
        } else issuer.issue(session.state.value, _state.value.group)
        pass?.let { finishGrant(it, DayStats.GATE_PASS) }
    }
    private fun finishGrant(pass: com.yishou.app.data.Pass, event: String) {
        yishou.stats.event(event)
        val s = _state.value
        if (s.exit.isNotBlank()) yishou.settings.updateApp { it.copy(pendingExit = s.exit.trim(), pendingExitAt = pass.endAt) }
        _state.update { it.copy(passMinutes = ((pass.endAt - pass.startAt) / 60_000L).toInt()) }
        if (event == DayStats.GATE_PASS) sendDshPass(pass)
    }

    /** 只有放行已存库的成功出口能把一次性凭证交回；失败后也不能自行打开目标应用。 */
    private fun sendDshPass(pass: com.yishou.app.data.Pass) {
        val req = dsh ?: return
        if (_state.value.isTest || _state.value.group == "早上") return
        val minutes = ((pass.endAt - pass.startAt) / 60_000L).toInt()
        val extras = DshHandoff.passExtras(req, minutes, "pass:" + pass.id)
        val intent = android.content.Intent(DshHandoff.ACTION_PASS).setPackage(req.replyPackage)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { (key, value) -> when (value) {
            is Int -> intent.putExtra(key, value)
            is String -> intent.putExtra(key, value)
        } }
        dsh = null
        val ok = runCatching { yishou.startActivity(intent) }.isSuccess
        _state.update { it.copy(dshSent = ok, error = if (ok) null else "没能回到 DSH Go，请回到拦截页重新开始。") }
    }

    private fun recalledToday(): Set<Long> = yishou.settings.app.value.let { prefs ->
        if (prefs.recallDay == java.time.LocalDate.now(AppZone.zone).toEpochDay()) prefs.recallIds else emptySet()
    }
    private fun availableReflexes(all: List<Reflex>) = all.filter { it.id !in recalledToday() }
    private suspend fun sourcePrompt(id: String): String {
        id.removePrefix("card-").toLongOrNull()?.let { cardId ->
            yishou.database.cards().getCard(cardId)?.let { row ->
                return runCatching { Card.fromJson(row.json).let { (it.sentences + it.question).joinToString("\n") } }.getOrDefault("")
            }
        }
        val strandId = Regex("^s(\\d+)-").find(id)?.groupValues?.get(1)?.toLongOrNull()
        strandId?.let { StrandStore(dao).get(it) }?.let { s ->
            val at = id.substringAfterLast('-').toLongOrNull()
            return s.moves.firstOrNull { it.at >= (at ?: Long.MAX_VALUE) }?.ask ?: s.goal
        }
        return ""
    }
    fun recallSource(): String = recallPrompt
    fun submitRecall(answer: String) = run {
        val r = recall ?: return@run
        if (recallFeedback != null) return@run
        val ok = Reflexes.recalled(r.action, answer)
        dao.updateReflex(ReflexRow.from(Reflexes.afterRecall(r, ok, System.currentTimeMillis())))
        val day = java.time.LocalDate.now(AppZone.zone).toEpochDay()
        yishou.settings.updateApp { it.copy(recallDay = day, recallIds = recalledToday() + r.id) }
        recallFeedback = if (ok) "记起来了" else "原来的动作：${r.action}"
        // 回响只是开题前闪一屏，不单独占一次放行
        if (recallingEcho) return@run
        recallTries++
        if (ok && mayGrant() && !_state.value.dshMode) issuer.issueRecall(_state.value.group, r, answer)?.let { finishGrant(it, DayStats.LOW_PASS) }
        if (ok && _state.value.dshMode) recallFeedback = "记起来了，继续做题后才能回到拦截页放行。"
        if (ok && _state.value.isTest) recallFeedback = "默写通过；测试页不放行"
    }
    fun nextRecall() = run {
        if (recallingEcho) { recallingEcho = false; recall = null; recallFeedback = null }
        else pick()
    }
}
