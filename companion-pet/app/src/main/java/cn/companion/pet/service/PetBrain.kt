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
import cn.companion.pet.bridge.GeminiLauncher
import cn.companion.pet.core.bridge.BridgeContext
import cn.companion.pet.core.bridge.GeminiBridgeTextBuilder
import cn.companion.pet.core.life.PetLife
import cn.companion.pet.core.life.WeightyMoments
import cn.companion.pet.core.memory.Exchange
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
import cn.companion.pet.core.text.ScreenTextCleaner
import cn.companion.pet.data.Stats
import cn.companion.pet.net.ChatMessage
import cn.companion.pet.net.ChatRequest
import cn.companion.pet.net.ChatResult
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
    private val scheduler = SpeakScheduler(settings.rhythm())
    private val life = PetLife()
    private val weighty = WeightyMoments()
    private val haptics = Haptics(context)
    private val voice = VoiceInput(context)
    private val gemini = GeminiLauncher(context)
    private val speech = Speech(context)
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
    ) {
        var interacting = false
        var userReply: String? = null
        var petReply: String? = null
        var gemini = GeminiExit.NONE
        var attentionDone = false
        var nudgeDone = false
        var ended = false
    }

    private var convo: Conversation? = null
    private var probeJob: Job? = null
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
        active = isUserActive()
        if (active) onUserPresent()
        handler.post(ticker)
        scope.launch { settings.changes.collect { scheduler.config = settings.rhythm() } }
    }

    fun stop() {
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
        scheduler.onScreenOff()
        probeJob?.cancel()
        voice.cancel()
        endConversation()
        overlay.retreat()
        Heartbeat.schedule(context, settings)
    }

    /** 解锁进入桌面（USER_PRESENT），或服务启动时已经是解锁状态。 */
    fun onUserPresent() {
        active = true
        Heartbeat.cancel(context)
        scheduler.onScreenOn(now())
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
        if (!active) return

        convo?.let { escalate(it, now); return }
        if (probeJob?.isActive == true || settings.petPaused) return

        if (PerceptionHub.stuck.check(now)) {
            if (!scheduler.isQuiet(now)) speak(stuck = true)
            return
        }
        val focus = SceneClassifier.focusActive(now, PerceptionHub.trail, settings.appLists())
        if (scheduler.isDue(now, focus)) speak(stuck = false)
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

    private fun speak(stuck: Boolean, afterHeartbeat: Boolean = false) {
        val now = now()
        if (probeJob?.isActive == true || convo != null) return
        heartbeatWaiting = false

        val key = apiKey()
        if (key == null) {
            // 没配置 Key：只做个动作，不打扰
            overlay.attention()
            scheduler.onSpoke(now)
            return
        }
        if (stats.get(Stats.Kind.API_CALLS) >= settings.dailyLimit) {
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
            petState = life.state.label,
            afterHeartbeat = afterHeartbeat,
        )

        probeJob = scope.launch {
            overlay.setMood(Mood.THINKING)
            val opening = withContext(Dispatchers.IO) {
                synchronized(app.memoryLock) {
                    ProbePrompt.opening(memory.persona(), memory.profile(), memory.todayRecent(20), memory.diarySummary(LocalDate.now()), moment)
                }
            }
            stats.add(Stats.Kind.API_CALLS)
            val result = app.client.complete(ChatRequest(settings.endpoint, settings.model, key, opening))
            overlay.setMood(Mood.IDLE)
            val reply = (result as? ChatResult.Success)?.let { ProbeReplyParser.parse(it.content) }
            when {
                reply == null -> scheduler.onSpoke(now()) // 失败或解析失败：静默，按正常节奏下次再来
                reply.skip || ContentGuard.violates(reply.hookText) -> scheduler.retrySoon(now())
                !active || convo != null -> scheduler.retrySoon(now())
                else -> {
                    if (isWeighty) { weighty.markUsed(today); stats.add(Stats.Kind.WEIGHTY) }
                    rephraseNext = false
                    present(decision, reply, opening, noEscalation = afterHeartbeat)
                }
            }
        }
    }

    private fun present(decision: SceneDecision, reply: ProbeReply, opening: List<ChatMessage>, noEscalation: Boolean) {
        val now = now()
        scheduler.onSpoke(now)
        stats.add(Stats.Kind.SPOKE)
        convo = Conversation(decision, reply, opening, now, noEscalation = noEscalation)
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
        showLive = scene == Scene.LEARNING,
    )

    // ---------- 用户主动 ----------

    override fun onPetTapped() {
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
        overlay.showBubble(entrySpec(greeting, decision.scene, false))
    }

    override fun onHoldStart() = startListening(fromHold = true)

    override fun onHoldEnd() = stopListening()

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
        val canCall = key != null && stats.get(Stats.Kind.API_CALLS) < settings.dailyLimit && c.opening.isNotEmpty()
        overlay.setMood(Mood.THINKING)
        overlay.showBubble(BubbleSpec("……", textOnly = true))
        probeJob = scope.launch {
            var text: String? = null
            var mood = Mood.IDLE
            if (canCall) {
                stats.add(Stats.Kind.API_CALLS)
                val hook = c.reply.hookText.ifEmpty { "嗯？" }
                val r = app.client.complete(ChatRequest(settings.endpoint, settings.model, key!!, ProbePrompt.reply(c.opening, hook, said)))
                val parsed = (r as? ChatResult.Success)?.let { ProbeReplyParser.parse(it.content) }
                if (parsed != null && !parsed.skip && !ContentGuard.violates(parsed.hookText)) {
                    text = parsed.hookText
                    mood = parsed.mood
                }
            }
            if (convo !== c || c.ended) return@launch
            // 调用失败不弹错误：点点头收尾
            val final = text ?: "嗯，我听到了。"
            c.petReply = final
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

    // ---------- Gemini ----------

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
        val pkg = settings.geminiPackage
        gemini.copy("桌宠 → Gemini", text)
        c.gemini = GeminiExit.TEXT
        stats.add(Stats.Kind.GEMINI_TEXT)
        endConversation()
        when (gemini.launch(pkg)) {
            GeminiLauncher.Outcome.NOT_INSTALLED -> { gemini.toast("没找到 Gemini（$pkg），文字已复制"); return }
            GeminiLauncher.Outcome.FAILED -> { gemini.toast("打不开 Gemini，文字已复制"); return }
            GeminiLauncher.Outcome.LAUNCHED -> Unit
        }
        if (settings.geminiAutoSend && PerceptionHub.service != null) {
            scope.launch {
                delay(800)
                val ok = GeminiAutoSender.send(pkg, text)
                if (!ok) gemini.toast("没能自动发送，已复制，粘贴发送即可")
            }
        } else {
            gemini.toast("已复制，粘贴发送即可")
        }
    }

    override fun onLive() {
        val c = convo ?: return
        gemini.copy("桌宠 → Gemini Live", GeminiBridgeTextBuilder.buildLiveOpening(c.reply.hookText))
        c.gemini = GeminiExit.LIVE
        stats.add(Stats.Kind.GEMINI_LIVE)
        endConversation()
        when (gemini.launch(settings.geminiPackage)) {
            GeminiLauncher.Outcome.LAUNCHED -> gemini.toast("点 Gemini 里的 Live，再点“共享屏幕”。开场白已复制，可以照着念")
            else -> gemini.toast("没打开 Gemini，开场白已复制")
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
        scope.launch(Dispatchers.IO) { synchronized(app.memoryLock) { memory.appendToday(LocalDate.now(), line) } }
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

    private fun buildOpeningSync(decision: SceneDecision): List<ChatMessage> = synchronized(app.memoryLock) {
        ProbePrompt.opening(
            memory.persona(), memory.profile(), memory.todayRecent(20), memory.diarySummary(LocalDate.now()),
            ProbeMoment(timeText(), decision.scene, PerceptionHub.trail.foreground?.let { label(it) } ?: "不清楚", trajectory(now()), decision.screenText, petState = life.state.label),
        )
    }

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

    private fun timeText(): String = LocalTime.now().format(HHMM)

    private fun now() = System.currentTimeMillis()

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
