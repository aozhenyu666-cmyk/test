package com.zongkong.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.zongkong.app.link.YishouClient
import com.zongkong.core.Config
import com.zongkong.core.Link
import com.zongkong.core.Status
import com.zongkong.core.ZkJson
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.AgentEnv
import com.zongkong.core.agent.ChatClient
import com.zongkong.core.agent.ChatMsg
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

/** 参谋的对话和它记住的事，存在 zk/chat.json。 */
@Serializable
data class ChatLog(
    val messages: List<ChatMsg> = emptyList(),
    val memory: List<String> = emptyList(),
)

/**
 * 参谋：对话在应用级的协程里跑，离开对话页也不中断；每一步（调用了什么工具、结果）实时写进对话。
 * 参谋要打开的页面放进 [route]，对话页看到就跳过去。
 */
class ChatRepo(private val context: Context, private val store: Store, private val actions: Actions) {
    private val file = File(File(context.filesDir, "zk"), "chat.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Any()

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
        override fun remember(fact: String) = update { it.copy(memory = (it.memory.filterNot { m -> m == fact } + fact).takeLast(30)) }
        override fun forget(fact: String): Boolean {
            val before = _log.value.memory
            update { it.copy(memory = it.memory.filterNot { m -> m == fact || m.contains(fact) }) }
            return _log.value.memory.size < before.size
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
                    update { it.copy(messages = (it.messages + m).takeLast(MAX_KEEP)) }
                    if (m.role == ChatMsg.TOOL && m.route.isNotBlank()) route.value = m.route
                    if (speak && m.role == ChatMsg.ASSISTANT && m.content.isNotBlank() && !m.error) com.zongkong.app.system.Speaker.speak(context, m.content)
                }, image = image, meta = if (image.isNotBlank()) photoMeta(image) else "")
            } catch (e: Exception) {
                Log.e(TAG, "参谋出错", e)
                update { it.copy(messages = it.messages + ChatMsg(ChatMsg.ASSISTANT, "出错了：${e.message ?: e.javaClass.simpleName}", at = System.currentTimeMillis(), error = true)) }
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
                        update { it.copy(messages = (it.messages + m).takeLast(MAX_KEEP)) }
                    }, hidden = true)
                    said = out.lastOrNull { it.role == ChatMsg.ASSISTANT && it.content.isNotBlank() && !it.error }?.content.orEmpty()
                    if (quiet && (said.isBlank() || said.trim().startsWith("无事"))) {
                        // 抽查没发现问题：这一轮整段藏起来，不打扰
                        update { log -> log.copy(messages = log.messages.map { m -> if (m in added) m.copy(hidden = true) else m }) }
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
                update { it.copy(messages = (it.messages + ChatMsg(ChatMsg.ASSISTANT, fallback, at = System.currentTimeMillis())).takeLast(MAX_KEEP)) }
            }
            val (body, _) = Agent.options(said)
            com.zongkong.app.system.Notifications.advisor(context, body)
            // 桌面宠物跳出来说；开口说话（睡觉时段不说）
            com.zongkong.app.system.Pet.say(context, body, 40, alert = kind == "binge" || kind == "absent")
            val quiet = com.zongkong.core.hq.Hq.quiet(cfg.hq, System.currentTimeMillis(), store.zone)
            if (!quiet && (cfg.voice.speakProactive || cfg.hq.speak)) com.zongkong.app.system.Voice.speak(context, body)
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

    /** 清空对话（记住的事保留）。 */
    fun clear() = update { it.copy(messages = emptyList()) }

    fun forgetAll() = update { it.copy(memory = emptyList()) }

    private fun update(f: (ChatLog) -> ChatLog): Unit = synchronized(lock) {
        val next = f(_log.value)
        _log.value = next
        val text = ZkJson.encodeToString(ChatLog.serializer(), next)
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) file.writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "写 chat.json 失败", e)
        }
        Unit
    }

    private fun load(): ChatLog = try {
        if (file.exists()) ZkJson.decodeFromString(ChatLog.serializer(), file.readText()) else ChatLog()
    } catch (e: Exception) {
        Log.e(TAG, "chat.json 读不了", e)
        ChatLog()
    }

    companion object {
        private const val TAG = "ChatRepo"
        const val MAX_KEEP = 300
        /** 主动询问的种类 → 档案里显示的名字。 */
        val ASK_KINDS = mapOf("absent" to "找不到人", "morning" to "早上", "noon" to "中午", "check" to "回访", "weekly" to "周复盘", "spot" to "抽查", "checkin" to "问候", "binge" to "连续娱乐")
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
