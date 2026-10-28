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
    private var job: Job? = null
    private var revision = 0L
    var anchor = ""
    var anchorSource: TopicSource = TopicSource.SMALL_TALK

    fun open() {
        val current = mutableSession.value
        if (current == null || !rules.canResume(current, now())) update(rules.start(UUID.randomUUID().toString(), now()))
        else update(rules.resume(current, now()))
    }

    fun away() { mutableSession.value?.let { update(rules.away(it, now())) } }
    fun tick() { mutableSession.value?.let { val next = rules.tick(it, now()); if (next != it) { cancel(); update(next) } } }
    fun cancel() { revision++; job?.cancel(); mutableBusy.value = false }
    fun close() { cancel(); mutableSession.value?.let { update(rules.close(it, "用户结束")) } }

    fun offer(text: String, threadId: String = ""): Boolean {
        if (blocked() != null || mutableBusy.value || ContentGuard.violates(text)) return false
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
        onUserReplied?.invoke()
        val expected = ++revision
        mutableBusy.value = true
        mutableStatus.value = "在想你刚才说的……"
        job = scope.launch {
            try {
                val inputVersion = withContext(Dispatchers.IO) { memoryVersion() }
                val key = withContext(Dispatchers.IO) { app.apiKey() }
                val input = withContext(Dispatchers.IO) { replyInput(clean, false) }
                val answer = if (key == null) null else generate(input, key)
                if (expected != revision || inputVersion != withContext(Dispatchers.IO) { memoryVersion() } || blocked() != null) return@launch
                appendReply(answer?.say ?: "这次没能可靠接上你的话，可以再说一句。")
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
        update(rules.add(s, DialogueTurn(Speaker.PET, text, now(), TurnChannel.REPLY)))
        if (!pageVisible) display?.invoke(text)
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
