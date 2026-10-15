package com.zongkong.pc

import com.zongkong.core.AiConfig
import com.zongkong.core.ApiResult
import com.zongkong.core.DayClock
import com.zongkong.core.NotionClient
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.ChatClient
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.boss.Boss
import com.zongkong.core.boss.ClaimVerdict
import com.zongkong.core.device.DeviceLink
import com.zongkong.core.device.PhoneSide
import com.zongkong.pc.win.KeyGuard
import com.zongkong.pc.win.NetGuard
import com.zongkong.pc.win.Screen
import com.zongkong.pc.win.Shell
import com.zongkong.pc.win.Voice
import com.zongkong.pc.win.Win32
import com.zongkong.pc.win.WinFg
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** 正在显示的拦截页。 */
data class GateView(val target: Target, val title: String, val reason: String, val used: Int, val limit: Int, val shownAt: Long)

/** 右下角的小问候：“这 45 分钟在做什么？” */
data class CheckInView(val minutes: Int, val summary: String, val at: Long)

/**
 * 电脑版的全部运行逻辑：每秒看一眼前台窗口，记时间、判断锁不锁、拦不拦；
 * 和手机互通；到点的回访和问候交给 AI。界面只读这里的状态、调这里的方法。
 */
class Controller(val dataDir: File) {
    val zone: ZoneId = DayClock.BEIJING
    val store = Store(dataDir)
    private val net = NetGuard(dataDir)

    private val _config = MutableStateFlow(store.loadConfig())
    val config: StateFlow<PcConfig> = _config.asStateFlow()
    private val _state = MutableStateFlow(store.loadState())
    val state: StateFlow<PcState> = _state.asStateFlow()
    private var date: LocalDate = Usage.day(System.currentTimeMillis(), zone)
    private val _today = MutableStateFlow(store.loadUsage(date))
    val today: StateFlow<UsageDay> = _today.asStateFlow()
    private val _phone = MutableStateFlow(store.loadPhone())
    val phone: StateFlow<PhoneSide?> = _phone.asStateFlow()
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()
    private val _fg = MutableStateFlow<Pair<Fg, Target>?>(null)
    /** 此刻前台（给界面显示）。 */
    val fg: StateFlow<Pair<Fg, Target>?> = _fg.asStateFlow()
    private val _lock = MutableStateFlow<Verdict.Machine?>(null)
    val lock: StateFlow<Verdict.Machine?> = _lock.asStateFlow()
    private val _gate = MutableStateFlow<GateView?>(null)
    val gate: StateFlow<GateView?> = _gate.asStateFlow()
    private val _checkIn = MutableStateFlow<CheckInView?>(null)
    val checkIn: StateFlow<CheckInView?> = _checkIn.asStateFlow()
    private val _confirm = MutableStateFlow<Confirm?>(null)
    val confirm: StateFlow<Confirm?> = _confirm.asStateFlow()
    private val _chat = MutableStateFlow(store.loadChat())
    val chat: StateFlow<List<ChatMsg>> = _chat.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _link = MutableStateFlow(if (_config.value.link.ready) "还没同步" else "没连手机")
    val link: StateFlow<String> = _link.asStateFlow()
    private val _toasts = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val toasts: SharedFlow<Pair<String, String>> = _toasts.asSharedFlow()
    private val _showMain = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** 要把主窗口叫出来（参数是要切到的页）。 */
    val showMain: SharedFlow<String> = _showMain.asSharedFlow()

    private var lastWinFg: WinFg? = null
    private var lastTick = System.currentTimeMillis()
    private val said = mutableSetOf<String>()
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    private var dirty = false
    @Volatile var manualQuit = false

    private val api = ChatClient({ _config.value.ai })
    val env = object : PcEnv {
        override val zone: ZoneId get() = this@Controller.zone
        override val rnd = Random(System.nanoTime())
        override fun now() = System.currentTimeMillis()
        override fun config() = _config.value
        override fun updateConfig(f: (PcConfig) -> PcConfig) = updateConfigInternal(f)
        override fun state() = _state.value
        override fun updateState(f: (PcState) -> PcState) = updateStateInternal(f)
        override fun usage(date: LocalDate) = if (date == this@Controller.date) _today.value else store.loadUsage(date)
        override fun foreground() = _fg.value?.first
        override fun phone() = _phone.value
        override suspend fun screenshot(): String? = withContext(Dispatchers.IO) { Screen.capture(File(dataDir, "shots")) }
        override suspend fun describeImage(path: String, question: String): String {
            val r = api.chat("你是总控的眼睛。看截图，用两三句话说屏幕上在干什么（哪个程序、哪个网站、在看 / 写什么），不猜不编。", listOf(ChatMsg(ChatMsg.USER, question, image = path)), emptyList())
            return when (r) {
                is ApiResult.Ok -> r.value.content.ifBlank { "（看图模型没说话）" }
                is ApiResult.Err -> "看不了图：${r.message}（AI 接口要支持看图，或者在设置里填看图模型）"
            }
        }
        override suspend fun shell(command: String) = withContext(Dispatchers.IO) { Shell.run(command) }
        override suspend fun confirm(command: String, why: String): Boolean = askConfirm(command, why)
        override fun closeApp(exe: String) = Win32.closeApp(exe)
        override fun say(text: String) { if (_config.value.speak) Voice.say(text) }
    }
    val agent = PcAgent(api, env)

    // ---------- 状态读写 ----------

    private fun updateConfigInternal(f: (PcConfig) -> PcConfig): PcConfig = synchronized(this) {
        val next = f(_config.value)
        if (next != _config.value) {
            val autostartChanged = next.rules.autostart != _config.value.rules.autostart
            _config.value = next
            store.saveConfig(next)
            if (autostartChanged) Win32.setAutostart(next.rules.autostart)
        }
        next
    }

    private fun updateStateInternal(f: (PcState) -> PcState): PcState = synchronized(this) {
        val next = f(_state.value)
        if (next != _state.value) {
            _state.value = next
            store.saveState(next)
        }
        next
    }

    fun ctx(now: Long = System.currentTimeMillis()) = Ctx(_config.value.rules, _state.value, _today.value, _phone.value, now, zone)

    // ---------- 运行 ----------

    fun start(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        // 上次没正常退出：电脑一直开着而总控没在运行，就是“失联”
        store.lastUnclean()?.let { (beat, why) ->
            val bootAt = now - Store.uptimeMs()
            if (why.isNotBlank() || bootAt < beat) {
                updateStateInternal { it.copy(gaps = (it.gaps + Gap(beat, now, why.ifBlank { "总控被结束了（任务管理器或强制关闭）" })).takeLast(50)) }
            }
        }
        store.beat(now, clean = false)
        Runtime.getRuntime().addShutdownHook(Thread {
            if (!manualQuit) store.beat(System.currentTimeMillis(), clean = true)
            net.stop()
        })
        if (_config.value.rules.autostart) Win32.setAutostart(true)
        KeyGuard.start()
        if (_config.value.rules.netBlock) net.start()
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { tick() }.onFailure { it.printStackTrace() }
                delay(1000)
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { slowTick() }.onFailure { it.printStackTrace() }
                delay(30_000)
            }
        }
        scope.launch(Dispatchers.IO) {
            delay(3_000)
            while (isActive) {
                runCatching { syncPhone() }.onFailure { _link.value = "同步出错：${it.message}" }
                delay(60_000)
            }
        }
    }

    /** 每秒：看前台、记时间、判断锁不锁 / 拦不拦。 */
    fun tick() {
        val now = System.currentTimeMillis()
        _now.value = now
        val step = (now - lastTick).coerceIn(0, 3000)
        // 两次之间隔太久（睡眠、休眠）：不算时间
        val asleep = now - lastTick > 60_000
        lastTick = now
        val day = Usage.day(now, zone)
        if (day != date) {
            store.saveUsage(_today.value)
            date = day
            _today.value = store.loadUsage(day)
        }
        val due = PcPolicy.applyDue(_config.value, now)
        if (due != _config.value) updateConfigInternal { due }

        val c = ctx(now)
        val machine = Decide.machine(c)
        if (machine is Verdict.Machine) {
            if (_lock.value?.kind != machine.kind || _lock.value?.reason != machine.reason) _lock.value = machine
            KeyGuard.active = true
            _gate.value = null
        } else {
            _lock.value = null
            KeyGuard.active = false
        }

        // 输入法的候选框、状态条在前台时当作“没换程序”：不计时、不拦
        val raw = Win32.foreground()
        val w = if (raw != null && Win32.isImeProcess(raw.fg.exe)) lastWinFg else raw
        lastWinFg = w
        val idle = Win32.idleMs() > IDLE_MS
        if (w == null) {
            if (!Win32.isWindows) _fg.value = null
        } else {
            val t = Classify.of(w.fg, c.rules)
            _fg.value = w.fg to t
            if (machine is Verdict.Ok && !idle && !asleep && step > 0 && w.fg.exe !in Defaults.ALWAYS) {
                _today.value = Usage.add(_today.value, t, w.fg.title, now, step)
                dirty = true
            }
            if (machine is Verdict.Ok) {
                val v = Decide.target(c, t)
                if (v is Verdict.Gate) {
                    Win32.minimize(w.hwnd)
                    if (_gate.value?.target?.key != t.key) {
                        _gate.value = GateView(t, w.fg.title, v.reason, v.used, v.limit, now)
                        updateStateInternal { s -> s.copy(blocks = (s.blocks + BlockHit(now, t.key, t.label, v.reason)).takeLast(300)) }
                        if (_config.value.speak) Voice.say("${t.label}现在关着。")
                    }
                }
            }
        }
        // 拦截页还开着，但那样东西已经放行了（总控批了）：收起
        _gate.value?.let { g -> if (Decide.target(ctx(now), g.target) is Verdict.Ok) _gate.value = null }
        reminders(now)
        if (!idle && machine is Verdict.Ok) maybeCheckIn(now)
    }

    /** 每半分钟：存盘、更新网络拦截、心跳、到点的回访。 */
    private suspend fun slowTick() {
        val now = System.currentTimeMillis()
        if (dirty) { store.saveUsage(_today.value); dirty = false }
        store.beat(now, clean = false)
        if (_config.value.rules.netBlock) {
            net.start()
            net.update(Decide.blockedDomains(ctx(now)))
        } else {
            net.stop()
        }
        // 到点的回访
        Boss.dueChecks(_state.value.dossier, now).firstOrNull()?.let { k ->
            if (_config.value.ai.ready && !_busy.value) {
                updateStateInternal { it.copy(dossier = Boss.markDone(it.dossier, k.id)) }
                proactive("[电脑·回访] 你之前排的回访到了：${k.about}\n先用 usage 取证，再对他说结论或问一个具体问题。一两句。", toast = "回访")
            }
        }
    }

    private fun once(key: String, block: () -> Unit) { if (said.add(key)) block() }

    /** 会话、放行快到点时提醒一句。 */
    private fun reminders(now: Long) {
        _state.value.activeSession(now)?.let { s ->
            if (s.until - now in 1..5 * 60_000L) once("s5:${s.start}") { toast("还有 5 分钟", "「${s.purpose}」快到点了。到点会锁屏对账。") }
        }
        _state.value.activePasses(now).forEach { p ->
            if (p.until - now in 1..60_000L) once("p1:${p.key}:${p.at}") { toast("${p.label}还有 1 分钟", if (p.backTo.isNotBlank()) "说好用完去做：${p.backTo}" else "到点就关。") }
        }
    }

    private fun maybeCheckIn(now: Long) {
        val base = _config.value.rules.checkEvery
        // 手机那边信用掉到审慎 / 严格：电脑问得更勤（战友模式）
        val every = when (_phone.value?.stance) {
            "严格" -> if (base <= 0) 25 else minOf(base, 25)
            "审慎" -> if (base <= 0) 35 else minOf(base, 35)
            else -> base
        }
        if (every <= 0 || _checkIn.value != null) return
        val st = _state.value
        val since = maxOf(st.lastCheckIn, st.activeSession(now)?.start ?: 0L)
        val cont = _today.value.continuousMs(now)
        if (now - since >= every * 60_000L && cont >= every * 60_000L) {
            val recent = Usage.between(_today.value, now - every * 60_000L, now)
            val top = recent.groupBy { it.label }.map { (k, v) -> k to v.sumOf { it.ms } / 60_000 }.sortedByDescending { it.second }.take(3)
            _checkIn.value = CheckInView(every, top.joinToString("、") { "${it.first} ${it.second} 分" }, now)
            updateStateInternal { it.copy(lastCheckIn = now, dossier = Boss.asked(it.dossier, "电脑问候", now)) }
            if (_config.value.speak) Voice.say("这 $every 分钟在做什么？")
        }
    }

    // ---------- 你做的事 ----------

    /** 自己开一次会话（夜里、总时长用完、锁机时不行，要找总控）。 */
    fun startSession(purpose: String, minutes: Int): String? {
        val now = System.currentTimeMillis()
        val m = Decide.machine(ctx(now))
        if (m is Verdict.Machine && m.kind !in setOf(LockKind.NO_SESSION, LockKind.SESSION_END)) return "现在是“${m.kind.title}”，自己开不了，向总控申请。"
        if (purpose.trim().length < 4) return "写具体一点：做什么（至少 4 个字）"
        val max = _config.value.rules.sessionMax
        if (minutes > max) return "自己一次最多开 $max 分钟。要更久，向总控申请。"
        val prev = _state.value.session?.takeIf { it.until <= now }
        updateStateInternal { s ->
            s.copy(session = Session(purpose.trim(), now, now + minutes.coerceAtLeast(5) * 60_000L), dossier = Boss.answered(s.dossier, now))
        }
        if (prev != null) said.removeAll { it.startsWith("s5:") }
        return null
    }

    /** 会话到点时回答“做完了吗”：记成一条陈述，总控之后可以对着记录查。 */
    fun review(done: Boolean, note: String) {
        val now = System.currentTimeMillis()
        val s = _state.value.session ?: return
        val id = "s" + (now / 1000).toString(36).takeLast(5)
        updateStateInternal { st ->
            st.copy(dossier = Boss.addClaim(st.dossier, id, "会话「${s.purpose}」：${if (done) "做完了" else "没做完"}${if (note.isNotBlank()) "——$note" else ""}", s.start, s.until, ClaimVerdict.PENDING, "", now))
        }
    }

    fun endSession() {
        val now = System.currentTimeMillis()
        updateStateInternal { s -> s.activeSession(now)?.let { s.copy(session = it.copy(until = now)) } ?: s }
    }

    /** 紧急解锁：一天一次、十分钟、要写清楚为什么（至少 30 字），记一笔并告诉手机。 */
    fun emergency(reason: String): String? {
        val now = System.currentTimeMillis()
        val st = _state.value
        val today = Usage.day(now, zone)
        if (st.emergencies.any { Usage.day(it, zone) == today }) return "今天已经用过一次紧急解锁了。"
        if (reason.trim().length < 30) return "写清楚为什么急（至少 30 个字，现在 ${reason.trim().length} 个）。"
        val id = "e" + (now / 1000).toString(36).takeLast(5)
        updateStateInternal {
            it.copy(
                emergencyUntil = now + 10 * 60_000L,
                emergencies = (it.emergencies + now).takeLast(30),
                dossier = Boss.addClaim(it.dossier, id, "紧急解锁：${reason.trim()}", now, now + 10 * 60_000L, ClaimVerdict.PENDING, "", now),
                outbox = (it.outbox + Outgoing(id, now, "电脑上用了紧急解锁（10 分钟）：${reason.trim()}")).takeLast(30),
            )
        }
        return null
    }

    fun answerCheckIn(text: String) {
        val now = System.currentTimeMillis()
        val view = _checkIn.value ?: return
        _checkIn.value = null
        updateStateInternal { it.copy(dossier = Boss.answered(it.dossier, now)) }
        if (text.isBlank()) return
        if (_config.value.ai.ready) {
            proactive(
                "[电脑·问候] 你问他这 ${view.minutes} 分钟在做什么（记录：${view.summary.ifBlank { "没有记录" }}），他说：「${text.trim()}」。" +
                    "对照 usage：对得上就简短认可并提醒下一步；对不上就把数字摆出来问一句（必要时 record_claim）。一两句。",
                toast = "总控",
            )
        } else {
            val id = "s" + (now / 1000).toString(36).takeLast(5)
            updateStateInternal { it.copy(dossier = Boss.addClaim(it.dossier, id, "电脑问候：${text.trim()}（记录：${view.summary}）", now - view.minutes * 60_000L, now, ClaimVerdict.PENDING, "", now)) }
        }
    }

    fun dismissCheckIn() { _checkIn.value = null }

    fun dismissGate() {
        _gate.value?.let { g -> lastWinFg?.takeIf { it.fg.exe == g.target.key.removePrefix("app:") }?.let { Win32.minimize(it.hwnd) } }
        _gate.value = null
    }

    fun setRules(next: PcRules): String = run {
        var msg = ""
        updateConfigInternal { c -> PcPolicy.setRules(c, next, System.currentTimeMillis()).also { msg = it.message }.config }
        msg
    }

    fun setAi(ai: AiConfig): String = run {
        var msg = ""
        updateConfigInternal { c -> PcPolicy.setAi(c, ai, System.currentTimeMillis()).also { msg = it.message }.config }
        msg
    }

    fun setSpeak(on: Boolean) = updateConfigInternal { it.copy(speak = on) }
    fun welcomed() = updateConfigInternal { it.copy(welcomed = true) }

    /** 粘贴手机上的配对码。 */
    fun pair(code: String): String {
        val p = DeviceLink.parsePairing(code) ?: return "配对码不对：在手机上 我 → 电脑 里复制完整的一段（以 ZKPC1: 开头）"
        updateConfigInternal { it.copy(link = LinkConfig(p.token, p.baseUrl, p.devicesDb)) }
        var msg = "连上了。"
        val ai = p.ai
        if (ai != null && ai.ready && !_config.value.ai.ready) { setAi(ai); msg += "AI 接口也用手机那份。" }
        _link.value = "配对好了，马上同步"
        return msg
    }

    // ---------- 和手机 ----------

    private var pcPage: String? = null
    private var lastWrite = 0L

    suspend fun syncPhone() {
        val link = _config.value.link
        if (!link.ready) { _link.value = "没连手机"; return }
        val api = NotionClient({ link.token }, baseUrl = { link.baseUrl.ifBlank { NotionClient.DEFAULT_BASE } })
        val now = System.currentTimeMillis()
        when (val r = DeviceLink.readAll(api, link.devicesDb)) {
            is ApiResult.Err -> { _link.value = "读不到手机：${r.message.take(60)}"; return }
            is ApiResult.Ok -> {
                pcPage = r.value[DeviceLink.PC]?.pageId ?: pcPage
                r.value[DeviceLink.PHONE]?.data?.let(DeviceLink::decodePhone)?.let { p ->
                    _phone.value = p
                    store.savePhone(p)
                    val (s, c, effects) = PcSync.apply(PcSync.acked(_state.value, p), _config.value, p, now)
                    updateConfigInternal { c }
                    updateStateInternal { s }
                    effects.forEach { e ->
                        when (e) {
                            is PcSync.Effect.Say -> { toast("手机上的总控", e.text); env.say(e.text) }
                            is PcSync.Effect.Locked -> toast("手机锁了电脑", "${e.minutes} 分钟：${e.reason}")
                            PcSync.Effect.Unlocked -> toast("手机解锁了电脑", "")
                            is PcSync.Effect.Blocked -> toast("手机拦了 ${e.name}", "今天剩下的时间都打不开。")
                        }
                    }
                }
            }
        }
        if (now - lastWrite >= 110_000 || _state.value.outbox.isNotEmpty()) {
            val side = PcSync.pcSide(_state.value, _today.value, _config.value.rules, now, zone, VERSION)
            when (val w = DeviceLink.write(api, link.devicesDb, DeviceLink.PC, DeviceLink.encodePc(side), pcPage)) {
                is ApiResult.Ok -> { pcPage = w.value; lastWrite = now; _link.value = "已同步 ${Usage.hm(now, zone)}" }
                is ApiResult.Err -> _link.value = "写不进 Notion：${w.message.take(60)}"
            }
        } else {
            _link.value = "已同步 ${Usage.hm(now, zone)}"
        }
    }

    // ---------- 和总控说话 ----------

    fun send(text: String, image: String = "") {
        if (text.isBlank() || _busy.value) return
        updateStateInternal { it.copy(dossier = Boss.answered(it.dossier, System.currentTimeMillis())) }
        talk(text, hidden = false, toast = null, image = image)
    }

    fun proactive(instruction: String, toast: String?) = talk(instruction, hidden = true, toast = toast)

    private fun talk(text: String, hidden: Boolean, toast: String?, image: String = "") {
        _busy.value = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                agent.reply(_chat.value, text, { m -> _chat.value = (_chat.value + m).takeLast(300) }, hidden = hidden, image = image)
            } catch (e: Exception) {
                _chat.value = _chat.value + ChatMsg(ChatMsg.ASSISTANT, "出错了：${e.message}", at = System.currentTimeMillis(), error = true)
            } finally {
                _busy.value = false
                store.saveChat(_chat.value)
                val last = _chat.value.lastOrNull { it.role == ChatMsg.ASSISTANT && it.content.isNotBlank() && !it.error }?.content
                if (toast != null && last != null) {
                    val body = Agent.options(last).first
                    this@Controller.toast(toast, body)
                    if (_config.value.speak) Voice.say(body)
                }
            }
        }
    }

    /** 截图测试用：放进一天的记录、手机那边的状态、一段对话。 */
    internal fun seed(today: UsageDay? = null, phone: PhoneSide? = null, chat: List<ChatMsg>? = null, checkIn: CheckInView? = null, confirm: Confirm? = null) {
        today?.let { _today.value = it }
        phone?.let { _phone.value = it }
        chat?.let { _chat.value = it }
        checkIn?.let { _checkIn.value = it }
        confirm?.let { _confirm.value = it }
    }

    fun clearChat() { _chat.value = emptyList(); store.saveChat(emptyList()) }

    private suspend fun askConfirm(command: String, why: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingConfirm?.complete(false)
        pendingConfirm = d
        _confirm.value = Confirm("c${System.currentTimeMillis()}", command, why, System.currentTimeMillis())
        _showMain.tryEmit("chat")
        val r = withTimeoutOrNull(180_000) { d.await() } ?: false
        _confirm.value = null
        return r
    }

    fun answerConfirm(yes: Boolean) {
        pendingConfirm?.complete(yes)
        pendingConfirm = null
        _confirm.value = null
    }

    fun toast(title: String, text: String) { _toasts.tryEmit(title to text) }
    fun openMain(tab: String = "") { _showMain.tryEmit(tab) }

    /** 退出：要写原因。手机会知道，下次打开时这段时间记成“失联”。 */
    fun quit(reason: String): String? {
        if (reason.trim().length < 10) return "写清楚为什么退出（至少 10 个字）。"
        val now = System.currentTimeMillis()
        manualQuit = true
        updateStateInternal { it.copy(outbox = (it.outbox + Outgoing("q$now", now, "电脑上的总控被手动退出：${reason.trim()}")).takeLast(30)) }
        store.saveUsage(_today.value)
        store.beat(now, clean = false, why = "手动退出：${reason.trim()}")
        runCatching { kotlinx.coroutines.runBlocking { withTimeoutOrNull(5000) { syncPhone() } } }
        net.stop()
        return null
    }

    companion object {
        const val VERSION = "电脑版 0.1.2"
        /** 多久没碰键盘鼠标算不在用。 */
        const val IDLE_MS = 5 * 60_000L
    }
}
