package com.zongkong.app.data

import android.content.Context
import android.util.Log
import com.zongkong.core.ApiResult
import com.zongkong.core.DayClock
import com.zongkong.core.YishouLedger
import com.zongkong.core.ZkJson
import com.zongkong.core.agent.ChatClient
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.buddy.Buddy
import com.zongkong.core.buddy.BuddyBook
import com.zongkong.core.buddy.BuddyConfig
import com.zongkong.core.buddy.Decision
import com.zongkong.core.buddy.Signals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * 伙伴的存储和说话。规则在 [Buddy]（纯逻辑）里；这里只管：读写 buddy.json、收集此刻的情况、调模型、发通知。
 * 和总控的对话完全分开（各存各的），只读总控的状况当背景；它说的【告诉总控】会作为一条结论记进总控。
 */
class BuddyRepo(private val context: Context, private val store: Store, private val actions: Actions) {
    private val file = File(File(context.filesDir, "zk"), "buddy.json")
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = ChatClient({ store.config.value.ai })

    private val _book = MutableStateFlow(load())
    val book: StateFlow<BuddyBook> = _book.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 最近一次判定为什么没开口（界面上显示，方便你知道它为什么安静）。 */
    @Volatile var lastWhyQuiet: String = ""
        private set

    fun update(f: (BuddyBook) -> BuddyBook): BuddyBook = synchronized(lock) {
        val next = f(_book.value)
        if (next != _book.value) {
            _book.value = next
            val text = ZkJson.encodeToString(BuddyBook.serializer(), next)
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) file.writeText(text)
            }.onFailure { Log.w(TAG, "存伙伴失败", it) }
        }
        next
    }

    fun setConfig(f: (BuddyConfig) -> BuddyConfig) = update { it.copy(config = f(it.config)) }

    fun forget(fact: String) = update { it.copy(facts = it.facts - fact) }

    fun clear() = update { it.copy(messages = emptyList(), unanswered = 0) }

    // ---------- 主动开口 ----------

    /** 看一眼要不要开口（后台线程上调用；自己会节流）。返回这次说的话，没说返回 null。 */
    suspend fun tick(signals: Signals, roll: Double = kotlin.random.Random.nextDouble()): String? {
        val b = _book.value
        Buddy.blocked(b, signals)?.let { lastWhyQuiet = it; return null }
        if (!store.config.value.ai.ready) { lastWhyQuiet = "还没配置 AI 接口"; return null }
        if (_busy.value) return null
        val d = Buddy.decide(b, signals, roll) ?: run { lastWhyQuiet = "掷了骰子，这次没开口"; return null }
        return speak(d, signals)
    }

    /**
     * 后台随手调用（无障碍服务每 20 秒、常驻服务每分钟）：每 5 分钟真正看一次；刚解锁 30 秒后额外看一次。
     * 自己开协程，不卡调用方。
     */
    fun maybeTick(now: Long = System.currentTimeMillis()) {
        // 外接大脑：每次都看一眼有没有新事件（自己去重），有就推给 DSH
        runCatching { bridgeTick(now) }.onFailure { Log.w(TAG, "外接大脑出错", it) }
        val cfgB = _book.value.bridge
        if (cfgB.enabled && cfgB.muteLocal) return
        val afterUnlock = Live.unlockedAt > Live.lastTick && now - Live.unlockedAt >= 30_000L
        if (now - Live.lastTick < TICK_MS && !afterUnlock) return
        Live.lastTick = now
        scope.launch { runCatching { tick(signals(now)) }.onFailure { Log.w(TAG, "伙伴检查出错", it) } }
    }

    // ---------- 外接大脑（DSH） ----------

    @Volatile private var bridgeState = com.zongkong.core.buddy.BridgeState()
    private val http by lazy { com.zongkong.core.Net.client(10) }

    /** 最近一次推送的结果（界面显示）。 */
    @Volatile var lastBridge: String = ""
        private set

    fun setBridge(f: (com.zongkong.core.buddy.BridgeConfig) -> com.zongkong.core.buddy.BridgeConfig) = update { it.copy(bridge = f(it.bridge)) }

    private fun bridgeTick(now: Long) {
        val cfg = _book.value.bridge
        if (!cfg.enabled || cfg.url.isBlank()) return
        val snap = snapshot(now)
        val (events, next) = com.zongkong.core.buddy.Bridge.events(bridgeState, snap)
        bridgeState = next
        events.forEach { post(cfg, it, snap) }
    }

    /** 发一条测试事件，看 DSH 那边收不收得到。 */
    fun testBridge() {
        val cfg = _book.value.bridge
        if (cfg.url.isBlank()) { lastBridge = "还没填地址"; return }
        val snap = snapshot(System.currentTimeMillis())
        post(cfg, com.zongkong.core.buddy.BridgeEvent(com.zongkong.core.buddy.Bridge.TEST, "总控发来的测试事件：连上了就随便跟他打个招呼"), snap)
    }

    private fun post(cfg: com.zongkong.core.buddy.BridgeConfig, e: com.zongkong.core.buddy.BridgeEvent, snap: com.zongkong.core.buddy.Snapshot) {
        val url = cfg.url.trim()
        if (!url.startsWith("https://")) {
            // 安卓默认不让 App 走明文 http；而且手机在外面用流量时也够不着家里电脑的局域网地址
            lastBridge = "${clock()} 地址要用 https://（用 Tailscale 或 Cloudflare Tunnel 把 DSH 暴露成 https，见部署报告）"
            return
        }
        scope.launch {
            lastBridge = try {
                val body = com.zongkong.core.buddy.Bridge.payload(e, snap).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = okhttp3.Request.Builder().url(cfg.url.trim()).post(body)
                    .apply { if (cfg.token.isNotBlank()) header("Authorization", "Bearer ${cfg.token.trim()}") }
                    .build()
                http.newCall(req).execute().use { r -> "${clock()} ${e.kind} → ${r.code}" + if (r.isSuccessful) "" else "（${r.message}）" }
            } catch (ex: Exception) {
                "${clock()} ${e.kind} → 没发出去：${ex.message ?: ex.javaClass.simpleName}"
            }
        }
    }

    private fun clock(): String = java.time.LocalTime.now(store.zone).withNano(0).toString()

    /** 外接大脑要的「此刻」。 */
    private fun snapshot(now: Long): com.zongkong.core.buddy.Snapshot {
        val s = signals(now)
        val zone = store.zone
        val work = store.work.value
        val status = runCatching { store.status(now) }.getOrNull()
        val from = DayClock.startOf(DayClock.date(now, zone), zone)
        val today = YishouLedger.between(store.yishouBook.value, from, now + 1)
        return com.zongkong.core.buddy.Snapshot(
            now = now,
            pkg = Live.fgPkg.orEmpty(),
            appName = s.appName,
            since = Live.fgSince,
            play = s.appIsPlay,
            unlockedAt = Live.unlockedAt,
            strict = status?.strict == true,
            headline = status?.let { st -> st.reasons.firstOrNull()?.text ?: st.headline }.orEmpty(),
            mainTask = runCatching { com.zongkong.core.plan.Plans.mainAction(store.plans.value, work, now, zone)?.title }.getOrNull().orEmpty(),
            step = runCatching { com.zongkong.core.work.Focus.current(work, now, zone, store.mainKey(now)).action?.title }.getOrNull().orEmpty(),
            yishouLine = if (today.isEmpty()) "" else YishouLedger.line(today),
            missAt = s.missAt,
            miss = s.miss,
            screenOn = s.interactive,
        )
    }

    /** 不掷骰子，马上开口（界面上的「让它说一句」）。 */
    suspend fun nudge(signals: Signals): String? = speak(Decision(com.zongkong.core.buddy.Topic.IDLE, "你叫它说一句"), signals)

    private suspend fun speak(d: Decision, s: Signals): String? {
        _busy.value = true
        try {
            val b = _book.value
            val history = Buddy.history(b).map { ChatMsg(if (it.role == com.zongkong.core.buddy.BuddyMsg.USER) ChatMsg.USER else ChatMsg.ASSISTANT, it.text, it.at) } +
                ChatMsg(ChatMsg.USER, Buddy.opener(d, s), s.now, hidden = true)
            val out = when (val r = client.chat(Buddy.system(b.config, b.facts, scene(s)), history, emptyList())) {
                is ApiResult.Ok -> r.value.content
                is ApiResult.Err -> { lastWhyQuiet = "模型没回：${r.message}"; return null }
            }
            val p = Buddy.parse(out)
            if (p.text.isBlank()) return null
            update { Buddy.remember(Buddy.spoke(it, d, p.text, System.currentTimeMillis(), s.missAt), p.remember) }
            relay(p.toZongkong)
            lastWhyQuiet = ""
            com.zongkong.app.system.Notifications.buddy(context, b.config.name, p.text)
            return p.text
        } catch (e: Exception) {
            Log.w(TAG, "伙伴开口失败", e)
            return null
        } finally {
            _busy.value = false
        }
    }

    // ---------- 你说话 ----------

    /** 你回了一句（在伙伴页或通知里）。[notify] = 回答也发到通知里（从通知回复时）。 */
    fun send(text: String, notify: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        update { Buddy.heard(it, t, System.currentTimeMillis()) }
        scope.launch {
            _busy.value = true
            try {
                val b = _book.value
                val s = signals()
                val history = Buddy.history(b).map { ChatMsg(if (it.role == com.zongkong.core.buddy.BuddyMsg.USER) ChatMsg.USER else ChatMsg.ASSISTANT, it.text, it.at) }
                val reply = when (val r = client.chat(Buddy.system(b.config, b.facts, scene(s)), history, emptyList())) {
                    is ApiResult.Ok -> r.value.content
                    is ApiResult.Err -> "（没连上模型：${r.message}）"
                }
                val p = Buddy.parse(reply)
                val shown = p.text.ifBlank { "嗯。" }
                update { Buddy.remember(Buddy.replied(it, shown, System.currentTimeMillis()), p.remember) }
                relay(p.toZongkong)
                if (notify) com.zongkong.app.system.Notifications.buddy(context, b.config.name, shown)
            } catch (e: Exception) {
                Log.w(TAG, "伙伴回话失败", e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** 【告诉总控】：作为一条结论记进总控（参谋和 Notion 都能看到），不改任何规则。 */
    private fun relay(items: List<String>) {
        items.forEach { msg ->
            runCatching { actions.work.addConclusion("伙伴转达：$msg", "") }
                .onFailure { Log.w(TAG, "转告总控失败", it) }
        }
    }

    // ---------- 此刻的情况 ----------

    /** 收集此刻的情况。前台应用、解锁时刻由无障碍服务实时写进 [Live]。 */
    fun signals(now: Long = System.currentTimeMillis()): Signals {
        val zone = store.zone
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        val fg = Live.fgPkg
        val fgMin = if (Live.fgSince > 0) ((now - Live.fgSince) / 60_000).toInt() else 0
        val lastChat = runCatching {
            (context.applicationContext as com.zongkong.app.ZkApp).chat.activeThread()?.messages
                ?.lastOrNull { it.role == ChatMsg.USER && !it.hidden }?.at ?: 0L
        }.getOrDefault(0L)
        val miss = store.yishouBook.value.entries.lastOrNull { it.miss && now - it.at < 2 * 3_600_000L }
        return Signals(
            now = now,
            zone = zone,
            interactive = pm?.isInteractive != false,
            hqActive = store.hq.value.active != null,
            lastZongkongChatAt = lastChat,
            unlockedAt = Live.unlockedAt,
            appName = fg?.let { AppNames.label(context, it) }.orEmpty(),
            appMinutes = if (fg == null || fg == context.packageName) 0 else fgMin,
            appIsPlay = fg != null && fg in store.config.value.blocked,
            missAt = miss?.at ?: 0L,
            miss = miss?.let { "（${it.name}）${it.what.take(80)}" + if (it.said.isNotBlank()) "｜他答：${it.said.take(40)}" else "" }.orEmpty(),
        )
    }

    /** 给模型的背景：时间、今天想做的事、此刻在干嘛、一手今天的账。 */
    private fun scene(s: Signals): String = buildString {
        val t = java.time.Instant.ofEpochMilli(s.now).atZone(s.zone)
        append("现在 ").append(String.format("%02d:%02d", t.hour, t.minute)).append('\n')
        runCatching {
            val work = store.work.value
            com.zongkong.core.plan.Plans.mainAction(store.plans.value, work, s.now, s.zone)?.let { append("他今天想做成的事：").append(it.title).append('\n') }
            com.zongkong.core.work.Focus.current(work, s.now, s.zone, store.mainKey(s.now)).action?.let { append("手上这一步：").append(it.title).append('\n') }
        }
        if (s.appName.isNotBlank() && s.appMinutes > 0) append("他此刻在「").append(s.appName).append("」，连续 ").append(s.appMinutes).append(" 分钟\n")
        val from = DayClock.startOf(DayClock.date(s.now, s.zone), s.zone)
        val today = YishouLedger.between(store.yishouBook.value, from, s.now + 1)
        if (today.isNotEmpty()) append("他今天在一手刷题：").append(YishouLedger.line(today)).append('\n')
        runCatching { if (store.status(s.now).strict) append("（总控现在在严管他，你不用提这个，也别替总控说话）\n") }
    }

    private fun load(): BuddyBook = try {
        if (file.exists()) ZkJson.decodeFromString(BuddyBook.serializer(), file.readText()) else BuddyBook()
    } catch (e: Exception) {
        Log.e(TAG, "buddy.json 读不了", e)
        BuddyBook()
    }

    /** 无障碍服务实时写进来的：前台应用、解锁时刻。进程重启后为空，伙伴就当不知道。 */
    object Live {
        @Volatile var fgPkg: String? = null
        @Volatile var fgSince: Long = 0
        @Volatile var unlockedAt: Long = 0
        @Volatile var lastTick: Long = 0
    }

    companion object {
        private const val TAG = "Buddy"
        /** 没事由时多久看一眼（有新解锁时提前看）。 */
        const val TICK_MS = 5 * 60_000L
    }
}
