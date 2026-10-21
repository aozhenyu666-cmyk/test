package com.moling.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.moling.app.*
import com.moling.app.brief.Brief
import com.moling.app.ui.Speaker
import com.moling.core.Brain
import com.moling.core.BrainFacts
import com.moling.core.ProposalState
import com.moling.core.flow.Cause
import com.moling.core.flow.Channel
import com.moling.core.flow.ChannelKind
import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.mind.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeFormatter

/** 只负责 Android 呈现和用户输入；执行、授权、推理都交回 BrainHost。 */
object Presence {
    data class Question(val channel: Channel, val key: String, val until: Long, val followup: Boolean = false)
    val question = MutableStateFlow<Question?>(null)
    val session = MutableStateFlow<ActiveSession?>(null)
    val card = MutableStateFlow<Card?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private val sessionGate = Mutex()
    private var initialized = false
    private var lastCategory = ""
    private var cardUntil = 0L
    private var resumeJob: Job? = null
    private var sessionMonitor: Job? = null
    private var concluding = false
    private var promptedSession = ""
    private fun store(context: Context) = PresenceStore(context)
    fun init(context: Context) {
        if (!initialized) {
            session.value = store(context).session(); initialized = true
            if (session.value != null) monitorSession(context.applicationContext)
        }
    }
    private fun monitorSession(context: Context) {
        sessionMonitor?.cancel()
        sessionMonitor = scope.launch {
            while (session.value != null) {
                delay(2000)
                try {
                    val active = session.value
                    if (active != null && System.currentTimeMillis() - active.startedAt >= 3 * 3_600_000L) finish(context, "", "超时没收线", active.opening.sessionId)
                    render(context)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error(context, "会话监测失败：${e.javaClass.simpleName}") }
            }
        }
    }
    fun error(context: Context, message: String) {
        store(context).prefs.edit().putString("last_error", message).apply()
        android.util.Log.w("Presence", message)
    }
    fun startSession(context: Context, opening: Opening, startedAt: Long = System.currentTimeMillis()) {
        init(context)
        if (opening.actor == Actor.NONE) return
        // 调用方只在演员成功打开后进入此函数。
        scope.launch {
            if (session.value != null) finish(context, "", "未收线")
            val next = ActiveSession(opening, startedAt)
            session.value = next; store(context).session(next)
            promptedSession = ""
            monitorSession(context.applicationContext)
            render(context)
        }
    }
    fun nextQuestion(context: Context) {
        val s = session.value ?: return
        val next = s.copy(index = (s.index + 1) % s.opening.cards.size.coerceAtLeast(1))
        session.value = next; store(context).session(next)
    }
    fun currentSessionCard(): Card? = session.value?.let { it.opening.cards.getOrNull(it.index) }
    fun onCard(context: Context, next: Card): Boolean {
        val show = !MainActivity.visible && store(context).enabled("overlay") && Settings.canDrawOverlays(context)
        scope.launch {
            init(context)
            BrainHost.setDirectorCard(next)
            if (!MainActivity.visible) {
                if (card.value?.id != next.id) { card.value = next; cardUntil = System.currentTimeMillis() + 600_000 }
                render(context)
            }
        }
        return show
    }
    suspend fun tick(context: Context, now: Long) = withContext(Dispatchers.Main.immediate) { gate.withLock {
        init(context)
        try {
            session.value?.let { s ->
                if (now - s.startedAt >= 3 * 3_600_000L) finish(context, "", "超时没收线")
                else updateTiming(context, now)
            }
            if (cardUntil > 0 && now >= cardUntil) expireCard(context)
            if (question.value?.until?.let { now >= it } == true) timeoutQuestion(context)
            maybeQuestion(context, now)
            remind(context, now)
            render(context)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error(context, "在你身边心跳失败：${e.javaClass.simpleName}") }
    }
    }
    private fun updateTiming(context: Context, now: Long) {
        val s = session.value ?: return
        if (!Usage.allowed(context)) { error(context, "使用情况访问未开：会话前台时长未知"); return }
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val events = usm.queryEvents(s.startedAt, now)
        val event = UsageEvents.Event()
        val samples = mutableListOf<SessionTiming.Event>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> samples += SessionTiming.Event(event.timeStamp, event.packageName.orEmpty())
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (event.packageName == s.opening.actor.pkg) samples += SessionTiming.Event(event.timeStamp, "")
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> samples += SessionTiming.Event(event.timeStamp, "")
            }
        }
        val timing = SessionTiming.measure(s.opening.actor.pkg, s.startedAt, now, samples)
        val next = s.copy(presentMs = timing.presentMs, leaves = timing.leaves)
        session.value = next; store(context).session(next)
    }
    private suspend fun maybeQuestion(context: Context, now: Long) {
        val prefs = store(context).prefs
        val fg = withContext(Dispatchers.IO) { PresenceUsage.foreground(context, now) }
        val status = BrainHost.state.value.lastStatus
        val category = BrainFacts.category(fg.orEmpty(), status)
        val entered = category == "ENTERTAINMENT" && lastCategory != category
        lastCategory = category
        if (!store(context).enabled("question") || !Settings.canDrawOverlays(context)) return
        val plan = BrainHost.state.value.flow?.takeIf { it.day == Brain.calendarDay(now) && it.confirmed } ?: return
        val minute = Flow.minuteOf(now)
        val c = Flow.current(plan, minute)?.takeIf { minute in it.startMin until it.endMin && it.kind in listOf(ChannelKind.MAIN, ChannelKind.SIDE, ChannelKind.SPRING) } ?: return
        // 状态未知时保守，不在可能的专注/静默期间弹问。
        if (status == null || now - status.at > 120_000 || status.focus || status.hqActive || status.quiet) return
        val key = "${plan.day}:${c.id}"
        val due = prefs.getLong("due", 0)
        val follow = due > 0 && now >= due && prefs.getString("due_key", "") == key
        if (follow && category != "ENTERTAINMENT") { prefs.edit().remove("due").apply(); return }
        if (category != "ENTERTAINMENT" || question.value != null) return
        if (!follow && (!entered || now - prefs.getLong("asked:$key", 0) < 1_200_000)) return
        prefs.edit().putLong("asked:$key", now).remove("due").apply()
        if (!follow) prefs.edit().putInt("extensions:$key", 0).apply()
        count(context, "asked")
        question.value = Question(c, key, now + 30_000, follow)
    }
    suspend fun choose(context: Context, choice: String) {
        val q = question.value ?: return
        question.value = null
        count(context, choice)
        // 他回应了：这次偏离的升级阶梯停下
        BrainHost.answeredLater()
        val now = System.currentTimeMillis()
        when (choice) {
            "A" -> {
                agree(context, BrainHost.suggest(ZkCommand.StartFocus(15, q.channel.title, "先答一问：先做 15 分钟"), now), now)
                open(context, 1)
            }
            "B" -> { BrainHost.reroute(Cause.STUCK, now); open(context, 0, "我卡在") }
            "C" -> {
                val prefs = store(context).prefs
                val times = prefs.getInt("extensions:${q.key}", 0)
                if (q.followup && times >= 2) return
                agree(context, BrainHost.suggest(ZkCommand.Commit("玩 10 分钟就回到「${q.channel.title}」", now + 600_000, "看前台应用", "先答一问：先玩 10 分钟"), now), now)
                prefs.edit().putLong("due", now + 600_000).putString("due_key", q.key)
                    .putInt("extensions:${q.key}", if (q.followup) times + 1 else times).apply()
            }
        }
        render(context)
    }
    fun canExtend(context: Context): Boolean = question.value?.let { !it.followup || store(context).prefs.getInt("extensions:${it.key}", 0) < 2 } ?: false
    private suspend fun agree(context: Context, proposal: com.moling.core.Proposal?, now: Long) {
        if (proposal == null) { error(context, "先答一问：未生成提议"); return }
        if (proposal.state == ProposalState.PENDING) {
            val outcome = BrainHost.accept(proposal.id, now)
            if (!outcome.ok) error(context, outcome.message)
        } else if (proposal.state == ProposalState.FAILED) error(context, proposal.receipt)
    }
    fun timeoutQuestion(context: Context) {
        if (question.value == null) return
        question.value = null; count(context, "missed")
        error(context, "先答一问没回")
        scope.launch { render(context) }
    }
    private fun count(context: Context, field: String) {
        val prefs = store(context).prefs
        val key = "${Brain.calendarDay(System.currentTimeMillis())}:$field"
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }
    suspend fun expireCard(context: Context) {
        val c = card.value ?: return
        card.value = null; cardUntil = 0
        BrainHost.setDirectorCard(BrainHost.reply(Reply.Dismissed(c.id, System.currentTimeMillis(), timeout = true)))
        render(context)
    }
    suspend fun respond(context: Context, reply: Reply) {
        val original = card.value ?: currentSessionCard()
        val next = BrainHost.reply(reply)
        if (reply is Reply.Stuck && next != null) session.value?.let { s ->
            val cards = s.opening.cards.toMutableList()
            if (s.index in cards.indices) { cards[s.index] = next; val changed = s.copy(opening = s.opening.copy(cards = cards)); session.value = changed; store(context).session(changed) }
        }
        BrainHost.setDirectorCard(next); card.value = next
        cardUntil = if (next == null) 0 else System.currentTimeMillis() + 600_000
        if (reply is Reply.Pick && reply.key == CHOICE_GO) {
            open(context, 1) // 开场白仍需用户复制并点打开演员。
            val op = BrainHost.opening(next?.actor ?: original?.actor ?: Actor.GEMINI_LIVE, next?.id ?: reply.cardId)
            if (op != null) PresenceActivity.open(context, "opening", opening = op)
        } else if (reply is Reply.Pick && reply.key == CHOICE_FIX) {
            runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        render(context)
    }
    suspend fun finish(context: Context, text: String, handoff: String, expectedSession: String? = null): Boolean = withContext(Dispatchers.Main.immediate) { sessionGate.withLock {
        val s = session.value ?: return@withLock false
        if (expectedSession != null && s.opening.sessionId != expectedSession) return@withLock false
        concluding = true
        try {
            updateTiming(context, System.currentTimeMillis())
            val last = session.value ?: s
            val next = BrainHost.session(SessionLog(last.opening.sessionId, last.opening.actor, last.startedAt, System.currentTimeMillis(), last.presentMs, last.leaves, handoff, text.isNotBlank(), text))
            BrainHost.setDirectorCard(next)
            store(context).prefs.edit().putString("last_session", "收线：${last.opening.actor.label} · ${if (text.isBlank()) "没收线" else "已交导演"}").apply()
            session.value = null; store(context).session(null)
            card.value = null; cardUntil = 0
            OverlayService.hide(context); PresenceNotices.cancel(context, PresenceNotices.SESSION)
            true
        } finally { concluding = false }
    } }
    /** 用户已同意的操作归 App 生命周期，不随悬浮窗口关闭而取消。 */
    fun perform(context: Context, block: suspend () -> Unit, done: () -> Unit = {}) {
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(context, "回应失败：${e.javaClass.simpleName}") }
            finally { done() }
        }
    }
    fun onResume(activity: ComponentActivity) {
        init(activity)
        if (concluding || session.value == null || !store(activity).enabled("handoff")) return
        if (promptedSession == session.value?.opening?.sessionId) return
        resumeJob?.cancel()
        resumeJob = activity.lifecycleScope.launch {
            delay(350)
            if (!activity.hasWindowFocus() || session.value == null) return@launch
            val s = session.value ?: return@launch
            val text = runCatching {
                val clip = activity.getSystemService(ClipboardManager::class.java)?.primaryClip
                val raw = clip?.getItemAt(0)?.text?.toString().orEmpty()
                // 开场白也是文本，时间不晚于会话开始的剪贴板不能误当收线。
                raw.takeIf { clip != null && clip.description.timestamp > s.startedAt && (it.contains("收线") || it.contains("下一步") || it.length >= 40) }
            }.getOrNull()
            promptedSession = s.opening.sessionId
            PresenceActivity.open(activity, "conclude", text = text?.take(12_000).orEmpty())
        }
    }
    fun open(context: Context, tab: Int, draft: String = "") {
        if (draft.isNotEmpty()) draftText.value = draft.take(500)
        runCatching { context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            .onFailure { error(context, "墨灵页面未打开：${it.javaClass.simpleName}") }
    }
    val draftText = MutableStateFlow<String?>(null)
    suspend fun unlock(context: Context) = withContext(Dispatchers.Main.immediate) { gate.withLock {
        init(context)
        val now = System.currentTimeMillis(); val prefs = store(context).prefs
        val day = Brain.calendarDay(now)
        val minute = Flow.minuteOf(now)
        if (!store(context).enabled("brief") || !BrainHost.settingsStore.load().speak || minute < 360 || quiet(now) || prefs.getString("brief_day", "") == day) return@withLock
        val state = BrainHost.state.value; val mind = BrainHost.directorSnapshot()
        val text = Brief.text(mind.diary.lastOrNull()?.text.orEmpty(), state.flow?.takeIf { it.day == day }?.intent.orEmpty(), mind.baton?.nextAsk.orEmpty(), state.predictions.firstOrNull { Brain.calendarDay(it.madeAt) == day }?.text.orEmpty())
        prefs.edit().putString("brief_day", day).apply()
        Speaker.prepare(context); Speaker.speak(text)
        PresenceNotices.show(context, 21501, "早上好", text)
    }
    }
    private fun quiet(now: Long): Boolean {
        val m = Flow.minuteOf(now)
        return BrainHost.state.value.lastStatus?.quiet == true || m >= 1410 || m < 360
    }
    private fun remind(context: Context, now: Long) {
        if (!store(context).enabled("reminders") || quiet(now)) return
        val plan = BrainHost.state.value.flow?.takeIf { it.day == Brain.calendarDay(now) } ?: return
        val minute = Flow.minuteOf(now); val prefs = store(context).prefs
        plan.channels.filter { it.open && it.kind in listOf(ChannelKind.MAIN, ChannelKind.SIDE) && it.startMin - minute in 1..5 }.forEach { c ->
            val key = "remind:${plan.day}:${c.id}"
            if (prefs.getBoolean(key, false)) return@forEach
            prefs.edit().putBoolean(key, true).apply()
            val text = "${c.startMin - minute} 分钟后：${c.title}（做完 = ${c.doneWhen.ifBlank { "按水道约定" }}）"
            PresenceNotices.show(context, 22000 + (c.id.hashCode() and 1023), "到点提醒", text)
            if (BrainHost.settingsStore.load().speak) { Speaker.prepare(context); Speaker.speak(text) }
        }
    }
    suspend fun render(context: Context) = withContext(Dispatchers.Main.immediate) {
        val allowed = Settings.canDrawOverlays(context)
        val fg = withContext(Dispatchers.IO) { PresenceUsage.foreground(context, System.currentTimeMillis()) }
        val s = session.value
        val actorVisible = s != null && fg == s.opening.actor.pkg
        if (actorVisible && !MainActivity.visible) promptedSession = ""
        val overlay = store(context).enabled("overlay")
        if (question.value != null || (overlay && !MainActivity.visible && (actorVisible || card.value != null))) {
            if (allowed) OverlayService.show(context, question.value != null)
            else { OverlayService.hide(context); if (s == null) card.value?.let { Notify.card(context, it.ask) } }
        } else OverlayService.hide(context)
        if (s != null && (!allowed || !overlay || !Usage.allowed(context))) PresenceNotices.show(context, PresenceNotices.SESSION, "正在和 ${s.opening.actor.label} 聊", "下一问：${currentSessionCard()?.ask.orEmpty()} · 点此收线", true)
        else PresenceNotices.cancel(context, PresenceNotices.SESSION)
    }
    fun debug(context: Context): String {
        init(context)
        val p = store(context).prefs; val day = Brain.calendarDay(System.currentTimeMillis())
        return buildString {
            appendLine("【在你身边】")
            appendLine("悬浮窗权限：${Settings.canDrawOverlays(context)}")
            appendLine("通知权限：${androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()}")
            appendLine("使用情况访问：${Usage.allowed(context)}")
            appendLine("当前会话：${session.value?.let { "${it.opening.actor.label} / ${it.startedAt} / 离开 ${it.leaves} 次 / 前台 ${it.presentMs / 1000} 秒" } ?: "无"}")
            appendLine("今天先答一问：${p.getInt("$day:asked", 0)} 次；A ${p.getInt("$day:A", 0)} / B ${p.getInt("$day:B", 0)} / C ${p.getInt("$day:C", 0)} / 没回 ${p.getInt("$day:missed", 0)}")
            appendLine("晨报今天念过：${p.getString("brief_day", "") == day}")
            appendLine(p.getString("last_session", "还没有收线记录"))
            appendLine("最近情况：${p.getString("last_error", "正常")}")
        }
    }
}
