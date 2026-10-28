package cn.companion.pet.service

import cn.companion.pet.PetApp
import cn.companion.pet.core.partner.*
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.probe.ContentGuard
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
    private val store by lazy { DialogueStore(app.memory.root) }
    private val mutableSession = MutableStateFlow<DialogueSession?>(store.load())
    val session: StateFlow<DialogueSession?> = mutableSession
    private val mutableStatus = MutableStateFlow("")
    val status: StateFlow<String> = mutableStatus
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy
    var pageVisible = false
    var typing = false
    var voiceCapturing = false
    var blocked: () -> String? = { null }
    /** Return true only when a surface has actually attached and displays the message. */
    var display: ((String) -> Boolean)? = null
    var onUserReplied: (() -> Unit)? = null
    var onReply: ((String) -> Unit)? = null
    var voiceDelivery: (suspend (String) -> Boolean)? = null
    private var job: Job? = null
    private var revision = 0L
    var anchor = ""
    var anchorSource: TopicSource = TopicSource.SMALL_TALK

    private val threadStore by lazy { ThreadBookStore(app.memory.root) }
    private val book = threadStore.load()
    private val mutableThreads = MutableStateFlow(book.all)
    val threads: StateFlow<List<PartnerThread>> = mutableThreads
    data class MemoryProposal(val text: String, val eventId: String, val threadId: String, val version: String)
    private val mutableProposal = MutableStateFlow<MemoryProposal?>(null)
    val proposal: StateFlow<MemoryProposal?> = mutableProposal
    private var lastUserEventId = ""
    private var pendingFollowUp: Pair<String, Long>? = null

    private fun saveThreads() {
        val snapshot = ThreadBook(book.all)
        mutableThreads.value = book.all
        writer.execute { threadStore.save(snapshot) }
    }
    fun pauseThread(id: String) { book.pause(id, now() + 24 * 3_600_000L); saveThreads() }
    fun closeThread(id: String) { book.close(id, "用户不再聊"); saveThreads() }
    fun deleteThread(id: String) {
        book.close(id, "用户删除")
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
        anchor = ""
        mutableProposal.value = null
        mutableSession.value?.let { update(rules.close(it.copy(turns = emptyList(), summary = ""), "记忆已更新，重新开始")) }
    }
    fun dueThreads(at: Long): List<TopicCandidate> {
        pendingFollowUp?.let { (id, shownAt) ->
            if (at - shownAt >= governor.answerWindowMillis(if (app.settings.partnerRiding) UsageContext.RIDING else UsageContext.UNKNOWN)) {
                book.onFollowUpIgnored(id, at); pendingFollowUp = null; saveThreads()
            }
        }
        return book.due(at).map { t -> TopicCandidate(t.id, TopicSource.THREAD_FOLLOW_UP,
            t.title + if (t.lastProgress.isNotBlank()) "\n上次原话：" + t.lastProgress else "", t.importance, t.createdAt, threadId = t.id) }
    }
    private fun followedUp(candidate: TopicCandidate) {
        if (candidate.threadId.isBlank()) return
        book.onPetFollowUpShown(candidate.threadId, now())
        pendingFollowUp = candidate.threadId to now()
        saveThreads()
    }
    private fun acceptMetadata(reply: PartnerReply, userText: String, uncertain: Boolean, version: String) {
        if (uncertain || lastUserEventId.isBlank()) return
        var threadId = mutableSession.value?.threadId.orEmpty()
        if (reply.newThreadTitle.isNotBlank()) {
            val t = book.start(UUID.randomUUID().toString(), ThreadKind.UNFINISHED_TALK, reply.newThreadTitle,
                reply.newThreadImportance, now(), listOf(lastUserEventId))
            book.onUserTurn(t.id, now(), userText)
            threadId = t.id
            mutableSession.value?.let { update(it.copy(threadId = t.id)) }
            saveThreads()
        }
        if (reply.memoryProposal.isNotBlank()) mutableProposal.value = MemoryProposal(reply.memoryProposal.take(200), lastUserEventId, threadId, version)
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
    data class Trace(val at: Long, val source: String, val anchor: String)
    private val mutableShown = MutableStateFlow<List<Trace>>(emptyList())
    val shown: StateFlow<List<Trace>> = mutableShown
    private val mutableBlocked = MutableStateFlow<List<String>>(emptyList())
    val blockedReasons: StateFlow<List<String>> = mutableBlocked
    var onProactiveShown: ((TopicCandidate) -> Unit)? = null
    fun noteBlocked(reason: String) { mutableBlocked.value = (listOf(reason) + mutableBlocked.value).distinct().take(5) }

    fun proactive(signals: GovernorSignals, candidates: List<TopicCandidate>, stillAllowed: () -> Boolean) {
        governor.configureTarget(app.settings.partnerPerHour)
        if (mutableBusy.value) return
        val decision = governor.decide(signals, candidates)
        if (decision is GovernorDecision.Silent) { noteBlocked(decision.reason); return }
        val candidate = (decision as GovernorDecision.Speak).candidate
        val expected = ++revision
        mutableBusy.value = true
        job = scope.launch {
            try {
                val version = withContext(Dispatchers.IO) { memoryVersion() }
                val key = withContext(Dispatchers.IO) { app.apiKey() } ?: run { noteBlocked("还没配置对话模型"); return@launch }
                val input = withContext(Dispatchers.IO) { replyInput("", true, candidate) }
                val reply = generate(input, key) ?: run { noteBlocked("回答不符合说话规则或调用失败"); return@launch }
                if (expected != revision || !stillAllowed() || version != withContext(Dispatchers.IO) { memoryVersion() }) return@launch
                if (candidate.source in listOf(TopicSource.SCREEN_TEXT, TopicSource.SCREEN_IMAGE) && now() - candidate.observedAt > 120_000) return@launch
                if (app.ledger.isTooSimilar(reply.say)) { noteBlocked("和刚说过的太像"); return@launch }
                val delivered = if (app.settings.partnerRiding) {
                    val spoken = voiceDelivery?.invoke(reply.say) == true
                    if (spoken) { open(); mutableSession.value?.let { update(rules.add(it.copy(threadId = candidate.threadId), DialogueTurn(Speaker.PET, reply.say, now(), TurnChannel.PROACTIVE))) } }
                    spoken
                } else offer(reply.say, candidate.threadId, allowBusy = true)
                if (delivered) {
                    anchor = candidate.anchor
                    anchorSource = candidate.source
                    val at = now()
                    governor.onShown(at)
                    app.ledger.record(reply.say, at)
                    // The candidate and final wording are separate evidence; consume only after delivery.
                    if (candidate.id.startsWith("vi-")) app.voiceQueue.markSpoken(candidate.id, at)
                    mutableShown.value = (listOf(Trace(at, candidate.source.name, candidate.anchor.take(100))) + mutableShown.value).take(5)
                    followedUp(candidate)
                    onProactiveShown?.invoke(candidate)
                    if (app.settings.ttsEnabled && !app.settings.partnerRiding) app.speech.say(reply.say)
                } else noteBlocked("输出界面未显示")
            } finally { if (expected == revision) mutableBusy.value = false }
        }
    }

    fun open() {
        val current = mutableSession.value
        if (current == null || !rules.canResume(current, now())) update(rules.start(UUID.randomUUID().toString(), now()))
        else update(rules.resume(current, now()))
    }

    fun away() { mutableSession.value?.let { update(rules.away(it, now())) } }
    fun tick() { mutableSession.value?.let { val next = rules.tick(it, now()); if (next != it) { cancel(); update(next) } } }
    fun cancel() { revision++; job?.cancel(); mutableBusy.value = false }
    fun close() { cancel(); mutableSession.value?.let { update(rules.close(it, "用户结束")) } }

    fun offer(text: String, threadId: String = "", allowBusy: Boolean = false): Boolean {
        if (blocked() != null || (mutableBusy.value && !allowBusy) || ContentGuard.violates(text)) return false
        open()
        val shown = if (pageVisible) true else display?.invoke(text) == true
        if (!shown) return false
        val current = mutableSession.value ?: return false
        update(rules.add(current.copy(threadId = threadId), DialogueTurn(Speaker.PET, text, now(), TurnChannel.PROACTIVE)))
        return true
    }

    fun send(text: String, channel: TurnChannel = TurnChannel.TYPED, uncertain: Boolean = false) {
        val clean = text.trim().take(1200)
        if (clean.isBlank() || mutableBusy.value) return
        blocked()?.let { mutableStatus.value = it; return }
        open()
        val s = mutableSession.value ?: return
        update(rules.add(s, DialogueTurn(Speaker.USER, clean, now(), channel, uncertain)))
        governor.onUserReplied()
        onUserReplied?.invoke()
        val expected = ++revision
        mutableBusy.value = true
        mutableStatus.value = "在想你刚才说的……"
        job = scope.launch {
            try {
                val inputVersion = withContext(Dispatchers.IO) {
                    synchronized(app.memoryLock) {
                        lastUserEventId = app.events.append(EventKind.USER_INPUT, clean, source = "partner", conversationId = s.id).id
                        memoryVersion()
                    }
                }
                s.threadId.takeIf { it.isNotBlank() }?.let { id ->
                    if (clean in listOf("解决了", "不聊了", "不聊这个了")) book.close(id, clean)
                    else book.onUserTurn(id, now(), clean)
                    pendingFollowUp = null
                    saveThreads()
                }
                val key = withContext(Dispatchers.IO) { app.apiKey() }
                val input = withContext(Dispatchers.IO) { replyInput(clean, false) }
                val answer = if (key == null) null else generate(input, key)
                if (expected != revision || inputVersion != withContext(Dispatchers.IO) { memoryVersion() } || blocked() != null) return@launch
                appendReply(answer?.say ?: "这次没能可靠接上你的话，可以再说一句。")
                if (answer != null) acceptMetadata(answer, clean, uncertain, inputVersion)
                compact(key)
            } finally { if (expected == revision) { mutableBusy.value = false; mutableStatus.value = "" } }
        }
    }

    private suspend fun replyInput(userText: String, proactive: Boolean, candidate: TopicCandidate? = null): ReplyInput = synchronized(app.memoryLock) {
        val visibility = cn.companion.pet.core.companion.EventVisibility(app.events.all(), app.memory.tombstones())
        val memories = app.facts.live().filter { visibility.allows(it) }.takeLast(12).associate { it.id to it.text }
        ReplyInput(candidate?.anchor ?: listOf(anchor, userText).filter { it.isNotBlank() }.joinToString("\n"),
            candidate?.source ?: if (anchor.isNotBlank()) anchorSource else TopicSource.THREAD_FOLLOW_UP,
            memories, voiceOnly = app.settings.partnerRiding, proactive = proactive)
    }

    private suspend fun generate(input: ReplyInput, key: String): PartnerReply? {
        val persona = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { app.memory.persona() } }
        return responder.respond(input, persona, mutableSession.value?.let { rules.contextLines(it) }.orEmpty()) { request(it, key) }
    }

    private fun appendReply(text: String) {
        val s = mutableSession.value ?: return
        update(rules.add(s, DialogueTurn(Speaker.PET, text, now(), TurnChannel.REPLY)).copy(state = s.state))
        if (!pageVisible && s.state == SessionState.ACTIVE && !app.settings.partnerRiding) display?.invoke(text)
        onReply?.invoke(text)
    }

    private suspend fun request(messages: List<ChatMessage>, key: String, tokens: Int = 600): String? {
        if (!app.budget.reserve(app.settings.dailyLimit, LocalDate.now().toString())) return null
        app.stats.add(Stats.Kind.API_CALLS)
        return (app.client.complete(ChatRequest(app.settings.endpoint, app.settings.model, key, messages, maxTokens = tokens, timeoutMillis = 30_000)) as? ChatResult.Success)?.content
    }

    private suspend fun compact(key: String?) {
        val snapshot = mutableSession.value ?: return
        if (key == null || !rules.needsCompaction(snapshot)) return
        val old = rules.turnsToCompact(snapshot)
        val summary = request(listOf(ChatMessage(ChatRole.SYSTEM, "把已有摘要和这些对话合并成一段短摘要，保留他的观点、未完事项和语音不确定标记。不添加事实。只输出摘要。"),
            ChatMessage(ChatRole.USER, snapshot.summary + "\n" + old.joinToString("\n") { it.speaker.name + ":" + it.text + if (it.uncertain) "（可能听错）" else "" })), key, 400) ?: return
        if (mutableSession.value == snapshot) update(rules.applyCompaction(snapshot, summary))
    }

    private fun update(s: DialogueSession) {
        mutableSession.value = s
        writer.execute { runCatching { store.save(s) } }
    }

    private fun memoryVersion(): String = synchronized(app.memoryLock) {
        app.facts.versionHash() + app.memory.tombstones().sorted().joinToString("|")
    }
    private fun now() = System.currentTimeMillis()
}
