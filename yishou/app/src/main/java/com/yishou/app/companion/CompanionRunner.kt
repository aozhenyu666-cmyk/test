package com.yishou.app.companion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.yishou.app.YishouApp
import com.yishou.app.core.Companion
import com.yishou.app.core.CompanionAction
import com.yishou.app.core.CompanionContext
import com.yishou.app.core.CompanionLocal
import com.yishou.app.core.CompanionPrompt
import com.yishou.app.core.CompanionState
import com.yishou.app.core.FinalReviewRules
import com.yishou.app.core.JudgementCheck
import com.yishou.app.core.JudgementRules
import com.yishou.app.core.QuestionLedger
import com.yishou.app.core.SilenceStep
import com.yishou.app.core.proactive.HighFreq
import com.yishou.app.core.proactive.HighFreqConfig
import com.yishou.app.core.proactive.ScreenDelta
import com.yishou.app.core.proactive.AnswerAction
import com.yishou.app.core.proactive.AskKind
import com.yishou.app.core.proactive.MemoryType
import com.yishou.app.core.proactive.ProactiveAnswer
import com.yishou.app.core.proactive.ProactiveContext
import com.yishou.app.core.proactive.ProactiveDecision
import com.yishou.app.core.proactive.ProactiveEngine
import com.yishou.app.core.proactive.ProactiveMode
import com.yishou.app.core.proactive.ProactivePolicy
import com.yishou.app.core.proactive.RelationMemory
import com.yishou.app.core.proactive.RelationRules
import com.yishou.app.core.proactive.Topic
import com.yishou.app.core.proactive.TopicBrief
import com.yishou.app.core.proactive.TopicState
import com.yishou.app.core.proactive.toProactive
import kotlin.random.Random
import com.yishou.app.data.EchoKind
import com.yishou.app.floating.CoachFloat
import com.yishou.app.floating.FloatAction
import com.yishou.app.floating.FloatPanel
import com.yishou.app.floating.FloatingReplyActivity
import com.yishou.app.gate.GateActivity
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 伴读：同一个娱乐应用待够时间 → 截一帧 → 转成文字 → 出一句必须做判断的问题 → 浮窗。
 *
 * 全部在一手里完成；总控只负责拦截。
 * 截图只在内存里，不落盘；两次提问之间、30 分钟内的次数、不答的升级都由 [Companion] 控制。
 */
object CompanionRunner {
    private const val TAG = "Companion"
    private const val SCENE = "companion"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var fgPkg: String? = null
    private var fgSince = 0L
    private var asking = false
    private var nudged = false
    private var attempt = 0
    private var warnedNoAi = false
    /** 用户正在浮窗里打字：这期间不要用提醒把输入框顶掉。 */
    private var replyOpen = false
    /** 高频会话里已经消费到的那个「新画面」时刻；会话结束时归零。 */
    private var lastFrameSeenAt = 0L

    /** GateService 每秒调一次。 */
    fun tick(service: AccessibilityService, pkg: String?) {
        val app = service.application as? YishouApp ?: return
        val prefs = CompanionPrefs(service)
        val cfg = prefs.load()
        // 高频陪练是一场显式会话：参数在设置里调，开始/结束由用户按；到点自动收线。
        val ap = app.settings.app.value
        val hfCfg = HighFreqConfig(
            enabled = ap.hfStartedAt > 0,
            lookEverySec = ap.hfLookSec,
            minGapSec = ap.hfGapSec,
            sessionMinutes = ap.hfMinutes,
        ).sane()
        var hf = prefs.loadHighFreq(ap.hfStartedAt, hfCfg.sessionMinutes)
        val hfRunning = HighFreq.running(hfCfg, hf, System.currentTimeMillis())
        if (ap.hfStartedAt > 0 && !hfRunning) {
            app.settings.updateApp { it.copy(hfStartedAt = 0) }
            prefs.resetHighFreq()
            lastFrameSeenAt = 0L
        }
        if (!cfg.enabled && !hfRunning) return

        val hasAi = app.settings.vision.value.isComplete && app.settings.llm.value.isComplete
        if (!hasAi && !warnedNoAi) {
            warnedNoAi = true
            Log.i(TAG, "识图或模型没配：走本地提醒")
        }

        val now = System.currentTimeMillis()
        if (pkg != fgPkg) {
            fgPkg = pkg
            fgSince = now
            nudged = false
        }

        val pending = prefs.pendingQuestion()
        if (!pending.isNullOrBlank()) {
            if (replyOpen) return
            val seconds = ((now - prefs.pendingAt()) / 1000).toInt()
            when (Companion.onSilence(cfg, seconds)) {
                SilenceStep.NUDGE -> if (!nudged) {
                    nudged = true
                    showQuestion(service, pending, "还剩 ${cfg.closeSeconds - seconds} 秒")
                }
                SilenceStep.CLOSE -> {
                    Log.i(TAG, "没答，收回桌面")
                    close(service, prefs)
                }
                SilenceStep.WAIT -> Unit
            }
            return
        }

        if (asking) return
        if (pkg == null || pkg == service.packageName) return

        // 高频会话：看和说是两条节拍，「说」必须建立在「刚看过、而且画面变了」之上。
        // 录屏在跑时由 ScreenLookService 比像素、识图，这里只消费「有新画面」这一信号；
        // 没开录屏才退回无障碍读屏文字比较（省钱，也够用）。
        if (hfRunning) {
            if (com.yishou.app.look.ScreenLookService.running.value) {
                val frameAt = prefs.highFreqFrameAt()
                if (frameAt <= lastFrameSeenAt) return
                lastFrameSeenAt = frameAt
            } else if (HighFreq.dueLook(hfCfg, hf, now)) {
                val text = runCatching { screenTextFromNodes(service) }.getOrDefault("")
                val moved = ScreenDelta.changed(prefs.lastScreenText(), text)
                hf = HighFreq.onLook(hf, now)
                prefs.saveHighFreq(hf)
                if (!moved) return
                prefs.setLastScreenText(text)
            } else {
                return
            }
        }

        asking = true
        val label = runCatching {
            service.packageManager.getApplicationLabel(
                service.packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        }.getOrDefault(pkg)
        val since = fgSince

        scope.launch {
            try {
                val at = System.currentTimeMillis()
                val state = prefs.loadState(at)
                val ctx = buildProactiveContext(app, pkg, since, at, prefs)
                // 仍然只有 ProactiveEngine 决定开不开口；高频会话只把间隔换成秒级。
                val decision = ProactiveEngine.decide(
                    if (hfRunning) HighFreq.toProactive(hfCfg, cfg.toProactive()) else cfg.toProactive(),
                    state, ctx, Random.nextInt(100),
                )
                if (decision !is ProactiveDecision.Ask) {
                    asking = false
                    return@launch
                }
                val kind = decision.kind
                val topicTitle = decision.topicId?.let { id ->
                    ctx.openTopics.firstOrNull { it.id == id }?.title
                }
                val question = runCatching {
                    buildQuestion(app, service, label, ctx.foregroundMinutes, hasAi, kind, topicTitle)
                }.onFailure { Log.w(TAG, "出题失败", it) }.getOrNull()
                asking = false
                prefs.markAttempt(System.currentTimeMillis())
                if (question.isNullOrBlank()) return@launch
                attempt = 0
                nudged = false
                val nowAt = System.currentTimeMillis()
                if (hfRunning) {
                    hf = HighFreq.onSpeak(hf, nowAt)
                    prefs.saveHighFreq(hf)
                }
                val latest = prefs.loadState(nowAt)
                prefs.saveState(
                    latest.copy(
                        mode = ProactiveMode.ASKING,
                        lastAskAt = nowAt,
                        promptsThisHour = latest.promptsThisHour + 1,
                        promptsToday = latest.promptsToday + 1,
                        lastTopicId = decision.topicId ?: latest.lastTopicId,
                    ),
                )
                prefs.setPendingKind(kind.name)
                prefs.setPendingTopicId(decision.topicId ?: 0L)
                prefs.setLocalMode(!hasAi || kind == AskKind.TOPIC_FOLLOW_UP)
                prefs.addPrompt(nowAt)
                prefs.setPending(question, nowAt)
                prefs.addQuestion(question)
                showQuestion(service, question, null)
            } catch (e: Throwable) {
                asking = false
                Log.w(TAG, "主动调度失败", e)
            }
        }
    }

    /** 一手唯一的上下文入口：任务、卡点、未解决话题、上次回答。 */
    private suspend fun buildProactiveContext(
        app: YishouApp,
        pkg: String,
        since: Long,
        now: Long,
        prefs: CompanionPrefs,
    ): ProactiveContext {
        val dao = app.database.dao()
        val task = dao.getCurrentTask()
        val bp = task?.let { dao.getBreakpoint(it.id) }
        val topics = runCatching { app.database.relation().openTopics(6) }.getOrDefault(emptyList())
        return ProactiveContext(
            now = now,
            hour = java.time.LocalDateTime.now(com.yishou.app.settings.AppZone.zone).hour,
            foregroundPkg = pkg,
            watched = app.settings.app.value.watched.containsKey(pkg),
            foregroundMinutes = ((now - since) / 60_000L).toInt(),
            taskTitle = task?.title,
            stuck = bp?.stuck,
            openTopics = topics.map {
                TopicBrief(it.id, it.title, it.state.name, it.lastMentionedAt, it.followUpAt)
            },
            lastAnswer = prefs.lastAnswerText(),
            ownUiOpen = GateActivity.isShowing,
            hasPending = false,
        )
    }

    /** 回答。返回判定结果，UI 据此决定是否留在页面上重答。 */
    fun answer(context: Context, text: String): JudgementCheck {
        val prefs = CompanionPrefs(context)
        val question = prefs.pendingQuestion()
        if (question.isNullOrBlank()) {
            // 已经交过一次（或页面被关掉后重复点）：不重复写记忆。
            return JudgementCheck(pass = true, hint = "")
        }
        val app = context.applicationContext as? YishouApp
        val kind = runCatching { AskKind.valueOf(prefs.pendingKind()) }.getOrNull()
        val local = prefs.localMode() || kind == AskKind.TOPIC_FOLLOW_UP
        replyOpen = false
        val check = when {
            kind == AskKind.TOPIC_FOLLOW_UP -> ProactiveAnswer.checkTopic(text, attempt)
            local -> CompanionLocal.checkAnswer(text, attempt)
            else -> JudgementRules.check(text, attempt)
        }
        val now = System.currentTimeMillis()
        val cfg = prefs.load()
        if (check.pass) {
            val topicId = prefs.pendingTopicId()
            val result = ProactivePolicy.onAnswer(cfg.toProactive(), prefs.loadState(now), text, now)
            prefs.saveState(result.state)
            prefs.setLastAnswerText(text)
            prefs.clearPending()
            prefs.setLocalMode(false)
            prefs.markAnswered(now)
            CoachFloat.remove("companion")
            attempt = 0
            nudged = false
            // 记忆只存“你说了什么”，不存它自己问的问题；
            // 但“休息”这类两个字的回答太短，Echo 会按“不可用”丢掉，所以短回答补上当时的提问。
            val memory = if (local && !com.yishou.app.line.Echoes.usable(text)) {
                "$text（当时问：${question.take(30)}）"
            } else {
                text
            }
            app?.echoLater(memory, EchoKind.CONCLUSION, SCENE)
            if (app != null) {
                app.appScopeLaunch {
                    val store = app.database.relation()
                    if (topicId > 0) {
                        val topic = store.openTopics(20).firstOrNull { it.id == topicId }
                        if (topic != null) {
                            val resolved = result.action == AnswerAction.DONE || RelationRules.isResolved(text)
                            store.updateTopic(RelationRules.updateTopicAfterAnswer(topic, text, now, resolved))
                        }
                    } else if (result.action == AnswerAction.DEFER || result.action == AnswerAction.BACK) {
                        store.addTopic(
                            Topic(
                                title = "约定：${text.take(30)}",
                                state = TopicState.OPEN,
                                createdAt = now,
                                lastMentionedAt = now,
                                followUpAt = if (result.action == AnswerAction.DEFER) {
                                    result.state.deferUntil
                                } else {
                                    now + 30 * 60_000L
                                },
                                summary = text.take(80),
                            ),
                        )
                    }
                    RelationRules.extract(text, now).forEach { store.addMemory(it) }
                    if (result.action == AnswerAction.REST) {
                        store.addMemory(
                            RelationMemory(type = MemoryType.EVENT, text = "休息：$text", observedAt = now),
                        )
                    }
                    if (result.action == AnswerAction.DONE) {
                        store.addMemory(
                            RelationMemory(type = MemoryType.EVENT, text = "完成了：$text", observedAt = now),
                        )
                    }
                }
            }
            Log.i(TAG, "判断通过：${text.take(60)}")
        } else {
            attempt += 1
            if (!check.reask) {
                prefs.saveState(ProactivePolicy.onMiss(cfg.toProactive(), prefs.loadState(now), now))
                prefs.clearPending()
                prefs.setLocalMode(false)
                prefs.markAnswered(now)
                prefs.addMiss()
                CoachFloat.remove("companion")
                app?.echoLater("伴读没答上来：$question", EchoKind.DELTA, SCENE)
                attempt = 0
            }
        }
        return check
    }

    fun reset() {
        fgPkg = null
        fgSince = 0L
        asking = false
        nudged = false
        attempt = 0
    }

    /** 页面上的“不想答”：记一次没做到，清掉当前问题。 */
    fun skip(context: Context) {
        val prefs = CompanionPrefs(context)
        val now = System.currentTimeMillis()
        prefs.saveState(ProactivePolicy.onMiss(prefs.load().toProactive(), prefs.loadState(now), now))
        prefs.clearPending()
        prefs.setLocalMode(false)
        prefs.markAnswered(now)
        prefs.addMiss()
        replyOpen = false
        CoachFloat.remove("companion")
    }

    private suspend fun buildQuestion(
        app: YishouApp,
        service: AccessibilityService,
        label: String,
        minutes: Int,
        hasAi: Boolean,
        kind: AskKind,
        topicTitle: String?,
    ): String? {
        if (kind == AskKind.TOPIC_FOLLOW_UP) {
            val followUp = com.yishou.app.core.Persona
                .topic(topicTitle ?: "上次那件事", CompanionPrefs(service).persona())
                .take(80)
            Log.i(TAG, "话题跟进：$followUp")
            return followUp
        }
        val ctx = runCatching { readContext(app) }.getOrElse { CompanionContext() }
        if (!hasAi || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val local = CompanionLocal.nudge(ctx, label, minutes, CompanionPrefs(service).persona()).take(80)
            Log.i(TAG, "本地提醒：$local")
            return local
        }

        val nodeText = runCatching { screenTextFromNodes(service) }.getOrDefault("")
        if (nodeText.length >= 10) Log.i(TAG, "节点文字：${nodeText.take(80)}")

        val screenText = if (nodeText.length >= 10) {
            nodeText
        } else {
            val bitmap = screenshot(service) ?: run {
                Log.w(TAG, "截屏失败或不可用")
                return null
            }
            Log.i(TAG, "截屏成功：${bitmap.width}x${bitmap.height}")
            val base64 = encode(bitmap)
            bitmap.recycle()
            if (base64.isBlank()) return null

            val seen = app.scrollLook.completeWithImage(
                "把屏幕正文完整转写出来：标题、字幕、正文。去掉按钮、点赞数、时间戳、广告；看不清写（看不清）。不要总结，不要评论。",
                "请转写这张屏幕。",
                "data:image/jpeg;base64,$base64",
                "伴读识屏",
            )
            val text = (seen as? LlmResult.Ok)?.value?.trim().orEmpty()
            if (seen is LlmResult.Err) Log.w(TAG, "识屏失败：${seen.error}")
            text
        }
        if (screenText.length < 10) return null

        val generated = app.scrollJudge.complete(
            JudgementRules.SYSTEM,
            CompanionPrompt.user(ctx, screenText, label),
            "伴读",
        )
        val raw = (generated as? LlmResult.Ok)?.value?.trim().orEmpty()
        if (raw.isNotBlank()) Log.i(TAG, "模型出题：$raw")
        val quoteOk = FinalReviewRules.quotesSource(raw, screenText)
        var question = when {
            raw.isBlank() -> CompanionPrompt.fallback(ctx, screenText)
            !quoteOk -> CompanionPrompt.fallback(ctx, screenText)
            else -> raw
        }.take(80).trim()

        val recent = CompanionPrefs(service).recentQuestions()
        if (QuestionLedger.isDuplicate(question, recent)) {
            question = CompanionPrompt.fallback(ctx, screenText).take(80).trim()
        }
        if (question.isBlank() || QuestionLedger.isDuplicate(question, recent)) return null
        Log.i(TAG, "题目：$question")
        return question
    }

    /** 读当前窗口的无障碍节点文字；不截图、不联网，优先用它。 */
    private fun screenTextFromNodes(service: AccessibilityService): String {
        val root = service.rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 14 || sb.length >= 1600) return
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().trim()
    }

    /** 读当前任务、断点、上一轮和最近的判断；任务可以没有，记忆不能因此丢掉。 */
    private suspend fun readContext(app: YishouApp): CompanionContext {
        val dao = app.database.dao()
        val task = dao.getCurrentTask()
        val bp = task?.let { dao.getBreakpoint(it.id) }
        val last = dao.lastRound()?.takeIf { task == null || it.taskId == task.id }
        val conclusions = app.database.echoes().recent()
            .filter { it.scene == SCENE }
            .take(3)
            .map { it.text }
        return CompanionContext(
            taskTitle = task?.title,
            goal = task?.goal,
            stuck = bp?.stuck,
            nextQuestion = bp?.nextQuestion,
            lastAnswer = last?.userAnswer,
            recentConclusions = conclusions,
        )
    }

    private fun showQuestion(service: AccessibilityService, question: String, countdown: String?) {
        replyOpen = false
        val detail = if (countdown.isNullOrBlank()) question else "$question\n$countdown"
        CoachFloat.update(
            service,
            "companion",
            FloatPanel(
                title = "看一眼，做个判断",
                detail = detail,
                actions = listOf(
                    FloatAction("回答", primary = true) {
                        showAnswerInput(service, question)
                    },
                    FloatAction("不想答") {
                        close(service, CompanionPrefs(service))
                    },
                ),
                priority = 3,
                autoExpand = true,
            ),
        )
    }

    /** 回答入口：直接在浮窗里输入，不启动 Activity（后台启动会被系统拦掉）。 */
    private fun showAnswerInput(service: AccessibilityService, question: String) {
        replyOpen = true
        CoachFloat.update(
            service,
            "companion",
            FloatPanel(
                title = "回一句",
                detail = question,
                actions = listOf(
                    FloatAction("取消") { showQuestion(service, question, null) },
                ),
                priority = 4,
                autoExpand = true,
                reply = true,
                replyHint = "休息 / 现在回去 / 定个时间",
                presets = if (CompanionPrefs(service).localMode()) {
                    listOf("休息", "现在回去", "定个时间")
                } else {
                    emptyList()
                },
                onSubmit = { text ->
                    val check = answer(service, text)
                    if (check.pass) {
                        CoachFloat.remove("companion")
                    } else {
                        showQuestion(service, check.hint.ifBlank { "再回一句" }, null)
                    }
                },
            ),
        )
    }

    private fun close(service: AccessibilityService, prefs: CompanionPrefs) {
        replyOpen = false
        val now = System.currentTimeMillis()
        prefs.saveState(ProactivePolicy.onMiss(prefs.load().toProactive(), prefs.loadState(now), now))
        prefs.clearPending()
        prefs.setLocalMode(false)
        prefs.markAnswered(now)
        prefs.addMiss()
        CoachFloat.remove("companion")
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        nudged = false
        attempt = 0
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun screenshot(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val bitmap = runCatching {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                            result.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "截屏失败：$errorCode")
                            if (cont.isActive) cont.resume(null as Bitmap?)
                        }
                    },
                )
            } catch (e: Throwable) {
                Log.w(TAG, "截屏异常", e)
                if (cont.isActive) cont.resume(null as Bitmap?)
            }
        }
    }

    private fun encode(bitmap: Bitmap): String {
        val width = 720
        val scaled = if (bitmap.width > width) {
            Bitmap.createScaledBitmap(
                bitmap,
                width,
                (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
