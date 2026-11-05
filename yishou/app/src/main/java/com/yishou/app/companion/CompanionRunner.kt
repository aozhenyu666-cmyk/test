package com.yishou.app.companion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
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

    /** GateService 每秒调一次。 */
    fun tick(service: AccessibilityService, pkg: String?) {
        val app = service.application as? YishouApp ?: return
        val prefs = CompanionPrefs(service)
        val cfg = prefs.load()
        if (!cfg.enabled) return

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
        if (!app.settings.app.value.watched.containsKey(pkg)) return

        val state = CompanionState(
            fgSeconds = ((now - fgSince) / 1000).toInt(),
            sinceLastPromptSeconds = maxOf(prefs.lastPromptAt(), prefs.lastAttemptAt()).let {
                if (it == 0L) Int.MAX_VALUE else ((now - it) / 1000).toInt()
            },
            promptsInHalfHour = prefs.promptsInHalfHour(now),
            misses = prefs.misses(),
            speaking = false,
            ownUiOpen = GateActivity.isShowing,
            hasPending = false,
        )
        if (Companion.decide(cfg, state) != CompanionAction.ASK) return

        asking = true
        val label = runCatching {
            service.packageManager.getApplicationLabel(
                service.packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        }.getOrDefault(pkg)

        scope.launch {
            val question = runCatching { buildQuestion(app, service, label, state.fgSeconds / 60, hasAi) }
                .onFailure { Log.w(TAG, "出题失败", it) }
                .getOrNull()
            asking = false
            prefs.markAttempt(System.currentTimeMillis())
            if (question.isNullOrBlank()) return@launch
            attempt = 0
            nudged = false
            val at = System.currentTimeMillis()
            prefs.setLocalMode(!hasAi)
            prefs.addPrompt(at)
            prefs.setPending(question, at)
            prefs.addQuestion(question)
            showQuestion(service, question, null)
        }
    }

    /** 回答。返回判定结果，UI 据此决定是否留在页面上重答。 */
    fun answer(context: Context, text: String): JudgementCheck {
        val prefs = CompanionPrefs(context)
        val app = context.applicationContext as? YishouApp
        replyOpen = false
        val local = prefs.localMode()
        val check = if (local) CompanionLocal.checkAnswer(text, attempt) else JudgementRules.check(text, attempt)
        if (check.pass) {
            val question = prefs.pendingQuestion().orEmpty()
            prefs.clearPending()
            prefs.setLocalMode(false)
            CoachFloat.remove("companion")
            attempt = 0
            nudged = false
            val memory = if (local && question.isNotBlank()) "$question → $text" else text
            app?.echoLater(memory, EchoKind.CONCLUSION, SCENE)
            Log.i(TAG, "判断通过：${text.take(60)}")
        } else {
            attempt += 1
            if (!check.reask) {
                val question = prefs.pendingQuestion().orEmpty()
                prefs.clearPending()
                prefs.setLocalMode(false)
                prefs.addMiss()
                CoachFloat.remove("companion")
                if (question.isNotBlank()) {
                    app?.echoLater("伴读没答上来：$question", EchoKind.DELTA, SCENE)
                }
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
        prefs.clearPending()
        prefs.setLocalMode(false)
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
    ): String? {
        val ctx = runCatching { readContext(app) }.getOrElse { CompanionContext() }
        if (!hasAi) {
            val local = CompanionLocal.nudge(ctx, label, minutes).take(80)
            Log.i(TAG, "本地提醒：$local")
            return local
        }

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
        val screenText = (seen as? LlmResult.Ok)?.value?.trim().orEmpty()
        if (seen is LlmResult.Err) Log.w(TAG, "识屏失败：${seen.error}")
        if (screenText.length < 10) return null

        val generated = app.scrollJudge.complete(
            JudgementRules.SYSTEM,
            CompanionPrompt.user(ctx, screenText, label),
            "伴读",
        )
        val raw = (generated as? LlmResult.Ok)?.value?.trim().orEmpty()
        val span = FinalReviewRules.quotableSpan(screenText)
        var question = when {
            raw.isBlank() -> CompanionPrompt.fallback(ctx, screenText)
            span.isNotBlank() && !raw.contains(span) -> CompanionPrompt.fallback(ctx, screenText)
            else -> raw
        }.take(80).trim()

        val recent = CompanionPrefs(service).recentQuestions()
        if (QuestionLedger.isDuplicate(question, recent)) {
            question = CompanionPrompt.fallback(ctx, screenText).take(80).trim()
        }
        if (question.isBlank() || QuestionLedger.isDuplicate(question, recent)) return null
        return question
    }

    /** 读当前任务、断点、上一轮和最近的判断；读不到就退化成纯屏幕题。 */
    private suspend fun readContext(app: YishouApp): CompanionContext {
        val dao = app.database.dao()
        val task = dao.getCurrentTask() ?: return CompanionContext()
        val bp = dao.getBreakpoint(task.id)
        val last = dao.lastRound()?.takeIf { it.taskId == task.id }
        val conclusions = app.database.echoes().recent()
            .filter { it.scene == SCENE }
            .take(3)
            .map { it.text }
        return CompanionContext(
            taskTitle = task.title,
            goal = task.goal,
            stuck = bp?.stuck,
            nextQuestion = bp?.nextQuestion,
            lastAnswer = last?.userAnswer,
            recentConclusions = conclusions,
        )
    }

    private fun showQuestion(service: AccessibilityService, question: String, countdown: String?) {
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
        prefs.clearPending()
        prefs.setLocalMode(false)
        prefs.addMiss()
        CoachFloat.remove("companion")
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        nudged = false
        attempt = 0
    }

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
