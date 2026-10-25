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
import cn.companion.pet.core.goal.GoalStatus
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
import cn.companion.pet.core.training.BuiltInQuestions
import cn.companion.pet.core.training.EnforcementAction
import cn.companion.pet.core.training.TrainingStore
import cn.companion.pet.core.training.TrainingTimer
import cn.companion.pet.core.training.TrainingFocusBridge
import cn.companion.pet.core.training.RestGrantStore
import cn.companion.pet.core.training.FocusPolicyType
import cn.companion.pet.core.training.EnforcementRules
import cn.companion.pet.core.training.InteractionMode
import cn.companion.pet.core.training.SlotKind
import cn.companion.pet.core.training.TrainingController
import cn.companion.pet.core.training.TrainingOutcome
import cn.companion.pet.core.training.TrainingPrompt
import cn.companion.pet.core.training.TrainingSession
import cn.companion.pet.core.training.TriggerReason
import cn.companion.pet.core.training.AssistanceLevel
import cn.companion.pet.core.continuity.StrictCriteria
import cn.companion.pet.core.continuity.StrictEvalPrompt
import cn.companion.pet.core.continuity.Phase
import cn.companion.pet.core.continuity.StrictOutcome
import cn.companion.pet.core.continuity.StrictSessionMachine
import cn.companion.pet.core.continuity.StrictSessionState
import cn.companion.pet.core.continuity.StrictSessionStore
import cn.companion.pet.core.think.ThinkMode
import cn.companion.pet.core.think.ThinkPrompt
import cn.companion.pet.core.think.ThinkResult
import cn.companion.pet.core.think.ThoughtNotes
import cn.companion.pet.core.session.SessionAction
import cn.companion.pet.core.session.SessionMode
import cn.companion.pet.core.session.SessionSummary
import cn.companion.pet.core.text.ScreenTextCleaner
import cn.companion.pet.data.Stats
import cn.companion.pet.net.CallDiagnosis
import cn.companion.pet.net.CallStatus
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
    private val skills by lazy {
        skillsInitialized = true
        SkillPetFlow(app, overlay, scope, handler, ::label) { overlay.setMood(Mood.IDLE) }
    }
    // ---------- 专注守护（移植自 deepseek/c3-focus-guard，按审计修过）：唯一的“拉回”拥有者 ----------
    private val focusGate by lazy { cn.companion.pet.core.focus.ForegroundGate(context.packageName) }
    private val focusOverlay: cn.companion.pet.overlay.FocusOverlay by lazy {
        cn.companion.pet.overlay.FocusOverlay(context, { PerceptionHub.service },
            onReturn = { if (blockOwner == BlockOwner.TRAINING) trainingBlockReturn() else focusController.userReturn() },
            onPause = { if (blockOwner == BlockOwner.TRAINING) trainingBlockPause() else focusController.pause() },
            onAbort = { if (blockOwner == BlockOwner.TRAINING) trainingBlockEnd() else endFocusByUser() })
    }
    /** 拦截层当前替谁拦：守护优先；守护不在时训练才能用它。同一时间只有一个拉回者。 */
    private enum class BlockOwner { FOCUS, TRAINING }
    private var blockOwner = BlockOwner.FOCUS
    private val focusController: cn.companion.pet.core.focus.FocusController by lazy {
        cn.companion.pet.core.focus.FocusController(
            store = cn.companion.pet.core.focus.FocusStore(memory.root),
            device = object : cn.companion.pet.core.focus.FocusDevicePort {
                override fun launch(pkg: String): Boolean = gemini.launch(pkg, adjacent = false) == GeminiLauncher.Outcome.LAUNCHED
                override fun activePackage(): String? = PerceptionHub.activePackage()?.takeUnless { focusGate.isTransient(it) }
            },
            ui = object : cn.companion.pet.core.focus.FocusUiPort {
                override fun showBlock(targetLabel: String, reason: String): cn.companion.pet.core.focus.OverlayResult {
                    blockOwner = BlockOwner.FOCUS
                    return focusOverlay.show(targetLabel, reason)
                }
                override fun hideBlock() { if (blockOwner == BlockOwner.FOCUS) focusOverlay.hide() }
                override fun remind(targetLabel: String, text: String) { showFocusRemind(text) }
                override fun ended(targetLabel: String, reason: cn.companion.pet.core.focus.FocusEndReason) { onFocusEnded(targetLabel, reason) }
            },
            scheduler = object : cn.companion.pet.core.focus.FocusScheduler {
                override fun post(delayMillis: Long, task: () -> Unit) { handler.postDelayed(task, delayMillis) }
            },
        )
    }
    private val focusActive: Boolean get() = focusController.active
    private val assembler by lazy { CompanionContextAssembler(app.events, app.facts, materials = app.materialInbox) }
    private val visibleMemory by lazy { VisibleMemoryReader(memory, app.events, app.facts) }
    private val stateStore by lazy { PetStateStore(memory.root) }
    private val strictStore by lazy { StrictSessionStore(memory.root) }
    /** 训练回合（双形态）。null 表示当前没有训练。 */
    private var training: TrainingController? = null
    private var trainingQuestion: cn.companion.pet.core.training.TrainingQuestion? = null
    private val enforcement = EnforcementRules()
    private var trainingMode = InteractionMode.WARM
    private val trainingStore by lazy { TrainingStore(memory.root) }
    private val restGrants by lazy { RestGrantStore(memory.root) }
    private val trainingTimer = TrainingTimer()
    private val focusBridge by lazy { TrainingFocusBridge() }
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
    /** 主线程和工作线程（Agent 组装上下文）都会查，用并发安全的表。 */
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
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
        /** C3：用户明确点 × / 点桌宠收起。 */
        var dismissed = false
        /** C3：走完了“被忽略”的升级。 */
        var ignoredFully = false
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
        // 设置页“运行”技能时交给桌宠来跑：进度和结果显示在气泡上，点桌宠可以停。
        app.skillLauncher = { id -> handler.post { endConversation(); skills.runSkill(id) } }
        memory.persona() // 首次运行写入默认人设
        weighty.restore(LocalDate.now().toString(), stats.get(Stats.Kind.WEIGHTY))
        overlay.show()
        restoreSession()
        // 严格回合跨重启恢复；到期/已结束的不复活。
        runCatching { restoreStrict() }
        // 训练回合跨重启恢复：回到同一题同一槽位，并如实说明空窗。
        runCatching { restoreTraining() }
        // 守护：杀进程/重启后恢复；已结束/过期/损坏的不复活。
        runCatching { focusController.restore() }
        app.focusStatus = { focusController.status() }
        active = isUserActive()
        if (active) onUserPresent()
        handler.post(ticker)
        app.events.addListener(agentListener)
        handler.postDelayed(agentTrigger, 1_200)
        PerceptionHub.onScroll = { pkg -> onScrollEvent(pkg) }
        // 前台确认只有一个入口：守护先看；守护不在时训练再看（训练内部也会让位给守护）。
        PerceptionHub.onForegroundConfirmed = { pkg ->
            runCatching { focusController.onForeground(pkg) }
            runCatching { onTrainingForeground(pkg) }
        }
        scope.launch {
            settings.changes.collect {
                scheduler.config = settings.rhythm()
                if (settings.presenceEnabled) {
                    app.presencePolicy.config = app.presencePolicy.config.copy(dailyCap = settings.presenceDailyCap)
                    if (settings.petPaused && !lastPausedSeen) app.presencePolicy.feedback(cn.companion.pet.core.presence.PresenceFeedback.PAUSED, now())
                }
                lastPausedSeen = settings.petPaused
                if (settings.agentAutonomy) scheduleAgentScan() else app.agentLoop.cancel()
            }
        }
    }

    fun stop() {
        app.skillLauncher = null
        if (skillsInitialized) skills.cancel()
        app.events.removeListener(agentListener)
        handler.removeCallbacks(agentTrigger)
        agentScanJob?.cancel()
        app.agentLoop.cancel()
        app.actions.cancelAll()
        PerceptionHub.onScroll = null
        PerceptionHub.onForegroundConfirmed = null
        app.focusStatus = null
        runCatching { focusOverlay.hide() }
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
        lookJob?.cancel()
        if (skillsInitialized) skills.cancel()
        voice.cancel()
        endConversation()
        // 严格回合/陪练跨锁屏保留：只收界面、停计时，亮屏后接着问（原来锁屏直接作废严格回合）。
        if (think?.strict?.state?.active == true) suspendThinkUi() else endThink()
        if (training != null) trainingTimer.pause(mono())
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
        if (training != null) {
            trainingTimer.resume(mono())
            handler.postDelayed({ if (training != null && convo == null) showTrainingAsk("回来了，接着这一步。") }, 1200)
        } else if (think?.strict?.state?.active == true) {
            handler.postDelayed({ if (convo == null) showStrictPrompt("回来了，接着刚才的。") }, 1200)
        }
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
        // 守护按秒推进（到期、宽限、重试），不受下面任何提前 return 影响；熄屏/锁屏时不拉回。
        runCatching { focusController.tick(interactive = active) }
        // 训练计时用单调时钟推进；熄屏/锁屏不催。
        runCatching { if (active) tickTraining(mono()) }
        if (convo == null && life.tick(now)) overlay.setLife(life.state)
        // Gemini 会话期间：只守着会话，不主动搭话
        session?.let { handleSession(it, now); return }
        if (!active) return
        // 陪练进行中：桌宠只陪这一题，不主动搭话、不自动开会话。
        if (training != null) return
        // 正在“想一想”或记念头：不插嘴；放着不管 3 分钟就收起
        if (think != null || noteDraft != null) {
            val since = think?.touchedAt ?: noteDraft!!.startedAt
            // 严格陪伴回合不因界面闲置被清掉；到期由状态机自己结算。
            if (think?.strict == null && now - since > 3 * 60_000L) endThink()
            tickStrict(now)
            return
        }
        if (maybeAutoStartSession(now)) return

        convo?.let { escalate(it, now); return }
        if (probeJob?.isActive == true || settings.petPaused) return
        // 忙时错过的积压事件，空闲后补扫一次；不是每 tick 都做模型请求。
        if (settings.agentAutonomy && now - lastAgentScanAt > 5_000) handler.post(agentTrigger)

        // C3：开启本机主动陪伴后，由协调器决定“微反应 / 本机提示 / 调模型 / 安静”，替代下面的旧逻辑。
        if (settings.presenceEnabled) { presenceTick(now); return }
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
        // 兜底：点开没说话 45 秒（留够时间点菜单）、或任何对话挂了 5 分钟，都自动收起
        if ((c.userStarted && !c.interacting && age > 45_000) || age > 5 * 60_000L) {
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
                c.ignoredFully = true
                endConversation()
                scheduler.onIgnoredFully(now)
                rephraseNext = true
            }
        }
    }

    // ---------- C3：本机主动性（开关打开时才走这里） ----------

    private var lastPausedSeen = false
    private var lastPresenceEvalAt = 0L
    /** 最近一次真实交流（用户回话/主动找它）；进程重启后从 0 开始。 */
    private var lastInteractionAt = 0L
    /** 角色表现参数的内存缓存：最多 5 分钟读一次文件，事件发生时直接用返回值更新。 */
    private var petStateCache: cn.companion.pet.core.companion.PetState? = null
    private var petStateReadAt = 0L

    private fun petState(now: Long): cn.companion.pet.core.companion.PetState {
        val cached = petStateCache
        if (cached != null && now - petStateReadAt < 5 * 60_000L) return cached
        return stateStore.current(now).also { petStateCache = it; petStateReadAt = now }
    }

    /** 用户自己找它：忽略计数清零，记下交流时间。 */
    private fun noteUserInitiated() {
        lastInteractionAt = now()
        if (settings.presenceEnabled) app.presencePolicy.userInitiated(now())
    }

    /** 此刻在忙什么（非空就不插嘴）。 */
    private fun presenceBusy(now: Long): String? = when {
        focusActive || (focusOverlay.isShown && blockOwner == BlockOwner.FOCUS) -> "守护"
        training != null -> "陪练"
        think?.strict?.state?.active == true -> "严格陪伴"
        restUntil != 0L && now < restUntil -> "休息"
        session != null -> "深聊"
        think != null -> "想一想"
        noteDraft != null -> "记念头"
        skillsBusy() -> "技能"
        lookJob?.isActive == true || now < lookNoticeUntil -> "看一眼"
        probeJob?.isActive == true -> "模型请求中"
        convo != null -> "对话中"
        else -> null
    }

    /**
     * 每 5 秒用内存里的轨迹评估一次；不读写文件、不调模型。只有到点时才会读一次待续话题。
     * 不新增计时器：挂在原来每秒一次的 tick 上。
     */
    private fun presenceTick(now: Long) {
        if (now - lastPresenceEvalAt < 5_000L) return
        lastPresenceEvalAt = now
        val trail = PerceptionHub.trail
        val lists = settings.appLists()
        val fg = trail.foreground
        val stuck = PerceptionHub.stuck.check(now)
        // 卡壳时才读一次屏幕判断是不是学习页（和旧逻辑一致：名单里或屏幕有大段正文）。
        val learning = fg != null && (fg in lists.learning || (stuck && classify(now).scene == Scene.LEARNING))
        val focus = SceneClassifier.focusActive(now, trail, lists)
        val state = petState(now)
        val budgetLeft = app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()) > 0
        val signals = cn.companion.pet.core.presence.PresenceSignals(
            now = now,
            foreground = fg,
            foregroundLabel = fg?.let { label(it) } ?: "手机",
            foregroundSince = trail.foregroundSince,
            distinctApps60s = trail.distinctAppsWithin(now, 60_000L),
            switches60s = trail.switchCountWithin(now, 60_000L),
            inFeed = fg != null && fg in lists.feed,
            inLearning = learning,
            inFocus = fg != null && fg in lists.focus,
            excluded = fg != null && fg in lists.exclude,
            inLauncher = fg != null && fg in launchers,
            stuck = stuck,
            scheduledDue = scheduler.isDue(now, focus),
            quiet = scheduler.isQuiet(now),
            userActive = active,
            paused = settings.petPaused,
            busy = presenceBusy(now),
            canCallModel = apiKey() != null && budgetLeft,
            sinceInteractionMillis = lastInteractionAt.takeIf { it > 0 }?.let { now - it },
            energy = state.energy,
            interest = state.interest,
        )
        var dueTopic: Topic? = null
        val d = app.presenceEngine.evaluate(signals) {
            if (!settings.continuityEnabled) false
            else topicGate.next(now = now, quiet = signals.quiet, userActive = active, lastProactiveAt = lastProactiveAt,
                recentTexts = recentExchangeLines(20).toSet()).also { dueTopic = it } != null
        }
        when (d.kind) {
            cn.companion.pet.core.presence.PresenceKind.MICRO_REACTION -> { playMicro(d); app.presenceEngine.commit(d, now) }
            cn.companion.pet.core.presence.PresenceKind.LOCAL_NUDGE -> if (showLocalNudge(d, signals)) app.presenceEngine.commit(d, now)
            cn.companion.pet.core.presence.PresenceKind.MODEL_REASONING -> {
                app.presenceEngine.commit(d, now)
                val reason = d.reasoning?.prompt.orEmpty() + if (d.reason.isNotBlank()) "（" + d.reason + "）" else ""
                speak(stuck = d.reasoning == cn.companion.pet.core.presence.ReasoningReason.STUCK,
                    topic = if (d.reasoning == cn.companion.pet.core.presence.ReasoningReason.SCHEDULED) dueTopic else null,
                    presenceReason = reason)
            }
            cn.companion.pet.core.presence.PresenceKind.SILENT -> if (d.consumeSchedule) scheduler.onSpoke(now)
        }
    }

    /** 微反应：只换表情/做一个已有的小动作，2.5 秒后回到平常；不震动、不说话。 */
    private fun playMicro(d: cn.companion.pet.core.presence.PresenceDecision) {
        val mood = when (d.micro) {
            cn.companion.pet.core.presence.MicroReaction.NOD, cn.companion.pet.core.presence.MicroReaction.CHEER -> Mood.HAPPY
            cn.companion.pet.core.presence.MicroReaction.LOOK -> Mood.CURIOUS
            cn.companion.pet.core.presence.MicroReaction.YAWN -> Mood.SLEEPY
            null -> return
        }
        overlay.setMood(mood)
        if (d.intense && d.micro == cn.companion.pet.core.presence.MicroReaction.LOOK) overlay.attention()
        handler.postDelayed({
            if (convo == null && probeJob?.isActive != true && think == null && noteDraft == null && !skillsBusy()) overlay.setMood(Mood.IDLE)
        }, 2_500)
    }

    /**
     * 本机提示：走原来的气泡和对话流程（回话、忽略升级、关闭、写日志都照旧），只是开场白来自本机规则、不调模型。
     * 用户回话后的接话仍走原来的回复流程（那是用户发起的交流）。
     */
    private fun showLocalNudge(d: cn.companion.pet.core.presence.PresenceDecision, s: cn.companion.pet.core.presence.PresenceSignals): Boolean {
        val text = d.nudgeText?.takeIf { it.isNotBlank() && !ContentGuard.violates(it) } ?: return false
        if (convo != null || probeJob?.isActive == true || presenceBusy(now()) != null || !active) return false
        val scene = when { s.inLearning -> Scene.LEARNING; s.inFeed -> Scene.WANDERING; else -> Scene.NORMAL }
        rephraseNext = false
        present(SceneDecision(scene, null), ProbeReply(text, "", Mood.CURIOUS, false, false),
            opening = listOf(ChatMessage(cn.companion.pet.net.ChatRole.SYSTEM, LOCAL_NUDGE_MARK)), noEscalation = false, haptic = false)
        return true
    }

    /** 当天短期记忆（只在调模型前、IO 线程、持有 memoryLock 时调用）。 */
    private fun todayDigest(): List<String> {
        val events = app.events.all()
        val visibility = EventVisibility(events, memory.tombstones(), now())
        val today = LocalDate.now().toString()
        val done = runCatching {
            app.skillStore.list().filter { it.lastRunAt > 0 && java.time.Instant.ofEpochMilli(it.lastRunAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() == today }
                .map { "技能「" + it.name + "」" + if (it.lastRunOk == true) "做完了" else "没做完" }
        }.getOrDefault(emptyList())
        return cn.companion.pet.core.presence.TodayDigest.build(events, visibility, today, topics.candidates(now()), done,
            forgotten = { memory.forgottenMatches(it) }, now = now())
    }

    // ---------- 专注守护：入口、提醒、结束 ----------

    /** 显式入口：先建立并保存契约，再打开目标；失败不留下守护。 */
    private fun startFocusGuard() {
        // 守护接管拉回：先撤下训练占用的拦截层。
        if (blockOwner == BlockOwner.TRAINING) runCatching { focusOverlay.hide() }
        val target = assistants.current
        val pkg = assistants.packageOf(target) ?: run { gemini.toast("没找到 ${target.label}，可以在设置里填包名"); return }
        val now = now()
        val strict = settings.focusEnforcement == "STRICT"
        val minutes = settings.focusMinutes.coerceIn(5, 180)
        val contract = cn.companion.pet.core.focus.FocusContract(
            sessionId = "focus-" + now,
            targetPackage = pkg,
            targetLabel = target.label,
            startedAt = now,
            expiresAt = now + minutes * 60_000L,
            mode = cn.companion.pet.core.focus.FocusMode.TEXT,
            enforcement = if (strict) cn.companion.pet.core.focus.FocusEnforcement.STRICT else cn.companion.pet.core.focus.FocusEnforcement.SOFT,
            allowedPackages = allowedForSession(),
            graceMillis = settings.sessionGraceSec.coerceIn(3, 600) * 1000L,
            allowedGraceMillis = settings.sessionAllowedGraceSec.coerceIn(3, 3600) * 1000L,
        )
        when (val r = focusController.start(contract, PerceptionHub.activePackage() ?: PerceptionHub.trail.foreground)) {
            cn.companion.pet.core.focus.StartResult.Started -> {
                if (session == null) startSession(SessionMode.TEXT, target, pkg)
                gemini.toast((if (strict) "已开始严格守护 " else "已开始守护 ") + target.label + "，$minutes 分钟")
            }
            is cn.companion.pet.core.focus.StartResult.Failed -> gemini.toast(r.reason)
        }
    }

    /** SOFT 提醒：桌宠气泡，不全屏。 */
    private fun showFocusRemind(text: String) {
        if (convo != null) endConversation()
        haptics.tap()
        overlay.attention()
        overlay.showBubble(BubbleSpec(text, instant = true, actions = listOf(
            BubbleAction("现在回去", highlighted = true) { overlay.hideBubble(); focusController.userReturn() },
            BubbleAction("暂停 5 分钟") { overlay.hideBubble(); focusController.pause() },
            BubbleAction("结束守护") { overlay.hideBubble(); endFocusByUser() },
        )))
    }

    /** 紧急退出 / 结束守护：守护和会话一起停，不会被任何自动逻辑重启。 */
    private fun endFocusByUser() {
        if (focusActive) focusController.abort()
        if (session != null) endSessionByUser()
    }

    private fun onFocusEnded(label: String, reason: cn.companion.pet.core.focus.FocusEndReason) {
        if (reason == cn.companion.pet.core.focus.FocusEndReason.USER_ABORTED) return
        overlay.showBubble(BubbleSpec("和 $label 的守护结束了（${reason.label}）。", textOnly = true)) {
            handler.postDelayed({ if (convo == null && think == null) overlay.hideBubble() }, 5000)
        }
    }

    // ---------- 开口 ----------

    private fun speak(stuck: Boolean, afterHeartbeat: Boolean = false, topic: Topic? = null, presenceReason: String = "") {
        val now = now()
        if (probeJob?.isActive == true || lookJob?.isActive == true || now() < lookNoticeUntil || skillsBusy() || convo != null || session != null || think != null || noteDraft != null) return
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
            presenceReason = presenceReason,
        )

        probeJob = scope.launch {
            overlay.setMood(Mood.THINKING)
            val (opening, inputVersion) = withContext(Dispatchers.IO) {
                synchronized(app.memoryLock) {
                    app.materialInbox.purgeExpired(now())
                    val eligible = topic == null || topics.candidates(now()).any { it.id == topic.id }
                    val withToday = if (settings.presenceEnabled) moment.copy(today = todayDigest()) else moment
                    openingMessages(if (eligible) withToday else withToday.copy(topic = null), decision.screenText) to memoryVersion()
                }
            }
            if (!app.budget.reserve(settings.dailyLimit, LocalDate.now().toString())) {
                overlay.setMood(Mood.IDLE)
                scheduler.retrySoon(now())
                return@launch
            }
            stats.add(Stats.Kind.API_CALLS)
            val result = app.client.complete(
                ChatRequest(settings.endpoint, settings.model, key, opening, maxTokens = ProbePrompt.REPLY_MAX_TOKENS, timeoutMillis = ProbePrompt.TIMEOUT_MILLIS),
            )
            overlay.setMood(Mood.IDLE)
            val unchanged = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { inputVersion == memoryVersion() } }
            if (!unchanged) { scheduler.retrySoon(now()); return@launch }
            val reply = (result as? ChatResult.Success)?.let { ProbeReplyParser.parse(it.content) }
            if (result is ChatResult.Success && reply == null) noteBadFormat()
            when {
                reply == null -> scheduler.onSpoke(now()) // 失败或解析失败：静默，按正常节奏下次再来
                reply.skip || ContentGuard.violates(reply.hookText) -> scheduler.retrySoon(now())
                !active || convo != null || lookJob?.isActive == true || now() < lookNoticeUntil || skillsBusy() -> scheduler.retrySoon(now())
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
        if (probeJob?.isActive == true || lookJob?.isActive == true || now() < lookNoticeUntil || skillsBusy() || convo != null || session != null || think != null || noteDraft != null) return
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
                    agentScanner.next(snapshot, app.agentEventLedger.processed(now), now, visibility,
                        app.agentEventLedger.highWater())
                }
            } ?: return@launch
            if (!settings.agentAutonomy || !active || settings.petPaused || scheduler.isQuiet(now())) return@launch
            // 最早那条还在退避期就等它，不跳到后面的事件（跳过会把高水位推过它）。
            val retryAt = withContext(Dispatchers.IO) { app.agentEventLedger.retryAt(event.id) }
            if (retryAt > now()) return@launch
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
                    val fgPackage = PerceptionHub.trail.foreground
                    AgentContext(
                        trigger = if (event.kind == EventKind.MATERIAL) AgentTrigger.MATERIAL_SAVED else AgentTrigger.NOTE_SAVED,
                        now = now,
                        day = LocalDate.now().toString(),
                        scene = decision.scene.name,
                        foreground = fgPackage?.let { label(it) } ?: "不清楚",
                        foregroundPackage = fgPackage,
                        memoryLines = lines,
                        materialTitles = titles,
                        eventIds = ids,
                        budgetRemaining = app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()),
                        eventId = event.id,
                        quiet = scheduler.isQuiet(now),
                        userActive = active,
                        lastActionAt = app.agentTrace.lastAt(),
                        observation = app.screenObservations.latestValid(now)?.summary,
                        observationId = app.screenObservations.latestValid(now)?.id,
                    )
                }
            }
            // 模型、工具、轨迹、目标状态全部在 IO；回来只做 UI。严格检测避免回归到主线程。
            var spokenText = ""
            var pending: cn.companion.pet.core.action.PendingAction? = null
            withContext(Dispatchers.IO) {
                check(Looper.myLooper() != Looper.getMainLooper()) { "agent must run off main" }
                val cancelled = { !settings.agentAutonomy || !active }
                val goalActive = app.goalStore.state().goals.any { it.status == GoalStatus.ACTIVE }
                val goalTurn = if (goalActive) app.goalCoordinator.runOnce(context, settings.agentAutonomy,
                    observationValid = app.screenObservations.latestValid(now) != null, cancelled = cancelled) else null
                // 目标没有可执行的步骤时照常走普通 AgentLoop，不让一个空目标吞掉所有事件（审计 P0-2）。
                val turn = if (goalTurn?.handled == true) goalTurn.agent
                else app.agentLoop.run(context, settings.agentAutonomy, cancelled = cancelled,
                    stableTurnId = "agent-event-" + event.id)
                spokenText = turn?.text.orEmpty()
                pending = turn?.pendingId?.let { app.pendingActions.find(it) }
                // 账本记“发生时间”；临时失败退避重试，没调用模型就不记账（审计 P1-2、P2-4）。
                when (cn.companion.pet.core.agent.EventDisposition.of(turn?.status)) {
                    cn.companion.pet.core.agent.EventDisposition.DONE -> app.agentEventLedger.mark(event.id, event.occurredAt)
                    cn.companion.pet.core.agent.EventDisposition.RETRY -> app.agentEventLedger.recordFailure(event.id, event.occurredAt, now())
                    cn.companion.pet.core.agent.EventDisposition.HOLD -> Unit
                }
            }
            overlay.setMood(Mood.IDLE)
            if (!active || !settings.agentAutonomy || convo != null) return@launch
            val proposal = pending
            if (proposal != null && proposal.status == cn.companion.pet.core.action.PendingStatus.PENDING) {
                showPendingConfirmation(proposal, spokenText)
            } else if (spokenText.isNotBlank() && settings.presenceEnabled && !app.presencePolicy.check(now(), settings.petPaused).allowed) {
                // C3：克制策略不允许主动开口时，Agent 的普通发言只留在轨迹里，不弹气泡。
                scheduler.onSpoke(now)
            } else if (spokenText.isNotBlank()) {
                if (settings.presenceEnabled) app.presencePolicy.recordShown(now())
                scheduler.onSpoke(now)
                stats.add(Stats.Kind.SPOKE)
                overlay.setTalking(true)
                overlay.showBubble(BubbleSpec(spokenText, textOnly = true)) {
                    overlay.setTalking(false)
                }
            } else {
                // 没有动作/被拒绝/失败时，把正常的搭话机会还给原节奏。
                scheduler.retrySoon(now)
            }
        }
    }

    // ---------- 看一眼：在用户正在看的 App 里截图 ----------

    /**
     * 从悬浮气泡触发：悬浮窗不是活动窗口，所以截到的是用户正在看的 App（审计 P1-3）。
     * 截图不落盘；只有用户打开了上传开关、预算够，才发给视觉模型，摘要作为 60 秒有效的观察。
     */
    /** “看一眼”进行中：这段时间不主动开口，免得搭话气泡盖掉看图结果。 */
    private var lookJob: Job? = null

    private fun lookOnce() {
        endConversation()
        if (android.os.Build.VERSION.SDK_INT < 30) { lookNotice("这台手机的系统版本太低，不能截图。"); return }
        if (lookJob?.isActive == true) return
        overlay.setMood(Mood.THINKING)
        lookJob = scope.launch {
            fun evidence(e: cn.companion.pet.core.vision.VisionEvidence) { runCatching { app.visionEvidence.record(e) } }
            val startedAt = now()
            delay(350) // 先让气泡收起，别把它拍进去
            // 截图前读一次无障碍节点，给可点元素编号；同一张编号表发给视觉模型。
            val marks = if (!settings.visionMarks) emptyList() else withContext(Dispatchers.IO) {
                val observed = runCatching { app.deviceController.observe() }.getOrNull()
                val obs = if (observed?.status == cn.companion.pet.core.device.DeviceStatus.SUCCESS) app.deviceController.lastObservation() else null
                obs?.let { cn.companion.pet.core.vision.ScreenMarks.plan(it.nodes) }.orEmpty()
            }
            val attempt = withContext(Dispatchers.IO) {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    cn.companion.pet.a11y.ScreenCaptureGateway.captureBlocking(PerceptionHub.service, executor, 5_000, marks)
                } finally {
                    executor.shutdown()
                }
            }
            val jpeg = attempt.jpeg
            if (attempt.status != cn.companion.pet.core.vision.CapturedStatus.OK || jpeg == null) {
                val why = attempt.error.ifBlank { attempt.status.name }
                evidence(cn.companion.pet.core.vision.VisionEvidence(startedAt, cn.companion.pet.core.vision.VisionOutcome.CAPTURE_FAILED,
                    failure = attempt.status.name))
                lookNotice("没截到：$why"); return@launch
            }
            if (attempt.packageName in settings.excludeApps) { lookNotice("这个 App 在排除名单里，我不看。"); return@launch }
            val setup = withContext(Dispatchers.IO) { app.visionSetup() }
            if (!settings.visionUploadEnabled) {
                evidence(cn.companion.pet.core.vision.VisionEvidence(startedAt, cn.companion.pet.core.vision.VisionOutcome.UPLOAD_OFF,
                    setup.model, setup.host))
                lookNotice("截到了，但还没允许把截图发给视觉模型（设置 → 模型与看图）。"); return@launch
            }
            if (!setup.ready) {
                evidence(cn.companion.pet.core.vision.VisionEvidence(startedAt, cn.companion.pet.core.vision.VisionOutcome.NOT_CONFIGURED,
                    setup.model, setup.host, failure = setup.status.name))
                lookNotice("截到了，但看图还没配好：" + setup.label().removePrefix("未配置：")); return@launch
            }
            if (!app.budget.reserve(settings.dailyLimit, LocalDate.now().toString())) {
                evidence(cn.companion.pet.core.vision.VisionEvidence(startedAt, cn.companion.pet.core.vision.VisionOutcome.BUDGET,
                    setup.model, setup.host))
                lookNotice("今天的调用次数用完了。"); return@launch
            }
            stats.add(Stats.Kind.API_CALLS)
            val table = if (attempt.marksDrawn > 0) cn.companion.pet.core.vision.ScreenMarks.promptTable(marks) else ""
            val prompt = cn.companion.pet.core.vision.VisionAnalyzer.DEFAULT_PROMPT + (if (table.isEmpty()) "" else "\n" + table)
            val run = withContext(Dispatchers.IO) { app.visionAnalyzer.analyzeDetailed(jpeg, attempt.width, attempt.height, prompt) }
            evidence(run.evidence.copy(marks = attempt.marksDrawn))
            val summary = run.summary
            if (summary == null) {
                // 说清楚是哪一步：请求失败（Key/模型名/网络）还是模型回了但看不懂。
                lookNotice(
                    when (run.evidence.outcome) {
                        cn.companion.pet.core.vision.VisionOutcome.REQUEST_FAILED -> "截图成功，但视觉模型调用失败：" + run.evidence.failure
                        cn.companion.pet.core.vision.VisionOutcome.PARSE_FAILED -> "截图成功，模型也回了，但内容没法解析。换一个视觉模型试试（比如 qwen-vl-plus）。"
                        else -> "截图成功，但看图没配好：" + run.evidence.failure
                    },
                )
                return@launch
            }
            val at = now()
            val text = (summary.subject + (if (summary.elements.isEmpty()) "" else "；" + summary.elements.take(5).joinToString("、"))).take(300)
            app.screenObservations.publish(cn.companion.pet.core.vision.ScreenObservation(
                id = UUID.randomUUID().toString(), capturedAt = at, packageName = attempt.packageName,
                source = cn.companion.pet.core.vision.ObservationSource.VISION, screenshotAvailable = true,
                nodesAvailable = false, visionAvailable = true, status = cn.companion.pet.core.vision.CapturedStatus.OK,
                summary = text, expiresAt = at + 60_000,
            ))
            lookNotice(LOOK_RESULT_PREFIX + summary.subject.ifBlank { "看不太清" })
        }
    }

    private var skillsInitialized = false
    /** 技能运行/录制/菜单期间不主动开口；没用过技能时不创建它。 */
    private fun skillsBusy(): Boolean = skillsInitialized && skills.busy

    /** 看图结果/失败原因显示期间也不主动开口。 */
    private var lookNoticeUntil = 0L

    private fun lookNotice(text: String) {
        lookNoticeUntil = now() + 6_500
        overlay.setMood(Mood.IDLE)
        overlay.showBubble(BubbleSpec(text, textOnly = true)) {
            handler.postDelayed({ if (convo == null && think == null && pendingBubbleId == null) overlay.hideBubble() }, 6000)
        }
    }

    // ---------- 待确认动作：在当前 App 里直接确认 ----------

    private var pendingBubbleId: String? = null

    /**
     * 在悬浮气泡上问“允许这次吗”。用户此刻还在原来的 App 里，所以前台包名、设备观察都还对得上；
     * 到设置页里批准时前台已经变成桌宠自己，点击/输入这类动作基本无法成立（审计 P1-4）。
     */
    private fun showPendingConfirmation(item: cn.companion.pet.core.action.PendingAction, text: String) {
        pendingBubbleId = item.id
        scheduler.onSpoke(now())
        haptics.tap()
        overlay.setMood(Mood.CURIOUS)
        overlay.showBubble(BubbleSpec(
            text.ifBlank { "我想" + cn.companion.pet.core.agent.AgentReport.label(item.kind) + "，需要你确认。" },
            instant = true,
            actions = listOf(
                BubbleAction("允许这次", highlighted = true) { approvePending(item) },
                BubbleAction("不用") {
                    pendingBubbleId = null
                    scope.launch(Dispatchers.IO) { app.pendingExecutor.reject(item.id) }
                    overlay.hideBubble()
                },
            ),
        ))
        // 不理它就悄悄收起；确认仍留在设置页，5 分钟后过期。
        handler.postDelayed({ if (pendingBubbleId == item.id && convo == null) { pendingBubbleId = null; overlay.hideBubble() } }, 60_000)
    }

    private fun approvePending(item: cn.companion.pet.core.action.PendingAction) {
        pendingBubbleId = null
        overlay.showBubble(BubbleSpec("……", textOnly = true))
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                app.pendingExecutor.approveAndRun(item.id, now(), PerceptionHub.trail.foreground, item.nonce)
            }
            val message = when (outcome) {
                is cn.companion.pet.core.action.PendingExecutor.Outcome.Rejected -> "没有执行：" + outcome.reason
                is cn.companion.pet.core.action.PendingExecutor.Outcome.Executed -> {
                    val call = cn.companion.pet.core.agent.AgentDecision.ToolCall(outcome.action.kind, outcome.action.args, emptyList(), "")
                    cn.companion.pet.core.agent.AgentReport.lead(outcome.result) + cn.companion.pet.core.agent.AgentReport.text(call, outcome.result)
                }
            }
            overlay.setMood(Mood.IDLE)
            overlay.showBubble(BubbleSpec(message, textOnly = true)) {
                handler.postDelayed({ if (convo == null && think == null && pendingBubbleId == null) overlay.hideBubble() }, 5000)
            }
        }
    }

    private fun present(decision: SceneDecision, reply: ProbeReply, opening: List<ChatMessage>, noEscalation: Boolean, topic: Topic? = null, inputVersion: String? = null, haptic: Boolean = true) {
        val now = now()
        scheduler.onSpoke(now)
        stats.add(Stats.Kind.SPOKE)
        if (settings.presenceEnabled) app.presencePolicy.recordShown(now)
        convo = Conversation(decision, reply, opening, now, noEscalation = noEscalation, topicId = topic?.id, inputVersion = inputVersion)
        lastProactiveAt = now
        topic?.let { topics.markShared(it.id, now) }
        overlay.setMood(reply.mood)
        overlay.setTalking(true)
        if (haptic) haptics.tap()
        if (settings.ttsEnabled) speech.say(reply.hookText)
        overlay.showBubble(entrySpec(reply.hookText, decision.scene, reply.suggestGemini)) { overlay.setTalking(false) }
    }

    private fun entrySpec(text: String, scene: Scene, highlightGemini: Boolean) = BubbleSpec(
        text = text,
        showMic = systemVoice,
        geminiHighlighted = highlightGemini,
        geminiLabel = "去 ${assistants.current.label}",
        showLive = scene == Scene.LEARNING,
    )

    // ---------- 用户主动 ----------

    override fun onPetTapped() {
        if (skillsBusy() && skills.onPetTapped()) return
        if (session != null) {
            if (overlay.isBubbleShown) hideSessionPrompt() else showSessionMenu()
            return
        }
        // 受约束回合进行中：点桌宠给当前回合的菜单，不是收起/作废。
        if (training != null) { showTrainingMenu(); return }
        if (think?.strict?.state?.active == true) { showStrictPrompt(""); return }
        if (think != null || noteDraft != null) { endThink(); return }
        if (overlay.isBubbleShown) {
            convo?.let { if (!it.userStarted) it.dismissed = true }
            endConversation()
            return
        }
        if (showLetterIfAny()) return
        if (probeJob?.isActive == true) return
        noteUserInitiated()
        // 用户点它：开一个不需要调 API 的轻量对话入口
        val decision = classify(now())
        val greeting = listOf("嗯？", "叫我？", "怎么啦", "在呢").random()
        val reply = ProbeReply(greeting, "", Mood.CURIOUS, false, false)
        val opening = buildOpeningSync(decision)
        convo = Conversation(decision, reply, opening, now(), userStarted = true)
        overlay.setMood(Mood.CURIOUS)
        overlay.showBubble(
            entrySpec(greeting, decision.scene, false).copy(
                extra = listOf(
                    BubbleAction("开始陪练") { openTraining() },
                    BubbleAction("想一想") { openThinkMenu() },
                    BubbleAction("记个念头") { startNote() },
                    BubbleAction("看一眼") { lookOnce() },
                    BubbleAction("技能") { endConversation(); skills.openMenu() },
                    BubbleAction("守护着去 ${assistants.current.label}") { endConversation(); startFocusGuard() },
                ),
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

    /** 用系统语音识别：用户在设置里打开、且系统确实有识别服务和麦克风权限。默认走输入法话筒。 */
    private val systemVoice: Boolean get() = settings.systemVoice && voice.available

    /**
     * 默认的“说话”：弹出输入框，让用户点输入法自带的话筒（例如豆包输入法）。
     * 系统 SpeechRecognizer 在不少国产机后台会立刻失败，用户只看到“没听清”。
     */
    private fun speakViaKeyboard() {
        if (convo == null && session == null && think == null && noteDraft == null) {
            val d = classify(now())
            convo = Conversation(d, ProbeReply("", "", Mood.CURIOUS, false, false), buildOpeningSync(d), now(), userStarted = true)
            overlay.setMood(Mood.CURIOUS)
            overlay.showBubble(entrySpec("说吧，我听着。", d.scene, false).copy(instant = true))
        }
        convo?.interacting = true
        overlay.openBubbleInput("点输入法上的话筒说话，或者直接打字")
    }

    private fun startListening(fromHold: Boolean) {
        if (!systemVoice) { speakViaKeyboard(); return }
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
            skillsInitialized && skills.awaitingText -> { skills.submitText(text); return }
            noteDraft != null -> { saveNote(text); return }
            training != null -> { submitTraining(text, emptyList(), "text", AssistanceLevel.INDEPENDENT); return }
            think?.awaitingAnswer == true -> { answerThink(text); return }
        }
        onUserInput(text, fromHold = false)
    }

    override fun onBubbleTouched() {
        convo?.interacting = true
    }

    override fun onClose() {
        convo?.let { if (!it.userStarted) it.dismissed = true }
        endConversation()
    }

    private fun onUserInput(text: String?, fromHold: Boolean) {
        val said = text?.trim().orEmpty()
        if (said.isEmpty()) {
            val error = voice.lastError
            val message = if (error == null) "没听清，再说一次？"
            else "系统语音识别失败：" + VoiceInput.describe(error) + "。可以点“打字”，用输入法上的话筒说；或在设置里关掉“用系统语音识别”。"
            overlay.showBubble(BubbleSpec(message, showMic = systemVoice, showGemini = false))
            return
        }
        // 按住桌宠说的话：训练/想一想在等回答时，交给它们，不另开闲聊。
        if (training != null) { submitTraining(said, emptyList(), "voice", AssistanceLevel.INDEPENDENT); return }
        if (think?.awaitingAnswer == true) { answerThink(said); return }
        val c = convo ?: run {
            // 没有对话时直接按住角色说话
            val d = classify(now())
            Conversation(d, ProbeReply("", "", Mood.IDLE, false, false), buildOpeningSync(d), now(), userStarted = true).also { convo = it }
        }
        c.interacting = true
        c.userReplyAt = now()
        petStateCache = stateStore.apply(StateEvent.USER_REPLIED)
        noteUserInitiated()
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
                val r = app.client.complete(
                    ChatRequest(settings.endpoint, settings.model, key!!, messages, maxTokens = ProbePrompt.REPLY_MAX_TOKENS, timeoutMillis = ProbePrompt.TIMEOUT_MILLIS),
                )
                val unchanged = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { inputVersion == memoryVersion() } }
                val parsed = if (unchanged) (r as? ChatResult.Success)?.let { ProbeReplyParser.parse(it.content) } else null
                if (unchanged && r is ChatResult.Success && parsed == null) noteBadFormat()
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
        if (settings.presenceEnabled && !c.userStarted) {
            // C3：区分“回应 / 没理 / 明确关掉”；锁屏、切去深聊等中断不算反馈。
            val feedback = when {
                c.userReply != null || c.gemini != GeminiExit.NONE -> cn.companion.pet.core.presence.PresenceFeedback.INTERACTED
                c.dismissed -> cn.companion.pet.core.presence.PresenceFeedback.DISMISSED
                c.ignoredFully -> cn.companion.pet.core.presence.PresenceFeedback.IGNORED
                else -> null
            }
            feedback?.let { app.presencePolicy.feedback(it, now()) }
            if (feedback == cn.companion.pet.core.presence.PresenceFeedback.INTERACTED) lastInteractionAt = now()
            if (feedback == cn.companion.pet.core.presence.PresenceFeedback.IGNORED) {
                petStateCache = stateStore.apply(StateEvent.PROACTIVE_IGNORED)
                c.topicId?.let { topics.dismiss(it) }
            }
            if (feedback == cn.companion.pet.core.presence.PresenceFeedback.DISMISSED) c.topicId?.let { topics.dismiss(it) }
        } else if (!c.userStarted && c.userReply == null && c.gemini == GeminiExit.NONE) {
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
                dismissed = c.dismissed,
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
        // 有效守护期间由守护独占“离开/倒计时/拉回”，普通会话不再并行拉回或因离开太久自动结束。
        if (focusActive) {
            if (sessionPrompt == PROMPT_COUNTDOWN || sessionPrompt == PROMPT_ASK) hideSessionPrompt()
            return
        }
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
        val focus = focusController.status()
        val focusText = if (focus.active) {
            actions.add(1, BubbleAction("暂停守护 5 分钟") { focusController.pause(); hideSessionPrompt() })
            "；守护（" + (if (focus.enforcement == cn.companion.pet.core.focus.FocusEnforcement.STRICT) "严格" else "提醒") + "）还剩 " + (focus.secondsLeft / 60 + 1) + " 分钟"
        } else ""
        overlay.showBubble(BubbleSpec("${sessionApp.label} 会话中（$modeText）· 已经 $minutes 分钟$status$focusText", instant = true, actions = actions))
    }

    private fun pullBack() {
        hideSessionPrompt()
        if (focusActive) { focusController.userReturn(); stats.add(Stats.Kind.PULLBACKS); return }
        when (gemini.launch(sessionPkg, adjacent = false)) {
            GeminiLauncher.Outcome.LAUNCHED -> stats.add(Stats.Kind.PULLBACKS)
            else -> gemini.toast("没能打开 ${sessionApp.label}。vivo 上要在应用权限里允许“后台弹出界面”")
        }
    }

    private fun endSessionByUser() {
        if (focusActive) focusController.abort()
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
        /** 严格陪伴：非空时本流程是受约束回合，不能被 3 分钟闲置清理掉。 */
        var strict: StrictSessionMachine? = null
        /** 首次结果回来后自动进入严格回合（不打断模型请求）。 */
        var strictPending = false
        /** 提示后等待用户回答的截止时间；0 表示未在等待。 */
        var waitSince = 0L
        var lastSilence = ""
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
        if (settings.strictEnabled) {
            actions.add(0, BubbleAction("严格陪我想", highlighted = true) {
                flow.strictPending = true
                runThink(ThinkMode.SOCRATIC)
            })
        }
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
        if (flow.strictPending) {
            flow.strictPending = false
            startStrict(flow)
            return
        }
        overlay.setMood(Mood.CURIOUS)
        val actions = mutableListOf<BubbleAction>()
        if (flow.question != null) {
            flow.awaitingAnswer = true
            if (systemVoice) actions += BubbleAction("说一句", highlighted = true) { thinkVoice { answerThink(it) } }
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
        // 严格回合的问题在状态机里（下一步的追问、恢复后的问题都不在 result 里）。
        val q = flow.strict?.state?.takeIf { it.active }?.question ?: flow.question ?: return
        val said = answer?.trim().orEmpty()
        if (said.isEmpty()) { if (flow.strict != null) showStrictPrompt("没听清，再说一次？") else showThinkResult(flow); return }
        flow.awaitingAnswer = false
        flow.touchedAt = now()
        flow.waitSince = now()
        flow.qa += q to said
        // 严格回合：先进入核验，通过与否由本地状态机 + 结构化结果决定。
        val machine = flow.strict
        if (machine != null) {
            machine.submit(said, now())
            persistStrict(flow)
            evaluateStrict(flow, machine, q, said)
            return
        }
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


    /** 让模型只判断“够不够”，结果必须过本地校验才采纳。 */
    private fun evaluateStrict(flow: ThinkFlow, machine: StrictSessionMachine, question: String, answer: String) {
        val key = apiKey()
        val sessionId = machine.state.sessionId
        val stepId = machine.state.stepId
        val revision = machine.state.revision
        if (key == null || app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()) <= 0) {
            // 不冒充通过，也不卡在“核对中”：回到等待。
            machine.evaluationUnavailable(now())
            persistStrict(flow)
            showStrictReply(flow, if (key == null) "还没填模型的 API Key，我没法核对，不算过也不算错。" else "今天的调用次数用完了，我没法核对，不算过也不算错。", "")
            return
        }
        overlay.setMood(Mood.THINKING)
        overlay.showBubble(BubbleSpec("我核对一下……", textOnly = true))
        probeJob = scope.launch {
            try {
                val material = flow.material.ifBlank { "（素材原文没有保存，只按问题和回答核对）" }
                val messages = withContext(Dispatchers.IO) {
                    synchronized(app.memoryLock) {
                        StrictEvalPrompt.build(memory.persona(), machine.state.criteria, material, question, answer)
                    }
                }
                if (!app.budget.reserve(settings.dailyLimit, LocalDate.now().toString())) {
                    machine.evaluationUnavailable(now())
                    persistStrict(flow)
                    showStrictReply(flow, "今天的次数用完了，我没法核对，不算过也不算错。", "")
                    return@launch
                }
                stats.add(Stats.Kind.API_CALLS)
                val r = app.client.complete(ChatRequest(settings.endpoint, settings.model, key, messages, maxTokens = 400, timeoutMillis = StrictEvalPrompt.TIMEOUT_MILLIS))
                // 迟到的结果、换题、结束：一律丢弃。
                val current = think
                if (current !== flow || current.strict !== machine || !machine.state.active) return@launch
                val parsed = (r as? ChatResult.Success)?.let { StrictEvalPrompt.parse(it.content, sessionId, stepId, revision) }
                if (parsed == null) {
                    machine.evaluationUnavailable(now())
                    persistStrict(flow)
                    showStrictReply(flow, "我这边核对没成功（网络或模型），不算你错。再说一次？", "")
                    return@launch
                }
                val out = machine.acceptEvaluation(parsed, now())
                persistStrict(flow)
                when (out) {
                    is StrictOutcome.Accepted -> showStrictReply(flow, out.text, out.nextPrompt)
                    is StrictOutcome.Support -> showStrictReply(flow, out.text, "")
                    else -> {
                        machine.evaluationUnavailable(now())
                        persistStrict(flow)
                        showStrictReply(flow, "这次核对结果对不上号，我没采用。再说一次？", "")
                    }
                }
            } finally {
                // 被取消（锁屏会取消请求）：别把回合卡在“核对中”。
                if (machine.state.active && machine.state.phase == Phase.EVALUATING && machine.state.revision == revision) {
                    machine.evaluationUnavailable(now())
                    if (think === flow) persistStrict(flow)
                }
            }
        }
    }

    private fun showStrictReply(flow: ThinkFlow, text: String, nextPrompt: String) {
        val m = flow.strict
        flow.touchedAt = now()
        flow.waitSince = now()
        if (m == null || !m.state.active) {
            // 最后一步通过 / 已结束：结算，不再挂着一个停不下来的回合。
            val summary = m?.summary().orEmpty()
            finishStrict(flow, text + if (summary.isNotBlank()) "\n\n" + summary else "")
            return
        }
        flow.awaitingAnswer = true
        overlay.setMood(Mood.CURIOUS)
        val body = if (nextPrompt.isNotBlank()) text + "\n\n下一步：" + nextPrompt else text + "\n\n问题：" + m.state.question
        overlay.showBubble(BubbleSpec(body, instant = true, actions = strictActions()))
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



    // ---------- 双形态训练（批次 A；整合轮捋顺：单调计时、核验不卡死、跨应用拦截走唯一拦截层） ----------

    /** 开始训练时所在的应用：训练气泡浮在它上面，待在这里不算离开。 */
    private var trainingBase: String? = null
    /** “歇 5 分钟”到点时间（单调时钟）；0 表示没在歇。 */
    private var trainingBlockPausedUntil = 0L
    private var trainingHeartbeatAt = 0L
    /** 拦截层没显示出来的原因（如实记下，不宣称已拦截）。 */
    private var trainingBlockError = ""
    /** 练习换休息：休息到点时间（墙上时间）；0 表示没有。 */
    private var restUntil = 0L

    private val trainingBlockLabels = cn.companion.pet.overlay.FocusOverlay.Labels(
        back = "回去接着练", pause = "歇 5 分钟", abort = "结束训练",
    )

    /** 当前真实前台（排除自己）；读不到为 null。 */
    private fun trainingForegroundNow(): String? =
        (PerceptionHub.activePackage() ?: PerceptionHub.trail.foreground)?.takeUnless { it == context.packageName }

    /** 打开训练：有题立即出题；没有就取内置示例，绝不留空白等用户想题。 */
    private fun openTraining() {
        // 严格陪伴回合进行中不另开训练：同一时间只有一个受约束回合。
        if (think?.strict?.state?.active == true) { showStrictPrompt(""); return }
        endConversation()
        val answered = app.events.all()
            .filter { it.text.contains("训练完成") }
            .flatMap { Regex("q:([a-z0-9-]+)").findAll(it.text).map { m -> m.groupValues[1] }.toList() }
            .toSet()
        val q = BuiltInQuestions.pick(answered)
        trainingQuestion = q
        val c = TrainingController(
            TrainingSession(
                id = "train-" + now(),
                interactionMode = trainingMode,
                createdAt = now(),
                updatedAt = now(),
            ),
        )
        c.bind(q)
        training = c
        trainingBase = trainingForegroundNow()
        trainingBlockPausedUntil = 0L
        trainingBlockError = ""
        enforcement.reset()
        val out = c.present(now())
        if (out is TrainingOutcome.Present) {
            trainingTimer.arm(mono(), stemLength = q.stem.length)
            showTrainingQuestion(q, out.ask)
        }
        persistTraining()
    }

    /** 训练回答入口：打字 / 说一句 / 我不会 / 结束。所有训练气泡都带上，不会出现“只剩一句话没法答”。 */
    private fun trainingActions(): List<BubbleAction> {
        val actions = mutableListOf<BubbleAction>()
        if (systemVoice) actions += BubbleAction("说一句", highlighted = true) { trainingVoice() }
        actions += BubbleAction("打字") { overlay.openBubbleInput() }
        actions += BubbleAction("我不会") { submitTrainingSupport() }
        actions += BubbleAction("×") { endTraining() }
        return actions
    }

    private fun trainingVoice() {
        // 录音/转写期间不算沉默
        trainingTimer.pause(mono())
        overlay.setListening(true)
        overlay.updateBubbleText("在听……说完停一下就好")
        voice.start { text ->
            overlay.setListening(false)
            trainingTimer.resume(mono())
            val said = text?.trim().orEmpty()
            if (said.isEmpty()) showTrainingAsk("没听清。可以再说一次，或者点“打字”。")
            else submitTraining(said, emptyList(), "voice", AssistanceLevel.INDEPENDENT)
        }
    }

    /** 重显当前这一步（点桌宠、亮屏、拦截后回来、出错后都走这里）。 */
    private fun showTrainingAsk(prefix: String = "") {
        val c = training ?: return
        val q = trainingQuestion ?: return
        if (c.current.state == cn.companion.pet.core.training.TrainingState.EVALUATING) {
            overlay.showBubble(BubbleSpec("我在核对你刚才那句……", textOnly = true))
            return
        }
        val head = if (prefix.isBlank()) "" else prefix + "\n\n"
        overlay.setMood(Mood.CURIOUS)
        overlay.showBubble(BubbleSpec(head + q.stem + "\n\n" + c.currentAsk(), instant = true, actions = trainingActions()))
    }

    /** 训练中点桌宠：给训练菜单，不是收起气泡（原来点一下训练气泡就没了，训练却还在后台计时）。 */
    private fun showTrainingMenu() {
        val c = training ?: return
        val q = trainingQuestion ?: return
        overlay.setMood(Mood.CURIOUS)
        val actions = mutableListOf(
            BubbleAction("接着答", highlighted = true) { showTrainingAsk() },
            BubbleAction("我不会") { submitTrainingSupport() },
        )
        if (systemVoice) actions += BubbleAction("说一句") { trainingVoice() }
        actions += BubbleAction("打字") { overlay.openBubbleInput() }
        actions += BubbleAction("结束训练") { endTraining() }
        val note = if (trainingBlockError.isNotBlank()) "\n（上次切走时拦截层没显示出来：" + trainingBlockError + "）" else ""
        overlay.showBubble(BubbleSpec("正在陪练（" + q.id + "）。" + c.summary() + note, instant = true, actions = actions))
    }

    private fun showTrainingQuestion(q: cn.companion.pet.core.training.TrainingQuestion, ask: String) {
        val tag = if (q.builtInSample) "内置示例题 · " else ""
        val body = tag + q.stem + "\n\n" + ask
        val actions = mutableListOf<BubbleAction>()
        if (training?.current?.currentSlotKind == SlotKind.LOCATE) {
            q.sentences.take(2).forEachIndexed { i, s ->
                actions += BubbleAction("选第 " + (i + 1) + " 句") {
                    submitTraining(transcript = s, selected = listOf(i), modality = "choice", assistance = AssistanceLevel.RECOGNIZED_WITH_CHOICES)
                }
            }
        }
        actions += trainingActions()
        overlay.setMood(Mood.CURIOUS)
        overlay.showBubble(BubbleSpec(body, instant = true, actions = actions))
    }

    /** 给缩小支架；成功返回 true。支架给出后计时进入下一阶段（手动点“我不会”和超时给支架走同一条路）。 */
    private fun submitTrainingSupport(): Boolean {
        val c = training ?: return false
        val out = c.scaffold(now())
        if (out !is TrainingOutcome.Scaffold) {
            showTrainingAsk()
            return false
        }
        trainingTimer.afterScaffold(mono())
        persistTraining()
        val actions = out.options.mapIndexed { i, opt ->
            BubbleAction("选 " + (i + 1)) {
                submitTraining(opt, emptyList(), "choice", AssistanceLevel.RECOGNIZED_WITH_CHOICES)
            }
        } + BubbleAction("打字") { overlay.openBubbleInput() } + BubbleAction("×") { endTraining() }
        overlay.showBubble(BubbleSpec(out.text + "\n\n" + out.options.mapIndexed { i, o -> (i + 1).toString() + ". " + o }.joinToString("\n"), instant = true, actions = actions))
        return true
    }

    private fun submitTraining(
        transcript: String,
        selected: List<Int> = emptyList(),
        modality: String = "text",
        assistance: AssistanceLevel = AssistanceLevel.INDEPENDENT,
    ) {
        val c = training ?: return
        val q = trainingQuestion ?: return
        val slot = c.current.currentSlotKind ?: return
        val key = apiKey()
        // 先看模型能不能用，再进核验：原实现先进 EVALUATING 再发现没 Key，会卡在核验中，“我不会”也失灵。
        if (key == null || app.budget.remaining(settings.dailyLimit, LocalDate.now().toString()) <= 0) {
            showTrainingAsk(if (key == null) "还没填模型的 API Key，我没法核对，也不假装给你过。" else "今天的调用次数用完了，我没法核对，也不假装给你过。")
            return
        }
        val out = c.submit(transcript, selected, modality, assistance, now())
        if (out is TrainingOutcome.Rejected) { overlay.showBubble(BubbleSpec(out.reason, textOnly = true)); return }
        if (c.current.state != cn.companion.pet.core.training.TrainingState.EVALUATING) return
        // 发请求那一刻的会话快照：迟到/换题的结果凭它作废（原实现用解析时的会话盖章，等于不校验）。
        val snap = c.current
        overlay.setMood(Mood.THINKING)
        overlay.showBubble(BubbleSpec("我核对一下……", textOnly = true))
        // 模型请求期间暂停计时：等待不算用户沉默。
        trainingTimer.pause(mono())
        persistTraining()
        probeJob = scope.launch {
            try {
                val messages = withContext(Dispatchers.IO) {
                    synchronized(app.memoryLock) { TrainingPrompt.build(memory.persona(), c.current.interactionMode, q, slot, transcript, selected) }
                }
                if (!app.budget.reserve(settings.dailyLimit, LocalDate.now().toString())) {
                    trainingEvalFailed(c, snap, "今天的次数用完了。你的回答我留着，不算过也不算错。")
                    return@launch
                }
                stats.add(Stats.Kind.API_CALLS)
                val r = app.client.complete(ChatRequest(settings.endpoint, settings.model, key, messages, maxTokens = TrainingPrompt.MAX_TOKENS, timeoutMillis = TrainingPrompt.TIMEOUT_MILLIS))
                // 迟到的结果一律丢弃：会话已换、已结束、或已不是这次请求。
                if (training !== c || !c.current.active || c.current.pendingRequestId != snap.pendingRequestId) return@launch
                trainingTimer.resume(mono())
                val advice = (r as? ChatResult.Success)?.let { TrainingPrompt.parse(it.content, snap, slot) }
                if (advice == null) {
                    trainingEvalFailed(c, snap, "我这边核对没成功（网络或模型）。不算你错，再说一次？")
                    return@launch
                }
                handleTrainingAdvice(c, advice)
            } finally {
                // 被取消（锁屏会取消请求）或异常：别把训练卡在“核对中”。
                if (training === c && c.current.state == cn.companion.pet.core.training.TrainingState.EVALUATING &&
                    c.current.pendingRequestId == snap.pendingRequestId
                ) {
                    c.evaluationFailed(now())
                    trainingTimer.resume(mono())
                    trainingTimer.arm(mono())
                    persistTraining()
                }
            }
        }
    }

    private fun trainingEvalFailed(c: TrainingController, snap: TrainingSession, text: String) {
        if (training !== c || c.current.pendingRequestId != snap.pendingRequestId) return
        c.evaluationFailed(now())
        trainingTimer.resume(mono())
        trainingTimer.arm(mono())
        persistTraining()
        showTrainingAsk(text)
    }

    private fun handleTrainingAdvice(c: TrainingController, advice: cn.companion.pet.core.training.EvaluationAdvice) {
        val out = c.acceptAdvice(advice, now())
        when (out) {
            is TrainingOutcome.Completed -> finishTraining(c, out.summary)
            is TrainingOutcome.NextSlot -> {
                trainingTimer.arm(mono())
                persistTraining()
                overlay.setMood(Mood.HAPPY)
                overlay.showBubble(BubbleSpec("这一步过了。\n\n" + out.ask, instant = true, actions = trainingActions()))
            }
            is TrainingOutcome.Waiting -> {
                trainingTimer.arm(mono())
                persistTraining()
                overlay.setMood(Mood.CURIOUS)
                overlay.showBubble(BubbleSpec(out.ask, instant = true, actions = trainingActions()))
            }
            is TrainingOutcome.Recovery -> {
                trainingTimer.toRecovery()
                persistTraining()
                overlay.showBubble(BubbleSpec(out.text, instant = true,
                    actions = listOf(
                        BubbleAction("继续") { resumeTraining() },
                        BubbleAction("换一题") { endTraining(); openTraining() },
                        BubbleAction("×") { endTraining() },
                    ),
                ))
            }
            // 校验没过（会话/槽位/请求号不符）：不显示内部原因；若仍卡在核验就退回等待。
            is TrainingOutcome.Rejected -> if (c.current.state == cn.companion.pet.core.training.TrainingState.EVALUATING) {
                c.evaluationFailed(now())
                trainingTimer.arm(mono())
                persistTraining()
                showTrainingAsk("这次核对结果对不上号，我没采用。再说一次？")
            }
            else -> Unit
        }
    }

    /** 做完：记一次“完成”、清掉存档、撤下拦截、按策略发休息许可。只记一次（原来完成后又被 endTraining 记成“中止”）。 */
    private fun finishTraining(c: TrainingController, summary: String) {
        logTrainingResult(c, completed = true)
        val grant = maybeGrantRest(c)
        clearTrainingState()
        overlay.setMood(Mood.HAPPY)
        val tail = if (grant != null) "\n\n给你 " + ((grant.expiresAt - grant.grantedAt) / 60_000) + " 分钟休息，到点我叫你。" else ""
        overlay.showBubble(BubbleSpec("练完了。" + summary + tail, textOnly = true)) {
            handler.postDelayed({ if (training == null && convo == null && think == null) overlay.hideBubble() }, 6000)
        }
    }

    /** 每次训练状态变化落盘；失败只记诊断，不打断用户。 */
    private fun persistTraining() {
        val c = training ?: return
        val q = trainingQuestion ?: return
        trainingStore.save(c.current, q)
        trainingHeartbeatAt = now()
    }

    /** 单调时钟：训练内计时不受系统时间调整影响。只给 TrainingTimer 用；存盘和空窗用墙上时间。 */
    private fun mono(): Long = android.os.SystemClock.elapsedRealtime()

    /** 恢复未结束的训练：回到原题原槽位，明确告知断开的空窗。 */
    private fun restoreTraining() {
        // 存档里是墙上时间，空窗也必须用墙上时间算（原实现拿开机时长去减，空窗总是 0）。
        when (val r = trainingStore.restore(now())) {
            is TrainingStore.Restore.Resume -> {
                val c = TrainingController(r.session)
                c.bind(r.question)
                training = c
                trainingQuestion = r.question
                trainingMode = r.session.interactionMode
                trainingBase = trainingForegroundNow()
                trainingTimer.arm(mono(), stemLength = r.question.stem.length)
                val gapMin = (r.gapMillis / 60_000).coerceAtLeast(0)
                val note = if (gapMin > 0) "刚才我这边断开了约 " + gapMin + " 分钟，那段时间没记到。" else "接着刚才那一步。"
                showTrainingAsk(note)
            }
            is TrainingStore.Restore.Finished -> trainingStore.clear()
            TrainingStore.Restore.None -> Unit
        }
        restGrants.active(now())?.let { restUntil = it.expiresAt }
    }

    private fun tickTraining(monoNow: Long) {
        // 休息许可到点：说一声（练习换休息策略）。
        if (restUntil != 0L && now() >= restUntil) {
            restUntil = 0L
            restGrants.active(now())
            if (training == null && convo == null && think == null && session == null && !focusActive) {
                overlay.showBubble(BubbleSpec("休息时间到了。再练一题？", instant = true,
                    actions = listOf(BubbleAction("再练一题", highlighted = true) { openTraining() }, BubbleAction("先不了") { overlay.hideBubble() })))
            }
        }
        val c = training ?: return
        if (!c.current.active) return
        // 心跳落盘：让重启后的“断开了几分钟”接近真实（误差不超过 30 秒）。
        if (now() - trainingHeartbeatAt > 30_000L) persistTraining()
        // “歇 5 分钟”到点：恢复计时，叫回来，并按当前前台重新判定一次。
        if (trainingBlockPausedUntil != 0L && monoNow >= trainingBlockPausedUntil) {
            trainingBlockPausedUntil = 0L
            trainingTimer.resume(monoNow)
            showTrainingAsk("歇好了，接着这一步。")
            onTrainingForeground(trainingForegroundNow())
            return
        }
        if (trainingTimer.isRecovery()) return
        if (!trainingTimer.shouldNudge(monoNow)) return
        when (trainingTimer.currentStage) {
            TrainingTimer.Stage.WAITING_FIRST -> {
                if (!submitTrainingSupport()) trainingTimer.afterScaffold(monoNow)
            }
            TrainingTimer.Stage.WAITING_AFTER_SCAFFOLD -> {
                trainingTimer.afterVoice(monoNow)
                onTrainingViolation(TriggerReason.NO_RESPONSE)
            }
            TrainingTimer.Stage.WAITING_AFTER_VOICE -> {
                trainingTimer.toRecovery()
                val out = c.recover(now())
                persistTraining()
                if (out is TrainingOutcome.Recovery) {
                    overlay.showBubble(BubbleSpec(out.text, instant = true,
                        actions = listOf(
                            BubbleAction("继续") { resumeTraining() },
                            BubbleAction("换一题") { endTraining(); openTraining() },
                            BubbleAction("×") { endTraining() },
                        ),
                    ))
                }
            }
            else -> Unit
        }
    }

    private fun resumeTraining() {
        val c = training ?: return
        val out = c.resume(now())
        trainingTimer.arm(mono())
        persistTraining()
        if (out is TrainingOutcome.Waiting) {
            overlay.showBubble(BubbleSpec(out.ask, instant = true, actions = trainingActions()))
        } else {
            showTrainingAsk()
        }
    }

    /**
     * 前台确认后调用：训练没做完却切到别的应用 → 拦回训练。
     * 只有一个“拉回”拥有者：守护在跑时由守护管，这里不叠第二层；拦截用同一个全屏拦截层。
     */
    private fun onTrainingForeground(pkg: String?) {
        val c = training ?: return
        if (focusActive) return
        if (pkg == null || focusGate.isTransient(pkg)) return
        if (mono() < trainingBlockPausedUntil) return
        val d = focusBridge.decide(
            session = c.current,
            foregroundPackage = pkg,
            ownPackage = context.packageName,
            allowedFocusPackages = allowedForSession(),
            basePackage = trainingBase,
            homePackages = launchers,
        )
        if (!d.shouldBlock) {
            // 自己回来了：撤下训练的拦截层。
            if (blockOwner == BlockOwner.TRAINING && focusOverlay.isShown) focusOverlay.hide()
            return
        }
        blockOwner = BlockOwner.TRAINING
        val reason = "现在是「" + label(pkg) + "」。" + d.reason + "\n\n当前这一步：" + c.currentAsk()
        val r = runCatching { focusOverlay.show("训练", reason, trainingBlockLabels) }
            .getOrElse { cn.companion.pet.core.focus.OverlayResult(false, it.javaClass.simpleName) }
        trainingBlockError = if (r.shown) "" else r.reason
        if (!r.shown) {
            // 全屏层显示不了（没有无障碍/悬浮窗）：退到气泡，并如实说明。
            overlay.showBubble(BubbleSpec(d.reason + "\n（拦截层没显示出来：" + r.reason + "）", instant = true,
                actions = listOf(
                    BubbleAction("回去接着练", highlighted = true) { trainingBlockReturn() },
                    BubbleAction("歇 5 分钟") { trainingBlockPause() },
                    BubbleAction("结束训练") { trainingBlockEnd() },
                ),
            ))
        }
    }

    private fun trainingBlockReturn() {
        runCatching { focusOverlay.hide() }
        val base = trainingBase
        val launched = base != null && runCatching { gemini.launch(base, adjacent = false) == GeminiLauncher.Outcome.LAUNCHED }.getOrDefault(false)
        if (!launched) runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        handler.postDelayed({ showTrainingAsk() }, 600)
    }

    private fun trainingBlockPause() {
        runCatching { focusOverlay.hide() }
        trainingBlockPausedUntil = mono() + 5 * 60_000L
        trainingTimer.pause(mono())
        overlay.showBubble(BubbleSpec("歇 5 分钟。到点我叫你，接着这一步。", textOnly = true)) {
            handler.postDelayed({ if (convo == null && think == null) overlay.hideBubble() }, 3000)
        }
    }

    private fun trainingBlockEnd() {
        runCatching { focusOverlay.hide() }
        endTraining()
    }

    /** 训练完成后的可选休息许可（练习换休息策略）；固定专注策略不发。 */
    private fun maybeGrantRest(c: TrainingController): cn.companion.pet.core.training.RestGrant? {
        val policy = if (settings.focusPolicyType == FocusPolicyType.PRACTICE_FOR_BREAK.name) FocusPolicyType.PRACTICE_FOR_BREAK else FocusPolicyType.FIXED_FOCUS
        val g = restGrants.grant(
            policy = policy,
            sessionId = c.current.id,
            eventId = "complete-" + c.current.id,
            allowedPackages = settings.feedApps,
            durationMillis = settings.restMinutes.coerceIn(1, 60) * 60_000L,
            now = now(),
        ) ?: return null
        restUntil = g.expiresAt
        return g
    }

    /** 强制规则入口：拒绝 / 未答 / 编造 → 切严格模态，不是只提示。提示气泡带回答入口。 */
    private fun onTrainingViolation(reason: TriggerReason) {
        val c = training
        val decision = enforcement.evaluate(
            reason = reason,
            mode = c?.current?.interactionMode ?: trainingMode,
            hasPendingQuestion = c?.current?.active == true,
        )
        when (decision.action) {
            EnforcementAction.FORCE_COACH -> {
                trainingMode = InteractionMode.COACH
                c?.switchMode(InteractionMode.COACH, now())
                persistTraining()
                if (c == null) openTraining() else showTrainingAsk(decision.message)
            }
            EnforcementAction.LOCK_COACH_SLOT, EnforcementAction.ONE_MORE_CHANCE, EnforcementAction.DOWNGRADE_TECHNICAL ->
                showTrainingAsk(decision.message)
            EnforcementAction.NONE -> Unit
        }
    }

    /** 用户主动结束（×、结束训练、拦截层的紧急退出）：记“中止”，不留可复活的存档。 */
    private fun endTraining() {
        val c = training
        if (c != null) {
            logTrainingResult(c, completed = false)
            c.end(now())
            persistTraining() // 墓碑：万一下面删文件失败，重启也不会复活
        }
        clearTrainingState()
        overlay.hideBubble()
        overlay.setMood(Mood.IDLE)
    }

    private fun clearTrainingState() {
        training = null
        trainingQuestion = null
        trainingBase = null
        trainingBlockPausedUntil = 0L
        trainingTimer.disarm()
        trainingStore.clear()
        enforcement.reset()
        if (blockOwner == BlockOwner.TRAINING) runCatching { focusOverlay.hide() }
    }

    private fun logTrainingResult(c: TrainingController, completed: Boolean) {
        val q = trainingQuestion ?: return
        val line = "- " + timeText() + " | 训练" + (if (completed) "完成" else "中止") +
            " | q:" + q.id + " | " + c.summary()
        scope.launch(Dispatchers.IO) { synchronized(app.memoryLock) { app.events.append(EventKind.EXCHANGE, line, source = "training") } }
    }

    // ---------- 严格陪伴（持续闭环；整合轮：补回答入口、标准跟着问题走、锁屏/点桌宠不再中止） ----------

    /** 严格回合的回答入口。原实现的气泡只有“我不会/结束”，也没打开 awaitingAnswer，答案根本进不来。 */
    private fun strictActions(): List<BubbleAction> {
        val actions = mutableListOf<BubbleAction>()
        if (systemVoice) actions += BubbleAction("说一句", highlighted = true) { thinkVoice { answerThink(it) } }
        actions += BubbleAction("打字") { overlay.openBubbleInput() }
        actions += BubbleAction("我不会") { strictNeedsSupport() }
        actions += BubbleAction("结束") { strictAbort() }
        return actions
    }

    /** 显示当前问题并等待回答（开始、恢复、点桌宠、亮屏都走这里）。 */
    private fun showStrictPrompt(prefix: String) {
        val flow = think ?: return
        val m = flow.strict ?: return
        if (!m.state.active) return
        if (m.state.phase == Phase.EVALUATING) {
            overlay.showBubble(BubbleSpec("我在核对你刚才那句……", textOnly = true))
            return
        }
        flow.awaitingAnswer = true
        flow.touchedAt = now()
        val left = ((m.state.endsAt - now()) / 60_000).coerceAtLeast(0)
        val head = if (prefix.isBlank()) "" else prefix + "\n"
        overlay.setMood(Mood.CURIOUS)
        overlay.showBubble(BubbleSpec(head + "第 " + m.state.stepIndex + " 步（还剩约 " + left + " 分钟）：" + m.state.question, instant = true, actions = strictActions()))
    }

    /** 开启严格陪伴：把当前“想一想”升级成有期限、有验收的回合。 */
    private fun startStrict(flow: ThinkFlow) {
        val minutes = settings.strictMinutes.coerceIn(3, 60)
        val q = flow.question ?: "先用一句话说清：这段最关键的一点是什么？"
        val criteria = StrictCriteria.forQuestion(q, hasMaterial = flow.material.isNotBlank())
        val machine = StrictSessionMachine(
            StrictSessionState.new(now(), minutes, flow.source, flow.appName, criteria),
        )
        flow.strict = machine
        flow.waitSince = now()
        machine.ask(q, criteria, now())
        persistStrict(flow)
        showStrictPrompt("严格陪伴 " + minutes + " 分钟。一步过了接着下一步，到点结算。")
    }

    /** 严格回合每次状态变化都落盘；失败只在诊断里记，不打断用户。 */
    private fun persistStrict(flow: ThinkFlow) {
        val m = flow.strict ?: return
        strictStore.save(m.state)
        strictHeartbeatAt = now()
    }

    private var strictHeartbeatAt = 0L

    /** 服务启动/重建界面后恢复未结束的严格回合；到期或已结束的不复活。 */
    private fun restoreStrict() {
        val r = strictStore.restore(now())
        when (r) {
            is StrictSessionStore.Restore.Resume -> {
                // 屏幕原文不落盘（隐私约束），恢复后素材为空，核对只按问题和回答来。
                val flow = ThinkFlow("", "恢复的回合", "")
                flow.strict = StrictSessionMachine(r.state)
                flow.waitSince = now()
                flow.touchedAt = now()
                think = flow
                strictHeartbeatAt = now()
                val gapMin = (r.gapMillis / 60_000).coerceAtLeast(0)
                val note = (if (gapMin > 0) "刚才我这边断开了约 " + gapMin + " 分钟，那段时间看不到。" else "接着刚才的回合。") +
                    "（素材原文没保存，按你的回答核对）"
                showStrictPrompt(note)
            }
            is StrictSessionStore.Restore.Expired -> {
                overlay.showBubble(BubbleSpec("上一回合已经到期了。", textOnly = true)) { handler.postDelayed({ overlay.hideBubble() }, 4000) }
            }
            is StrictSessionStore.Restore.Finished -> strictStore.clear()
            StrictSessionStore.Restore.None -> Unit
        }
    }

    private fun strictNeedsSupport() {
        val flow = think ?: return
        val m = flow.strict ?: return
        val out = m.needsSupport(now())
        flow.touchedAt = now()
        flow.waitSince = now()
        flow.awaitingAnswer = true
        persistStrict(flow)
        if (out is StrictOutcome.Support) {
            overlay.showBubble(BubbleSpec(out.text + "\n\n问题还是：" + m.state.question, instant = true, actions = strictActions()))
        }
    }

    private fun strictAbort() {
        val flow = think ?: return
        val m = flow.strict ?: return
        val out = m.abort(now())
        strictStore.save(m.state)
        flow.strict = null
        flow.awaitingAnswer = false
        val summary = (out as? StrictOutcome.Finished)?.summary ?: m.summary()
        overlay.showBubble(BubbleSpec("结束了。" + summary, textOnly = true)) { handler.postDelayed({ if (think === flow) endThink() }, 3000) }
    }

    /** 回合结束（到期/最后一步通过）：写墓碑、收起，不留可复活的残留。 */
    private fun finishStrict(flow: ThinkFlow, text: String) {
        flow.strict?.let { strictStore.save(it.state) }
        flow.strict = null
        flow.awaitingAnswer = false
        overlay.setMood(Mood.HAPPY)
        overlay.showBubble(BubbleSpec(text, textOnly = true)) { handler.postDelayed({ if (think === flow) endThink() }, 5000) }
    }

    /** 每秒推进：到期结算、沉默提醒（有上限）。 */
    private fun tickStrict(now: Long) {
        val flow = think ?: return
        val m = flow.strict ?: return
        val tick = m.tick(now)
        if (tick is StrictOutcome.Finished) {
            finishStrict(flow, "这一回合结束：" + tick.summary)
            return
        }
        // 心跳落盘：重启后报的空窗接近真实（原来只在状态变化时落盘，空窗会被夸大）。
        if (now - strictHeartbeatAt > 30_000L) persistStrict(flow)
        val st = m.state
        if (st.phase != Phase.WAITING && st.phase != Phase.SUPPORT) return
        val waited = now - flow.waitSince
        if (waited >= StrictSessionMachine.REMIND_AT_MILLIS) {
            val out = m.remind(now)
            if (out is StrictOutcome.Ask) {
                flow.waitSince = now
                flow.awaitingAnswer = true
                persistStrict(flow)
                overlay.showBubble(BubbleSpec(out.text + "\n\n问题：" + st.question, instant = true, actions = strictActions()))
            }
        }
    }

    /** 锁屏时：严格回合不作废，只收起界面；亮屏后接着问。 */
    private fun suspendThinkUi() {
        voice.cancel()
        overlay.setListening(false)
        overlay.setBubbleFocusable(false)
        overlay.hideBubble()
    }

    private fun endThink() {
        if (think == null && noteDraft == null) return
        // 用户主动收起：作废这一回合，不留可在重启后复活的残留。
        think?.strict?.let { m ->
            if (m.state.active) strictStore.save(m.abort(now()).let { m.state })
        }
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
        if (systemVoice) actions += BubbleAction("说一句", highlighted = true) { thinkVoice { saveNote(it) } }
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
        var noteEventId: String? = null
        val saving = scope.launch(Dispatchers.IO) {
            synchronized(app.memoryLock) {
                app.thoughts.add(today, t)
                val noteEvent = app.events.append(EventKind.NOTE, said, source = "note")
                noteEventId = noteEvent.id
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
                        scope.launch(Dispatchers.IO) {
                            saving.join() // 先等保存写完，否则撤销会落在保存之前
                            synchronized(app.memoryLock) {
                                app.thoughts.removeLast(today)
                                // 只删笔记文件不够：事件、交流行和话题还在，会被召回或主动提起。
                                noteEventId?.let { app.memoryEdits.retractNote(it) }
                            }
                        }
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

    /** 网络是通的，但模型没按格式回：也要让“开始”页看得到，不然只是莫名其妙不说话。 */
    private fun noteBadFormat() {
        app.callStatus.record(CallStatus(false, CallDiagnosis.BAD_FORMAT, now()))
    }

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
        /** 本机提示的占位开场（回话时会按当时上下文重新组装，不发给模型）。 */
        const val LOCAL_NUDGE_MARK = "local-nudge"
        /** “看一眼”成功时气泡的开头；验收脚本靠它区分看图结果和普通搭话。 */
        const val LOOK_RESULT_PREFIX = "我看了一眼："
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        private const val PROMPT_COUNTDOWN = "countdown"
        private const val PROMPT_ASK = "ask"
        private const val PROMPT_MENU = "menu"
    }
}
