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
) {
    val isTest: Boolean get() = triggerPackage == null
}

/**
 * 入口思考页：打开关注的应用前，
 * ① 上次说好看完做的事，先答做了没有；② 做一张题卡（下注、依据、揭晓，一两分钟）；
 * ③ 说一句看完做的第一件事，放行。判对错只看题卡，答错也照样放行——这里要的是“先停下来想一步”，不是考试。
 * 题出不来（没网、接口坏了）时，写一句理由可以离线放行，每天有次数。
 */
class GateViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val session: CardSession = yishou.newSession()
    val card: StateFlow<SessionState> = session.state
    private val lineSession = yishou.newLine()
    val line: StateFlow<com.yishou.app.line.LineState> = lineSession.state

    private val _state = MutableStateFlow(GateState())
    val state: StateFlow<GateState> = _state.asStateFlow()

    private var startedFor: Pair<String?, String>? = null

    /** 每次思考页被（重新）打开时调用。同一个应用重复打开不会清掉正在做的题。 */
    fun start(pkg: String?, group: String, label: String) {
        if (startedFor == pkg to group && !_state.value.loading && _state.value.passMinutes == null) return
        startedFor = pkg to group
        val prefs = yishou.settings.app.value
        val now = System.currentTimeMillis()
        val inWindow = pkg != null && (group == GateActivity.WINDOW_GROUP || WindowClock.active(now, prefs, AppZone.zone) != null)
        val follow = prefs.pendingExit.takeIf { it.isNotBlank() && now >= prefs.pendingExitAt }
        _state.value = GateState(
            loading = false,
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
        if (inWindow) return
        viewModelScope.launch {
            // 主线下一手：有在下的局就问它的下一问；没有就退成一张题卡
            val open = if (prefs.gateMode == "line") yishou.database.lines().latestOpen() else null
            when {
                open != null -> {
                    lineSession.open(open, yishou.level)
                    _state.update { it.copy(useLine = true, gateCard = false) }
                }
                prefs.gateMode == "check" -> _state.update { it.copy(gateCard = false) }
                else -> {
                    _state.update { it.copy(gateCard = true) }
                    session.start(CardMode.GATE, null, 1)
                    if (session.state.value.error != null) _state.update { it.copy(offlineLeft = offlineLeft()) }
                }
            }
        }
    }

    /** 上次说好的事：做了没有 */
    fun answerFollowUp(done: Boolean) {
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
                yishou.prefetchCards()
            }
        }
    }

    fun retry() = viewModelScope.launch {
        session.deal()
        if (session.state.value.error != null) _state.update { it.copy(offlineLeft = offlineLeft()) }
    }

    fun onExitChange(text: String) = _state.update { it.copy(exit = text) }

    /** 主线下一手 */
    fun move(text: String) = viewModelScope.launch { lineSession.answer(text) }

    /** 做完题卡、说了看完做什么：放行 */
    fun pass() {
        val s = _state.value
        if (s.isTest || s.inWindow || s.passMinutes != null || s.exit.isBlank()) return
        // 出不了题（没网、接口出错、题库空了又生成失败）时不卡人：退成只打卡
        val card = session.state.value
        val cardFailed = card.error != null && card.round == null
        if (s.gateCard && !card.done && !cardFailed) return
        // 主线：接住一手，或者降阶到底、老实记下“先放着”，才放行
        if (s.useLine && line.value.caught == 0 && !line.value.parked) return
        grant(yishou.settings.app.value.passMinutesFor(s.group), DayStats.GATE_PASS)
    }

    /** 题出不来：写一句为什么现在要用，离线放行（每天有次数） */
    fun passOffline() {
        val s = _state.value
        if (s.isTest || s.inWindow || s.passMinutes != null || s.exit.isBlank()) return
        viewModelScope.launch {
            if (offlineLeft() <= 0) return@launch
            grant(yishou.settings.app.value.offlinePassMinutes, DayStats.OFFLINE_PASS)
        }
    }

    private fun grant(minutes: Int, event: String) {
        val s = _state.value
        viewModelScope.launch {
            yishou.engine.grantPass(s.group, minutes, null)
            yishou.stats.event(event)
            val end = System.currentTimeMillis() + minutes * 60_000L
            yishou.settings.updateApp { it.copy(pendingExit = s.exit.trim(), pendingExitAt = end) }
            _state.update { it.copy(passMinutes = minutes) }
        }
    }

    private fun offlineLeft(): Int {
        val used = yishou.stats.day(java.time.LocalDate.now(AppZone.zone)).event(DayStats.OFFLINE_PASS)
        return (yishou.settings.app.value.offlineDailyLimit - used).coerceAtLeast(0)
    }
}
