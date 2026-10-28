package com.zongkong.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.zongkong.app.link.YishouClient
import com.zongkong.core.ApiResult
import com.zongkong.core.Config
import com.zongkong.core.Link
import com.zongkong.core.Status
import com.zongkong.core.ZkJson
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.AgentEnv
import com.zongkong.core.agent.ChatClient
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.agent.ChatThread
import com.zongkong.core.agent.ChatThreads
import com.zongkong.core.agent.ChatMode
import com.zongkong.core.agent.OverallSnapshot
import com.zongkong.core.work.Work
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZoneId
import kotlin.random.Random

/**
 * 参谋的对话和它记住的事，存在 zk/chat.json。
 *
 * 对话分成多段（[threads]），[activeId] 是当前这一段。旧的 chat.json 只有 `messages`，
 * 读进来时自动迁移成第一段（并把旧文件备份成 chat.json.bak）；`messages` 这个计算属性让
 * 现有界面和主动消息代码不用改。
 */
@Serializable
data class ChatLog(
    val threads: List<ChatThread> = emptyList(),
    /**
     * 当前这一段。全局只有一个，但任何时候都必须指向「当前模式」的段——
     * 切换模式时由 [com.zongkong.app.data.ChatRepo] 改写成目标模式的段，界面读到的就是那一套消息。
     */
    val activeId: String = "",
    val memory: List<String> = emptyList(),
    /** 打完的战役：标题、时刻、总结（最近 10 次）。 */
    val campaignLog: List<String> = emptyList(),
    /**
     * 旧版本把战役存在这里（全局一份）。现在战役跟着对话段走（[ChatThread.campaign]），
     * 这两个字段只为兼容旧 chat.json 的读取保留，不再写入；读到旧数据时会在加载阶段搬进段里。
     */
    val campaign: String = "",
    val campaignTopic: String = "",
    val campaignAt: Long = 0,
    /**
     * 已经追问过的缺口：「键|时刻」。
     *
     * 存成字符串列表而不是 Map：这个文件里的数据一直是"能一眼读的简单结构"，
     * 而且老 chat.json 里没有这个字段，靠默认值空列表就能平滑升级。
     * 有了它，重启后不会再把同一个问题问一遍。
     */
    val asked: List<String> = emptyList(),
) {
    /**
     * 当前这一段；activeId 对不上就退到最近没被收尾的一段。
     * 全部收尾过就退到最近一段（老逻辑，保证不会突然没段可用）。
     */
    val activeThread: ChatThread?
        get() = threads.firstOrNull { it.id == activeId }
            ?: threads.lastOrNull { !it.closed }
            ?: threads.lastOrNull()

    /** [mode] 这一种对话里该用哪一段：activeId 属于它就用它，否则回到它最近动过的段。 */
    fun threadOf(mode: ChatMode): ChatThread? {
        threads.firstOrNull { it.id == activeId && it.chatMode == mode }?.let { return it }
        return ChatThreads.activeOf(threads, mode)
    }

    /** 当前这一段的全部消息（界面、主动消息、头像气泡都还按这个读）。 */
    val messages: List<ChatMsg> get() = activeThread?.messages ?: emptyList()
}

/** 只改当前模式的当前这一段。 */
internal fun ChatLog.mapActive(mode: ChatMode, f: (ChatThread) -> ChatThread): ChatLog {
    val t = threadOf(mode) ?: return this
    return copy(threads = threads.map { if (it.id == t.id) f(it) else it }, activeId = t.id)
}

/** 按 id 换掉这一段。 */
internal fun ChatLog.replaceThread(t: ChatThread): ChatLog =
    copy(threads = threads.map { if (it.id == t.id) t else it }, activeId = t.id)

/** 旧格式（只有 messages）读出来用。 */
@Serializable
private data class LegacyChatLog(val messages: List<ChatMsg> = emptyList())

/**
 * 参谋：对话在应用级的协程里跑，离开对话页也不中断；每一步（调用了什么工具、结果）实时写进对话。
 * 参谋要打开的页面放进 [route]，对话页看到就跳过去。
 */
class ChatRepo(private val context: Context, private val store: Store, private val actions: Actions) {
    private val file = File(File(context.filesDir, "zk"), "chat.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Any()
    /** 今天新开了几段对话（跨天归零）。 */
    private val prefs = context.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val rnd = Random.Default

    private val _log = MutableStateFlow(load())
    val log: StateFlow<ChatLog> = _log.asStateFlow()


    /**
     * 当前是哪种对话（当下 / 整体）。
     *
     * 用户拍板：每次冷启动进总控对话都默认「当下」，所以这里只放在内存里，**不持久化**；
     * 切换模式只改这个值和 activeId 指向哪一段，不删、不合并任何消息。
     */
    private val _mode = MutableStateFlow(ChatMode.DEFAULT)
    val mode: StateFlow<ChatMode> = _mode.asStateFlow()

    /**
     * 这一轮的整体快照。用户消息开头读一次（一手那种异步读取也只读这一次），
     * 同一次工具循环里反复要系统提示都拿这份，下一条消息再重算。
     */
    @Volatile
    private var overallCache: String = ""

    /**
     * 这一轮该问的那一个（[com.zongkong.core.agent.Inquiry] 算出来的现成文本）。
     *
     * 和 [overallCache] 一样：用户消息开头算一次、钉死，同一次工具循环里反复拼系统提示都拿这份。
     * 顺带把「这一问」记进 [asked]，下次冷却期内不再重问同一个口子。
     */
    @Volatile
    private var inquiryCache: String = ""

    /** 已经问过的缺口：[键] → 上次问的时刻。跟着对话记录走，重启后还记得问过什么。 */
    @Volatile
    private var asked: com.zongkong.core.agent.AskedGaps = decodeAsked(_log.value.asked)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 参谋说要打开的页面（导航完由界面清掉）。 */
    val route = MutableStateFlow<String?>(null)

    private var job: Job? = null

    val client = ChatClient({ store.config.value.ai })

    private val env = object : AgentEnv {
        override val zone: ZoneId get() = store.zone
        override val rnd: Random = Random.Default
        override fun now() = System.currentTimeMillis()
        override fun config(): Config = store.config.value
        override fun updateConfig(f: (Config) -> Config): Config = store.updateConfig(f)
        override fun work(): Work = store.work.value
        override fun updateWork(f: (Work) -> Work): Work = store.updateWork(f)
        override fun plans(): com.zongkong.core.plan.PlanBook = store.plans.value
        override fun updatePlans(f: (com.zongkong.core.plan.PlanBook) -> com.zongkong.core.plan.PlanBook) = store.updatePlans(f)
        override fun status(): Status = store.status()
        override fun kick() = actions.work.kick()
        override suspend fun yishou(): String = when (val r = YishouClient.status(context)) {
            is YishouClient.Result.Ok -> r.value.let { s ->
                buildString {
                    append("一手（${s.appVersion}）：").append(s.summary()).append('\n')
                    if (s.taskTitle.isNotBlank()) append("当前任务：${s.taskTitle}；目标：${s.taskGoal}\n断点：已知 ${s.known}｜卡在 ${s.stuck}｜下一问 ${s.next}\n")
                    if (s.ref.isNotBlank()) append("这个任务是总控交过去的：${s.ref}\n")
                    append("入口思考页：${if (s.gateOn) "开着" else "没开"}\n")
                    if (s.rule.isNotBlank()) append("${s.summaryDate} 总结的规则：${s.rule}；明天第一问：${s.tomorrowQuestion}\n")
                }
            }
            is YishouClient.Result.Err -> "读不到一手：${r.message}"
        }
        override fun pcSide(): com.zongkong.core.device.PcSide? = store.device.value.pc.takeIf { actions.device.ready }
        override fun pcCommand(kind: String, minutes: Int, text: String): String = actions.device.command(kind, minutes, text)
        override suspend fun sendToYishou(actionKey: String): String {
            val order = Link.taskOrder(store.work.value, actionKey) ?: return "失败：没有这步行动"
            return when (val r = YishouClient.setTask(context, order)) {
                is YishouClient.Result.Ok -> r.value.ifBlank { "已交给一手" }
                is YishouClient.Result.Err -> "失败：${r.message}"
            }
        }
        override suspend fun startYishouWindow(): String = when (val r = YishouClient.startWindow(context)) {
            is YishouClient.Result.Ok -> r.value.also { YishouClient.openWindow(context) }
            is YishouClient.Result.Err -> "开局失败：${r.message}"
        }
        override suspend fun sync(): String = actions.sync()
        override fun memory(): List<String> = _log.value.memory
        override fun threadContext(): String =
            ChatThreads.contextLine(_log.value.threads, _log.value.activeId, System.currentTimeMillis(), store.zone)
        override fun chatMode(): ChatMode = _mode.value
        override fun overallSnapshot(): String = overallCache
        override fun askedGaps(): com.zongkong.core.agent.AskedGaps = asked
        override fun inquiryPrompt(): String = inquiryCache
        override fun directives(): List<com.zongkong.core.plan.Directive> = store.config.value.directives
        override fun updateDirectives(f: (List<com.zongkong.core.plan.Directive>) -> List<com.zongkong.core.plan.Directive>) =
            store.updateConfig { it.copy(directives = f(it.directives)) }.directives
        override fun remember(fact: String) = update { it.copy(memory = (it.memory.filterNot { m -> m == fact } + fact).takeLast(30)) }
        override fun forget(fact: String): Boolean {
            val before = _log.value.memory
            update { it.copy(memory = it.memory.filterNot { m -> m == fact || m.contains(fact) }) }
            return _log.value.memory.size < before.size
        }
        override fun campaign(): String {
            val now = System.currentTimeMillis()
            // 战役跟着对话段走：只读「当前模式的当前段」自己的那场，绝不从另一种对话串过来
            val t = _log.value.threadOf(_mode.value) ?: return ""
            val c = com.zongkong.core.agent.Campaign.of(t.campaign) ?: return ""
            // 三小时没收尾就当放下了
            if (now - t.campaignAt > 3 * 3600_000L) return ""
            return c.prompt(t.campaignTopic, t.campaignAt, now)
        }
        override fun startCampaign(kind: String, topic: String): String {
            val c = com.zongkong.core.agent.Campaign.of(kind) ?: return "没有这种战役：$kind"
            val now = System.currentTimeMillis()
            var started = false
            update { log0 ->
                val (log, t) = ensureActive(log0, now)
                started = true
                log.replaceThread(t.copy(campaign = c.name, campaignTopic = topic.trim(), campaignAt = now, updatedAt = now))
            }
            if (!started) return "开不了：没有可用的对话段"
            return "「${c.title}」开始了（在${_mode.value.label}这条对话里）。阶段说明已经放进你的系统提示，从阶段 1 开始。"
        }
        override fun endCampaign(summary: String): String {
            val now = System.currentTimeMillis()
            var title = ""
            update { log0 ->
                val t = log0.threadOf(_mode.value) ?: return@update log0
                val c = com.zongkong.core.agent.Campaign.of(t.campaign) ?: return@update log0
                title = c.title
                val line = "${c.title} ${com.zongkong.core.boss.Timeline.hm(now, store.zone)}：${summary.trim()}（${_mode.value.label}）"
                log0.replaceThread(t.copy(campaign = "", campaignTopic = "", campaignAt = 0, updatedAt = now))
                    .copy(campaignLog = (log0.campaignLog + line).takeLast(10))
            }
            if (title.isBlank()) return "没有进行中的战役"
            return "「$title」收尾了。"
        }
        override fun sessionContext(): String {
            val h = store.hq.value.active ?: return ""
            val st = h.current
            return buildString {
                append("当前环节：${st.seal} · ${st.title}（第 ${h.step + 1}/${com.zongkong.core.hq.HqStep.entries.size}），要求：${st.hint}\n")
                append("到 ${java.time.Instant.ofEpochMilli(h.endsAt).atZone(store.zone).toLocalTime().toString().take(5)} 结束（${h.trigger.label}）。\n")
                h.notes.forEach { (k, v) -> append("已记：${com.zongkong.core.hq.HqStep.valueOf(k).title}：${v.take(200)}\n") }
            }
        }
        override fun startHq(minutes: Int): String {
            val h = actions.hq.start(com.zongkong.core.hq.HqTrigger.AGENT, minutes)
            return "总控时间开始了，${h.minutes} 分钟。说完这句会打开总控时间。"
        }
        override fun intake(): com.zongkong.core.info.IntakeBook = store.intake.value
        override fun updateIntake(f: (com.zongkong.core.info.IntakeBook) -> com.zongkong.core.info.IntakeBook) = store.updateIntake(f)
        override fun vein(): com.zongkong.core.vein.Vein = store.vein.value
        override fun updateVein(f: (com.zongkong.core.vein.Vein) -> com.zongkong.core.vein.Vein) = store.updateVein(f)
        override fun dossier(): com.zongkong.core.boss.Dossier = store.dossier.value
        override fun updateDossier(f: (com.zongkong.core.boss.Dossier) -> com.zongkong.core.boss.Dossier) = store.updateDossier(f)
        override fun hqSessions(): List<com.zongkong.core.hq.HqSession> = store.hq.value.sessions
        override fun dayLog(): com.zongkong.core.DayLog = store.today.value
        override fun ledger(): com.zongkong.core.ledger.LedgerBook = store.ledger.value
        override fun updateLedger(f: (com.zongkong.core.ledger.LedgerBook) -> com.zongkong.core.ledger.LedgerBook) = store.updateLedger(f)
        override fun deviceEvents(from: Long, to: Long) = com.zongkong.app.system.Usage.events(context, from, to)
        override fun screenshots(from: Long, to: Long) = store.shots(from, to)
        override fun sensors(): String = buildString {
            val boss = store.config.value.boss
            // 先说眼睛的模式，再说细节：无障碍“开着但没在运行”时，后面的数字都是残缺的，
            // 不先讲清楚，模型会把一段空白当成“他那段时间很省”。
            val eye = com.zongkong.app.ui.PermissionStatus.eyeMode(context)
            append("- 眼睛：${com.zongkong.app.system.Eye.label(eye)}——${com.zongkong.app.system.Eye.forAi(eye)}\n")
            append("- 手机使用记录：")
            append(if (com.zongkong.app.system.Usage.granted(context)) "有系统的“使用情况访问”授权，总控没运行的时段也查得到\n" else "只有总控自己记的（没授权“使用情况访问”），总控没在运行的时段是空白\n")
            append("- 无障碍服务：${if (com.zongkong.app.guard.GuardService.running) "运行中" else "没在运行（拦不住、记不到，提醒用户去权限页打开）"}\n")
            append("- 截图抽查：")
            append(if (boss.shots) "开着，亮屏时每小时约 ${boss.shotsPerHour} 张，留 ${boss.keepDays} 天；连续娱乐时也会截一张\n" else "没开（用户在“总控 → 档案”里能开；你可以建议）\n")
            append("- 照片：用户发来的照片会附上拍摄时间；没有拍摄时间的多半是截图或转存的旧图\n")
            append("- 识图：${if (store.config.value.ai.visionModel.isNotBlank()) "看图模型 ${store.config.value.ai.visionModel}" else "用主模型看图（主模型不支持看图时会失败）"}\n")
            append("- 连续娱乐 ${if (boss.bingeMinutes > 0) "${boss.bingeMinutes} 分钟会叫醒你" else "不管"}；每天抽查 ${boss.spotChecks} 次")
        }
        override suspend fun describeImage(path: String, question: String): String {
            val r = client.chat(
                "你是总控的识图助手。只说截图里看得到的事实：哪个应用、什么内容、是学习 / 工作还是娱乐。看不清就说看不清，不要猜。一两句话。",
                listOf(ChatMsg(ChatMsg.USER, question, at = System.currentTimeMillis(), image = path)),
                emptyList(),
            )
            return when (r) {
                is com.zongkong.core.ApiResult.Ok -> r.value.content.ifBlank { "（识图模型没有回答）" }
                is com.zongkong.core.ApiResult.Err -> "看不了：${r.message}"
            }
        }
        override fun resolveApp(name: String): String? = AppNames.resolve(context, name)
        override fun appLabel(pkg: String): String = AppNames.label(context, pkg)
    }

    val agent = Agent(client, env)

    // ---------- 两种对话：当下 / 整体 ----------

    /** 切换对话模式：只改模式标签和 activeId 指向哪一段，消息一条不动。 */
    fun switchMode(to: ChatMode) {
        if (_mode.value == to) return
        _mode.value = to
        // activeId 必须指向新模式的段（没有就留空，界面显示空对话态；第一次说话时才建段）
        val t = _log.value.threadOf(to)
        update { it.copy(activeId = t?.id.orEmpty()) }
    }

    /** 当前模式的段列表，最近动过的在前。 */
    fun threads(): List<ChatThread> = threadsOf(_mode.value)

    /** 某一种对话的段列表，最近动过的在前。 */
    fun threadsOf(mode: ChatMode = _mode.value): List<ChatThread> =
        ChatThreads.recentFirst(ChatThreads.inMode(_log.value.threads, mode))

    /** 当前这一段的 id（没有段时为空）。 */
    val activeId: String get() = _log.value.threadOf(_mode.value)?.id.orEmpty()

    /** 当前这一段（当前模式的）。 */
    fun activeThread(): ChatThread? = _log.value.threadOf(_mode.value)

    /** 每天最多新开几段；0 = 不限（config.chat.threadsPerDay，默认 3）。当下 / 整体各算各的。 */
    fun threadsPerDay(): Int = store.config.value.chat.threadsPerDay

    /** 今天这一种对话新开了几段（记账 + 段数兜底，跨天归零）。 */
    fun openedToday(mode: ChatMode = _mode.value): Int {
        val day = ChatThreads.dayKey(System.currentTimeMillis(), store.zone)
        val stored = prefs.getString(prefDay(mode), "") ?: ""
        val recorded = if (stored == day) prefs.getInt(prefCount(mode), 0) else 0
        return ChatThreads.openedToday(_log.value.threads, System.currentTimeMillis(), store.zone, mode, recorded)
    }

    /** 今天这一种对话还能新开几段；-1 = 不限。 */
    fun remainingToday(mode: ChatMode = _mode.value): Int = ChatThreads.remaining(threadsPerDay(), openedToday(mode))

    /** 今天新建受限时给用户看的一句话。 */
    fun limitText(mode: ChatMode = _mode.value): String = ChatThreads.limitText(threadsPerDay(), openedToday(mode), mode)

    /**
     * 打开对话时摆在最上面的那一问：它现在最想问你什么。
     *
     * 和系统提示里的「追问」是同一套诊断，但这里**不写冷却记录**——只是给用户看，
     * 真正问出口（发出去、或者他点了这个卡片）才算问过。否则他一进对话就白白消耗一次机会。
     */
    fun pendingQuestion(): com.zongkong.core.agent.Inquiry.Gap? = try {
        com.zongkong.core.agent.Inquiry.plan(env, System.currentTimeMillis(), store.zone, asked).gap
    } catch (e: Exception) {
        Log.w(TAG, "算当前该问的问题失败", e)
        null
    }


    /** 新开一段（当前模式的）。返回（成功了吗，给用户的一句话）。受每日上限限制。 */
    fun newThread(): Pair<Boolean, String> = newThread(_mode.value)

    /** 在 [mode] 这一种对话里新开一段；额度按模式各算各的，交接只从同模式带过来。 */
    fun newThread(mode: ChatMode): Pair<Boolean, String> {
        val perDay = threadsPerDay()
        val opened = openedToday(mode)
        if (!ChatThreads.canOpen(perDay, opened)) return false to ChatThreads.limitText(perDay, opened, mode)
        if (!markOpened(mode)) return false to ChatThreads.limitText(perDay, opened, mode)
        val now = System.currentTimeMillis()
        // 只带同一种对话里上一段压出来的交接：整体的结论不会被当下当成上一段
        val carry = ChatThreads.inMode(_log.value.threads, mode).filter { it.carry.isNotBlank() }
            .maxByOrNull { it.updatedAt }?.carry.orEmpty()
        val t = ChatThread(id = ChatThreads.newId(rnd), createdAt = now, updatedAt = now, carry = carry, mode = mode.key)
        update { it.copy(threads = it.threads + t, activeId = t.id) }
        return true to "新开了一段（${mode.label}）。${mode.label}这条线上上一段的结论已经带着了。"
    }

    /** 关掉当前这段再新开一段。先查每日上限，开不了就不关（免得两截都空着）。 */
    fun closeAndNew(): Pair<Boolean, String> {
        val mode = _mode.value
        val perDay = threadsPerDay()
        val opened = openedToday(mode)
        if (!ChatThreads.canOpen(perDay, opened)) return false to ChatThreads.limitText(perDay, opened, mode)
        activeId.takeIf { it.isNotBlank() }?.let { closeThread(it) }
        return newThread(mode)
    }

    /**
     * 打开某一段（收尾过的也能接着聊，打开即重新激活）。
     * 打开旧段时要一起切到它所属的模式：否则界面还停在另一套对话上，会看错历史。
     */
    fun openThread(id: String) {
        val target = _log.value.threads.firstOrNull { it.id == id } ?: return
        _mode.value = target.chatMode
        update { log ->
            val t = log.threads.firstOrNull { it.id == id } ?: return@update log
            log.copy(threads = log.threads.map { if (it.id == id) ChatThreads.opened(t) else it }, activeId = id)
        }
    }

    /**
     * 收尾一段：先用本地摘要兜底立刻写回（界面不等），再让模型压一次 120 字的交接。
     * 模型失败/没接就留着本地摘要，绝不阻塞界面。
     */
    fun closeThread(id: String) {
        val now = System.currentTimeMillis()
        var target: ChatThread? = null
        update { log ->
            val t = log.threads.firstOrNull { it.id == id } ?: return@update log
            if (t.closed) return@update log
            target = t
            val carry = ChatThreads.mergeCarry(t.carry, ChatThreads.carryOf(t))
            log.copy(threads = log.threads.map { if (it.id == id) ChatThreads.closed(it, carry, now) else it })
        }
        target?.let { aiCarry(it) }
    }

    /** 删掉一段；删的是当前这段就把当前模式挪到它最近的一段上（没有就清空，落到空对话态）。 */
    fun deleteThread(id: String) {
        update { log ->
            val left = log.threads.filterNot { it.id == id }
            if (left.size == log.threads.size) return@update log
            val current = log.threadOf(_mode.value)
            if (current?.id == id) {
                return@update log.copy(threads = left, activeId = ChatThreads.activeOf(left, _mode.value)?.id.orEmpty())
            }
            return@update log.copy(threads = left)
        }
    }

    /**
     * 你说一句。[echo]：从桌面宠物、通知、对讲来的——回答也回到那里（气泡、通知、念出来）。
     */
    fun send(text: String, image: String = "", echo: Boolean = false) {
        val t = text.trim()
        if ((t.isEmpty() && image.isBlank()) || _busy.value) return
        store.touch()
        // 回话了：之前没回的主动询问都算回了
        store.updateDossier { com.zongkong.core.boss.Boss.answered(it, System.currentTimeMillis()) }
        _busy.value = true
        // 这一轮属于哪种对话、读哪一段历史，在开口时就钉死：回答到一半用户切了标签也不会串
        val mode = _mode.value
        job = scope.launch {
            try {
                // 整体：每条用户消息只读一次整体快照（一手那种异步读取也只在这里读一次），
                // 后面同一次工具循环里反复拼系统提示都用这一份
                if (mode == ChatMode.OVERALL) overallCache = readOverallSnapshot()
                // 追问同理：这一轮问什么，开口时就定死（用户提到的那件事优先）
                pinInquiry(t)
                val history = _log.value.threadOf(mode)?.messages.orEmpty()
                val speak = store.config.value.hq.speak && store.hq.value.active != null
                agent.reply(history, t.ifBlank { "看看这张图" }, { m ->
                    append(listOf(m), mode)
                    if (m.role == ChatMsg.TOOL && m.route.isNotBlank()) route.value = m.route
                    if (speak && m.role == ChatMsg.ASSISTANT && m.content.isNotBlank() && !m.error) com.zongkong.app.system.Speaker.speak(context, m.content)
                }, image = image, meta = if (image.isNotBlank()) photoMeta(image) else "")
            } catch (e: Exception) {
                Log.e(TAG, "参谋出错", e)
                append(listOf(ChatMsg(ChatMsg.ASSISTANT, "出错了：${e.message ?: e.javaClass.simpleName}", at = System.currentTimeMillis(), error = true)), mode)
            } finally {
                _busy.value = false
                // 这一轮结束：下一轮重新诊断（他可能刚答完、刚改完事）
                clearInquiry()
                if (echo) echoReply()
                com.zongkong.app.system.PetWidget.refresh(context)
            }
        }
    }

    /** 把最后一句回答送回桌面宠物 / 通知，并念出来。 */
    private fun echoReply() {
        val reply = _log.value.threadOf(_mode.value)?.messages.orEmpty().lastOrNull { it.role == ChatMsg.ASSISTANT && it.content.isNotBlank() }?.content ?: return
        val (body, _) = Agent.options(reply)
        com.zongkong.app.system.Pet.say(context, body, 30)
        com.zongkong.app.system.Notifications.advisor(context, body)
        if (store.config.value.voice.speakReplies) com.zongkong.app.system.Voice.speak(context, body)
    }

    /**
     * 参谋主动发一条消息（定时任务）：把指令当作看不见的一句话发给模型，回复显示在对话里并发通知。
     * 没配 AI 或者出错，就发 [fallback] 这句固定的话。
     */
    fun proactive(kind: String, instruction: String, fallback: String, quiet: Boolean = false): Boolean {
        if (_busy.value) return false
        val cfg0 = store.config.value
        // 抽查要 AI 自己判断有没有事；没接 AI 就不做
        if (quiet && !cfg0.ai.ready) return false
        _busy.value = true
        // 定时来的一律算「当下」：整体那条线不放每天的固定询问
        val mode = ChatMode.NOW
        scope.launch {
            var said = ""
            val cfg = store.config.value
            if (cfg.ai.ready) {
                try {
                    // 主动开口也带追问：早上那条“今天做什么”就是最该问的那一问。
                    // 用户不在场，没有焦点可挑，按诊断的优先级问最急的那个。
                    pinInquiry("")
                    val added = mutableListOf<ChatMsg>()
                    val out = agent.reply(_log.value.threadOf(mode)?.messages.orEmpty(), instruction, { m ->
                        added += m
                        append(listOf(m), mode)
                    }, hidden = true)
                    said = out.lastOrNull { it.role == ChatMsg.ASSISTANT && it.content.isNotBlank() && !it.error }?.content.orEmpty()
                    if (quiet && (said.isBlank() || said.trim().startsWith("无事"))) {
                        // 抽查没发现问题：这一轮整段藏起来，不打扰
                        update { log -> log.mapActive(mode) { t -> t.copy(messages = t.messages.map { m -> if (m in added) m.copy(hidden = true) else m }) } }
                        Log.i(TAG, "抽查无事：$kind")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "总控定时消息失败：$kind", e)
                } finally {
                    _busy.value = false
                    clearInquiry()
                }
            } else {
                _busy.value = false
            }
            if (said.isBlank()) {
                said = fallback
                append(listOf(ChatMsg(ChatMsg.ASSISTANT, fallback, at = System.currentTimeMillis())), mode)
            }
            val (body, _) = Agent.options(said)
            com.zongkong.app.system.Notifications.advisor(context, body)
            // 桌面宠物跳出来说；开口说话（睡觉时段不说）
            com.zongkong.app.system.Pet.say(context, body, 40, alert = kind == "binge" || kind == "absent")
            val quiet = com.zongkong.core.hq.Hq.quiet(cfg.hq, System.currentTimeMillis(), store.zone)
            val loud = com.zongkong.core.boss.Intensity.speakAlways(cfg.boss, store.stance())
            if (!quiet && (cfg.voice.speakProactive || cfg.hq.speak || loud)) com.zongkong.app.system.Voice.speak(context, body)
            // 记下这次主动询问：2 小时不回，信用 −3（睡觉时段发的不算）
            if (!quiet) store.updateDossier { com.zongkong.core.boss.Boss.asked(it, ASK_KINDS[kind] ?: kind, System.currentTimeMillis()) }
            com.zongkong.app.system.PetWidget.refresh(context)
        }
        return true
    }

    /** 照片的拍摄时间（EXIF）：给总控判断是不是刚拍的。 */
    private fun photoMeta(path: String): String = try {
        val exif = android.media.ExifInterface(path)
        val raw = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
        if (raw.isNullOrBlank()) "[照片信息：没有拍摄时间——可能是截图、转存或处理过的图]"
        else {
            val t = java.time.LocalDateTime.parse(raw.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).atZone(store.zone).toInstant().toEpochMilli()
            val ago = (System.currentTimeMillis() - t) / 60_000
            "[照片信息：拍摄于 ${com.zongkong.core.boss.Timeline.hm(t, store.zone)}（" + when {
                ago < 0 -> "时间在未来，手机时间可能不对"
                ago < 60 -> "$ago 分钟前"
                ago < 24 * 60 -> "${ago / 60} 小时前"
                else -> "${ago / 1440} 天前"
            } + "）]"
        }
    } catch (e: Exception) {
        "[照片信息：读不到拍摄时间]"
    }

    fun stop() {
        job?.cancel()
        _busy.value = false
    }

    /** 清空当前这一段的对话（其他段、另一种对话和记住的事都保留）。 */
    fun clear() = update { it.mapActive(_mode.value) { t -> t.copy(messages = emptyList()) } }

    fun forgetAll() = update { it.copy(memory = emptyList()) }

    /**
     * 开一场战役：记在当前模式的当前段上（系统提示里一直带着阶段说明），再让总控开口。没接 AI 返回 false。
     * 战役跟着段走：在整体里开的不会出现在当下，切模式也不会带过去。
     */
    fun begin(c: com.zongkong.core.agent.Campaign, topic: String = "", material: String = ""): Boolean {
        if (!store.config.value.ai.ready || _busy.value) return false
        val mode = _mode.value
        val now = System.currentTimeMillis()
        update { log0 ->
            val (log, t) = ensureActive(log0, now)
            log.replaceThread(t.copy(campaign = c.name, campaignTopic = topic.trim(), campaignAt = now, updatedAt = now))
        }
        store.touch()
        _busy.value = true
        job = scope.launch {
            try {
                if (mode == ChatMode.OVERALL) overallCache = readOverallSnapshot()
                agent.reply(_log.value.threadOf(mode)?.messages.orEmpty(), c.opening(topic, material), { m ->
                    append(listOf(m), mode)
                    if (m.role == ChatMsg.TOOL && m.route.isNotBlank()) route.value = m.route
                }, hidden = true)
            } catch (e: Exception) {
                append(listOf(ChatMsg(ChatMsg.ASSISTANT, "出错了：${e.message ?: e.javaClass.simpleName}", at = System.currentTimeMillis(), error = true)), mode)
            } finally {
                _busy.value = false
            }
        }
        return true
    }

    /** 手动结束战役（对话页顶上的“收工”）。只收当前模式这一段自己的战役。 */
    fun stopCampaign() {
        val now = System.currentTimeMillis()
        update { log0 ->
            val t = log0.threadOf(_mode.value) ?: return@update log0
            val c = com.zongkong.core.agent.Campaign.of(t.campaign) ?: return@update log0
            log0.replaceThread(t.copy(campaign = "", campaignTopic = "", campaignAt = 0, updatedAt = now))
                .copy(campaignLog = (log0.campaignLog + "${c.title}：中途停下").takeLast(10))
        }
    }

    /** 别处来的一句话（比如电脑上的总控发来的），放进当前这一段。 */
    fun note(text: String) {
        append(listOf(ChatMsg(ChatMsg.ASSISTANT, text, at = System.currentTimeMillis())), _mode.value)
    }

    // ---------- 往当前段追加消息 / 满了自动换段 ----------

    /**
     * 往当前这一段加消息。一段满 60 条后，在下一轮“用户的话”开头收尾换段（不把一轮问答劈成两半）：
     * 旧段压成交接摘要收尾，新段从这句话开始接着走。到每日上限就不新开，把交接并进当前段、裁到最近 60 条。
     */
    private fun append(msgs: List<ChatMsg>, mode: ChatMode = _mode.value) {
        if (msgs.isEmpty()) return
        val now = System.currentTimeMillis()
        var toSummarize: ChatThread? = null
        update { log0 ->
            val (log, current) = ensureActive(log0, now, mode)
            if (msgs.first().role == ChatMsg.USER && ChatThreads.overflows(current, msgs)) {
                val carry = ChatThreads.mergeCarry(current.carry, ChatThreads.carryOf(current))
                if (markOpened(mode)) {
                    val (old, fresh) = ChatThreads.rollover(current, msgs, ChatThreads.newId(rnd), now)
                    toSummarize = current
                    log.copy(threads = log.threads.map { if (it.id == old.id) old else it } + fresh, activeId = fresh.id)
                } else {
                    // 今天的新建额度用完了：不新开，交接并进这一段，只留最近 60 条
                    val merged = current.copy(messages = current.messages + msgs, updatedAt = now, closed = false)
                    log.replaceThread(ChatThreads.trimmed(ChatThreads.withCarry(merged, carry, now)))
                }
            } else {
                val next = ChatThreads.titled(
                    current.copy(messages = (current.messages + msgs).takeLast(MAX_KEEP), updatedAt = now, closed = false),
                )
                log.replaceThread(next)
            }
        }
        toSummarize?.let { aiCarry(it) }
    }

    /**
     * [mode] 这一种对话的当前段；这一段没有（新装、刚切过来、或都删光了）就建一段。
     * 这一段自己空白（没消息、没交接、没战役）不占每日额度：切换模式后第一次开口不该被上限拦住。
     */
    private fun ensureActive(log: ChatLog, now: Long, mode: ChatMode = _mode.value): Pair<ChatLog, ChatThread> {
        log.threadOf(mode)?.let { return log to it }
        val t = ChatThread(id = ChatThreads.newId(rnd), createdAt = now, updatedAt = now, mode = mode.key)
        return log.copy(threads = log.threads + t, activeId = t.id) to t
    }

    /**
     * 用一次便宜的模型调用把这段压成不超过 120 字的交接摘要。
     * 失败、没接 AI、超时：什么都不做，本地兜底摘要已经写好了，界面不受影响。
     */
    private fun aiCarry(t: ChatThread) {
        if (!store.config.value.ai.ready) return
        val text = transcript(t)
        if (text.isBlank()) return
        scope.launch {
            try {
                val r = client.chat(SUMMARY_SYSTEM, listOf(ChatMsg(ChatMsg.USER, text, at = System.currentTimeMillis())), emptyList())
                val said = (r as? ApiResult.Ok)?.value?.content?.trim().orEmpty()
                if (said.isNotBlank()) {
                    val carry = said.take(ChatThreads.CARRY_MAX)
                    update { log -> log.copy(threads = log.threads.map { if (it.id == t.id) it.copy(carry = carry) else it }) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "交接摘要失败，保留本地兜底", e)
            }
        }
    }

    /** 给摘要模型看的这段时间线：更早的交接 + 最后 30 条（工具结果不要，太长）。 */
    private fun transcript(t: ChatThread): String = buildString {
        if (t.carry.isNotBlank()) append("更早的交接：").append(t.carry.take(ChatThreads.CARRY_MAX)).append('\n')
        append("这几轮：\n")
        t.messages
            .filter { it.role != ChatMsg.TOOL && !it.error && !it.hidden && it.content.isNotBlank() }
            .takeLast(30)
            .forEach { m ->
                append(if (m.role == ChatMsg.USER) "用户：" else "总控：")
                append(m.content.trim().take(500)).append('\n')
            }
    }.trim()

    /** 记一次“今天新开了一段”（按模式各记一份）；到上限返回 false。0 = 不限（也照样记数，界面能显示）。 */
    private fun markOpened(mode: ChatMode = _mode.value): Boolean {
        val day = ChatThreads.dayKey(System.currentTimeMillis(), store.zone)
        val perDay = threadsPerDay()
        val stored = prefs.getString(prefDay(mode), "") ?: ""
        val used = if (stored == day) prefs.getInt(prefCount(mode), 0) else 0
        if (!ChatThreads.canOpen(perDay, used)) return false
        prefs.edit().putString(prefDay(mode), day).putInt(prefCount(mode), used + 1).apply()
        return true
    }

    /** 每日新建计数的键：当下和整体各记一份，一边用光不影响另一边。 */
    private fun prefDay(mode: ChatMode) = "${PREF_DAY}_${mode.key}"

    private fun prefCount(mode: ChatMode) = "${PREF_COUNT}_${mode.key}"

    /**
     * 读一份整体快照（每一条用户消息开头读一次）。
     * 一手是网络读取：失败就记成「读取失败」，不阻塞这条消息，也不拿旧结果冒充。
     */
    private suspend fun readOverallSnapshot(): String {
        val external = try {
            OverallSnapshot.External(
                source = "一手",
                text = env.yishou(),
                readAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            OverallSnapshot.External(source = "一手", error = e.message ?: e.javaClass.simpleName, readAt = System.currentTimeMillis())
        }
        return try {
            OverallSnapshot.build(env, external)
        } catch (e: Exception) {
            Log.w(TAG, "整体快照生成失败，降级成简版", e)
            "（整体快照生成失败：${e.message ?: e.javaClass.simpleName}。只能按对话里的信息回答，缺的状态要说明未知。）"
        }
    }

    /**
     * 算这一轮该问的那一个，钉死成 [inquiryCache]，并记进 [asked]（冷却期内不再重问）。
     *
     * 用户这句话里提到了某件事（[focus]），就优先问那件事的缺口——他正在想它，
     * 这会儿问最答得出来。这是「追问」和「唠叨」的分界：问在点上，不是按清单念。
     */
    private fun pinInquiry(text: String) {
        val now = System.currentTimeMillis()
        val focus = try {
            com.zongkong.core.agent.Inquiry.focusOf(env, text)
        } catch (e: Exception) {
            ""
        }
        val plan = try {
            com.zongkong.core.agent.Inquiry.plan(env, now, store.zone, asked, focus)
        } catch (e: Exception) {
            Log.w(TAG, "追问诊断失败，这一轮不问", e)
            com.zongkong.core.agent.Inquiry.Plan(gap = null)
        }
        inquiryCache = com.zongkong.core.agent.Inquiry.prompt(plan)
        plan.gap?.let { g ->
            // 记下来：同一个口子在冷却期内不再问。同时写进对话记录，重启后还认得。
            asked = asked + (g.key to now)
            val line = encodeAsked(g.key, now)
            update { it.copy(asked = (it.asked.filterNot { a -> a.startsWith(g.key + SEP) } + line).takeLast(ASKED_MAX)) }
        }
    }

    /** 用户答完（或者这轮过去了）就清掉，下一轮重新算：数据可能已经变了。 */
    private fun clearInquiry() {
        inquiryCache = ""
    }

    // ---------- 读写 ----------

    private fun update(f: (ChatLog) -> ChatLog): Unit = synchronized(lock) {
        val next = f(_log.value)
        _log.value = next
        writeFile(next)
        Unit
    }

    private fun writeFile(log: ChatLog) {
        try {
            file.parentFile?.mkdirs()
            val text = ZkJson.encodeToString(ChatLog.serializer(), log)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) file.writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "写 chat.json 失败", e)
        }
    }

    /** 读 chat.json；旧格式（只有 messages）迁移成第一段对话，迁移前把旧文件备份成 chat.json.bak（只备份一次）。 */
    private fun load(): ChatLog {
        if (!file.exists()) return ChatLog()
        val raw = try {
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "读 chat.json 失败", e)
            return ChatLog()
        }
        val decoded = try {
            ZkJson.decodeFromString(ChatLog.serializer(), raw)
        } catch (e: Exception) {
            Log.e(TAG, "chat.json 读不了，备份后从空开始", e)
            try {
                file.copyTo(File(file.parentFile, "chat.json.broken.${System.currentTimeMillis()}.json"), overwrite = true)
            } catch (_: Exception) {
            }
            return ChatLog()
        }
        // 旧格式：只有 messages，没有 threads
        val old = legacyMessages(raw)
        if (old.isNotEmpty() && decoded.threads.isEmpty()) {
            val now = System.currentTimeMillis()
            val firstAt = old.firstOrNull { it.at > 0 }?.at ?: now
            val lastAt = old.lastOrNull { it.at > 0 }?.at ?: firstAt
            val t = ChatThreads.titled(
                ChatThread(
                    id = ChatThreads.newId(rnd),
                    createdAt = firstAt,
                    updatedAt = lastAt,
                    messages = old.takeLast(MAX_KEEP),
                    // 老数据没有模式：一律算「当下」
                    mode = ChatMode.NOW.key,
                ),
            )
            val migrated = decoded.copy(threads = listOf(t), activeId = t.id)
            backupOnce()
            writeFile(migrated)
            Log.i(TAG, "旧对话迁移成第一段：${old.size} 条")
            return migrated
        }
        // 老会话里的战役是全局一份：搬进它当时所在的段（一般就是当时那段），之后战役只看段自己的
        var migrated = decoded
        if (migrated.campaign.isNotBlank()) {
            val owner = migrated.threads.firstOrNull { it.id == migrated.activeId }
                ?: migrated.threads.lastOrNull { !it.closed }
                ?: migrated.threads.lastOrNull()
            if (owner != null && owner.campaign.isBlank()) {
                migrated = migrated.copy(
                    threads = migrated.threads.map {
                        if (it.id == owner.id) it.copy(campaign = migrated.campaign, campaignTopic = migrated.campaignTopic, campaignAt = migrated.campaignAt) else it
                    },
                    campaign = "",
                    campaignTopic = "",
                    campaignAt = 0,
                )
                Log.i(TAG, "旧战役 ${owner.campaign} 搬进了段 ${owner.id}")
            }
        }
        // 当前模式：一律「当下」（用户拍板）。activeId 必须指向当下的段
        val want = ChatThreads.activeOf(migrated.threads, ChatMode.DEFAULT)
        val needsFix = migrated.threads.isNotEmpty() &&
            (migrated.threads.none { it.id == migrated.activeId && it.chatMode == ChatMode.DEFAULT } || migrated.activeId != want?.id)
        if (needsFix) {
            val fixed = migrated.copy(activeId = want?.id.orEmpty())
            writeFile(fixed)
            return fixed
        }
        return migrated
    }

    private fun legacyMessages(raw: String): List<ChatMsg> = try {
        ZkJson.decodeFromString(LegacyChatLog.serializer(), raw).messages
    } catch (e: Exception) {
        emptyList()
    }

    /** 旧 chat.json 只备份一次（chat.json.bak 已存在就不再覆盖）。 */
    private fun backupOnce() {
        try {
            val bak = File(file.parentFile, "chat.json.bak")
            if (!bak.exists() && file.exists()) {
                file.copyTo(bak, overwrite = false)
                Log.i(TAG, "旧 chat.json 已备份：${bak.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "备份 chat.json 失败", e)
        }
    }

    // ---------- 「问过什么」的存取 ----------

    /**
     * `键|时刻` ↔ `Map<键, 时刻>`。
     *
     * 键里本来就可能带冒号（`project:ZK-123:为什么`），所以分隔符用竖线：键里不会出现它。
     */
    private fun encodeAsked(key: String, at: Long): String = key + SEP + at

    private fun decodeAsked(lines: List<String>): com.zongkong.core.agent.AskedGaps {
        val out = mutableMapOf<String, Long>()
        lines.forEach { line ->
            val i = line.lastIndexOf(SEP)
            if (i <= 0) return@forEach
            val key = line.substring(0, i)
            val at = line.substring(i + 1).toLongOrNull() ?: return@forEach
            // 同一个键只留最新的一条
            if ((out[key] ?: 0L) < at) out[key] = at
        }
        return out
    }

    companion object {
        private const val TAG = "ChatRepo"
        const val MAX_KEEP = 300

        /** SharedPreferences：今天已经新开了几段对话。 */
        private const val PREF_DAY = "threads_day"
        private const val PREF_COUNT = "threads_count"

        /** 收尾一段时让模型压缩成交接摘要（一次便宜调用；失败保留本地兜底）。 */
        private const val SUMMARY_SYSTEM =
            "把这轮对话压成不超过 120 字的交接摘要，只写结论、做到哪、下一步、没解决的。只输出摘要本身，不要寒暄、不要分点。"

        /** 主动询问的种类 → 档案里显示的名字。 */
        val ASK_KINDS = mapOf("absent" to "找不到人", "morning" to "早上", "noon" to "中午", "check" to "回访", "weekly" to "周复盘", "spot" to "抽查", "checkin" to "问候", "binge" to "连续娱乐", "unlock" to "解锁", "intake" to "自动收集", "think" to "自动谋划", "plan" to "自动统筹")

        /** 「问过什么」里键和时刻的分隔符；键里不会出现它（都是字母、数字、冒号、中文）。 */
        private const val SEP = "|"

        /** 最多记多少条「问过」：只用来算冷却，留太多没意义。 */
        private const val ASKED_MAX = 200
    }
}

/** 应用名 ↔ 包名：参谋说“把抖音加进拦截名单”时用。 */
object AppNames {
    private val aliases = mapOf(
        "抖音" to "com.ss.android.ugc.aweme", "快手" to "com.smile.gifmaker", "b站" to "tv.danmaku.bili", "B站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili", "小红书" to "com.xingin.xhs", "微博" to "com.sina.weibo", "知乎" to "com.zhihu.android",
        "贴吧" to "com.baidu.tieba", "王者荣耀" to "com.tencent.tmgp.sgame", "和平精英" to "com.tencent.tmgp.pubgmhd",
    )

    fun resolve(context: Context, name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val pm = context.packageManager
        if ('.' in n && installed(pm, n)) return n
        aliases[n]?.let { if (installed(pm, it)) return it }
        val launchers = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        } catch (e: Exception) {
            emptyList()
        }
        return launchers.map { it.activityInfo.applicationInfo }.firstOrNull { pm.getApplicationLabel(it).toString().equals(n, ignoreCase = true) }?.packageName
            ?: launchers.map { it.activityInfo.applicationInfo }.firstOrNull { pm.getApplicationLabel(it).toString().contains(n, ignoreCase = true) }?.packageName
            ?: aliases[n]
            ?: n.takeIf { '.' in it }
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun installed(pm: PackageManager, pkg: String) = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }
}
