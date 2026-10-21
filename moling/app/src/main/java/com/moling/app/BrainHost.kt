package com.moling.app

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.moling.app.data.LlmClient
import com.moling.app.data.MolingAsk
import com.moling.app.data.SettingsStore
import com.moling.core.Brain
import com.moling.core.BrainFacts
import com.moling.core.BrainState
import com.moling.core.ChatResult
import com.moling.core.Notice
import com.moling.core.Outcome
import com.moling.core.Proposal
import com.moling.core.ProposalState
import com.moling.app.live.Appear
import com.moling.app.live.GateActivity
import com.moling.app.mirror.GptImport
import com.moling.app.mirror.NotionMirror
import com.moling.core.mind.Ask
import com.moling.core.mirror.Absorbed
import com.moling.core.mirror.Question
import com.moling.app.live.LiveScreens
import com.moling.app.live.PetOverlay
import com.moling.core.LiveTurn
import com.moling.core.flow.Cause
import com.moling.core.live.GateRules
import com.moling.core.live.LiveConfig
import com.moling.core.live.Mood
import com.moling.core.live.Activity as LiveActivity
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.guard.GuardSample
import com.moling.core.link.ZkStatus
import com.moling.core.mind.Actor
import com.moling.core.mind.Baton
import com.moling.core.mind.Card
import com.moling.core.mind.DirectorConfig
import com.moling.core.mind.FileMindStore
import com.moling.core.spar.MindAct
import com.moling.core.workspace.ArtifactAction
import com.moling.core.workspace.ArtifactActionKind
import com.moling.core.workspace.Artifacts
import com.moling.core.workspace.Focus
import com.moling.core.workspace.JobKind
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.Workbench
import com.moling.core.workspace.WorkbenchState
import com.moling.core.workspace.WorkspaceArtifact
import com.moling.core.mind.MindState
import com.moling.core.mind.Opening
import com.moling.core.mind.Reply
import com.moling.core.mind.Sense
import com.moling.core.autopilot.AutopilotSignal
import com.moling.core.power.Charter
import com.moling.core.power.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/*
 * 墨灵的大脑宿主（M4）：App 访问核心逻辑的唯一入口。
 *
 * 这里只持有一个 [Brain]（导演、水道、预言、体系图、权柄、总控都串在里面），
 * 界面通过 [state] / [notices] 看状态，通过下面几个 suspend 方法做事。
 * 文件路径和第一批一样（mind/moling.json、mind/director.json），旧的对话记录直接读回来。
 *
 * 规矩：brain.* 自带锁，这里统一放 IO 线程；失败不抛给界面，记日志 + 合理兜底。
 */
object BrainHost {
    private const val TAG = "BrainHost"

    @Volatile
    private var ready = false

    private lateinit var brain: Brain
    private lateinit var bench: Workbench

    private val _bench = MutableStateFlow(WorkbenchState())

    /** 案板：焦点、稿子、撤销栈、作业（界面读这个；每次改动都会推新值）。 */
    val workbench: StateFlow<WorkbenchState> = _bench
    private lateinit var settings: SettingsStore

    /** 持有 application context：读前台应用、系统状态、起服务都要用。 */
    private var appContext: Context? = null

    private val _state = MutableStateFlow(BrainState())

    /** 界面读这个：水道、对话、提议、预言、日志、最后一次总控状态。 */
    val state: StateFlow<BrainState> = _state

    @Volatile
    private var lastHeartbeat = 0L

    /** 早晚自动的重入闸（心跳可能叠在一起）。 */
    private val autoGate = AtomicBoolean(false)

    @Volatile
    private var lastAutoMinute = -1L

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())

    /** tick 返回的提醒，最近 3 条，新的在前。 */
    val notices: StateFlow<List<Notice>> = _notices

    /** 设置页 / 对话页用。 */
    val settingsStore: SettingsStore get() = settings

    /** App 启动时调一次（MolingApp.onCreate）。重复调直接返回。 */
    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val app = context.applicationContext
            appContext = app
            val dir = File(app.filesDir, "mind").apply { mkdirs() }

            ZkBridge.init(app)
            settings = SettingsStore(app)
            val ask = MolingAsk(settings, LlmClient({ settings.load() }))
            // 深想模型：没填就和平时同一个（大脑里 deep = null 时退回 ask）
            val deep = MolingAsk(settings, LlmClient({ settings.load().deep() }, timeoutSec = 120, withTemperature = false), timeoutMs = 125_000L)
            brain = Brain(
                phone = PhoneFacts(
                    foreground = { appContext?.let { Usage.foreground(it) } },
                    usageAllowed = { appContext?.let { Usage.allowed(it) } ?: false },
                    status = { _state.value.lastStatus },
                ),
                ask = ask,
                store = FileMindStore(File(dir, "moling.json")),
                mindStore = FileMindStore(File(dir, "director.json")),
                zk = ZkBridge,
                // 每次现读设置：设置页改完人格 / 称呼，下一句话就生效。
                config = { settings.load().let { DirectorConfig(persona = it.persona, callUser = it.callUser) } },
                // 没填深想模型时直接返回 null，大脑就用平时的模型
                deep = Ask { system, user -> if (settings.load().hasDeep()) deep.ask(system, user) else null },
            )
            _state.value = brain.state()
            // 案板（v13 第九批）：自己的文件、自己的锁；只有他点了才调模型
            bench = Workbench(
                store = FileMindStore(File(dir, "workbench.json")),
                ask = ask,
                deep = Ask { system, user -> if (settings.load().hasDeep()) deep.ask(system, user) else null },
                persona = { settings.load().persona },
                about = { runCatching { brain.state().about }.getOrDefault("") },
                atlas = { q -> runCatching { Workbench.atlasLines(brain.state().atlas, q) }.getOrDefault(emptyList()) },
                onChange = { _bench.value = it },
            )
            _bench.value = bench.state()
            ready = true
            Log.i(TAG, "大脑宿主起来了")
        }
    }

    // ---------------------------------------------------------------- 读（不调模型、不碰文件）

    /** 最后一次读到的总控状态；null = 连不上（或总控没装）。 */
    fun lastStatus(): ZkStatus? = runCatching { brain.state().lastStatus }.getOrNull()

    /** 当前权柄等级（用户上限和挣到的取小）。 */
    fun tier(): Tier = runCatching { brain.tier() }.getOrDefault(Tier.PLANNER)

    /** 等用户决定的提议。 */
    fun pending(now: Long): List<Proposal> = runCatching { brain.pending(now) }.getOrDefault(emptyList())

    /** 调试页用：导演的记忆快照（接力棒、议程、今天打扰次数、模型花费）。 */
    fun directorSnapshot(): MindState =
        if (ready) runCatching { brain.director.snapshot() }.getOrDefault(MindState()) else MindState()

    /** 上次心跳跑到的时间（后台服务或前台循环）；0 = 还没跑过。 */
    fun lastHeartbeatAt(): Long = lastHeartbeat

    /** 划掉一条提醒。 */
    fun dismissNotice(at: Long) {
        if (_notices.value.any { it.at == at && it.kind == "drift" }) answeredLater()
        _notices.value = _notices.value.filterNot { it.at == at }
    }

    // ---------------------------------------------------------------- 对话

    /** [mode] = 他在模式条上选的（null = 让她按局面判断，或沿用他一小时内选过的）。 */
    suspend fun chat(text: String, now: Long, mode: com.moling.core.talk.TalkMode? = null): ChatResult =
        io("chat", { ChatResult("刚才没接上，再说一次", emptyList(), false) }) { brain.chat(text, now, mode) }

    /** “反驳我”：挑他上一句判断的漏洞。 */
    suspend fun devil(now: Long): ChatResult =
        io("devil", { ChatResult("刚才没接上，再试一次", emptyList(), false) }) { brain.devil(now) }

    /** “疯想—冷判”：给 [topic] 五条离谱路线，挑一个小实验（提议）。 */
    suspend fun wild(topic: String, now: Long): ChatResult =
        io("wild", { ChatResult("刚才没接上，再试一次", emptyList(), false) }) { brain.wild(topic, now) }

    /** 四个参谋（大判断前）。 */
    suspend fun council(question: String): com.moling.core.review.Council? =
        io<com.moling.core.review.Council?>("council", { null }) { brain.council(question, System.currentTimeMillis()) }

    /** 他自己改“对你的了解”。 */
    suspend fun editAbout(text: String) {
        io("editAbout", {}) { brain.editAbout(text, System.currentTimeMillis()) }
    }

    /** 现在出一份周报。 */
    suspend fun week(): com.moling.core.review.WeekReport? =
        io<com.moling.core.review.WeekReport?>("week", { null }) { brain.week(System.currentTimeMillis()) }

    suspend fun cancelWake(id: String) {
        io("cancelWake", {}) { brain.cancelWake(id) }
    }

    /** 他回应了这次偏离（划掉提醒、在小窗里选了什么）：这次偏离不再升级。不等结果。 */
    fun answeredLater() {
        answerScope.launch { runCatching { io("answered", {}) { brain.answered() } } }
    }

    private val answerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** 用户点了提议上的“好”。 */
    suspend fun accept(id: String, now: Long): Outcome =
        io("accept", { Outcome(false, "刚才没接上，等会儿再试") }) { brain.accept(id, now) }

    /** 用户点了“不用”。 */
    suspend fun decline(id: String, now: Long) {
        io("decline", {}) { brain.decline(id, now) }
    }

    /** 没送到总控的提议重发。 */
    suspend fun retry(id: String, now: Long): Outcome =
        io("retry", { Outcome(false, "刚才没接上，等会儿再试") }) { brain.retry(id, now) }

    // ---------------------------------------------------------------- 今天

    suspend fun planDay(now: Long, force: Boolean = false): FlowPlan =
        io("planDay", { _state.value.flow ?: fallbackPlan(now) }) { brain.planDay(now, force) }

    /** 定稿今天的水道，返回“交给总控”的提议（用户点“好”才交）。 */
    suspend fun confirmFlow(now: Long): Proposal? =
        io<Proposal?>("confirmFlow", { null }) { brain.confirmFlow(now) }

    /** 今天页的改道按钮（用户自己点的，直接做）。返回一句说明，空串 = 现在没有在流的水道。 */
    suspend fun reroute(cause: Cause, now: Long, note: String = ""): String =
        io("reroute", { "" }) { brain.reroute(cause, now, note) }

    /** App 在前台时每 60 秒一次（打开 App 也调一次）。 */
    suspend fun tickNow(now: Long = System.currentTimeMillis()): List<Notice> {
        lastHeartbeat = now
        val (category, guard) = tickInputs(now)
        val out = io("tick", { emptyList<Notice>() }) { brain.tick(now, category, guard) }
        _notices.value = (out + _notices.value).distinctBy { it.at to it.text }.take(3)
        if (out.isNotEmpty()) runCatching { postNotices(now, out) }
        runCatching { withContext(Dispatchers.IO) { autoSchedule(now) } }
        runCatching { liveTick(now) }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "活的墨灵失败", it) }
        if (interactive()) runCatching { refreshUsage() }
        runCatching { relay(now, force = false) }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "联络站同步失败", it) }
        runCatching { mirror(now, force = false) }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Notion 同步失败", it) }
        return out
    }

    // ---------------------------------------------------------------- 贯通（v6）：Notion 黑板 / 收件箱、ChatGPT 记录

    /** 和 Notion 同步一次（心跳里每 15 分钟；贯通页点“现在同步”时 [force]）。返回给人看的一句；没设置返回 null。 */
    suspend fun mirror(now: Long = System.currentTimeMillis(), force: Boolean = true): String? {
        if (!ready) return null
        val r = withContext(Dispatchers.IO) {
            NotionMirror.sync(
                settings, now, force,
                board = { brain.board(now) },
                seen = { brain.state().mirrorSeen },
                absorb = { items -> io("absorb", { Absorbed() }) { brain.absorb(items, now) } },
            )
        } ?: return null
        refreshState()
        tellAbsorbed(r.absorbed)
        return r.status
    }

    /** 和联络站同步一次（心跳里每 10 分钟；贯通页点“同步”时 [force]）。没设置返回 null。 */
    suspend fun relay(now: Long = System.currentTimeMillis(), force: Boolean = true): String? {
        if (!ready) return null
        val r = withContext(Dispatchers.IO) {
            com.moling.app.mirror.RelaySync.sync(
                settings, now, force,
                board = { brain.handoff(now) to brain.state().questions },
                seen = { brain.state().mirrorSeen },
                absorb = { items -> io("absorb", { Absorbed() }) { brain.absorb(items, now) } },
            )
        } ?: return null
        refreshState()
        tellAbsorbed(r.absorbed)
        return r.status
    }

    /** 收件箱里有回答 / 要告诉他的事：发一条通知。 */
    private fun tellAbsorbed(results: List<Absorbed>) {
        val ctx = appContext ?: return
        val qs = _state.value.questions
        val lines = results.flatMap { r -> r.answered.mapNotNull { id -> qs.firstOrNull { it.id == id }?.let { "$id：${it.answer.take(60)}" } } + r.notes }
        if (lines.isNotEmpty()) runCatching { Notify.card(ctx, "GPT 那边：" + lines.joinToString("；").take(300)) }
    }

    /** 交接包：发给任何 AI 应用的那段话。 */
    fun handoff(): String = if (ready) runCatching { brain.handoff(System.currentTimeMillis()) }.getOrDefault("") else ""

    /** 从分享菜单进来的 GPT 回答：和收件箱一样交给她把关。返回给人看的一句。 */
    suspend fun absorbShared(text: String): String {
        val now = System.currentTimeMillis()
        val r = io("absorbShared", { Absorbed() }) {
            brain.absorb(listOf(com.moling.core.mirror.InboxItem("share:$now", text.take(6000), "分享")), now)
        }
        tellAbsorbed(listOf(r))
        return when {
            r.answered.isNotEmpty() -> "收到了：回答了 ${r.answered.joinToString("、")}，体系图 +${r.added}"
            r.added > 0 -> "收进体系图 ${r.added} 条"
            else -> "没收到什么（模型没接上，或没有可收的）"
        }
    }

    /** 现在就让她想一次（今天页“让她想想”）。 */
    suspend fun thinkNow(): com.moling.core.think.ThinkResult? {
        val now = System.currentTimeMillis()
        val usage = runCatching { usageDigest(now) }.getOrDefault("")
        return io<com.moling.core.think.ThinkResult?>("think", { null }) { brain.think(now, usage) }
    }

    /** 她把想好的那句说出来。 */
    suspend fun sayNow(): String? = io<String?>("sayNow", { null }) { brain.sayNow(System.currentTimeMillis()) }

    suspend fun dropSay() {
        io("dropSay", {}) { brain.dropSay(System.currentTimeMillis()) }
    }

    /** 他说她的一个判断对 / 不对。 */
    suspend fun judgeThought(id: String, right: Boolean) {
        io("judgeThought", {}) { brain.judgeThought(id, right, System.currentTimeMillis()) }
    }

    /** 今天的手机使用（只有应用和时间）记进大脑，返回给她思考用的一段话；没权限给空。 */
    private suspend fun usageDigest(now: Long): String {
        val ctx = appContext ?: return ""
        if (!Usage.allowed(ctx)) return ""
        val status = _state.value.lastStatus
        val start = Flow.dayStart(Brain.calendarDay(now))
        val sessions = withContext(Dispatchers.IO) {
            runCatching { Usage.sessions(ctx, start, now) { BrainFacts.category(it, status) } }.getOrDefault(emptyList())
        }
        return io("usage", { "" }) { brain.noteUsage(sessions, now) }
    }

    @Volatile
    private var lastUsageAt = 0L

    /** 今天页打开时、心跳里每 15 分钟：把今天的使用更新一下（本地算，不调模型）。 */
    suspend fun refreshUsage(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!ready || (!force && now - lastUsageAt < 15 * 60_000L)) return
        lastUsageAt = now
        runCatching { usageDigest(now) }
    }

    /** 他想让她问 GPT 的。 */
    suspend fun askGpt(text: String): Question? =
        io<Question?>("askGpt", { null }) { brain.askGpt(text, System.currentTimeMillis()) }

    suspend fun dropQuestion(id: String) {
        io("dropQuestion", {}) { brain.dropQuestion(id) }
    }

    /** 让她现在想想要问 GPT 什么。 */
    suspend fun prepareQuestions(): List<Question> =
        io("prepareQuestions", { emptyList<Question>() }) { brain.prepareQuestions(System.currentTimeMillis()) }

    /**
     * 导入 ChatGPT 的导出记录：只收上次导入之后更新过的、最新的 20 段，交给她把关（深想模型）。
     * 返回给人看的一句。
     */
    suspend fun importGpt(uri: android.net.Uri): String {
        val ctx = appContext ?: return "还没准备好"
        val since = settings.gptImportedUntil()
        val list = withContext(Dispatchers.IO) { runCatching { GptImport.read(ctx, uri, since) } }
            .getOrElse { return "读不了这个文件：${it.message ?: it.javaClass.simpleName}" }
        if (list.isEmpty()) return "没有新的对话（上次导入之后没更新过）"
        val items = GptImport.items(list)
        var added = 0
        var done = 0
        var answered = 0
        for (part in items.chunked(3)) {
            val r = io("importGpt", { Absorbed() }) { brain.absorb(part, System.currentTimeMillis()) }
            added += r.added
            answered += r.answered.size
            if (r.added > 0 || r.answered.isNotEmpty() || r.aboutProposal || r.notes.isNotEmpty() ||
                part.all { it.id in brain.state().mirrorSeen }
            ) done += part.size
        }
        // 全部收完才往前挪“导入到哪了”；模型中途不通，下次还能接着收
        if (done == items.size) settings.setGptImportedUntil(list.maxOf { it.updated })
        return "读了 ${items.size} 段对话，收了 $done 段：体系图 +$added" + (if (answered > 0) "，顺带回答了 $answered 个问题" else "") +
            if (done < items.size) "（有的没收成，模型可能没接上，再导一次会接着收）" else ""
    }

    // ---------------------------------------------------------------- 活的墨灵（v5）

    /** 活的墨灵这一跳的重入闸（她在想要不要出声时可能要等模型几秒）。 */
    private val liveBusy = AtomicBoolean(false)

    /** 设置里关了“她可以拦住你”，今天就一次也不拦（只说话）。 */
    fun liveConfig(): LiveConfig =
        if (ready) LiveConfig.of(settings.eagerness(), settings.gateOn()) else LiveConfig(gatesPerDay = 0)

    /** 上次熄屏的时间（后台服务收到熄屏广播时记）。 */
    @Volatile
    private var screenOffAt = 0L

    fun onScreenOff(now: Long) {
        screenOffAt = now
    }

    /** 解锁了：放下手机够久的话，她可以来打个招呼（还是她自己决定说不说）。 */
    suspend fun onUnlock(now: Long) {
        val gap = if (screenOffAt > 0) ((now - screenOffAt) / 60_000L).toInt() else 0
        // 等一两秒，让他先进到想用的应用里，再看前台
        kotlinx.coroutines.delay(2_500)
        runCatching { liveTick(System.currentTimeMillis(), gap) }
    }

    /** 设置里开了“她说话出声音”。 */
    fun speakOn(): Boolean = ready && runCatching { settings.load().speak }.getOrDefault(false)

    /**
     * 每一跳看一眼他在干什么，值得的时候把她叫醒（她自己决定说不说、拦不拦）。
     *  - 正拦着：到 5 分钟自己放；他溜回那个娱乐应用、拦人的界面又不在前面，就再弹出来；
     *  - 没拦着：大脑判断有没有值得叫醒她的事（该做事时在刷、夜里躺着刷、刷了很久）。
     * 只在亮屏时做；设置里关了“她会主动找你”就什么都不做。
     */
    private suspend fun liveTick(now: Long, unlockGapMin: Int = 0) {
        val ctx = appContext ?: return
        if (!ready || !settings.liveOn() || !interactive()) {
            // 关掉“她会主动找你”或屏幕灭了：褪色立刻恢复。
            runCatching { PetOverlay.fade(ctx, 0f) }
            return
        }
        if (!liveBusy.compareAndSet(false, true)) return
        try {
            val cfg = liveConfig()
            val status = _state.value.lastStatus
            val fg = if (Usage.allowed(ctx)) runCatching { Usage.foreground(ctx, now, Usage.LONG_WINDOW_MS) }.getOrNull() else null
            val activity = fg?.let { LiveActivity(it.pkg, it.label, BrainFacts.category(it.pkg, status), it.minutes) }
            val gate = brain.state().gate
            if (gate != null) {
                if (GateRules.expired(gate, now, cfg)) {
                    io("gateTimeout", {}) { brain.gateEscape("超时", now, cfg) }
                } else if (activity?.pkg == gate.pkg && !GateActivity.visible) {
                    val last = _state.value.talk.lastOrNull { it.who == "墨灵" }?.text.orEmpty()
                    withContext(Dispatchers.Main) { LiveScreens.gate(ctx, Mood.POUT, last.ifBlank { "还没回我呢。" }, gate.label) }
                }
                // 拦人页在前面：褪色先恢复，别让他看不清。
                runCatching { PetOverlay.fade(ctx, 0f) }
                return
            }
            if (MainActivity.visible) {
                runCatching { PetOverlay.fade(ctx, 0f) }
                return
            }
            // 自动驾驶：看最近 30 分钟的前台记录（只有应用和时间）
            val sessions = if (Usage.allowed(ctx)) runCatching {
                Usage.sessions(ctx, now - 60 * 60_000L, now) { BrainFacts.category(it, status) }
            }.getOrNull().orEmpty() else emptyList()
            val autopilot = if (Usage.allowed(ctx)) com.moling.core.autopilot.Autopilot.read(sessions.filter { it.end > now - 30 * 60_000L }, now, _state.value.flow) else null
            val leftPlay = leftPlayMinutes(sessions, activity?.pkg.orEmpty(), now)
            // Z6 褪色：只在自动驾驶、还停在娱乐里时慢慢变灰（默认关、每天最多 20 分钟）。
            fadeTick(ctx, autopilot, activity)
            val trigger = brain.liveTrigger(activity, now, unlockGapMin, cfg, autopilot, leftPlay) ?: return
            val cues = if (trigger == com.moling.core.live.Trigger.AUTOPILOT) autopilot?.reasons.orEmpty() else emptyList()
            val turn = io<LiveTurn?>("live", { null }) { brain.live(trigger, activity, now, cfg = cfg, can = Appear.can(ctx), cues = cues) } ?: return
            withContext(Dispatchers.Main) {
                LiveScreens.nextChoices = turn.choices
                Appear.show(ctx, turn, activity?.label.orEmpty())
            }
        } finally {
            liveBusy.set(false)
        }
    }

    /**
     * Z6 褪色（默认关）：他已经在自动驾驶、而且还停在娱乐应用里时，让画面 10 分钟里慢慢变灰。
     * 完全由他自己在设置里打开；每天最多 20 分钟（分钟数由 PetOverlay 记）；离开娱乐 / 拦人 / 开 App 就恢复。
     */
    private fun fadeTick(ctx: Context, autopilot: AutopilotSignal?, activity: LiveActivity?) {
        val want = settings.liveOn() && settings.fadeOn() && autopilot?.on == true && activity != null &&
            (activity.category == "ENTERTAINMENT" || activity.category == "SHOPPING") &&
            activity.pkg !in Charter.neverTouch &&
            PetOverlay.fadeMinutesToday(ctx) < 20
        runCatching { PetOverlay.fade(ctx, if (want) 1f else 0f) }
    }

    /**
     * 他刚离开的那一段娱乐有多长：最后一段娱乐在 3 分钟内结束、现在前台已经不是它，
     * 往前把同一个应用、中间断开不到 1 分钟的几段连起来算。否则 0。
     */
    private fun leftPlayMinutes(sessions: List<com.moling.core.usage.Session>, fgPkg: String, now: Long): Int {
        val play = sessions.filter { it.category == "ENTERTAINMENT" || it.category == "SHOPPING" }.sortedBy { it.start }
        val last = play.lastOrNull() ?: return 0
        if (now - last.end > 3 * 60_000L || last.pkg == fgPkg) return 0
        var start = last.start
        for (s in play.dropLast(1).asReversed()) {
            if (s.pkg != last.pkg || start - s.end > 60_000L) break
            start = s.start
        }
        return ((last.end - start) / 60_000L).toInt()
    }

    // ---------------------------------------------------------------- 平时也让脑子转起来（v12）

    /** 他点“来一局”：开一局三手棋。 */
    suspend fun spar(): LiveTurn? = io<LiveTurn?>("spar", { null }) { brain.spar(System.currentTimeMillis()) }

    /** 简报：收下 / 不要。返回收进体系图几条。 */
    suspend fun keepBrief(id: String, keep: Boolean): Int = io("keepBrief", { 0 }) { brain.keepBrief(id, keep, System.currentTimeMillis()) }

    /** 规律：对（生效）/ 不对。 */
    suspend fun setRule(id: String, keep: Boolean) {
        io("setRule", {}) { brain.setRule(id, keep, System.currentTimeMillis()) }
    }

    // ---------------------------------------------------------------- 案板（v13 第九批）

    /** 开一件新事：问题 + 他贴的材料（之前正在做的挂起）。 */
    suspend fun benchStart(question: String, material: String): Focus? =
        io<Focus?>("benchStart", { null }) { bench.start(question, material, System.currentTimeMillis()) }

    /** 从体系图选一个主题当材料开一件事；主题里没论断返回 null。 */
    suspend fun benchFromAtlas(topicId: String, question: String = ""): Focus? = io<Focus?>("benchFromAtlas", { null }) {
        val (name, text) = Workbench.topicMaterial(brain.state().atlas, topicId) ?: return@io null
        bench.start(question.ifBlank { name }, text, System.currentTimeMillis(), kind = "atlas", title = name)
    }

    suspend fun benchAddMaterial(focusId: String, text: String): PortResult<Focus> =
        io("benchAddMaterial", { offline() }) { bench.addMaterial(focusId, text, System.currentTimeMillis()) }

    suspend fun benchSwitch(focusId: String): PortResult<Focus> =
        io("benchSwitch", { offline() }) { bench.switchTo(focusId, System.currentTimeMillis()) }

    suspend fun benchArchive(focusId: String): PortResult<Focus> =
        io("benchArchive", { offline() }) { bench.archive(focusId, System.currentTimeMillis()) }

    /** “替我先做一块”：OUTLINE 纲要 / DRAFT 初稿（[artifactId] = 哪份纲要，null = 最新的）/ COUNCIL 四参谋（[artifactId] 必填）。 */
    suspend fun benchPrework(focusId: String, kind: JobKind, artifactId: String? = null): PortResult<WorkspaceArtifact> =
        io("benchPrework", { offline() }) { bench.prework(focusId, kind, System.currentTimeMillis(), artifactId) }

    /**
     * 稿子上的一下。界面用 [Artifacts.actionId] 生成 id（同一版本上同一个按钮 → 同一个 id，连点只算一次）。
     * 挑刺 / 选路线 / 采用记进思维转速。
     */
    suspend fun benchApply(
        artifact: WorkspaceArtifact,
        kind: ArtifactActionKind,
        blockId: String? = null,
        optionId: String? = null,
        text: String? = null,
    ): PortResult<WorkspaceArtifact> = io("benchApply", { offline() }) {
        val now = System.currentTimeMillis()
        val a = ArtifactAction(Artifacts.actionId(artifact, kind, blockId, optionId, text), artifact.id, artifact.revision, kind, blockId, optionId, text, now)
        val r = bench.apply(a)
        if (r is PortResult.Ok) when (kind) {
            ArtifactActionKind.REQUEST_CRITIC -> brain.markMind(MindAct.QUIZ, now)
            ArtifactActionKind.CHOOSE_BRANCH, ArtifactActionKind.ACCEPT_BLOCK, ArtifactActionKind.ACCEPT_ARTIFACT -> brain.markMind(MindAct.DECIDE, now)
            ArtifactActionKind.EDIT_BLOCK -> brain.markMind(MindAct.SAY, now)
            else -> {}
        }
        r
    }

    suspend fun benchCancel(jobId: String): Boolean = io("benchCancel", { false }) { bench.cancel(jobId, System.currentTimeMillis()) }

    /** 导出 Markdown（复制 / 分享用）。 */
    fun benchMarkdown(artifactId: String): String = runCatching { bench.markdown(artifactId) }.getOrDefault("")

    /** 能收进体系图的论断（先给他看，他点“收进”再调 [benchKeep]）。 */
    fun benchClaims(artifactId: String): List<String> = runCatching { bench.claims(artifactId) }.getOrDefault(emptyList())

    /** 他确认后：把采用过的论断织进体系图（[topicId] null = 当前主题）。返回收进几条。 */
    suspend fun benchKeep(artifactId: String, topicId: String? = null): Int =
        io("benchKeep", { 0 }) { brain.keepClaims(bench.claims(artifactId), topicId, System.currentTimeMillis()) }

    private fun <T> offline(): PortResult<T> = PortResult.Failed(PortError(PortError.OFFLINE, "刚才没接上，再试一次"))

    /** 他对她这次出现的反应（反免疫账本）。不等结果。 */
    fun react(modality: com.moling.core.live.Modality, reaction: com.moling.core.live.Reaction, ms: Long) {
        answerScope.launch { runCatching { io("react", {}) { brain.react(modality, reaction, ms, System.currentTimeMillis()) } } }
    }

    /** 出一道捉虫题。 */
    suspend fun quiz(): com.moling.core.live.Quiz? = io<com.moling.core.live.Quiz?>("quiz", { null }) { brain.quiz(System.currentTimeMillis()) }

    suspend fun quizResult(right: Boolean) {
        io("quizResult", {}) { brain.quizResult(right, System.currentTimeMillis()) }
    }

    /** 他点了她给的一个选项（破雾、盲盒、赌……）。 */
    suspend fun tap(choice: String): LiveTurn {
        // 同一个选项 4 秒内只算一次；上一下还没回来时再点也不算（防连点、防两个界面同时报）
        val now = System.currentTimeMillis()
        lastTap?.let { (c, at, turn) -> if (c == choice && now - at < 4_000 && turn != null) return turn }
        if (!tapBusy.compareAndSet(false, true)) {
            // 上一下还在路上：等它回来，用它的结果
            repeat(400) {
                kotlinx.coroutines.delay(100)
                if (!tapBusy.get()) lastTap?.third?.let { return it }
            }
            return LiveTurn(Mood.CALM, "刚才没接上，等会儿再点一下", false, false, emptyList())
        }
        try {
            lastTap = Triple(choice, now, null)
            val t = io("tap", { LiveTurn(Mood.CALM, "刚才没接上，等会儿再点一下", false, false, emptyList()) }) {
                brain.tap(choice, now, liveConfig())
            }
            lastTap = Triple(choice, now, t)
            return t
        } finally {
            tapBusy.set(false)
        }
    }

    private val tapBusy = AtomicBoolean(false)

    @Volatile
    private var lastTap: Triple<String, Long, LiveTurn?>? = null

    /** 拦着他时他回了一句。 */
    suspend fun gateReply(text: String): LiveTurn =
        io("gateReply", { LiveTurn(Mood.WORRIED, "刚才没接上，再说一次（或者点「急事」）", true, false, emptyList()) }) {
            brain.gateReply(text, System.currentTimeMillis(), liveConfig())
        }

    /** 不回答也走：急事 / 到点。 */
    suspend fun gateEscape(reason: String) {
        io("gateEscape", {}) { brain.gateEscape(reason, System.currentTimeMillis(), liveConfig()) }
    }

    /**
     * 早晚自动（M12），都在心跳里做：
     *  - 早上（6:00–22:00、今天还没有水道、开关开着、亮屏）→ 自动排一份**草稿**（不定稿、不交给总控），发通知；
     *  - 夜里（23:30–4:00、这一夜还没整理）→ brain.night(now)，返回的话发一条安静的「今天的账」。
     * 同一个判断一分钟内只做一次：后台服务和前台心跳可能同时跳。
     */
    private suspend fun autoSchedule(now: Long) {
        val ctx = appContext ?: return
        val minute = now / 60_000L
        if (lastAutoMinute == minute) return
        if (!autoGate.compareAndSet(false, true)) return
        lastAutoMinute = minute
        try {
            if (settings.autoPlan() && interactive() && brain.needsPlan(now)) {
                // 先用昨晚的亮屏 / 熄屏推算睡了多久（没权限就跳过），睡得少主河道会排短一点
                runCatching { brain.sleep(Usage.screenEvents(ctx, now - 36 * 3_600_000L, now), now) }
                val plan = runCatching { brain.planDay(now) }.getOrNull()
                if (plan != null) {
                    refreshState()
                    Notify.planReady(ctx, plan.intent)
                }
            }
            // 她自己的思考（v8）：醒着时隔两三个小时一次，他说了不少话时早一点；只在亮屏时想，省电省钱
            if (interactive() && brain.needsThink(now)) {
                runCatching { brain.think(now, usageDigest(now)) }
                // 想完顺手采一次蜜（一天最多一次，大脑自己管）
                runCatching { brain.forage(now) }
                refreshState()
            }
            if (brain.needsNight(now)) {
                val text = runCatching { brain.night(now) }.getOrNull()
                refreshState()
                if (!text.isNullOrBlank()) Notify.nightAccount(ctx, text)
                // 开着 Notion：她夜里想好明天要问 GPT 的，下一次同步写上黑板
                if (settings.notion().ready()) runCatching { brain.prepareQuestions(now) }
                // 夜里从这几天的数字里摸规律（他点“对”才生效）
                runCatching { brain.proposeRules(now) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "早晚自动失败", e)
        } finally {
            autoGate.set(false)
        }
    }

    private fun refreshState() {
        _state.value = runCatching { brain.state() }.getOrDefault(_state.value)
    }

    private fun interactive(): Boolean = runCatching {
        appContext?.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }.getOrDefault(true)

    /**
     * 把心跳拿到的提醒发成系统通知（M11）。安静时段（总控静默或 23:30–07:00）照发但不响；
     * 挂着提议的带上「好 / 不用」，和 App 里点卡片同一条路。
     */
    private fun postNotices(now: Long, notices: List<Notice>) {
        val ctx = appContext ?: return
        val s = _state.value
        val minute = Flow.minuteOf(now)
        val silent = s.lastStatus?.quiet == true || minute >= 23 * 60 + 30 || minute < 7 * 60
        notices.forEach { n ->
            val p = s.proposals.firstOrNull { it.id == n.proposalId && it.state == ProposalState.PENDING }
            Notify.notice(ctx, n, p, silent)
        }
    }

    /**
     * 心跳的输入（M10）：前台类别 + 总控健康样本。
     * 没开「使用情况访问」→ 类别空串、事实里标 unknown；总控连不上 → zkReachable=false。
     * 墨灵不修总控，canRepair 永远 false。
     */
    /** 此刻前台（M15 心跳给导演看）；没权限或读不到给 null。 */
    fun foregroundNow(now: Long): Foreground? {
        val ctx = appContext ?: return null
        return if (Usage.allowed(ctx)) runCatching { Usage.foreground(ctx, now) }.getOrNull() else null
    }

    private fun tickInputs(now: Long): Pair<String, GuardSample?> {
        val ctx = appContext
        val status = _state.value.lastStatus
        val fg = if (ctx != null && Usage.allowed(ctx)) runCatching { Usage.foreground(ctx, now) }.getOrNull() else null
        val category = BrainFacts.category(fg?.pkg.orEmpty(), status)
        val screenOn = runCatching { ctx?.getSystemService(PowerManager::class.java)?.isInteractive ?: true }.getOrDefault(true)
        val reachable = status != null && status.at > 0 && now - status.at <= 2 * 60_000L
        val guard = GuardSample(
            now = now,
            zkReachable = reachable,
            secureSettingsOn = reachable && status?.accessibilityOn == true,
            heartbeatAt = status?.heartbeatAt ?: 0,
            userDisabled = status?.userDisabled ?: false,
            screenOn = screenOn,
            canRepair = false,
        )
        return category to guard
    }

    // ---------------------------------------------------------------- 体系图 / 权柄

    /** 贴进来的材料拆成论断挂进体系图。返回新增几条；模型不可用时大脑给 0。 */
    suspend fun ingest(text: String, source: String, topicName: String): Int =
        io("ingest", { 0 }) { brain.ingest(text, source, topicName, System.currentTimeMillis()) }

    /** 切换当前主题（没有就建一个）。 */
    suspend fun setTopic(name: String) {
        io("setTopic", {}) { brain.setTopic(name, System.currentTimeMillis()) }
    }

    /** 用户设的权柄上限：只影响墨灵多主动，不绕过总控的确认。 */
    suspend fun setUserMax(tier: Tier) {
        io("setUserMax", {}) { brain.setUserMax(tier) }
    }

    /** 核实一条论断（写下验证结果，把握升到高）；成功返回 true。 */
    suspend fun verifyNode(nodeId: String, result: String): Boolean =
        io("verifyNode", { false }) { brain.verifyNode(nodeId, result, System.currentTimeMillis()) }

    /** 删掉一条论断（连同它的关系）。 */
    suspend fun removeNode(nodeId: String) {
        io("removeNode", {}) { brain.removeNode(nodeId) }
    }

    /** 让模型归纳当前主题；模型不可用返回 null。 */
    suspend fun synthesize(): String? =
        io<String?>("synthesize", { null }) { brain.synthesize(System.currentTimeMillis()) }

    /** 体系图的一个缺口 → 一条去聊的提议（点「好」才去）。 */
    suspend fun askGap(question: String, actor: Actor): Proposal? =
        io<Proposal?>("askGap", { null }) { brain.askGap(question, actor, System.currentTimeMillis()) }

    // ---------------------------------------------------------------- 导演（M15）

    private val _directorCard = MutableStateFlow<Card?>(null)

    /** 导演此刻要说的那张卡；App 在前台时今天页显示它。 */
    val directorCard: StateFlow<Card?> = _directorCard

    /** 此刻议程最上面的卡（不调模型）；换页/重开 App 后也能拿回来。 */
    fun currentCard(now: Long): Card? =
        if (ready) runCatching { brain.director.currentCard(now) }.getOrNull() else null

    fun setDirectorCard(card: Card?) {
        _directorCard.value = card
    }

    /** 没想完的念头（今天页「此刻」卡片下面那一行）。 */
    fun directorBaton(): Baton? = directorSnapshot().baton

    /** 用户回应了导演的一张卡（继续 / 卡住了 / 有结论 / 休息 / 点选 / 划掉）。返回下一张卡。 */
    suspend fun reply(reply: Reply): Card? = io<Card?>("reply", { null }) { brain.director.onReply(reply) }

    /** 「去聊」：让导演准备开场白（点「好」之后才调）。 */
    suspend fun opening(actor: Actor, cardId: String?): Opening? =
        io<Opening?>("opening", { null }) { brain.director.opening(actor, cardId, System.currentTimeMillis()) }

    /** 喂导演一条感知事件；返回需要立刻显示的卡。 */
    suspend fun sense(sense: Sense): Card? = io<Card?>("sense", { null }) { brain.director.onSense(sense) }

    /** 在你身边的命令入口；用户选 A/C 后才调用 accept。 */
    suspend fun suggest(command: com.moling.core.link.ZkCommand, now: Long): Proposal? =
        io<Proposal?>("suggest", { null }) { brain.suggest(command, now) }

    suspend fun session(log: com.moling.core.mind.SessionLog): Card? =
        io<Card?>("session", { null }) { brain.director.onSession(log) }

    // ---------------------------------------------------------------- 工具

    /** 所有 brain 调用都从这里走：IO 线程、失败记日志给兜底、调完把状态推给界面。 */
    private suspend fun <T> io(name: String, fallback: () -> T, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            val r = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$name 失败", e)
                fallback()
            }
            _state.value = runCatching { brain.state() }.getOrElse { _state.value }
            r
        }

    /** 大脑意外出错时的兜底水道：至少今天页有东西可显示，不崩。 */
    private fun fallbackPlan(now: Long): FlowPlan = Flow.local(
        Flow.Inputs(
            day = Brain.calendarDay(now),
            now = now,
            mainTitle = "",
            mainPlannedAt = 0L,
            baton = "",
            aims = emptyList(),
            deliverables = emptyList(),
            patterns = emptyList(),
            bestHours = emptyList(),
            freeMinutes = 60,
        ),
    )
}