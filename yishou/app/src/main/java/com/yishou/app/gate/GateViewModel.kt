package com.yishou.app.gate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.card.CardSession
import com.yishou.app.card.SessionState
import com.yishou.app.data.CardMode
import com.yishou.app.settings.AppZone
import com.yishou.app.stats.DayStats
import com.yishou.app.system.Haptics
import com.yishou.app.ui.CardActions
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 思考页的界面状态（题卡本身在 [CardSession] 里）。 */
data class GateState(
    val loading: Boolean = true,
    /** 触发的应用包名；从权限页“测试思考页”打开时为 null */
    val triggerPackage: String? = null,
    val appLabel: String = "",
    val group: String = "",
    /** 陪练窗口进行中：不发放行 */
    val inWindow: Boolean = false,
    /** 上次放行时说好“看完做的第一件事”，先问做了没有 */
    val followUp: String? = null,
    val partnerPending: Boolean = false,
    /** 这次放行要说的“看完做什么” */
    val exit: String = "",
    val offlineLeft: Int = 0,
    val offlineMinutes: Int = 5,
    /** 已放行的分钟数；非 null 表示可以回到原应用 */
    val passMinutes: Int? = null,
    /** 要不要做一张题卡；false 时只打卡 */
    val gateCard: Boolean = true,
    /** 用主线的下一手当这一关 */
    val useLine: Boolean = false,
    /** 岔路：开局先过一遍的旧规则 / 差值（念一遍或点记得才往下） */
    val echo: com.yishou.app.data.Echo? = null,
    /** 岔路：题卡答错后写的差值（本来想……，漏看了……，下次见到……先……） */
    val delta: String = "",
    /** 这一关是一个残局（静音单手：点破绽、翻底牌） */
    val endgame: com.yishou.app.data.Endgame? = null,
    /** 残局翻了底牌 */
    val endgameDone: Boolean = false,
) {
    val isTest: Boolean get() = triggerPackage == null
}

/** 入口只接受题卡的答案与原文依据，其他训练不签发放行。 */
class GateViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val session: CardSession = yishou.newSession()
    val card: StateFlow<SessionState> = session.state
    private val lineSession = yishou.newLine()
    val line: StateFlow<com.yishou.app.line.LineState> = lineSession.state

    private val _state = MutableStateFlow(GateState())
    val state: StateFlow<GateState> = _state.asStateFlow()

    private var startedFor: Pair<String?, String>? = null
    private var granting = false
    private val issuer = PassIssuer(dao = yishou.database.dao())

    /** 每次思考页被（重新）打开时调用。同一个应用重复打开不会清掉正在做的题。 */
    fun start(pkg: String?, group: String, label: String) {
        if (startedFor == pkg to group && !_state.value.loading && _state.value.passMinutes == null) return
        startedFor = pkg to group
        val prefs = yishou.settings.app.value
        val now = System.currentTimeMillis()
        val inWindow = pkg != null && (group == GateActivity.WINDOW_GROUP || WindowClock.active(now, prefs, AppZone.zone) != null)
        val follow = prefs.pendingExit.takeIf { it.isNotBlank() && now >= prefs.pendingExitAt }
        _state.value = GateState(
            loading = true,
            triggerPackage = pkg,
            appLabel = label,
            group = group,
            inWindow = inWindow,
            followUp = follow,
            offlineMinutes = prefs.offlinePassMinutes,
            gateCard = prefs.gateCard,
        )
        session.stop()
        lineSession.stop()
        viewModelScope.launch {
            refreshPartnerCloseNow()
            _state.update { it.copy(loading = false) }
            if (inWindow) return@launch
            pushEcho(pkg.orEmpty())
            _state.update { it.copy(gateCard = true, useLine = false, endgame = null) }
            session.start(CardMode.GATE, null, 1)
        }
    }

    fun refreshPartnerClose() = viewModelScope.launch { refreshPartnerCloseNow() }

    private suspend fun refreshPartnerCloseNow() {
        val pending = yishou.partner.pendingClose() != null
        _state.update { it.copy(partnerPending = pending) }
    }

    private val chalu = com.yishou.app.system.Variant.chalu

    /** 回响：同一个 App 的差值优先，其次是确认得最少的规则 / 结论；推出来就记一次 */
    private suspend fun pushEcho(pkg: String) {
        val dao = yishou.database.echoes()
        val now = System.currentTimeMillis()
        val e = com.yishou.app.line.Echoes.pick(dao.recent(), pkg, now) ?: return
        val shown = e.copy(shownAt = now, shown = e.shown + 1)
        dao.update(shown)
        _state.update { it.copy(echo = shown) }
    }

    /** 回响确认：念对一半以上，或者点了记得 */
    fun confirmEcho(said: String) {
        val e = _state.value.echo ?: return
        _state.update { it.copy(echo = null) }
        if (said.isNotBlank() && !com.yishou.app.line.Echoes.readBack(e.text, said)) {
            // 念得对不上：这次不算确认，下次还推
            return
        }
        viewModelScope.launch { yishou.database.echoes().update(e.copy(confirmed = e.confirmed + 1)) }
    }

    /** 残局翻了底牌：记下点没点中，排下次 */
    fun endgameFlipped(firstHit: Boolean) {
        val e = _state.value.endgame ?: return
        _state.update { it.copy(endgameDone = true) }
        viewModelScope.launch { yishou.database.endgames().update(com.yishou.app.endgame.Endgames.after(e, firstHit, System.currentTimeMillis())) }
    }

    fun saveLaw(text: String) = viewModelScope.launch { yishou.echo(text, com.yishou.app.data.EchoKind.LAW) }

    fun onDeltaChange(text: String) = _state.update { it.copy(delta = text) }

    /** 这一张答错了（首答不对）：岔路要先写差值 */
    val needsDelta: Boolean get() {
        if (!chalu) return false
        val c = session.state.value
        return _state.value.gateCard && c.done && c.results.any { !it.firstCorrect }
    }

    /** 放着之后回来接着下（岔路里“先放着”不放行） */
    fun resumeLine() = lineSession.resume()

    /** 上次说好的事：做了没有 */
    fun answerFollowUp(done: Boolean) {
        com.yishou.app.system.Notifications.cancelCapsule(yishou)
        yishou.stats.event(if (done) DayStats.EXIT_KEPT else DayStats.EXIT_MISSED)
        yishou.settings.updateApp { it.copy(pendingExit = "", pendingExitAt = 0) }
        _state.update { it.copy(followUp = null) }
    }

    val actions = CardActions(
        bet = { o, c -> act { session.bet(o, c) } },
        evidence = { e -> act { session.evidence(e) } },
        check = { o, f -> act { session.check(o, f) } },
        change = { o -> act { session.change(o) } },
        hint = { act { session.hint() } },
        cause = { c -> act { session.cause(c) } },
        key = { o -> act { session.officialKey(o) } },
        hide = { session.hide() },
        note = { session.note(it) },
    )

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            val before = session.state.value.results.size
            block()
            val s = session.state.value
            if (s.results.size > before) {
                Haptics.play(yishou, if (s.results.last().firstCorrect) Haptics.Kind.PASS else Haptics.Kind.REDO)
            }
        }
    }

    fun retry() = viewModelScope.launch {
        if (session.state.value.done) {
            session.stop()
            session.start(CardMode.GATE, null, 1)
        } else session.deal()
    }

    fun onExitChange(text: String) = _state.update { it.copy(exit = text) }

    /** 主线下一手 */
    fun move(text: String) = viewModelScope.launch { lineSession.answer(text) }

    /** 只有当前题卡完成双重校验后才能签发。 */
    fun pass() {
        val s = _state.value
        if (granting || s.loading || s.partnerPending || s.isTest || s.inWindow || s.passMinutes != null) return
        if (!PassIssuer.eligible(session.state.value)) return
        granting = true
        viewModelScope.launch {
            try {
                val pass = issuer.issue(session.state.value, s.group) ?: return@launch
                yishou.stats.event(DayStats.GATE_PASS)
                _state.update { it.copy(passMinutes = ((pass.endAt - pass.startAt) / 60_000L).toInt()) }
            } finally { granting = false }
        }
    }
}
