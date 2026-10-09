package com.yishou.pc

import com.sun.jna.platform.win32.WinDef
import com.yishou.pc.core.Classifier
import com.yishou.pc.core.DayState
import com.yishou.pc.core.Event
import com.yishou.pc.core.EventStore
import com.yishou.pc.core.EventType
import com.yishou.pc.core.Focus
import com.yishou.pc.core.GateInfo
import com.yishou.pc.core.Gatekeeper
import com.yishou.pc.core.Heartbeat
import com.yishou.pc.core.Prefs
import com.yishou.pc.core.Verdict
import com.yishou.pc.win.Voice
import com.yishou.pc.win.Win32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/** 正在显示的守门页：要守的是哪个窗口、显示什么。 */
data class GateRequest(
    val info: GateInfo,
    /** 想打开的东西：窗口标题或程序名 */
    val target: String,
    val hwnd: WinDef.HWND?,
    val shownAt: Long,
)

/**
 * 电脑版的全部运行逻辑：每 0.7 秒看一眼前台窗口，是要守的就弹守门页；
 * 算专注时段，写心跳，记逃跑记录。界面只读这里的状态、调这里的方法。
 */
class Controller(val dataDir: File) {
    private val zone get() = ZoneId.systemDefault()
    private val prefsFile = File(dataDir, "prefs.json")
    private val store = EventStore(File(dataDir, "events"))
    private val heartbeat = Heartbeat(File(dataDir, "heartbeat.json"))

    private val _prefs = MutableStateFlow(Prefs.fromJson(prefsFile.takeIf { it.exists() }?.readText()))
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    private var date: LocalDate = today()
    private val _day = MutableStateFlow(DayState.from(store.read(date)))
    val day: StateFlow<DayState> = _day.asStateFlow()

    private val _gate = MutableStateFlow<GateRequest?>(null)
    val gate: StateFlow<GateRequest?> = _gate.asStateFlow()

    private val _focus = MutableStateFlow<Focus.Span?>(null)
    val focus: StateFlow<Focus.Span?> = _focus.asStateFlow()

    private val _now = MutableStateFlow(System.currentTimeMillis())
    /** 每秒走一下，倒计时用 */
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _toasts = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    /** 托盘气泡通知：标题、内容 */
    val toasts: SharedFlow<Pair<String, String>> = _toasts.asSharedFlow()

    private val _quitAsked = MutableStateFlow(false)
    /** 托盘里点了“退出…”：打开主窗口，在设置页写原因 */
    val quitAsked: StateFlow<Boolean> = _quitAsked.asStateFlow()

    private val _showMain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 又从开始菜单打开了一次：把已经在运行的主窗口叫出来 */
    val showMain: SharedFlow<Unit> = _showMain.asSharedFlow()
    private val showRequest = File(dataDir, "show.request")

    private var warnedPass = 0L
    private var lastBeat = 0L

    private fun today(): LocalDate = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()

    fun start(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        heartbeat.lastUnclean()?.let { last ->
            record(Event(now, EventType.UNCLEAN, note = "最后一次心跳在 ${time(last)}"))
        }
        heartbeat.beat(now)
        // 关机、注销时系统会让程序正常收尾；任务管理器强行结束时不会，下次启动就能看出来
        Runtime.getRuntime().addShutdownHook(Thread { heartbeat.markClean(System.currentTimeMillis()) })
        applyAutostart(_prefs.value.autostart)
        _focus.value = Focus.active(now, _prefs.value, zone)
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    // 一次出错不影响下一次
                }
                delay(700)
            }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        _now.value = now
        if (today() != date) {
            date = today()
            _day.value = DayState.from(store.read(date))
        }
        if (showRequest.exists()) {
            showRequest.delete()
            _showMain.tryEmit(Unit)
        }
        if (now - lastBeat > 30_000) {
            heartbeat.beat(now)
            lastBeat = now
        }
        val prefs = _prefs.value
        val f = Focus.active(now, prefs, zone)
        val was = _focus.value
        if (f != null && was == null) {
            record(Event(now, EventType.FOCUS_START, minutes = ((f.end - f.start) / 60_000).toInt()))
            notify("专注时段开始", "到 ${time(f.end)} 为止，电脑上要守的东西都不开。拿起手机，应那一手。")
            say("专注时段开始。拿起手机，应那一手。")
        } else if (f == null && was != null && now >= was.end) {
            notify("专注时段结束", "辛苦了。额度照常算。")
            say("专注时段结束。")
        }
        _focus.value = f

        if (_gate.value != null) return
        val fg = Win32.foreground() ?: return
        val cat = Classifier.categoryOf(fg.info, prefs) ?: return
        when (val v = Gatekeeper.check(cat, now, _day.value, prefs, f != null)) {
            is Verdict.Allow -> {
                val left = v.pass.end - now
                if (left in 1..60_000 && warnedPass != v.pass.start) {
                    warnedPass = v.pass.start
                    notify("还剩 1 分钟", if (v.pass.then.isNotBlank()) "说好看完回来：${v.pass.then}" else "准备收尾。")
                    say("还剩一分钟。")
                }
            }
            is Verdict.Gate -> {
                Win32.minimize(fg.hwnd)
                val target = fg.info.title.ifBlank { fg.info.exe }.take(60)
                record(Event(now, EventType.GATE, cat, target = target))
                _gate.value = GateRequest(v.info, target, fg.hwnd, now)
                when {
                    v.info.focus -> say("专注时段。手机上那一手在等你。")
                    v.info.timeUp != null -> say("时间到。你说看完回来${v.info.timeUp?.then}。")
                }
            }
        }
    }

    // ---------- 守门页上的选择 ----------

    fun grant(minutes: Int, what: String, then: String) {
        val g = _gate.value ?: return
        if (!g.info.canGrant || minutes !in 1..g.info.maxGrant || !Gatekeeper.intentionOk(what, then)) return
        record(Event(System.currentTimeMillis(), EventType.GRANT, g.info.category.id, g.target, minutes, what.trim(), then.trim()))
        close(restore = true)
    }

    fun emergency(reason: String) {
        val g = _gate.value ?: return
        if (g.info.emergencyLeft <= 0 || Gatekeeper.chars(reason) < Gatekeeper.EMERGENCY_MIN_CHARS) return
        record(
            Event(System.currentTimeMillis(), EventType.EMERGENCY, g.info.category.id, g.target, Gatekeeper.EMERGENCY_MINUTES, note = reason.trim()),
        )
        close(restore = true)
    }

    fun decline() {
        val g = _gate.value ?: return
        record(Event(System.currentTimeMillis(), EventType.DECLINE, g.info.category.id, g.target))
        close(restore = false)
    }

    /** 时间到：回去做说好的事 */
    fun goBack() {
        val g = _gate.value ?: return
        record(Event(System.currentTimeMillis(), EventType.RETURN, g.info.category.id, g.target, then = g.info.timeUp?.then.orEmpty()))
        close(restore = false)
    }

    fun followUp(done: Boolean) {
        val g = _gate.value ?: return
        val p = g.info.followUp ?: return
        record(Event(System.currentTimeMillis(), EventType.FOLLOWUP, p.category, then = p.then, done = done))
        _gate.value = g.copy(info = g.info.copy(followUp = null))
    }

    private fun close(restore: Boolean) {
        val g = _gate.value
        _gate.value = null
        if (restore) g?.hwnd?.let(Win32::restore)
    }

    // ---------- 专注 ----------

    fun startFocus(minutes: Int) {
        updatePrefs { it.copy(manualFocusStart = System.currentTimeMillis(), manualFocusMinutes = minutes) }
    }

    /** 提前结束专注要写原因 */
    fun stopFocus(reason: String): Boolean {
        if (_focus.value == null || Gatekeeper.chars(reason) < Gatekeeper.QUIT_MIN_CHARS) return false
        val now = System.currentTimeMillis()
        updatePrefs { it.copy(focusStoppedAt = now) }
        record(Event(now, EventType.FOCUS_STOP, note = reason.trim()))
        _focus.value = null
        return true
    }

    // ---------- 设置 ----------

    fun updatePrefs(change: (Prefs) -> Prefs) {
        val old = _prefs.value
        val next = change(old)
        _prefs.value = next
        try {
            dataDir.mkdirs()
            prefsFile.writeText(next.toJson())
        } catch (e: Exception) {
            notify("设置没保存上", e.message ?: "写文件失败")
        }
        if (next.autostart != old.autostart) applyAutostart(next.autostart)
    }

    /** 开机自启是否真的设上了（从安装版运行时才能设） */
    var autostartOk: Boolean = false
        private set

    private fun applyAutostart(on: Boolean) {
        Win32.setAutostart(on)
        autostartOk = Win32.autostartOn()
    }

    fun askQuit(ask: Boolean) {
        _quitAsked.value = ask
    }

    /** 从托盘退出：写了原因才退，记一笔。 */
    fun quit(reason: String): Boolean {
        if (Gatekeeper.chars(reason) < Gatekeeper.QUIT_MIN_CHARS) return false
        val now = System.currentTimeMillis()
        record(Event(now, EventType.QUIT, note = reason.trim()))
        heartbeat.markClean(now)
        exitProcess(0)
    }

    /** 某一天的逃跑记录（今天的直接用 [day]） */
    fun eventsOn(date: LocalDate): List<Event> = store.read(date)

    // ---------- 小工具 ----------

    private fun record(e: Event) {
        store.append(e)
        if (Instant.ofEpochMilli(e.time).atZone(zone).toLocalDate() == date) {
            _day.value = DayState.from(store.read(date))
        }
    }

    private fun notify(title: String, text: String) {
        _toasts.tryEmit(title to text)
    }

    private fun say(text: String) {
        if (_prefs.value.speak) Voice.say(text)
    }

    companion object {
        fun time(t: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(t))
    }
}
