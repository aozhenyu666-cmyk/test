package com.moling.app

import android.content.Context
import android.util.Log
import com.moling.app.data.LlmClient
import com.moling.app.data.MolingAsk
import com.moling.app.data.SettingsStore
import com.moling.core.Brain
import com.moling.core.BrainState
import com.moling.core.ChatResult
import com.moling.core.Notice
import com.moling.core.Outcome
import com.moling.core.Proposal
import com.moling.core.flow.Cause
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.link.ZkStatus
import com.moling.core.mind.DirectorConfig
import com.moling.core.mind.FileMindStore
import com.moling.core.power.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

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

    private val _state = MutableStateFlow(BrainState())

    /** 界面读这个：水道、对话、提议、预言、日志、最后一次总控状态。 */
    val state: StateFlow<BrainState> = _state

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
            val dir = File(app.filesDir, "mind").apply { mkdirs() }

            ZkBridge.init(app)
            settings = SettingsStore(app)
            val ask = MolingAsk(settings, LlmClient { settings.load() })
            brain = Brain(
                phone = PhoneFacts(),
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
        // 这一批不做后台定时、不做无障碍、不读前台应用：类别传空串、guard 传 null。
        val out = io("tick", { emptyList<Notice>() }) { brain.tick(now, "", null) }
        _notices.value = (out + _notices.value).distinctBy { it.at to it.text }.take(3)
        return out
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
