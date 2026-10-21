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
import com.moling.core.flow.Cause
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.guard.GuardSample
import com.moling.core.link.ZkStatus
import com.moling.core.mind.Actor
import com.moling.core.mind.DirectorConfig
import com.moling.core.mind.FileMindStore
import com.moling.core.mind.MindState
import com.moling.core.power.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
            val ask = MolingAsk(settings, LlmClient { settings.load() })
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
            )
            _state.value = brain.state()
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
        _notices.value = _notices.value.filterNot { it.at == at }
    }

    // ---------------------------------------------------------------- 对话

    suspend fun chat(text: String, now: Long): ChatResult =
        io("chat", { ChatResult("刚才没接上，再说一次", emptyList(), false) }) { brain.chat(text, now) }

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
        return out
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
                val plan = runCatching { brain.planDay(now) }.getOrNull()
                if (plan != null) {
                    refreshState()
                    Notify.planReady(ctx, plan.intent)
                }
            }
            if (brain.needsNight(now)) {
                val text = runCatching { brain.night(now) }.getOrNull()
                refreshState()
                if (!text.isNullOrBlank()) Notify.nightAccount(ctx, text)
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