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
    val activeId: String = "",
    val memory: List<String> = emptyList(),
    /** 正在进行的战役（大统筹、大谋划、整理收集箱），空 = 没有。 */
    val campaign: String = "",
    val campaignTopic: String = "",
    val campaignAt: Long = 0,
    /** 打完的战役：标题、时刻、总结（最近 10 次）。 */
    val campaignLog: List<String> = emptyList(),
) {
    /** 当前这一段；activeId 对不上就退到最近没被收尾的一段。 */
    val activeThread: ChatThread?
        get() = threads.firstOrNull { it.id == activeId }
            ?: threads.lastOrNull { !it.closed }
            ?: threads.lastOrNull()

    /** 当前这一段的全部消息（界面、主动消息、头像气泡都还按这个读）。 */
    val messages: List<ChatMsg> get() = activeThread?.messages ?: emptyList()
}

/** 只改当前这一段。 */
internal fun ChatLog.mapActive(f: (ChatThread) -> ChatThread): ChatLog {
    val t = activeThread ?: return this
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
            val log = _log.value
            val c = com.zongkong.core.agent.Campaign.of(log.campaign) ?: return ""
            val now = System.currentTimeMillis()
            // 三小时没收尾就当放下了
            if (now - log.campaignAt > 3 * 3600_000L) return ""
            return c.prompt(log.campaignTopic, log.campaignAt, now)
        }
        override fun startCampaign(kind: String, topic: String): String {
            val c = com.zongkong.core.agent.Campaign.of(kind) ?: return "没有这种战役：$kind"
            update { it.copy(campaign = c.name, campaignTopic = topic.trim(), campaignAt = System.currentTimeMillis()) }
            return "「${c.title}」开始了。阶段说明已经放进你的系统提示，从阶段 1 开始。"
        }
        override fun endCampaign(summary: String): String {
            val log = _log.value
            val c = com.zongkong.core.agent.Campaign.of(log.campaign) ?: return "没有进行中的战役"
            val line = "${c.title} ${com.zongkong.core.boss.Timeline.hm(System.currentTimeMillis(), store.zone)}：${summary.trim()}"
            update { it.copy(campaign = "", campaignTopic = "", campaignAt = 0, campaignLog = (it.campaignLog + line).takeLast(10)) }
            return "「${c.title}」收尾了。"
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

    // ---------- 多段对话 ----------

    /** 现在的段列表，最近动过的在前。 */
    fun threads(): List<ChatThread> = ChatThreads.recentFirst(_log.value.threads)

    /** 当前这一段的 id（没有段时为空）。 */
    val activeId: String get() = _log.value.activeThread?.id.orEmpty()

    /** 当前这一段。 */
    fun activeThread(): ChatThread? = _log.value.activeThread

    /** 每天最多新开几段；0 = 不限（config.chat.threadsPerDay，默认 1）。 */
    fun threadsPerDay(): Int = store.config.value.chat.threadsPerDay

    /** 今天已经新开了几段（SharedPreferences 记，跨天归零）。 */
    fun openedToday(): Int {
        val day = ChatThreads.dayKey(System.currentTimeMillis(), store.zone)
        val stored = prefs.getString(PREF_DAY, "") ?: ""
        return if (stored == day) prefs.getInt(PREF_COUNT, 0) else 0
    }

    /** 今天还能新开几段；-1 = 不限。 */
    fun remainingToday(): Int = ChatThreads.remaining(threadsPerDay(), openedToday())

    /** 今天新建受限时给用户看的一句话。 */
    fun limitText(): String = ChatThreads.limitText(threadsPerDay(), openedToday())

    /** 新开一段。返回（成功了吗，给用户的一句话）。受每日上限限制。 */
    fun newThread(): Pair<Boolean, String> {
        val perDay = threadsPerDay()
        val opened = openedToday()
        if (!ChatThreads.canOpen(perDay, opened)) return false to ChatThreads.limitText(perDay, opened)
        if (!markOpened()) return false to ChatThreads.limitText(perDay, opened)
        val now = System.currentTimeMillis()
        // 上一段压出来的交接带过来，新的一开始就知道之前到哪了
        val carry = _log.value.threads.filter { it.carry.isNotBlank() }.maxByOrNull { it.updatedAt }?.carry.orEmpty()
        val t = ChatThread(id = ChatThreads.newId(rnd), createdAt = now, updatedAt = now, carry = carry)
        update { it.copy(threads = it.threads + t, activeId = t.id) }
        return true to "新开了一段。上一段的结论已经带着了。"
    }

    /** 关掉当前这段再新开一段。先查每日上限，开不了就不关（免得两截都空着）。 */
    fun closeAndNew(): Pair<Boolean, String> {
        val perDay = threadsPerDay()
        val opened = openedToday()
        if (!ChatThreads.canOpen(perDay, opened)) return false to ChatThreads.limitText(perDay, opened)
        closeThread(activeId)
        return newThread()
    }

    /** 打开某一段（收尾过的也能接着聊，打开即重新激活）。 */
    fun openThread(id: String) {
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

    /** 删掉一段；删的是当前这段就把当前段挪到最近一段上。 */
    fun deleteThread(id: String) {
        update { log ->
            val left = log.threads.filterNot { it.id == id }
            if (left.size == log.threads.size) return@update log
            val active = if (log.activeThread?.id == id) {
                (left.lastOrNull { !it.closed } ?: left.lastOrNull())?.id.orEmpty()
            } else log.activeThread?.id.orEmpty()
            log.copy(threads = left, activeId = active)
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
        job = scope.launch {
            try {
                val history = _log.value.messages
                val speak = store.config.value.hq.speak && store.hq.value.active != null
                agent.reply(history, t.ifBlank { "看看这张图" }, { m ->
                    append(listOf(m))
                    if (m.role == ChatMsg.TOOL && m.route.isNotBlank()) route.value = m.route
                    if (speak && m.role == ChatMsg.ASSISTANT && m.content.isNotBlank() && !m.error) com.zongkong.app.system.Speaker.speak(context, m.content)
                }, image = image, meta = if (image.isNotBlank()) photoMeta(image) else "")
            } catch (e: Exception) {
                Log.e(TAG, "参谋出错", e)
                append(listOf(ChatMsg(ChatMsg.ASSISTANT, "出错了：${e.message ?: e.javaClass.simpleName}", at = System.currentTimeMillis(), error = true)))
            } finally {
                _busy.value = false
                if (echo) echoReply()
                com.zongkong.app.system.PetWidget.refresh(context)
            }
        }
    }

    /** 把最后一句回答送回桌面宠物 / 通知，并念出来。 */
    private fun echoReply() {
        val reply = _log.value.messages.lastOrNull { it.role == ChatMsg.ASSISTANT && it.content.isNotBlank() }?.content ?: return
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
        scope.launch {
            var said = ""
            val cfg = store.config.value
            if (cfg.ai.ready) {
                try {
                    val added = mutableListOf<ChatMsg>()
                    val out = agent.reply(_log.value.messages, instruction, { m ->
                        added += m
                        append(listOf(m))
                    }, hidden = true)
                    said = out.lastOrNull { it.role == ChatMsg.ASSISTANT && it.content.isNotBlank() && !it.error }?.content.orEmpty()
                    if (quiet && (said.isBlank() || said.trim().startsWith("无事"))) {
                        // 抽查没发现问题：这一轮整段藏起来，不打扰
                        update { log -> log.mapActive { t -> t.copy(messages = t.messages.map { m -> if (m in added) m.copy(hidden = true) else m }) } }
                        Log.i(TAG, "抽查无事：$kind")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "总控定时消息失败：$kind", e)
                } finally {
                    _busy.value = false
                }
            } else {
                _busy.value = false
            }
            if (said.isBlank()) {
                said = fallback
                append(listOf(ChatMsg(ChatMsg.ASSISTANT, fallback, at = System.currentTimeMillis())))
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

    /** 清空当前这一段的对话（其他段和记住的事都保留）。 */
    fun clear() = update { it.mapActive { t -> t.copy(messages = emptyList()) } }

    fun forgetAll() = update { it.copy(memory = emptyList()) }

    /** 开一场战役：记下来（系统提示里一直带着阶段说明），再让总控开口。没接 AI 返回 false。 */
    fun begin(c: com.zongkong.core.agent.Campaign, topic: String = "", material: String = ""): Boolean {
        if (!store.config.value.ai.ready || _busy.value) return false
        update { it.copy(campaign = c.name, campaignTopic = topic.trim(), campaignAt = System.currentTimeMillis()) }
        store.touch()
        _busy.value = true
        job = scope.launch {
            try {
                agent.reply(_log.value.messages, c.opening(topic, material), { m ->
                    append(listOf(m))
                    if (m.role == ChatMsg.TOOL && m.route.isNotBlank()) route.value = m.route
                }, hidden = true)
            } catch (e: Exception) {
                append(listOf(ChatMsg(ChatMsg.ASSISTANT, "出错了：${e.message ?: e.javaClass.simpleName}", at = System.currentTimeMillis(), error = true)))
            } finally {
                _busy.value = false
            }
        }
        return true
    }

    /** 手动结束战役（对话页顶上的“收工”）。 */
    fun stopCampaign() {
        val c = com.zongkong.core.agent.Campaign.of(_log.value.campaign) ?: return
        update { it.copy(campaign = "", campaignTopic = "", campaignAt = 0, campaignLog = (it.campaignLog + "${c.title}：中途停下").takeLast(10)) }
    }

    /** 别处来的一句话（比如电脑上的总控发来的），放进当前这一段。 */
    fun note(text: String) {
        append(listOf(ChatMsg(ChatMsg.ASSISTANT, text, at = System.currentTimeMillis())))
    }

    // ---------- 往当前段追加消息 / 满了自动换段 ----------

    /**
     * 往当前这一段加消息。一段满 60 条后，在下一轮“用户的话”开头收尾换段（不把一轮问答劈成两半）：
     * 旧段压成交接摘要收尾，新段从这句话开始接着走。到每日上限就不新开，把交接并进当前段、裁到最近 60 条。
     */
    private fun append(msgs: List<ChatMsg>) {
        if (msgs.isEmpty()) return
        val now = System.currentTimeMillis()
        var toSummarize: ChatThread? = null
        update { log0 ->
            val (log, current) = ensureActive(log0, now)
            if (msgs.first().role == ChatMsg.USER && ChatThreads.overflows(current, msgs)) {
                val carry = ChatThreads.mergeCarry(current.carry, ChatThreads.carryOf(current))
                if (markOpened()) {
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

    /** 当前这一段；一段都没有（新装、或都删光了）就建第一段（不占每日额度）。 */
    private fun ensureActive(log: ChatLog, now: Long): Pair<ChatLog, ChatThread> {
        log.activeThread?.let { return log to it }
        val t = ChatThread(id = ChatThreads.newId(rnd), createdAt = now, updatedAt = now)
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

    /** 记一次“今天新开了一段”；到上限返回 false。0 = 不限（也照样记数，界面能显示）。 */
    private fun markOpened(): Boolean {
        val day = ChatThreads.dayKey(System.currentTimeMillis(), store.zone)
        val perDay = threadsPerDay()
        val stored = prefs.getString(PREF_DAY, "") ?: ""
        val used = if (stored == day) prefs.getInt(PREF_COUNT, 0) else 0
        if (!ChatThreads.canOpen(perDay, used)) return false
        prefs.edit().putString(PREF_DAY, day).putInt(PREF_COUNT, used + 1).apply()
        return true
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
                ),
            )
            val migrated = decoded.copy(threads = listOf(t), activeId = t.id)
            backupOnce()
            writeFile(migrated)
            Log.i(TAG, "旧对话迁移成第一段：${old.size} 条")
            return migrated
        }
        // 结构在但当前段对不上：修一下再写回
        if (decoded.threads.isNotEmpty() && decoded.threads.none { it.id == decoded.activeId }) {
            val fixed = decoded.copy(activeId = (decoded.threads.lastOrNull { !it.closed } ?: decoded.threads.last()).id)
            writeFile(fixed)
            return fixed
        }
        return decoded
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
