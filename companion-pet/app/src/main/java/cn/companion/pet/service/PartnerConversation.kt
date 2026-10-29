package cn.companion.pet.service

import cn.companion.pet.core.partner.PartnerAction
import cn.companion.pet.PetApp
import cn.companion.pet.core.partner.*
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.MemoryRecall
import cn.companion.pet.core.companion.PetStateStore
import cn.companion.pet.core.companion.StateEvent
import cn.companion.pet.data.Stats
import cn.companion.pet.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

/** Application-owned conversation. Views attach/detach; they never own or reset its history. */
class PartnerConversation(private val app: PetApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "partner-store").apply { isDaemon = true } }
    private val rules = DialogueRules()
    private val responder = PartnerResponder()
    private val contextBuilder by lazy { PartnerContext(MemoryRecall(app.events, app.facts)) }
    private val characterState by lazy { PetStateStore(app.memory.root) }
    private val cadenceStore by lazy { ProactiveStateStore(app.memory.root) }
    private var cadence = cadenceStore.load()
    private val mutableProgress = MutableStateFlow(ProactiveProgress())
    val progress: StateFlow<ProactiveProgress> = mutableProgress
    var requestProactive: (() -> Unit)? = null
    fun requestNow() {
        val request = requestProactive
        if (request == null) markProgress("未运行", "先在首页启动桌宠，再试一次。") else request()
    }
    fun markProgress(stage: String, detail: String, nextAt: Long = 0) {
        mutableProgress.value = mutableProgress.value.copy(stage = stage, detail = detail, at = now(), nextAt = nextAt)
    }
    fun waiting(reason: String, nextAt: Long) {
        noteBlocked(reason)
        if (!mutableBusy.value) markProgress("等待时机", reason, nextAt)
    }
    fun readyForAttempt(at: Long, requested: Boolean): Boolean {
        if (!requested && at < cadence.nextAttemptAt) {
            markProgress("等待重试", cadence.lastOutcome, cadence.nextAttemptAt)
            return false
        }
        return true
    }
    private fun saveCadence() {
        cadence = governor.snapshot().copy(nextAttemptAt = cadence.nextAttemptAt,
            consecutiveFailures = cadence.consecutiveFailures, lastOutcome = cadence.lastOutcome,
            recentObservations = cadence.recentObservations)
        val snapshot = cadence
        persist("主动节奏") { check(cadenceStore.save(snapshot)) }
    }
    private fun failProactive(reason: String) {
        cadence = ProactiveRetry.failed(cadence, now(), reason)
        markProgress("未能开口", reason, cadence.nextAttemptAt)
        noteBlocked(reason)
        saveCadence()
    }
    private val store by lazy { DialogueStore(app.memory.root) }
    private val mutableSession = MutableStateFlow<DialogueSession?>(store.load())
    val session: StateFlow<DialogueSession?> = mutableSession
    private val mutableStatus = MutableStateFlow("")
    val status: StateFlow<String> = mutableStatus
    private val mutablePersistenceErrors = MutableStateFlow<Map<String, String>>(store.lastError?.let { mapOf("对话" to it) }.orEmpty())
    val persistenceErrors: StateFlow<Map<String, String>> = mutablePersistenceErrors

    private fun persist(kind: String, block: () -> Unit) {
        writer.execute {
            val failed = runCatching(block).isFailure
            scope.launch {
                mutablePersistenceErrors.value = mutablePersistenceErrors.value.toMutableMap().apply {
                    if (failed) put(kind, kind + "暂未保存，重启后可能无法恢复；请检查存储空间") else remove(kind)
                }
            }
        }
    }
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy
    var pageVisible = false
    var look: ((String) -> Unit)? = null
    var typing = false
    var voiceCapturing = false
    var blocked: () -> String? = { null }
    /** Return true only when a surface has actually attached and displays the message. */
    var display: ((String) -> Boolean)? = null
    var displayConfirmed: (suspend (String) -> Boolean)? = null
    var onUserReplied: (() -> Unit)? = null
    var onReply: ((String) -> Unit)? = null    /**
     * 执行本机动作。返回 true 表示**已经确认生效**（不是"已发出"）。
     * 由 PetBrain 接到 DeviceController 上；这里只做路由，不碰无障碍。
     */
    var performAction: (suspend (PartnerAction) -> Boolean)? = null

    /**
     * 主动消息的投递器（通知优先，气泡可选）。
     *
     * 为什么不直接调 overlay：气泡依赖 UI 存活，页面/悬浮窗一旦不可用，
     * 生成成功也发不出去——这正是"它从来不说"的根因之一。
     * 通知通道不依赖任何 UI，能在纯后台把消息送到。
     */
    var dispatcher: cn.companion.pet.core.proactive.MessageDispatcher? = null

    /** 最近一次成功投递的消息 id，用于用户点通知后标记已读。 */
    var lastDeliveryId: String = ""
        private set
    /** 最近一次动作的结果：聊天页显示"已返回"/"已请求，未确认"，不混说。 */
    private val mutableActionStatus = MutableStateFlow("")
    val actionStatus: StateFlow<String> = mutableActionStatus
    var onMetadata: (() -> Unit)? = null
    var voiceDelivery: (suspend (String) -> Boolean)? = null
    data class Presentation(val id: String, val text: String)
    private val mutablePresentation = MutableStateFlow<Presentation?>(null)
    val presentation: StateFlow<Presentation?> = mutablePresentation
    private var pageReceipt: CompletableDeferred<Boolean>? = null
    fun pageRendered(id: String) {
        if (pageVisible && mutablePresentation.value?.id == id) pageReceipt?.complete(true)
    }
    fun pageHidden() { pageVisible = false; pageReceipt?.complete(false) }

    suspend fun deliver(text: String, threadId: String = ""): Boolean {
        if (blocked() != null || PartnerLanguage.violates(text)) return false

        // 消息先落进会话，保证"说过了"这件事在页面上有记录，不管用哪条通道送出。
        fun remember() {
            mutableSession.value?.let { update(rules.add(it.copy(threadId = threadId), DialogueTurn(Speaker.PET, text, now(), TurnChannel.PROACTIVE))) }
        }

        // 1) 有投递器时走它：通知优先，气泡作为可选通道。
        //    这样即使整个聊天 UI 没初始化，也能把话送出去。
        val d = dispatcher
        if (d != null) {
            val record = d.dispatch(text, trigger = sourceLabel(anchorSource))
            if (record.reachedUser) {
                open()
                remember()
                lastDeliveryId = record.id
                return true
            }
            // 投递器所有通道都失败：继续往下试页内路径（它可能此刻恰好可用）。
        }

        // 2) 页内展示（用户就在聊天页）：这条路径能拿到真实渲染回执。
        if (pageVisible) {
            val receipt = CompletableDeferred<Boolean>()
            val p = Presentation(UUID.randomUUID().toString(), text)
            pageReceipt = receipt
            mutablePresentation.value = p
            try {
                val rendered = withTimeoutOrNull(2500) { receipt.await() } == true
                if (!rendered || !pageVisible || blocked() != null) return false
                open()
                remember()
                return true
            } finally {
                if (mutablePresentation.value?.id == p.id) { mutablePresentation.value = null; pageReceipt = null }
            }
        }

        // 3) 兜底：悬浮气泡。这条最依赖 UI，放最后。
        open()
        val sessionId = mutableSession.value?.id
        val shown = displayConfirmed?.invoke(text) == true
        if (!shown || blocked() != null || pageVisible || mutableSession.value?.id != sessionId) return false
        remember()
        return true
    }
    private var job: Job? = null
    private var revision = 0L
    private var lastAttemptAt: Long? = null
    var proactivePending = false
        private set
    var anchor = ""
    var anchorSource: TopicSource = TopicSource.SMALL_TALK

    private val threadStore by lazy { ThreadBookStore(app.memory.root) }
    private val book = threadStore.load()
    init { threadStore.lastError?.let { mutablePersistenceErrors.value = mutablePersistenceErrors.value + ("话题线" to it) } }
    private val mutableThreads = MutableStateFlow(book.all)
    val threads: StateFlow<List<PartnerThread>> = mutableThreads
    data class MemoryProposal(val text: String, val eventId: String, val threadId: String, val version: String)
    private val mutableProposal = MutableStateFlow<MemoryProposal?>(null)
    val proposal: StateFlow<MemoryProposal?> = mutableProposal
    private var lastUserEventId = ""
    private var pendingFollowUp: Pair<String, Long>? = book.awaitingReply()?.let { it.id to it.lastPetTurnAt }

    private fun saveThreads() {
        val snapshot = ThreadBook(book.all)
        mutableThreads.value = book.all
        persist("话题线") { check(threadStore.save(snapshot)) }
    }
    fun pauseThread(id: String) { book.pause(id, now() + 24 * 3_600_000L); saveThreads() }
    fun closeThread(id: String) { book.close(id, "用户不再聊"); saveThreads() }
    fun deleteThread(id: String) {
        book.remove(id)
        saveThreads()
        invalidateMemory()
    }
    fun forgetFact(id: String) {
        scope.launch {
            book.onFactForgotten(id)
            // A thread may still refer to the original user event rather than its confirmed fact.
            val sourceIds = withContext(Dispatchers.IO) { app.facts.find(id)?.sourceEventIds.orEmpty() }
            book.all.filter { t -> t.sourceRefs.any { it in sourceIds } }.forEach { book.close(it.id, "来源记忆已被删除") }
            saveThreads()
            invalidateMemory()
        }
    }
    fun invalidateMemory() {
        cancel()
        app.stopSpeech()
        anchor = ""
        mutableProposal.value = null
        mutableSession.value?.let { update(rules.close(it.copy(turns = emptyList(), summary = ""), "记忆已更新，重新开始")) }
    }
    suspend fun dueThreads(at: Long): List<TopicCandidate> {
        val invalid = withContext(Dispatchers.IO) {
            synchronized(app.memoryLock) {
                val visibility = cn.companion.pet.core.companion.EventVisibility(app.events.all(), app.memory.tombstones(), at)
                visibility.invalidSourceIds + app.facts.all().filter { !visibility.allows(it) }.map { it.id }
            }
        }
        var changed = false
        book.all.filter { t -> t.sourceRefs.any { it in invalid } && t.status != ThreadStatus.CLOSED }.forEach {
            book.close(it.id, "来源已失效"); changed = true
        }
        if (changed) saveThreads()
        pendingFollowUp?.let { (id, shownAt) ->
            if (at - shownAt >= governor.answerWindowMillis(if (app.settings.partnerRiding) UsageContext.RIDING else UsageContext.UNKNOWN)) {
                book.onFollowUpIgnored(id, at); pendingFollowUp = null; saveThreads()
            }
        }
        return book.due(at).filterNot { it.id == pendingFollowUp?.first }.map { t -> TopicCandidate(t.id, TopicSource.THREAD_FOLLOW_UP,
            t.title + if (t.lastProgress.isNotBlank()) "\n上次原话：" + t.lastProgress else "", t.importance, t.createdAt, threadId = t.id) }
    }
    private fun followedUp(candidate: TopicCandidate) {
        if (candidate.threadId.isBlank()) return
        book.onPetFollowUpShown(candidate.threadId, now())
        pendingFollowUp = candidate.threadId to now()
        saveThreads()
    }
    private suspend fun retainQuotedMaterial(candidate: TopicCandidate, reply: PartnerReply) {
        if (candidate.source !in listOf(TopicSource.SCREEN_TEXT, TopicSource.SCREEN_IMAGE)) return
        val quote = reply.anchorQuote.trim().take(140)
        // Only the exact quoted fragment survives; never the whole observation or screenshot.
        if (quote.length < 8 || !candidate.anchor.contains(quote)) return
        val sourceApp = cn.companion.pet.a11y.PerceptionHub.trail.foreground.orEmpty()
        val material = withContext(Dispatchers.IO) {
            val result = app.materialInbox.save(cn.companion.pet.core.companion.MaterialDraft(
                title = quote.take(60), body = quote, source = "partner:$sourceApp"))
            when (result) {
                is cn.companion.pet.core.companion.MaterialSaveResult.Created -> result.material
                is cn.companion.pet.core.companion.MaterialSaveResult.AlreadySaved -> result.material
                else -> null
            }
        } ?: return
        val thread = book.start("material-" + material.id, ThreadKind.MATERIAL, quote.take(60), 2, now(), listOf(material.sourceEventId))
        book.onUserTurn(thread.id, now(), "摘录（" + sourceApp + "）：" + quote)
        saveThreads()
    }

    private fun acceptMetadata(reply: PartnerReply, userText: String, uncertain: Boolean, version: String) {
        if (uncertain || lastUserEventId.isBlank()) return
        var threadId = mutableSession.value?.threadId.orEmpty()
        if (reply.newThreadTitle.isNotBlank()) {
            val kind = when {
                listOf("目标", "打算", "计划", "想要").any { userText.contains(it) } -> ThreadKind.GOAL
                listOf("烦", "担心", "矛盾", "问题").any { userText.contains(it) } -> ThreadKind.OPEN_PROBLEM
                else -> ThreadKind.UNFINISHED_TALK
            }
            val t = book.start(UUID.randomUUID().toString(), kind, reply.newThreadTitle,
                reply.newThreadImportance, now(), listOf(lastUserEventId))
            book.onUserTurn(t.id, now(), userText)
            threadId = t.id
            mutableSession.value?.let { update(it.copy(threadId = t.id)) }
            saveThreads()
        }
        if (reply.memoryProposal.isNotBlank()) mutableProposal.value = MemoryProposal(reply.memoryProposal.take(48), lastUserEventId, threadId, version)
    }
    fun answerMemory(remember: Boolean) {
        val p = mutableProposal.value ?: return
        mutableProposal.value = null
        if (!remember) return
        scope.launch {
            val fact = withContext(Dispatchers.IO) {
                synchronized(app.memoryLock) {
                    if (p.version != memoryVersion()) return@synchronized null
                    val event = app.events.all().firstOrNull { it.id == p.eventId } ?: return@synchronized null
                    val visibility = cn.companion.pet.core.companion.EventVisibility(app.events.all(), app.memory.tombstones())
                    if (!visibility.allows(event)) return@synchronized null
                    app.facts.recordSaid(p.text, listOf(p.eventId), app.memory.tombstones())
                }
            }
            if (fact != null && p.threadId.isNotBlank()) { book.replaceSources(p.threadId, listOf(fact.id)); saveThreads() }
            mutableStatus.value = if (fact == null) "这条来源已经变化，没有保存。" else "记住了。你可以在记忆页更正或删除。"
        }
    }

    val governor = ProactiveGovernor(app.settings.partnerPerHour)
    init { governor.restore(cadence, now()) }
    private val deliveries by lazy { PartnerDelivery(governor, app.voiceQueue, app.ledger) }
    data class Trace(val at: Long, val source: String, val anchor: String)
    private val mutableShown = MutableStateFlow<List<Trace>>(emptyList())
    val shown: StateFlow<List<Trace>> = mutableShown
    private val mutableBlocked = MutableStateFlow<List<String>>(emptyList())
    val blockedReasons: StateFlow<List<String>> = mutableBlocked
    var onProactiveShown: ((TopicCandidate) -> Unit)? = null
    fun noteBlocked(reason: String) { mutableBlocked.value = (listOf(reason) + mutableBlocked.value).distinct().take(5) }

    fun proactive(signals: GovernorSignals, candidates: List<TopicCandidate>, requested: Boolean = false, stillAllowed: () -> Boolean) {
        governor.configureTarget(app.settings.partnerPerHour)
        if (mutableBusy.value) return
        if (!readyForAttempt(signals.now, requested)) return
        if (!requested && lastAttemptAt?.let { signals.now - it < 30_000 } == true) return
        val fresh = candidates.filterNot { !requested && ObservationFingerprint.screen(it) && ObservationFingerprint.of(it) in cadence.recentObservations }
        val decision = governor.decide(signals, fresh, requested)
        if (decision is GovernorDecision.Silent) { waiting(decision.reason, decision.retryAt); return }
        val candidate = (decision as GovernorDecision.Speak).candidate
        lastAttemptAt = signals.now
        proactivePending = true
        val expected = ++revision
        mutableBusy.value = true
        mutableProgress.value = mutableProgress.value.copy(attempts = mutableProgress.value.attempts + 1)
        markProgress("正在想", "已经选好" + sourceLabel(candidate.source) + "，正在生成一句话。")
        cadence = cadence.copy(nextAttemptAt = now() + 120_000L, lastOutcome = "上次生成尚未完成，等待恢复。")
        saveCadence()
        job = scope.launch {
            try {
                val version = withContext(Dispatchers.IO) { memoryVersion() }
                val key = withContext(Dispatchers.IO) { app.apiKey() } ?: run { failProactive("还没配置对话模型的 API Key"); return@launch }
                val input = withContext(Dispatchers.IO) { replyInput("", true, candidate) }
                val reply = generate(input, key) ?: run { failProactive("模型两次回复都没通过说话规则；本次没有显示。"); return@launch }
                if (expected != revision) return@launch
                if (!stillAllowed() || version != withContext(Dispatchers.IO) { memoryVersion() }) {
                    markProgress("等待时机", "生成期间场景或记忆变了，已取消这句话。"); return@launch
                }
                if (candidate.source in listOf(TopicSource.SCREEN_TEXT, TopicSource.SCREEN_IMAGE) && now() - candidate.observedAt > 120_000) {
                    markProgress("等待时机", "画面内容已过期，等待新内容。"); return@launch
                }
                if (app.ledger.isTooSimilar(reply.say)) { failProactive("和刚说过的太像，稍后换个话题。"); return@launch }
                markProgress("准备开口", "正在等待悬浮气泡或语音的实际回执。")
                val delivered = if (app.settings.partnerRiding) {
                    val spoken = voiceDelivery?.invoke(reply.say) == true
                    if (spoken) { open(); mutableSession.value?.let { update(rules.add(it.copy(threadId = candidate.threadId), DialogueTurn(Speaker.PET, reply.say, now(), TurnChannel.PROACTIVE))) } }
                    spoken
                } else deliver(reply.say, candidate.threadId)
                if (delivered && expected == revision) {
                    anchor = candidate.anchor
                    anchorSource = candidate.source
                    val at = now()
                    deliveries.confirm(expected.toString(), candidate, reply.say, at, delivered = true)
                    cadence = ProactiveRetry.success(cadence)
                    if (ObservationFingerprint.screen(candidate)) cadence = cadence.copy(
                        recentObservations = (cadence.recentObservations + ObservationFingerprint.of(candidate)).distinct().takeLast(64))
                    saveCadence()
                    mutableProgress.value = mutableProgress.value.copy(delivered = mutableProgress.value.delivered + 1)
                    markProgress("已开口", "这句话已" + (if (app.settings.partnerRiding) "播出" else "显示") + "，正在等你接话。")
                    mutableShown.value = (listOf(Trace(at, candidate.source.name, candidate.anchor.take(100))) + mutableShown.value).take(5)
                    followedUp(candidate)
                    retainQuotedMaterial(candidate, reply)
                    val conversationId = mutableSession.value?.id
                    scope.launch(Dispatchers.IO) {
                        synchronized(app.memoryLock) {
                            val event = app.events.append(EventKind.PROACTIVE, reply.say, source = "partner", conversationId = conversationId)
                            app.events.append(EventKind.EXCHANGE, "同桌主动聊起：" + reply.say, source = "partner", conversationId = conversationId, sourceIds = listOf(event.id))
                        }
                    }
                    onProactiveShown?.invoke(candidate)
                    if (app.settings.ttsEnabled && !app.settings.partnerRiding) app.speech.say(reply.say)
                } else failProactive("输出界面未显示；请检查悬浮窗权限，或打开完整聊天后再试。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (expected == revision) failProactive(if (error is PartnerCallFailure) error.message.orEmpty() else "主动交流暂时失败：" + error.javaClass.simpleName)
            } finally {
                android.util.Log.i("PartnerBusy", "proactive finally: expected=" + expected + " revision=" + revision + " match=" + (expected == revision))
                if (expected == revision) { proactivePending = false; mutableBusy.value = false }
                android.util.Log.i("PartnerBusy", "busy now=" + mutableBusy.value)
            }
        }
    }

    /**
     * 执行一次动作，并把**真实结果**写回界面。
     *
     * 三种结果分开说，绝不混：
     * - 执行且确认生效 → "已返回上一页"
     * - 已请求但拿不到证据 → "已请求，未确认"
     * - 做不了 → 具体原因
     */
    private fun runAction(action: PartnerAction) {
        val perform = performAction
        if (perform == null) { mutableActionStatus.value = "这个操作现在用不了：" + action.label; return }
        scope.launch {
            val ok = runCatching { perform(action) }.getOrDefault(false)
            mutableActionStatus.value = if (ok) "已" + action.label else "已请求，未确认（" + action.label + "）"
            mutableSession.value?.let { sess ->
                update(rules.add(sess, DialogueTurn(Speaker.PET, mutableActionStatus.value, now(), TurnChannel.REPLY)))
            }
            onReply?.invoke(mutableActionStatus.value)
        }
    }

    fun open() {
        val current = mutableSession.value
        if (current == null || !rules.canResume(current, now())) {
            anchor = ""
            update(rules.start(UUID.randomUUID().toString(), now()))
        }
        else update(rules.resume(current, now()))
    }

    fun away() { mutableSession.value?.let { update(rules.away(it, now())) } }
    fun tick() { mutableSession.value?.let { val next = rules.tick(it, now()); if (next != it) { cancel(); update(next) } } }
    fun cancel() {
        android.util.Log.i("PartnerBusy", "cancel() called, revision " + revision + " -> " + (revision + 1) + " busyBefore=" + mutableBusy.value)
        revision++; job?.cancel(); proactivePending = false; mutableBusy.value = false
    }
    fun close() { cancel(); mutableSession.value?.let { update(rules.close(it, "用户结束")) } }

    fun observe(text: String, material: String, source: TopicSource) {
        open()
        anchor = material
        anchorSource = source
        mutableSession.value?.let { update(rules.addObservation(it, text, now())) }
        if (!pageVisible) display?.invoke(text)
    }

    fun send(text: String, channel: TurnChannel = TurnChannel.TYPED, uncertain: Boolean = false) {
        val clean = text.trim().take(1200)
        if (proposal.value != null && !uncertain) {
            if (clean in listOf("记住", "记住吧", "要", "是")) { answerMemory(true); return }
            if (clean in listOf("不要", "不记", "这次不记")) { answerMemory(false); return }
        }
        if (clean in listOf("结束话题", "不聊了", "不聊这个了", "解决了")) {
            session.value?.threadId?.takeIf { it.isNotBlank() }?.let { closeThread(it) }
            close(); return
        }
        if (clean.isBlank() || (mutableBusy.value && !proactivePending)) return
        if (proactivePending) cancel()
        // 先看这句话是不是在要求一个具体动作。是就执行，不让模型把它"说"成已经做过了。
        ActionIntent.detect(clean)?.let { runAction(it) }
        blocked()?.let { mutableStatus.value = it; return }
        open()
        val s = mutableSession.value ?: return
        update(rules.add(s, DialogueTurn(Speaker.USER, clean, now(), channel, uncertain)))
        governor.onUserReplied()
        saveCadence()
        onUserReplied?.invoke()
        val expected = ++revision
        mutableBusy.value = true
        mutableStatus.value = "在想你刚才说的……"
        job = scope.launch {
            try {
                val inputVersion = withContext(Dispatchers.IO) {
                    synchronized(app.memoryLock) {
                        lastUserEventId = app.events.append(EventKind.USER_INPUT, clean, source = "partner", conversationId = s.id).id
                        characterState.apply(StateEvent.USER_REPLIED)
                        memoryVersion()
                    }
                }
                s.threadId.takeIf { it.isNotBlank() }?.let { id ->
                    if (clean in listOf("解决了", "不聊了", "不聊这个了")) book.close(id, clean)
                    else book.onUserTurn(id, now(), if (uncertain) "（可能听错）" + clean else clean)
                    pendingFollowUp = null
                    saveThreads()
                }
                val key = withContext(Dispatchers.IO) { app.apiKey() }
                val input = withContext(Dispatchers.IO) { replyInput(clean, false) }
                val answer = if (key == null) null else generate(input, key)
                if (expected != revision || inputVersion != withContext(Dispatchers.IO) { memoryVersion() } || blocked() != null) return@launch
                if (answer != null) acceptMetadata(answer, clean, uncertain, inputVersion)
                val say = answer?.say ?: when {
                    key == null -> "还没配置对话模型。可以先去设置里填写接口和 Key。"
                    app.budget.remaining(app.settings.dailyLimit, LocalDate.now().toString()) <= 0 -> "今天的模型调用次数用完了，调整设置里的每日额度后可以继续。"
                    else -> "这次没能可靠接上你的话，可以再说一句。"
                }
                appendReply(say)
                onMetadata?.invoke()
                mutableBusy.value = false
                mutableStatus.value = ""
                try { compact(key) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableStatus.value = "回复已保留；早期对话摘要稍后再整理。" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (expected == revision) {
                    mutableStatus.value = if (error is PartnerCallFailure) error.message.orEmpty() else "这次处理没有完成，你刚才的话还在，可以重试。"
                    noteBlocked("回应暂时失败：" + error.javaClass.simpleName)
                }
            } finally { if (expected == revision) {
                mutableBusy.value = false
                if (mutableStatus.value == "在想你刚才说的……") mutableStatus.value = ""
            } }
        }
    }

    private suspend fun replyInput(userText: String, proactive: Boolean, candidate: TopicCandidate? = null): ReplyInput = synchronized(app.memoryLock) {
        val query = if (userText.isNotBlank()) userText else candidate?.anchor.orEmpty()
        val memories = contextBuilder.memories(query, now(), app.memory.tombstones(), setOf(lastUserEventId))
        ReplyInput(candidate?.anchor ?: listOf(anchor, userText).filter { it.isNotBlank() }.joinToString("\n"),
            candidate?.source ?: if (anchor.isNotBlank()) anchorSource else TopicSource.THREAD_FOLLOW_UP,
            memories, voiceOnly = app.settings.partnerRiding, proactive = proactive,
            intent = if (proactive) ReplyIntent.CHAT else ReplyIntent.from(userText))
    }

    private suspend fun generate(input: ReplyInput, key: String): PartnerReply? {
        val persona = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { app.memory.persona() } }
        val state = withContext(Dispatchers.IO) { characterState.summary(characterState.current()) }
        val s = mutableSession.value
        val thread = s?.threadId?.let { book.get(it) }?.takeIf { it.status != ThreadStatus.CLOSED }
        val context = PartnerContext.project(s?.let { rules.contextLines(it) }.orEmpty(), state,
            thread?.let { it.title + "；最近原话：" + it.lastProgress }.orEmpty())
        return responder.respond(input, persona, context) { request(it, key, if (input.intent == ReplyIntent.CHAT) 600 else 1800) }
    }

    private fun appendReply(text: String) {
        val s = mutableSession.value ?: return
        val sourceId = lastUserEventId
        val userTurn = s.turns.lastOrNull { it.speaker == Speaker.USER }
        update(rules.add(s, DialogueTurn(Speaker.PET, text, now(), TurnChannel.REPLY)).copy(state = s.state))
        if (!pageVisible && s.state == SessionState.ACTIVE && !app.settings.partnerRiding) display?.invoke(text)
        if (s.state == SessionState.ACTIVE) onReply?.invoke(text)
        scope.launch(Dispatchers.IO) {
            synchronized(app.memoryLock) {
                val reply = app.events.append(EventKind.PET_REPLY, text, source = "partner", conversationId = s.id, sourceIds = listOfNotNull(sourceId.takeIf { it.isNotBlank() }))
                val line = "你：" + userTurn?.text.orEmpty().take(300) + (if (userTurn?.uncertain == true) "（可能听错）" else "") + " | 同桌：" + text
                app.events.append(EventKind.EXCHANGE, line, source = "partner", conversationId = s.id, sourceIds = listOfNotNull(sourceId.takeIf { it.isNotBlank() }, reply.id))
            }
        }
    }

    private suspend fun request(messages: List<ChatMessage>, key: String, tokens: Int = 600): String? {
        if (!app.budget.reserve(app.settings.dailyLimit, LocalDate.now().toString())) throw PartnerCallFailure("今天的模型调用额度已用完；可以在设置里调整每日额度。")
        app.stats.add(Stats.Kind.API_CALLS)
        return when (val result = app.client.complete(ChatRequest(app.settings.endpoint, app.settings.model, key, messages, maxTokens = tokens, timeoutMillis = 30_000))) {
            is ChatResult.Success -> result.content
            is ChatResult.Failure -> throw PartnerCallFailure(CallDiagnosis.describe(result))
        }
    }

    private suspend fun compact(key: String?) {
        val snapshot = mutableSession.value ?: return
        if (!rules.needsCompaction(snapshot)) return
        val version = withContext(Dispatchers.IO) { memoryVersion() }
        val old = rules.turnsToCompact(snapshot)
        if (old.isEmpty()) { if (mutableSession.value == snapshot) update(rules.applyCompaction(snapshot, snapshot.summary)); return }
        val summary = (if (key == null) null else request(listOf(ChatMessage(ChatRole.SYSTEM, "把已有摘要和这些对话合并成一段短摘要，保留他的观点、未完事项和语音不确定标记。不添加事实。只输出摘要。"),
            ChatMessage(ChatRole.USER, snapshot.summary + "\n" + old.joinToString("\n") { it.speaker.name + ":" + it.text + if (it.uncertain) "（可能听错）" else "" })), key, 400)) ?: (snapshot.summary + "\n" + old.joinToString("\n") { it.speaker.name + "：" + it.text + if (it.uncertain) "（可能听错）" else "" }).takeLast(800)
        if (mutableSession.value == snapshot && version == withContext(Dispatchers.IO) { memoryVersion() }) update(rules.applyCompaction(snapshot, summary))
    }

    private fun update(s: DialogueSession) {
        mutableSession.value = s
        persist("对话") { store.save(s) }
    }

    private fun memoryVersion(): String = synchronized(app.memoryLock) {
        app.facts.versionHash() + app.materialInbox.versionHash() + app.memory.tombstones().sorted().joinToString("|")
    }
    private fun now() = System.currentTimeMillis()
    private fun sourceLabel(source: TopicSource): String = when (source) {
        TopicSource.SCREEN_TEXT -> "屏幕上的文字"; TopicSource.SCREEN_IMAGE -> "当前画面"
        TopicSource.THREAD_FOLLOW_UP -> "之前没聊完的事"; TopicSource.GOAL_CHECK -> "你的目标"
        TopicSource.OWN_READING -> "自己找到的内容"; TopicSource.SMALL_TALK -> "一个闲聊话题"
    }
    private class PartnerCallFailure(message: String) : Exception(message)
}
