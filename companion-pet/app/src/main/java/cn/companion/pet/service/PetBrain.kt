package cn.companion.pet.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import cn.companion.pet.PetApp
import cn.companion.pet.a11y.GeminiAutoSender
import cn.companion.pet.a11y.PerceptionHub
import cn.companion.pet.bridge.Assistants
import cn.companion.pet.bridge.GeminiLauncher
import cn.companion.pet.core.bridge.BridgeContext
import cn.companion.pet.core.companion.PetStateStore
import cn.companion.pet.core.companion.StateEvent
import cn.companion.pet.core.companion.Topic
import cn.companion.pet.core.companion.TopicGate
import cn.companion.pet.core.companion.TopicQueue
import cn.companion.pet.core.companion.CompanionContextAssembler
import cn.companion.pet.core.bridge.GeminiBridgeTextBuilder
import cn.companion.pet.core.companion.CompanionEvent
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventVisibility
import cn.companion.pet.core.agent.AgentContext
import cn.companion.pet.core.agent.AgentEventScanner
import cn.companion.pet.core.agent.AgentTrigger
import cn.companion.pet.core.action.VisibleMemoryReader
import cn.companion.pet.core.life.PetLife
import cn.companion.pet.core.life.WeightyMoments
import cn.companion.pet.core.memory.Exchange
import cn.companion.pet.core.memory.ProfileRules
import cn.companion.pet.core.memory.GeminiExit
import cn.companion.pet.core.memory.TodayLog
import cn.companion.pet.core.probe.ContentGuard
import cn.companion.pet.core.probe.Mood
import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.probe.ProbePrompt
import cn.companion.pet.core.probe.ProbeReply
import cn.companion.pet.core.probe.ProbeReplyParser
import cn.companion.pet.core.rhythm.EscalationLevel
import cn.companion.pet.core.rhythm.IgnoreEscalation
import cn.companion.pet.core.rhythm.SpeakScheduler
import cn.companion.pet.core.scene.Scene
import cn.companion.pet.core.scene.SceneClassifier
import cn.companion.pet.core.scene.SceneDecision
import cn.companion.pet.core.session.EndReason
import cn.companion.pet.core.session.ChatSession
import cn.companion.pet.core.session.AssistantApp
import cn.companion.pet.core.skim.SkimBuffer
import cn.companion.pet.core.think.ThinkMode
import cn.companion.pet.core.think.ThinkPrompt
import cn.companion.pet.core.think.ThinkResult
import cn.companion.pet.core.think.ThoughtNotes
import cn.companion.pet.core.session.SessionAction
import cn.companion.pet.core.session.SessionMode
import cn.companion.pet.core.session.SessionSummary
import cn.companion.pet.core.text.ScreenTextCleaner
import cn.companion.pet.data.Stats
import cn.companion.pet.net.ChatMessage
import cn.companion.pet.net.ChatRequest
import cn.companion.pet.net.ChatResult
import cn.companion.pet.overlay.BubbleAction
import cn.companion.pet.overlay.BubbleListener
import cn.companion.pet.overlay.BubbleSpec
import cn.companion.pet.overlay.PetListener
import cn.companion.pet.overlay.PetOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 桌宠的大脑：所有“什么时候开口、说什么、用户回应后怎么走”的决定都在这里。
 * 只在主线程上运行；网络和文件读写切到 IO。
 */
class PetBrain(private val context: Context) : PetListener, BubbleListener {

    private val app = context.applicationContext as PetApp
    private val settings = app.settings
    private val stats = app.stats
    private val memory = app.memory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private val overlay = PetOverlay(context, settings, this, this)
    private val assembler by lazy { CompanionContextAssembler(app.events, app.facts, materials = app.materialInbox) }
    private val visibleMemory by lazy { VisibleMemoryReader(memory, app.events, app.facts) }
    private val stateStore by lazy { PetStateStore(memory.root) }
    private val topics by lazy { app.topics }
    private val topicGate by lazy { TopicGate(topics) }
    private var lastProactiveAt = 0L
    private val scheduler = SpeakScheduler(settings.rhythm())
    private val life = PetLife()
    private val weighty = WeightyMoments()
    private val haptics = Haptics(context)
    private val voice = VoiceInput(context)
    private val gemini = GeminiLauncher(context)
    private val assistants = Assistants(context, settings)
    private val speech by lazy { app.speech }
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val labels = HashMap<String, String>()
    private val launchers: Set<String> by lazy { queryLaunchers() }

    /** 一次交流：桌宠开口 → 用户最多回一句 → 桌宠接一句收尾。 */
    private inner class Conversation(
        val decision: SceneDecision,
        val reply: ProbeReply,
        val opening: List<ChatMessage>,
        val shownAt: Long,
        /** 用户点角色主动开始的，不升级。 */
        val userStarted: Boolean = false,
        /** 锁屏心跳后的那一句，忽略不升级。 */
        val noEscalation: Boolean = false,
        val id: String = UUID.randomUUID().toString(),
        val topicId: String? = null,
        val inputVersion: String? = null,
    ) {
        var interacting = false
        var userReply: String? = null
        var petReply: String? = null
        var gemini = GeminiExit.NONE
        var userReplyAt: Long = 0L
        var petReplyAt: Long = 0L
        var attentionDone = false
        var nudgeDone = false
        var ended = false
    }

    private var convo: Conversation? = null
    private var probeJob: Job? = null
    private var agentScanJob: Job? = null
    private var lastAgentScanAt = 0L
    private val agentScanner = AgentEventScanner()
    private val agentTrigger = Runnable { maybeScanAgent(now()) }
    private val agentListener: (CompanionEvent) -> Unit = {
        handler.post { scheduleAgentScan() }
    }
    private var active = false
    private var rephraseNext = false
    private var heartbeatWaiting = false
    private var closeRunnable: Runnable? = null
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        memory.persona() // 首次运行写入默认人设
        weighty.restore(LocalDate.now().toString(), stats.get(Stats.Kind.WEIGHTY))
        overlay.show()
        restoreSession()
        active = isUserActive()
        if (active) onUserPresent()
        handler.post(ticker)
        app.events.addListener(agentListener)
        handler.postDelayed(agentTrigger, 1_200)
        PerceptionHub.onScroll = { pkg -> onScrollEvent(pkg) }
        scope.launch { settings.changes.collect { scheduler.config = settings.rhythm() } }
    }

    fun stop() {
        app.events.removeListener(agentListener)
        handler.removeCallbacks(agentTrigger)
        agentScanJob?.cancel()
        app.agentLoop.cancel()
        app.actions.cancelAll()
        PerceptionHub.onScroll = null
        handler.removeCallbacksAndMessages(null)
        voice.cancel()
        speech.shutdown()
        probeJob?.cancel()
        overlay.hide()
        scope.cancel()
    }

    // ---------- 屏幕状态 ----------

    fun onScreenOff() {
        active = false
        agentScanJob?.cancel()
        handler.removeCallbacks(agentTrigger)
        app.agentLoop.cancel()
        app.actions.cancelAll() // 锁屏后按现有规则停止动作
        scheduler.onScreenOff()
        probeJob?.cancel()
        voice.cancel()
        endConversation()
        endThink()
        skim.clear() // 划过的内容只留在亮屏期间
        overlay.retreat()
        Heartbeat.schedule(context, settings)
    }

    /** 解锁进入桌面（USER_PRESENT），或服务启动时已经是解锁状态。 */
    fun onUserPresent() {
        active = true
        Heartbeat.cancel(context)
        scheduler.onScreenOn(now())
        handler.removeCallbacks(agentTrigger)
        handler.postDelayed(agentTrigger, 1_200)
        heartbeatWaiting = Heartbeat.consumePending(context)
        checkLetter()
        if (heartbeatWaiting) {
            // 心跳之后亮屏：稍等一下让桌面稳定，再问一句
            handler.postDelayed({ if (heartbeatWaiting && convo == null) speak(stuck = false, afterHeartbeat = true) }, 1500)
        }
    }

    private fun isUserActive(): Boolean = power.isInteractive && !keyguard.isKeyguardLocked

    // ---------- 每秒一次 ----------

    private fun tick() {
        val now = now()
        if (convo == null && life.tick(now)) overlay.setLife(life.state)
        // Gemini 会话期间：只守着会话，不主动搭话
        session?.let { handleSession(it, now); return }
        if (!active) return
        // 正在“想一想”或记念头：不插嘴；放着不管 3 分钟就收起
        if (think != null || noteDraft != null) {
            val since = think?.touchedAt ?: noteDraft!!.startedAt
            if (now - since > 3 * 60_000L) endThink()
            return
        }
        if (maybeAutoStartSession(now)) return

        convo?.let { escalate(it, now); return }
        if (probeJob?.isActive == true || settings.petPaused) return

        if (PerceptionHub.stuck.check(now)) {
            if (!scheduler.isQuiet(now)) speak(stuck = true)
            return
        }
        val focus = SceneClassifier.focusActive(now, PerceptionHub.trail, settings.appLists())
        if (scheduler.isDue(now, focus)) {
            val topic = if (settings.continuityEnabled) {
                topicGate.next(
                    now = now,
                    quiet = scheduler.isQuiet(now),
                    userActive = active,
                    lastProactiveAt = lastProactiveAt,
                    recentTexts = recentExchangeLines(20).toSet(),
                )
            } else null
            speak(stuck = false, topic = topic)
        }
    }

    private fun escalate(c: Conversation, now: Long) {
        val age = now - c.shownAt
        // 兜底：点开没说话 20 秒、或任何对话挂了 5 分钟，都自动收起
        if ((c.userStarted && !c.interacting && age > 20_000) || age > 5 * 60_000L) {
            endConversation()
            return
        }
        // 锁屏心跳后那一句：忽略不升级，2 分钟后悄悄收起
        if (c.noEscalation && !c.interacting && c.userReply == null && age > 120_000) {
            endConversation()
            return
        }
        if (c.userStarted || c.noEscalation || c.interacting || c.userReply != null) return
        when (IgnoreEscalation.levelAt(c.shownAt, now)) {
            EscalationLevel.WAITING -> Unit
            EscalationLevel.ATTENTION -> if (!c.attentionDone) { c.attentionDone = true; overlay.attention() }
            EscalationLevel.NUDGE -> if (!c.nudgeDone) { c.nudgeDone = true; haptics.nudge(); overlay.nudgeInward() }
            EscalationLevel.RETREAT -> {
                overlay.retreat()
                endConversation()
                scheduler.onIgnoredFully(now)
                rephraseNext = true
            }
        }
    }

    // ---------- 开口 ----------

    private fun speak(stuck: Boolean, afterHeartbeat: Boolean = false, topic: Topic? = null) {
        val now = now()
        if (probeJob?.isActive == true || convo != null || session != null || think != null || noteDraft != null) return
        heartbeatWaiting = false

        val key = apiKey()
        if (key == null) {
            // 没配置 Key：只做个动作，不打扰
            overlay.attention()
            scheduler.onSpoke(now)
            return
        }
        if (app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()) <= 0) {
            // 到上限：当天只保留动画和震动
            overlay.attention(); haptics.tap()
            scheduler.onSpoke(now)
            return
        }

        val decision = classify(now)
        if (decision.scene == Scene.EXCLUDED) { scheduler.retrySoon(now); return }
        if (stuck && decision.scene != Scene.LEARNING) return

        val today = LocalDate.now().toString()
        val isWeighty = !stuck && !afterHeartbeat && weighty.shouldUse(today, LocalTime.now().hour)
        val moment = ProbeMoment(
            timeText = timeText(),
            scene = decision.scene,
            foregroundLabel = PerceptionHub.trail.foreground?.let { label(it) } ?: "不清楚",
            trajectory = trajectory(now),
            screenText = decision.screenText,
            rephrase = rephraseNext,
            weighty = isWeighty,
            stuck = stuck,
            petState = life.state.label + " · " + stateStore.summary(stateStore.current()),
            topic = topic?.summary,
            afterHeartbeat = afterHeartbeat,
        )

        probeJob = scope.launch {
            overlay.setMood(Mood.THINKING)
            val (opening, inputVersion) = withContext(Dispatchers.IO) {
                synchronized(app.memoryLock) {
                    app.materialInbox.purgeExpired(now())
                    val eligible = topic == null || topics.candidates(now()).any { it.id == topic.id }
                    openingMessages(if (eligible) moment else moment.copy(topic = null), decision.screenText) to memoryVersion()
                }
            }
            if (!app.budget.reserve(settings.dailyLimit, LocalDate.now().toString())) {
                overlay.setMood(Mood.IDLE)
                scheduler.retrySoon(now())
                return@launch
            }
            stats.add(Stats.Kind.API_CALLS)
            val result = app.client.complete(ChatRequest(settings.endpoint, settings.model, key, opening))
            overlay.setMood(Mood.IDLE)
            val unchanged = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { inputVersion == memoryVersion() } }
            if (!unchanged) { scheduler.retrySoon(now()); return@launch }
            val reply = (result as? ChatResult.Success)?.let { ProbeReplyParser.parse(it.content) }
            when {
                reply == null -> scheduler.onSpoke(now()) // 失败或解析失败：静默，按正常节奏下次再来
                reply.skip || ContentGuard.violates(reply.hookText) -> scheduler.retrySoon(now())
                !active || convo != null -> scheduler.retrySoon(now())
                else -> {
                    if (isWeighty) { weighty.markUsed(today); stats.add(Stats.Kind.WEIGHTY) }
                    rephraseNext = false
                    present(decision, reply, opening, noEscalation = afterHeartbeat, topic = topic, inputVersion = inputVersion)
                }
            }
        }
    }

    /** 短防抖：事件写入后 1.2 秒再看一次，避免一次保存连发多个事件。 */
    private fun scheduleAgentScan() {
        handler.removeCallbacks(agentTrigger)
        handler.postDelayed(agentTrigger, 1_200)
    }

    private fun maybeScanAgent(now: Long) {
        if (!settings.agentAutonomy || !active || settings.petPaused) return
        if (probeJob?.isActive == true || convo != null || session != null || think != null || noteDraft != null) return
        if (!isUserActive() || scheduler.isQuiet(now)) return
        if (app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()) <= 0) return
        if (now - lastAgentScanAt < 1_000) return
        lastAgentScanAt = now
        agentScanJob?.cancel()
        agentScanJob = scope.launch {
            val event = withContext(Dispatchers.IO) {
                synchronized(app.memoryLock) {
                    val snapshot = app.events.all()
                    val visibility = EventVisibility(snapshot, memory.tombstones(), now)
                    agentScanner.next(snapshot, app.agentTrace.processedEventIds(500), now, visibility)
                }
            } ?: return@launch
            if (!settings.agentAutonomy || !active || settings.petPaused || scheduler.isQuiet(now())) return@launch
            runAgentTurn(event, now)
        }
    }

    private fun runAgentTurn(event: CompanionEvent, now: Long) {
        if (probeJob?.isActive == true) return
        val decision = classify(now)
        if (decision.scene == Scene.EXCLUDED) return
        probeJob = scope.launch {
            overlay.setMood(Mood.THINKING)
            val context = withContext(Dispatchers.IO) {
                synchronized(app.memoryLock) {
                    val lines = visibleMemory.read("", 5)
                    val titles = app.materialInbox.all().map { it.title.ifBlank { it.url ?: it.id } }.take(5)
                    val ids = (app.events.all().takeLast(10).map { it.id } + event.id).distinct()
                    AgentContext(
                        trigger = if (event.kind == EventKind.MATERIAL) AgentTrigger.MATERIAL_SAVED else AgentTrigger.NOTE_SAVED,
                        now = now,
                        day = LocalDate.now().toString(),
                        scene = decision.scene.name,
                        foreground = PerceptionHub.trail.foreground?.let { label(it) } ?: "不清楚",
                        memoryLines = lines,
                        materialTitles = titles,
                        eventIds = ids,
                        budgetRemaining = app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()),
                        eventId = event.id,
                        quiet = scheduler.isQuiet(now),
                        userActive = active,
                        lastActionAt = app.agentTrace.lastAt(),
                    )
                }
            }
            // 模型、工具、轨迹全部在 IO；回来只做 UI。严格检测避免回归到主线程。
            val result = withContext(Dispatchers.IO) {
                check(Looper.myLooper() != Looper.getMainLooper()) { "agent must run off main" }
                app.agentLoop.run(context, settings.agentAutonomy,
                    cancelled = { !settings.agentAutonomy || !active })
            }
            overlay.setMood(Mood.IDLE)
            if (!active || !settings.agentAutonomy || convo != null) return@launch
            val spoke = result.text.isNotBlank()
            if (spoke) {
                scheduler.onSpoke(now)
                stats.add(Stats.Kind.SPOKE)
                overlay.setTalking(true)
                overlay.showBubble(BubbleSpec(result.text, textOnly = true)) {
                    overlay.setTalking(false)
                }
            } else {
                // 没有动作/被拒绝/失败时，把正常的搭话机会还给原节奏。
                scheduler.retrySoon(now)
            }
        }
    }

    private fun present(decision: SceneDecision, reply: ProbeReply, opening: List<ChatMessage>, noEscalation: Boolean, topic: Topic? = null, inputVersion: String? = null) {
        val now = now()
        scheduler.onSpoke(now)
        stats.add(Stats.Kind.SPOKE)
        convo = Conversation(decision, reply, opening, now, noEscalation = noEscalation, topicId = topic?.id, inputVersion = inputVersion)
        lastProactiveAt = now
        topic?.let { topics.markShared(it.id, now) }
        overlay.setMood(reply.mood)
        overlay.setTalking(true)
        haptics.tap()
        if (settings.ttsEnabled) speech.say(reply.hookText)
        overlay.showBubble(entrySpec(reply.hookText, decision.scene, reply.suggestGemini)) { overlay.setTalking(false) }
    }

    private fun entrySpec(text: String, scene: Scene, highlightGemini: Boolean) = BubbleSpec(
        text = text,
        showMic = voice.available,
        geminiHighlighted = highlightGemini,
        geminiLabel = "去 ${assistants.current.label}",
        showLive = scene == Scene.LEARNING,
    )

    // ---------- 用户主动 ----------

    override fun onPetTapped() {
        if (session != null) {
            if (overlay.isBubbleShown) hideSessionPrompt() else showSessionMenu()
            return
        }
        if (think != null || noteDraft != null) { endThink(); return }
        if (overlay.isBubbleShown) {
            endConversation()
            return
        }
        if (showLetterIfAny()) return
        if (probeJob?.isActive == true) return
        // 用户点它：开一个不需要调 API 的轻量对话入口
        val decision = classify(now())
        val greeting = listOf("嗯？", "叫我？", "怎么啦", "在呢").random()
        val reply = ProbeReply(greeting, "", Mood.CURIOUS, false, false)
        val opening = buildOpeningSync(decision)
        convo = Conversation(decision, reply, opening, now(), userStarted = true)
        overlay.setMood(Mood.CURIOUS)
        overlay.showBubble(
            entrySpec(greeting, decision.scene, false).copy(
                extra = listOf(BubbleAction("想一想") { openThinkMenu() }, BubbleAction("记个念头") { startNote() }),
            ),
        )
    }

    override fun onHoldStart() {
        if (session != null) { showSessionMenu(); return }
        startListening(fromHold = true)
    }

    override fun onHoldEnd() { if (session == null) stopListening() }

    override fun onMicDown() = startListening(fromHold = false)

    override fun onMicUp() = stopListening()

    private var listeningFromHold = false

    private fun startListening(fromHold: Boolean) {
        convo?.interacting = true
        if (!voice.available) {
            overlay.showBubble(
                BubbleSpec("这台手机的系统语音识别用不了。点“打字”，用输入法上的麦克风说也行。", showMic = false, showGemini = false),
            )
            if (convo == null) convo = Conversation(classify(now()), ProbeReply("", "", Mood.IDLE, false, false), emptyList(), now(), userStarted = true)
            return
        }
        listeningFromHold = fromHold
        overlay.setListening(true)
        haptics.tap()
        voice.start { text ->
            overlay.setListening(false)
            onUserInput(text, listeningFromHold)
        }
    }

    private fun stopListening() {
        voice.stop()
    }

    override fun onTypingStarted() {
        convo?.interacting = true
        overlay.setBubbleFocusable(true)
    }

    override fun onTextSubmitted(text: String) {
        overlay.setBubbleFocusable(false)
        when {
            noteDraft != null -> { saveNote(text); return }
            think?.awaitingAnswer == true -> { answerThink(text); return }
        }
        onUserInput(text, fromHold = false)
    }

    override fun onBubbleTouched() {
        convo?.interacting = true
    }

    override fun onClose() = endConversation()

    private fun onUserInput(text: String?, fromHold: Boolean) {
        val said = text?.trim().orEmpty()
        if (said.isEmpty()) {
            overlay.showBubble(BubbleSpec("没听清，再说一次？", showMic = voice.available, showGemini = false))
            return
        }
        val c = convo ?: run {
            // 没有对话时直接按住角色说话
            val d = classify(now())
            Conversation(d, ProbeReply("", "", Mood.IDLE, false, false), buildOpeningSync(d), now(), userStarted = true).also { convo = it }
        }
        c.interacting = true
        c.userReplyAt = now()
        stateStore.apply(StateEvent.USER_REPLIED)
        if (c.userReply == null) stats.add(Stats.Kind.REPLIED)
        c.userReply = said

        // 学习场景按住角色说话：直接拉 Gemini 同桌
        if (fromHold && c.decision.scene == Scene.LEARNING) {
            bridgeText(c)
            return
        }
        if (c.petReply != null) return // 已经聊满两轮
        replyRound(c, said)
    }

    private fun replyRound(c: Conversation, said: String) {
        val key = apiKey()
        val canCall = key != null && c.opening.isNotEmpty() &&
            app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()) > 0
        overlay.setMood(Mood.THINKING)
        overlay.showBubble(BubbleSpec("……", textOnly = true))
        probeJob = scope.launch {
            var text: String? = null
            var mood = Mood.IDLE
            if (canCall && app.budget.reserve(settings.dailyLimit, LocalDate.now().toString())) {
                stats.add(Stats.Kind.API_CALLS)
                val (messages, inputVersion) = withContext(Dispatchers.IO) {
                    synchronized(app.memoryLock) {
                        val changed = c.inputVersion != null && c.inputVersion != memoryVersion()
                        val hook = c.reply.hookText.takeUnless { changed || memory.forgottenMatches(it) }.orEmpty().ifEmpty { "嗯？" }
                        ProbePrompt.reply(buildOpeningSync(c.decision, said), hook, said) to memoryVersion()
                    }
                }
                val r = app.client.complete(ChatRequest(settings.endpoint, settings.model, key!!, messages))
                val unchanged = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { inputVersion == memoryVersion() } }
                val parsed = if (unchanged) (r as? ChatResult.Success)?.let { ProbeReplyParser.parse(it.content) } else null
                if (parsed != null && !parsed.skip && !ContentGuard.violates(parsed.hookText)) {
                    text = parsed.hookText
                    mood = parsed.mood
                }
            }
            if (convo !== c || c.ended) return@launch
            // 调用失败不弹错误：点点头收尾
            val final = text ?: "嗯，我听到了。"
            c.petReply = final
            c.petReplyAt = now()
            overlay.setMood(mood)
            overlay.setTalking(true)
            if (settings.ttsEnabled) speech.say(final)
            overlay.showBubble(BubbleSpec(final, textOnly = true)) {
                overlay.setTalking(false)
                closeLater(c, 5000)
            }
        }
    }

    private fun closeLater(c: Conversation, delayMillis: Long) {
        closeRunnable?.let { handler.removeCallbacks(it) }
        closeRunnable = Runnable { if (convo === c) endConversation() }.also { handler.postDelayed(it, delayMillis) }
    }

    // ---------- 去 AI App 深聊（Gemini / ChatGPT / 千问） ----------

    override fun onGemini() {
        val c = convo ?: return
        bridgeText(c)
    }

    private fun bridgeText(c: Conversation) {
        val isLearning = c.decision.scene == Scene.LEARNING
        val excerpt = if (isLearning) {
            c.decision.screenText ?: ScreenTextCleaner.clean(PerceptionHub.readScreen(), ScreenTextCleaner.BRIDGE_MAX_CHARS)
        } else ""
        val text = GeminiBridgeTextBuilder.buildText(
            BridgeContext(
                timeText = timeText(),
                trajectorySummary = trajectory(now()),
                isLearning = isLearning,
                contextSummary = c.reply.contextSummary,
                excerpt = excerpt,
                hookText = c.reply.hookText,
                userInput = c.userReply.orEmpty(),
            ),
        )
        c.gemini = GeminiExit.TEXT
        stats.add(Stats.Kind.GEMINI_TEXT)
        endConversation()
        sendToAssistant(text)
    }

    /** 把拼好的文字带去当前选的 AI App：复制 → 打开 → 开始会话 →（可选）自动发送。 */
    private fun sendToAssistant(text: String) {
        val target = assistants.current
        val pkg = assistants.packageOf(target)
        gemini.copy("桌宠 → ${target.label}", text)
        if (pkg == null) { gemini.toast("没找到 ${target.label}，文字已复制。可以在设置里填它的包名"); return }
        when (gemini.launch(pkg)) {
            GeminiLauncher.Outcome.NOT_INSTALLED -> { gemini.toast("没找到 ${target.label}（$pkg），文字已复制"); return }
            GeminiLauncher.Outcome.FAILED -> { gemini.toast("打不开 ${target.label}，文字已复制"); return }
            GeminiLauncher.Outcome.LAUNCHED -> startSession(SessionMode.TEXT, target, pkg)
        }
        if (settings.geminiAutoSend && PerceptionHub.service != null) {
            scope.launch {
                delay(800)
                val ok = GeminiAutoSender.send(target.sessionPackages(pkg), text)
                if (!ok) gemini.toast("没能自动发送，已复制，粘贴发送即可")
            }
        } else {
            gemini.toast("已复制，到 ${target.label} 里粘贴发送即可")
        }
    }

    override fun onLive() {
        val c = convo ?: return
        val target = assistants.current
        gemini.copy("桌宠 → ${target.label}", GeminiBridgeTextBuilder.buildLiveOpening(c.reply.hookText))
        c.gemini = GeminiExit.LIVE
        stats.add(Stats.Kind.GEMINI_LIVE)
        endConversation()
        val pkg = assistants.packageOf(target)
        if (pkg != null && gemini.launch(pkg) == GeminiLauncher.Outcome.LAUNCHED) {
            gemini.toast("${target.liveHint}。开场白已复制，可以照着念")
            startSession(SessionMode.LIVE, target, pkg)
        } else {
            gemini.toast("没打开 ${target.label}，开场白已复制")
        }
    }

    // ---------- 收尾和记录 ----------

    private fun endConversation() {
        val c = convo ?: run { overlay.hideBubble(); return }
        convo = null
        c.ended = true
        closeRunnable?.let { handler.removeCallbacks(it) }
        voice.cancel()
        overlay.setListening(false)
        overlay.setTalking(false)
        overlay.hideBubble()
        overlay.retreat()
        overlay.setMood(Mood.IDLE)
        if (!c.userStarted && c.userReply == null && c.gemini == GeminiExit.NONE) stats.add(Stats.Kind.IGNORED)
        if (!c.userStarted && c.userReply == null && c.gemini == GeminiExit.NONE) {
            stateStore.apply(StateEvent.PROACTIVE_IGNORED)
            c.topicId?.let { topics.dismiss(it) }
        }
        // 用户点开又什么都没说的空对话，不记
        if (c.userStarted && c.userReply == null) return
        if (TodayLog.wantsForget(c.userReply)) return
        val line = TodayLog.format(
            Exchange(
                timeText = java.time.Instant.ofEpochMilli(c.shownAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(HHMM),
                sceneLabel = c.decision.scene.label,
                hook = c.reply.hookText.ifEmpty { "（他先开口）" },
                userReply = c.userReply,
                petReply = c.petReply,
                gemini = c.gemini,
            ),
        )
        val hook = c.reply.hookText.ifEmpty { "（他先开口）" }
        val userAt = if (c.userReply != null) c.userReplyAt else c.shownAt
        val petAt = if (c.petReply != null) c.petReplyAt else c.shownAt
        scope.launch(Dispatchers.IO) {
            synchronized(app.memoryLock) {
                val ids = mutableListOf<String>()
                ids += app.events.append(EventKind.PROACTIVE, hook, occurredAt = c.shownAt, source = "proactive", conversationId = c.id).id
                c.userReply?.let { ids += app.events.append(EventKind.USER_INPUT, it, occurredAt = userAt, source = "chat", conversationId = c.id).id }
                c.petReply?.let { ids += app.events.append(EventKind.PET_REPLY, it, occurredAt = petAt, source = "chat", conversationId = c.id).id }
                app.events.append(EventKind.EXCHANGE, line, occurredAt = now(), source = "chat", conversationId = c.id, sourceIds = ids)
            }
        }
    }

    // ---------- AI 会话（守住和 Gemini / ChatGPT / 千问 的对话） ----------

    private var session: ChatSession? = null
    private var sessionApp = AssistantApp.GEMINI
    private var sessionPkg = ""
    private var sessionPrompt: String? = null
    private var lastSessionText = ""
    /** 用户在 AI App 里手动结束会话后，先离开一次再允许自动开始。 */
    private var autoStartBlocked = false
    private var lastGeminiSeenAt = 0L

    private fun startSession(mode: SessionMode, target: AssistantApp, pkg: String) {
        val now = now()
        val existing = session
        if (existing != null) {
            if (existing.mode == mode && sessionPkg == pkg) return
            finishSession(existing.end(now).summary, announce = false)
        }
        endConversation()
        endThink()
        sessionApp = target
        sessionPkg = pkg
        session = ChatSession(mode, now, target.sessionPackages(pkg), allowedForSession(), settings.sessionConfig())
        settings.sessionMode = mode.name
        settings.sessionStartedAt = now
        settings.sessionAssistant = target.name
        settings.sessionPackage = pkg
        stats.add(Stats.Kind.SESSIONS)
        overlay.setInSession(true)
    }

    private fun restoreSession() {
        val mode = runCatching { SessionMode.valueOf(settings.sessionMode) }.getOrNull() ?: return
        sessionApp = AssistantApp.fromName(settings.sessionAssistant)
        sessionPkg = settings.sessionPackage.ifBlank { assistants.packageOf(sessionApp) ?: return }
        val startedAt = settings.sessionStartedAt.takeIf { it > 0 } ?: now()
        session = ChatSession(mode, startedAt, sessionApp.sessionPackages(sessionPkg), allowedForSession(), settings.sessionConfig())
        overlay.setInSession(true)
    }

    private fun allowedForSession() = settings.sessionAllowedApps + settings.learningApps

    /** 自己打开 Gemini / ChatGPT / 千问 并停留一会儿，也算开始一次会话。 */
    private fun maybeAutoStartSession(now: Long): Boolean {
        val fg = PerceptionHub.trail.foreground ?: return false
        val since = PerceptionHub.trail.foregroundSince ?: return false
        var target = assistants.detect(fg)
        var pkg = fg
        if (target == AssistantApp.GEMINI) lastGeminiSeenAt = now
        // Google App 也可能托管 Gemini 的界面：只有刚从 Gemini 跳过去时才算，平时用 Google 搜索不算
        if (target == null && fg == AssistantApp.GOOGLE_APP && now - lastGeminiSeenAt < 5 * 60_000L) {
            target = AssistantApp.GEMINI
            pkg = assistants.packageOf(AssistantApp.GEMINI) ?: return false
        }
        if (target == null) { autoStartBlocked = false; return false }
        if (!settings.sessionAutoStart || autoStartBlocked || convo != null || think != null || probeJob?.isActive == true) return false
        if (now - since < settings.sessionAutoStartSec.coerceAtLeast(5) * 1000L) return false
        startSession(SessionMode.TEXT, target, pkg)
        return true
    }

    private fun handleSession(s: ChatSession, now: Long) {
        s.allowedPackages = allowedForSession()
        s.config = settings.sessionConfig()
        val fg = PerceptionHub.trail.foreground
        if (sessionApp == AssistantApp.GEMINI && fg == sessionPkg) lastGeminiSeenAt = now
        when (val action = s.update(now, fg, active)) {
            SessionAction.None -> Unit
            SessionAction.Back -> hideSessionPrompt()
            is SessionAction.Countdown -> if (settings.sessionPullBack) showCountdown(action)
            SessionAction.PullBack -> if (settings.sessionPullBack) pullBack()
            SessionAction.AskEnd -> if (settings.sessionPullBack) showAskEnd()
            is SessionAction.Ended -> finishSession(action.summary, announce = active)
        }
    }

    private fun showCountdown(c: SessionAction.Countdown) {
        val name = sessionApp.label
        val text = if (c.allowed) "还在跟 $name 聊呢。这个 App 多给你一会儿，${c.secondsLeft} 秒后带你回去"
        else "还在跟 $name 聊呢，${c.secondsLeft} 秒后带你回去"
        if (sessionPrompt == PROMPT_COUNTDOWN && overlay.isBubbleShown) {
            if (text != lastSessionText) overlay.updateBubbleText(text)
            lastSessionText = text
            return
        }
        sessionPrompt = PROMPT_COUNTDOWN
        lastSessionText = text
        haptics.tap()
        overlay.attention()
        overlay.showBubble(
            BubbleSpec(
                text, instant = true,
                actions = listOf(
                    BubbleAction("现在回去", highlighted = true) { session?.let { if (it.returnNow() == SessionAction.PullBack) pullBack() } },
                    BubbleAction("给我 5 分钟") { session?.pause(now()); hideSessionPrompt() },
                    BubbleAction("结束会话") { endSessionByUser() },
                ),
            ),
        )
    }

    private fun showAskEnd() {
        sessionPrompt = PROMPT_ASK
        haptics.tap()
        overlay.showBubble(
            BubbleSpec(
                "这几分钟拉了你好几次，看来你在忙别的。这次和 ${sessionApp.label} 的会话要结束吗？",
                instant = true,
                actions = listOf(
                    BubbleAction("结束会话", highlighted = true) { endSessionByUser() },
                    BubbleAction("继续守着") { session?.keepGoing(now()); hideSessionPrompt() },
                ),
            ),
        )
    }

    private fun showSessionMenu() {
        val s = session ?: return
        val minutes = (now() - s.startedAt) / 60_000
        val modeText = if (s.mode == SessionMode.LIVE) "一起看" else "文字深聊"
        val status = when {
            s.isPaused -> "，暂停中"
            s.mode == SessionMode.LIVE -> "，切出去不会拉你回来"
            !settings.sessionPullBack -> "，只记录不拉回"
            else -> ""
        }
        sessionPrompt = PROMPT_MENU
        val actions = mutableListOf(
            BubbleAction("回 ${sessionApp.label}", highlighted = true) { pullBack() },
            BubbleAction("结束会话") { endSessionByUser() },
        )
        if (s.isPaused) actions.add(1, BubbleAction("继续守着") { session?.keepGoing(now()); hideSessionPrompt() })
        overlay.showBubble(BubbleSpec("${sessionApp.label} 会话中（$modeText）· 已经 $minutes 分钟$status", instant = true, actions = actions))
    }

    private fun pullBack() {
        hideSessionPrompt()
        when (gemini.launch(sessionPkg, adjacent = false)) {
            GeminiLauncher.Outcome.LAUNCHED -> stats.add(Stats.Kind.PULLBACKS)
            else -> gemini.toast("没能打开 ${sessionApp.label}。vivo 上要在应用权限里允许“后台弹出界面”")
        }
    }

    private fun endSessionByUser() {
        val s = session ?: return
        if (s.isAssistant(PerceptionHub.trail.foreground)) autoStartBlocked = true
        finishSession(s.end(now()).summary, announce = false)
    }

    private fun finishSession(summary: SessionSummary, announce: Boolean) {
        session = null
        settings.sessionMode = ""
        overlay.setInSession(false)
        hideSessionPrompt()
        scheduler.onSpoke(now()) // 会话刚结束别马上搭话
        val minutes = summary.durationMillis / 60_000
        val mode = if (summary.mode == SessionMode.LIVE) "一起看" else "文字深聊"
        val line = "- ${HHMM.format(java.time.Instant.ofEpochMilli(summary.startedAt).atZone(java.time.ZoneId.systemDefault()))} | ${sessionApp.label} 会话 | " +
            "$mode，$minutes 分钟，离开 ${summary.leaves} 次，拉回 ${summary.pullBacks} 次（${summary.reason.label}）"
        scope.launch(Dispatchers.IO) { synchronized(app.memoryLock) { app.events.append(EventKind.SESSION, line, source = "session") } }
        if (announce && summary.reason != EndReason.USER) {
            overlay.showBubble(BubbleSpec("和 ${sessionApp.label} 的会话结束了（${summary.reason.label}），聊了 $minutes 分钟。", textOnly = true)) {
                handler.postDelayed({ if (convo == null && session == null && think == null) overlay.hideBubble() }, 5000)
            }
        }
    }

    private fun hideSessionPrompt() {
        if (sessionPrompt != null) overlay.hideBubble()
        sessionPrompt = null
        lastSessionText = ""
    }

    // ---------- 划过留痕 ----------

    private val skim = SkimBuffer()
    private val skimCapture = Runnable { captureSkim() }

    /** 无障碍收到滚动事件时调用：停下 0.7 秒后读一次屏幕。 */
    private fun onScrollEvent(pkg: String) {
        if (!settings.skimEnabled || !active) return
        if (!skimAllowed(pkg)) return
        handler.removeCallbacks(skimCapture)
        handler.postDelayed(skimCapture, 700)
    }

    private fun skimAllowed(pkg: String?): Boolean {
        if (pkg == null || pkg == context.packageName) return false
        val lists = settings.appLists()
        // 信息流不给内容加热、排除 App 不读；AI App 里的对话本身也不收
        if (pkg in lists.feed || pkg in lists.exclude) return false
        if (assistants.detect(pkg) != null || pkg == AssistantApp.GOOGLE_APP) return false
        return true
    }

    private fun captureSkim() {
        val fg = PerceptionHub.trail.foreground
        if (!active || !skimAllowed(fg)) return
        skim.add(now(), fg!!, PerceptionHub.readScreen())
    }

    // ---------- 想一想（提问模式） ----------

    private inner class ThinkFlow(val material: String, val source: String, val appName: String) {
        var mode = ThinkMode.SOCRATIC
        var result: ThinkResult? = null
        var questionIndex = 0
        var awaitingAnswer = false
        val qa = mutableListOf<Pair<String, String>>()
        var touchedAt = now()
        val question: String? get() = result?.questions?.getOrNull(questionIndex)
    }

    private var think: ThinkFlow? = null

    private fun thinkMaterial(): ThinkFlow? {
        val now = now()
        val fg = PerceptionHub.trail.foreground
        val appName = fg?.let { label(it) } ?: "手机"
        if (!skim.isEmpty(now)) {
            val parts = skim.recent(now, ThinkPrompt.MAX_MATERIAL_CHARS)
            return ThinkFlow(parts.joinToString("\n") { it.text }, "刚才划过的 ${parts.size} 段", parts.lastOrNull()?.pkg?.let { label(it) } ?: appName)
        }
        if (!skimAllowed(fg)) return null
        val text = ScreenTextCleaner.clean(PerceptionHub.readScreen(), ThinkPrompt.MAX_MATERIAL_CHARS)
        if (cn.companion.pet.core.text.UnicodeText.count(text) < 40) return null
        return ThinkFlow(text, "当前屏幕", appName)
    }

    private fun openThinkMenu() {
        endConversation()
        val flow = thinkMaterial()
        if (flow == null) {
            overlay.showBubble(BubbleSpec("屏幕上没读到正文。先滑一会儿要看的内容，再叫我。", textOnly = true)) {
                handler.postDelayed({ if (think == null && convo == null) overlay.hideBubble() }, 4000)
            }
            return
        }
        think = flow
        val actions = mutableListOf<BubbleAction>()
        val fromSkim = flow.source.startsWith("刚才")
        ThinkMode.entries.filter { it != ThinkMode.RECAP || fromSkim }.forEach { m ->
            actions += BubbleAction(m.label, highlighted = m == ThinkMode.RECAP) { runThink(m) }
        }
        actions += BubbleAction("×") { endThink() }
        overlay.setMood(Mood.CURIOUS)
        overlay.showBubble(BubbleSpec("想怎么想？（素材：${flow.source}）", instant = true, actions = actions))
    }

    private fun runThink(mode: ThinkMode) {
        val flow = think ?: return
        flow.mode = mode
        flow.touchedAt = now()
        val key = apiKey()
        if (key == null || app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()) <= 0) {
            thinkNotice(if (key == null) "还没填模型的 API Key，到设置里填一下。" else "今天的调用次数用完了，明天再陪你想。")
            return
        }
        overlay.setMood(Mood.THINKING)
        overlay.showBubble(BubbleSpec("……", textOnly = true))
        probeJob = scope.launch {
            val messages = withContext(Dispatchers.IO) {
                synchronized(app.memoryLock) { ThinkPrompt.build(memory.persona(), memory.profile(), mode, flow.material) }
            }
            if (!app.budget.reserve(settings.dailyLimit, LocalDate.now().toString())) {
                thinkNotice("今天的调用次数用完了，明天再陪你想。")
                return@launch
            }
            stats.add(Stats.Kind.API_CALLS)
            val r = app.client.complete(
                ChatRequest(settings.endpoint, settings.model, key, messages, maxTokens = ThinkPrompt.MAX_TOKENS, timeoutMillis = ThinkPrompt.TIMEOUT_MILLIS),
            )
            if (think !== flow) return@launch
            val result = (r as? ChatResult.Success)?.let { ThinkPrompt.parse(it.content) }
            if (result == null) { thinkNotice("这次没想出来，等会儿再试试。"); return@launch }
            flow.result = result
            flow.questionIndex = 0
            showThinkResult(flow)
        }
    }

    private fun showThinkResult(flow: ThinkFlow) {
        val r = flow.result ?: return
        overlay.setMood(Mood.CURIOUS)
        val actions = mutableListOf<BubbleAction>()
        if (flow.question != null) {
            flow.awaitingAnswer = true
            if (voice.available) actions += BubbleAction("说一句", highlighted = true) { thinkVoice { answerThink(it) } }
            actions += BubbleAction("打字") { overlay.openBubbleInput() }
            if (r.questions.size > 1) actions += BubbleAction("换个问题") { nextQuestion() }
        }
        actions += BubbleAction("带去 ${assistants.current.label}") { thinkToAssistant() }
        actions += BubbleAction("×") { endThink() }
        val text = if (flow.questionIndex == 0) ThinkPrompt.display(flow.mode, r) else "问你：${flow.question}"
        overlay.showBubble(BubbleSpec(text, actions = actions))
    }

    private fun nextQuestion() {
        val flow = think ?: return
        val n = flow.result?.questions?.size ?: return
        flow.questionIndex = (flow.questionIndex + 1) % n
        flow.touchedAt = now()
        showThinkResult(flow)
    }

    private fun answerThink(answer: String?) {
        val flow = think ?: return
        val q = flow.question ?: return
        val said = answer?.trim().orEmpty()
        if (said.isEmpty()) { showThinkResult(flow); return }
        flow.awaitingAnswer = false
        flow.touchedAt = now()
        flow.qa += q to said
        val key = apiKey()
        if (key == null || app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()) <= 0) { showThinkFollowUp(flow, "记下了。"); return }
        overlay.setMood(Mood.THINKING)
        overlay.showBubble(BubbleSpec("……", textOnly = true))
        probeJob = scope.launch {
            val messages = withContext(Dispatchers.IO) {
                synchronized(app.memoryLock) { ThinkPrompt.feedback(memory.persona(), flow.mode, flow.material, q, said) }
            }
            if (!app.budget.reserve(settings.dailyLimit, LocalDate.now().toString())) {
                showThinkFollowUp(flow, "记下了。")
                return@launch
            }
            stats.add(Stats.Kind.API_CALLS)
            val r = app.client.complete(ChatRequest(settings.endpoint, settings.model, key, messages, maxTokens = 250, timeoutMillis = ThinkPrompt.TIMEOUT_MILLIS))
            if (think !== flow) return@launch
            val reply = (r as? ChatResult.Success)?.let { ThinkPrompt.parseFeedback(it.content) } ?: "嗯，我听到了。"
            showThinkFollowUp(flow, reply)
        }
        // 他自己的回答是他的想法，记进今天的流水（以后整理档案能用上）
        val line = "- ${timeText()} | 想一想·${flow.mode.label} | 问：$q | 他答：${said.replace('\n', ' ')}"
        if (!TodayLog.wantsForget(said)) scope.launch(Dispatchers.IO) { synchronized(app.memoryLock) { app.events.append(EventKind.EXCHANGE, line, source = "think") } }
    }

    private fun showThinkFollowUp(flow: ThinkFlow, reply: String) {
        overlay.setMood(Mood.HAPPY)
        val actions = mutableListOf<BubbleAction>()
        val total = flow.result?.questions?.size ?: 0
        if (flow.qa.size < total) actions += BubbleAction("下一个问题") {
            flow.questionIndex = (flow.questionIndex + 1) % total
            showThinkResult(flow)
        }
        actions += BubbleAction("带去 ${assistants.current.label}", highlighted = true) { thinkToAssistant() }
        actions += BubbleAction("×") { endThink() }
        overlay.showBubble(BubbleSpec(reply, actions = actions))
    }

    private fun thinkToAssistant() {
        val flow = think ?: return
        val text = ThinkPrompt.bridgeText(timeText(), flow.appName, flow.mode, flow.material, flow.result, flow.qa)
        endThink()
        stats.add(Stats.Kind.GEMINI_TEXT)
        sendToAssistant(text)
    }

    private fun thinkNotice(text: String) {
        overlay.setMood(Mood.IDLE)
        overlay.showBubble(BubbleSpec(text, textOnly = true)) { handler.postDelayed({ if (convo == null) endThink() }, 4000) }
    }

    private fun endThink() {
        if (think == null && noteDraft == null) return
        think = null
        noteDraft = null
        voice.cancel()
        overlay.setListening(false)
        overlay.setBubbleFocusable(false)
        overlay.hideBubble()
        overlay.setMood(Mood.IDLE)
    }

    /** 点一下开始听，停顿后自动结束。 */
    private fun thinkVoice(onResult: (String?) -> Unit) {
        think?.touchedAt = now()
        overlay.setListening(true)
        overlay.updateBubbleText("在听……说完停一下就好")
        voice.start { text ->
            overlay.setListening(false)
            onResult(text)
        }
    }

    // ---------- 念头速记 ----------

    private data class NoteDraft(val app: String, val quote: String, val startedAt: Long)

    private var noteDraft: NoteDraft? = null
    private var lastSavedNote: LocalDate? = null

    private fun startNote() {
        endConversation()
        val fg = PerceptionHub.trail.foreground
        val quote = if (skimAllowed(fg)) {
            PerceptionHub.readScreen().firstOrNull { it.inScrollableContainer && cn.companion.pet.core.text.UnicodeText.count(it.text.trim()) >= 12 }?.text?.trim().orEmpty()
        } else ""
        noteDraft = NoteDraft(fg?.let { label(it) } ?: "手机", quote, now())
        val actions = mutableListOf<BubbleAction>()
        if (voice.available) actions += BubbleAction("说一句", highlighted = true) { thinkVoice { saveNote(it) } }
        actions += BubbleAction("打字") { overlay.openBubbleInput() }
        actions += BubbleAction("×") { endThink() }
        overlay.setMood(Mood.CURIOUS)
        overlay.showBubble(BubbleSpec("想到什么了？我帮你记下来。", instant = true, actions = actions))
    }

    private fun saveNote(text: String?) {
        val d = noteDraft ?: return
        val said = text?.trim().orEmpty()
        if (said.isEmpty()) { startNote(); return }
        noteDraft = null
        val today = LocalDate.now()
        val t = ThoughtNotes.Thought(timeText(), d.app, d.quote, said)
        scope.launch(Dispatchers.IO) {
            synchronized(app.memoryLock) {
                app.thoughts.add(today, t)
                val noteEvent = app.events.append(EventKind.NOTE, said, source = "note")
                app.events.append(EventKind.EXCHANGE, "- " + t.time + " | 念头 | " + said.replace('\n', ' '), source = "note", sourceIds = listOf(noteEvent.id))
                stateStore.apply(StateEvent.USER_NOTE)
                topics.enqueue(said, listOf(noteEvent.id))
            }
        }
        lastSavedNote = today
        overlay.setMood(Mood.HAPPY)
        overlay.showBubble(
            BubbleSpec(
                "记下了：$said", instant = true,
                actions = listOf(
                    BubbleAction("撤销") {
                        scope.launch(Dispatchers.IO) { synchronized(app.memoryLock) { app.thoughts.removeLast(today) } }
                        overlay.hideBubble()
                    },
                    BubbleAction("好") { overlay.hideBubble() },
                ),
            ),
        ) { handler.postDelayed({ if (convo == null && think == null && noteDraft == null && session == null) overlay.hideBubble() }, 5000) }
    }

    // ---------- 信 ----------

    private var letterWaiting: Pair<LocalDate, String>? = null

    private fun checkLetter() {
        scope.launch {
            val l = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { memory.unreadLetter() } }
            if (l != null && l.first >= LocalDate.now().minusDays(2)) {
                letterWaiting = l
                overlay.setHoldingLetter(true)
                overlay.attention()
            }
        }
    }

    private fun showLetterIfAny(): Boolean {
        val l = letterWaiting ?: return false
        letterWaiting = null
        overlay.setHoldingLetter(false)
        overlay.setMood(Mood.HAPPY)
        overlay.showBubble(BubbleSpec("给你的信：\n${l.second}", textOnly = true)) { handler.postDelayed({ if (convo == null) overlay.hideBubble() }, 12_000) }
        scope.launch(Dispatchers.IO) { synchronized(app.memoryLock) { memory.markLetterRead(l.first) } }
        return true
    }

    // ---------- 工具 ----------

    private fun classify(now: Long): SceneDecision = SceneClassifier.classify(
        now, PerceptionHub.trail, settings.appLists(), launchers,
    ) { ScreenTextCleaner.clean(PerceptionHub.readScreen(), ScreenTextCleaner.PROBE_MAX_CHARS) }

    private fun buildOpeningSync(decision: SceneDecision, userQuery: String? = null): List<ChatMessage> = synchronized(app.memoryLock) {
        openingMessages(
            ProbeMoment(timeText(), decision.scene, PerceptionHub.trail.foreground?.let { label(it) } ?: "不清楚", trajectory(now()), decision.screenText, petState = life.state.label),
            decision.screenText,
            userQuery,
        )
    }

    /** 连续性开关打开时走有界召回；关掉时仍用旧的整份 profile 路径。 */
    private fun openingMessages(moment: ProbeMoment, material: String?, userQuery: String? = null): List<ChatMessage> =
        if (settings.continuityEnabled) {
            assembler.assemble(memory.persona(), moment, material, now(), memory.tombstones(), userQuery = userQuery)
        } else {
            val profile = ProfileRules.applyTombstones(memory.profileLines(), memory.tombstones()).joinToString("\n")
            val diary = app.nightly.diarySummary(LocalDate.now())
            val shared = app.materialInbox.contextBlock(now())
            ProbePrompt.opening(memory.persona(), profile, recentExchangeLines(20), diary, moment).map {
                if (it.role == cn.companion.pet.net.ChatRole.USER && shared.isNotEmpty()) it.copy(content = it.content + "\n\n" + shared) else it
            }
        }

    /** Drop in-flight answers if the user changed the memory they were based on. */
    private fun memoryVersion(): String = cn.companion.pet.core.memory.NightlyStore.hash(
        listOf(memory.persona(), memory.profile(), memory.tombstones().sorted().joinToString("\n"), app.facts.versionHash(), app.materialInbox.versionHash()).joinToString("\u0000"),
    )

    private fun apiKey(): String? = app.apiKey()

    private fun trajectory(now: Long) = PerceptionHub.trail.summary(now, ::label)

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun queryLaunchers(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
    }

    /** 最近的真实交流行（开口/回应/念头等），用于探针和夜间整理的上下文。 */
    private fun recentExchangeLines(limit: Int): List<String> {
        val snapshot = app.events.all()
        val visibility = cn.companion.pet.core.companion.EventVisibility(snapshot, memory.tombstones())
        return snapshot
            .filter { it.kind == EventKind.EXCHANGE || it.kind == EventKind.LEGACY_LINE }
            .filter { visibility.allows(it) }
            .map { it.text }
            .takeLast(limit)
    }

    private fun timeText(): String = LocalTime.now().format(HHMM)

    private fun now() = System.currentTimeMillis()

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        private const val PROMPT_COUNTDOWN = "countdown"
        private const val PROMPT_ASK = "ask"
        private const val PROMPT_MENU = "menu"
    }
}
