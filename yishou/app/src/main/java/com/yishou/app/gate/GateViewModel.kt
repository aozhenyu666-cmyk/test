package com.yishou.app.gate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yishou.app.YishouApp
import com.yishou.app.data.RoundSource
import com.yishou.app.round.AnswerRules
import com.yishou.app.settings.AppZone
import com.yishou.app.stats.DayStats
import com.yishou.app.training.PracticeSession
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GateState(
    val loading: Boolean = true,
    val triggerPackage: String? = null,
    val appLabel: String = "",
    val group: String = "",
    val inWindow: Boolean = false,
    val followUp: String? = null,
    val offlineMinutes: Int = 5,
    val offlineLeft: Int = 0,
    val exit: String = "",
    val passMinutes: Int? = null,
    val startedAt: Long = 0,
) {
    val isTest: Boolean get() = triggerPackage == null
}

/** 入口接续当前任务。有效的推导或现实结果都能应一手，不再强制先刷一道无关的题。 */
class GateViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val _state = MutableStateFlow(GateState())
    val state = _state.asStateFlow()
    val practice = PracticeSession(yishou, viewModelScope, RoundSource.GATE) { _state.value.triggerPackage }
    private var startedFor: Pair<String?, String>? = null
    private var granting = false

    fun start(pkg: String?, group: String, label: String) {
        if (startedFor == pkg to group && !_state.value.loading && _state.value.passMinutes == null) return
        startedFor = pkg to group
        val prefs = yishou.settings.app.value
        val now = System.currentTimeMillis()
        val inWindow = pkg != null && (group == GateActivity.WINDOW_GROUP || WindowClock.active(now, prefs, AppZone.zone) != null)
        _state.value = GateState(loading = false, triggerPackage = pkg, appLabel = label, group = group,
            inWindow = inWindow, followUp = prefs.pendingExit.takeIf { it.isNotBlank() && now >= prefs.pendingExitAt },
            offlineMinutes = prefs.offlinePassMinutes, offlineLeft = offlineLeft(), startedAt = now)
        if (!inWindow) practice.start()
    }

    fun answerFollowUp(done: Boolean) {
        yishou.stats.event(if (done) DayStats.EXIT_KEPT else DayStats.EXIT_MISSED)
        yishou.settings.updateApp { it.copy(pendingExit = "", pendingExitAt = 0) }
        _state.update { it.copy(followUp = null) }
    }

    fun onExitChange(text: String) = _state.update { it.copy(exit = text) }
    fun pass() {
        val s = _state.value
        val round = practice.state.value.last ?: return
        if (!round.effective || !round.judged || round.createdAt < s.startedAt) return
        grant(false)
    }
    fun passOffline() = grant(true)
    private fun grant(offline: Boolean) {
        val s = _state.value
        val p = practice.state.value
        if (granting || s.isTest || s.inWindow || s.passMinutes != null || s.exit.isBlank() || p.busy) return
        val text = AnswerRules.compose(p.answer, practice.attachment.readyText)
        if (offline && (offlineLeft() <= 0 || p.error == null || text.isBlank() || p.task == null || practice.attachment.busy)) return
        granting = true
        viewModelScope.launch {
            try {
                val minutes = if (offline) yishou.settings.app.value.offlinePassMinutes else yishou.settings.app.value.passMinutesFor(s.group)
                if (offline) yishou.engine.saveOffline(p.task!!, p.move.orEmpty(), text, s.triggerPackage, s.group, minutes)
                else yishou.engine.grantPass(s.group, minutes, p.last?.id)
                yishou.stats.event(if (offline) DayStats.OFFLINE_PASS else DayStats.GATE_PASS)
                yishou.settings.updateApp { it.copy(pendingExit = s.exit.trim(), pendingExitAt = System.currentTimeMillis() + minutes * 60_000L) }
                practice.stop()
                _state.update { it.copy(passMinutes = minutes) }
            } finally { granting = false }
        }
    }
    private fun offlineLeft(): Int {
        val used = yishou.stats.day(java.time.LocalDate.now(AppZone.zone)).event(DayStats.OFFLINE_PASS)
        return (yishou.settings.app.value.offlineDailyLimit - used).coerceAtLeast(0)
    }
}
