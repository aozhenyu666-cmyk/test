package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.VoicePlan
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist

/** 纯 JVM 可测的无障碍节点快照（零 Android 运行时依赖）。 */
data class UiNodeSnapshot(
    val id: Int,
    val packageName: String = "",
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val hintText: String = "",
    val viewIdResourceName: String = "",
    val isEditable: Boolean = false,
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    val isFocused: Boolean = false,
    val isPassword: Boolean = false,
    val supportsSetText: Boolean = false,
    val supportsPaste: Boolean = false,
)

enum class GeminiAutoState {
    WAIT_GEMINI_WINDOW,
    PASTE_AND_SEND_BRIEF,
    WAIT_REPLY_START,
    CLICK_LIVE_BUTTON,
    CLICK_SCREEN_SHARE,
    DONE,
    ABORTED,
}

sealed interface GeminiAutoAction {
    data object Wait : GeminiAutoAction
    data class PasteBrief(val node: UiNodeSnapshot, val text: String) : GeminiAutoAction
    data class ClickSend(val node: UiNodeSnapshot) : GeminiAutoAction
    data class ClickLive(val node: UiNodeSnapshot) : GeminiAutoAction
    data class ClickScreenShare(val node: UiNodeSnapshot) : GeminiAutoAction
    data class Finish(val reason: String) : GeminiAutoAction
    data class Abort(val reason: String) : GeminiAutoAction
}

data class GeminiStepInput(
    val state: GeminiAutoState,
    val round: Int,
    val elapsedMs: Long,
    val frontPackage: String?,
    val emergencyActive: Boolean,
    val brief: String,
    val briefWritten: Boolean = false,
    val sendClicked: Boolean = false,
    val replyWaitRounds: Int = 0,
    val sawGeminiWindow: Boolean = false,
    val nodes: List<UiNodeSnapshot> = emptyList(),
    val ownPackage: String = "",
    val requestScreenShare: Boolean = false,
    val liveClicked: Boolean = false,
    val shareWaitRounds: Int = 0,
    val directLive: Boolean = false,
)

data class GeminiStepOutput(
    val nextState: GeminiAutoState,
    val action: GeminiAutoAction,
    val briefWritten: Boolean = false,
    val sendClicked: Boolean = false,
    val replyWaitRounds: Int = 0,
    val sawGeminiWindow: Boolean = false,
    val liveClicked: Boolean = false,
    val shareWaitRounds: Int = 0,
)

/** 纯 JVM 可测：Gemini 包名、输入框、发送按钮、Live 按钮及回复状态识别规则。 */
object GeminiNodeMatcher {
    val GEMINI_PACKAGES = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
    )

    private val LIVE_VIEW_ID_REGEX = Regex("""(^|[_/:])live($|[_/])""")

    private val EXCLUDED_SEND_TERMS = listOf("feedback", "反馈", "resend", "重发", "share", "分享", "cancel", "取消")
    private val SEND_EXACT_LABELS = setOf(
        "发送",
        "send",
        "submit",
        "提交",
        "发送消息",
        "发送提示",
        "send message",
        "send prompt",
        "ask",
    )

    private val EXCLUDED_LIVE_TERMS = listOf(
        "end live",
        "结束 live",
        "结束live",
        "leave live",
        "退出 live",
        "退出live",
        "close live",
        "关闭 live",
        "stop live",
        "停止 live",
        "live caption",
        "实时字幕",
        "live wallpaper",
        "动态壁纸",
    )
    private val LIVE_EXACT_LABELS = setOf(
        "live",
        "go live",
        "gemini live",
        "开启 live",
        "开启live",
        "进入 live",
        "进入live",
        "打开 live",
        "打开live",
        "start live",
        "talk live",
        "实时语音",
        "live 语音",
        "live语音",
    )
    private val LIVE_PHRASES = listOf(
        "gemini live",
        "go live",
        "开启 live",
        "开启live",
        "进入 live",
        "进入live",
        "start live",
        "talk live",
        "live mode",
        "live 模式",
    )

    private val ACTIVE_LIVE_DIRECT_MARKERS = listOf(
        "正在聆听",
        "end live",
        "结束 live",
        "结束live",
        "结束语音",
        "tap to interrupt",
        "轻触可打断",
    )

    private val EXCLUDED_SHARE_TERMS = listOf(
        "stop sharing",
        "stop screen",
        "end sharing",
        "停止共享",
        "关闭共享",
        "结束共享",
        "停止投屏",
        "结束投屏",
        "cancel",
        "取消",
    )

    private val SHARE_EXACT_LABELS = setOf(
        "share screen",
        "screen share",
        "share your screen",
        "共享屏幕",
        "屏幕共享",
        "分享屏幕",
        "投屏",
    )

    private val SHARE_PHRASES = listOf(
        "share screen",
        "screen share",
        "share your screen",
        "共享屏幕",
        "屏幕共享",
        "分享屏幕",
    )

    private val REPLY_ACTIVE_MARKERS = listOf(
        "stop generating",
        "stop response",
        "停止生成",
        "停止回答",
        "正在思考",
        "正在生成",
        "生成中",
    )

    fun isGeminiPackage(pkg: String?): Boolean = pkg?.trim() in GEMINI_PACKAGES

    private fun pkgAllowed(node: UiNodeSnapshot, requireGeminiPackage: Boolean): Boolean {
        val pkg = node.packageName.trim()
        return if (requireGeminiPackage) {
            pkg in GEMINI_PACKAGES
        } else {
            pkg.isEmpty() || pkg in GEMINI_PACKAGES
        }
    }

    fun findInputNode(nodes: List<UiNodeSnapshot>, requireGeminiPackage: Boolean = true): UiNodeSnapshot? {
        var best: UiNodeSnapshot? = null
        var bestScore = Int.MIN_VALUE
        for (n in nodes) {
            if (!n.isVisibleToUser || !n.isEnabled || n.isPassword) continue
            if (!pkgAllowed(n, requireGeminiPackage)) continue
            val isEditClass = n.className.contains("EditText", ignoreCase = true)
            if (!n.isEditable && !isEditClass && !n.supportsSetText && !n.supportsPaste) continue

            var score = 0
            if (n.isEditable) score += 100
            if (isEditClass) score += 80
            if (n.supportsSetText) score += 30
            if (n.supportsPaste) score += 20
            if (n.isFocused) score += 15
            val meta = "${n.hintText} ${n.contentDescription} ${n.viewIdResourceName}".lowercase()
            if (meta.contains("gemini") || meta.contains("prompt") || meta.contains("ask") ||
                meta.contains("composer") || meta.contains("输入") || meta.contains("提问") ||
                meta.contains("message") || meta.contains("chat")
            ) {
                score += 25
            }
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }

    fun findSendButton(nodes: List<UiNodeSnapshot>, requireGeminiPackage: Boolean = true): UiNodeSnapshot? {
        var best: UiNodeSnapshot? = null
        var bestScore = Int.MIN_VALUE
        for (n in nodes) {
            if (!n.isVisibleToUser || !n.isEnabled) continue
            if (!pkgAllowed(n, requireGeminiPackage)) continue
            if (n.isEditable || n.className.contains("EditText", ignoreCase = true)) continue

            val t = n.text.trim().lowercase()
            val d = n.contentDescription.trim().lowercase()
            val v = n.viewIdResourceName.trim().lowercase()
            val combined = "$t $d $v"
            if (EXCLUDED_SEND_TERMS.any { combined.contains(it) }) continue

            val exactMatch = t in SEND_EXACT_LABELS || d in SEND_EXACT_LABELS
            val phraseMatch = t.startsWith("send ") || d.startsWith("send ") || t.contains("发送") || d.contains("发送")
            val idMatch = v.contains("send_button") || v.contains("send_btn") || v.contains("btn_send") ||
                v.contains("submit_button") || v.endsWith(":id/send") || v.endsWith("/send")
            if (!exactMatch && !phraseMatch && !idMatch) continue

            var score = 0
            if (n.isClickable) score += 50
            if (exactMatch) score += 40
            if (idMatch) score += 30
            if (phraseMatch) score += 20
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }

    fun findLiveButton(nodes: List<UiNodeSnapshot>, requireGeminiPackage: Boolean = true): UiNodeSnapshot? {
        var best: UiNodeSnapshot? = null
        var bestScore = Int.MIN_VALUE
        for (n in nodes) {
            if (!n.isVisibleToUser || !n.isEnabled) continue
            if (!pkgAllowed(n, requireGeminiPackage)) continue
            if (n.isEditable || n.className.contains("EditText", ignoreCase = true)) continue

            val t = n.text.trim().lowercase()
            val d = n.contentDescription.trim().lowercase()
            val v = n.viewIdResourceName.trim().lowercase()
            val combined = "$t $d $v"
            if (EXCLUDED_LIVE_TERMS.any { combined.contains(it) }) continue

            val exactMatch = t in LIVE_EXACT_LABELS || d in LIVE_EXACT_LABELS
            val phraseMatch = LIVE_PHRASES.any { t.contains(it) || d.contains(it) }
            val idMatch = v.contains("gemini_live") || v.contains("go_live") || v.contains("live_button") ||
                v.contains("live_btn") || v.contains("start_live") || v.contains("live_entry") ||
                v.contains("live_fab") || LIVE_VIEW_ID_REGEX.containsMatchIn(v)
            if (!exactMatch && !phraseMatch && !idMatch) continue

            var score = 0
            if (n.isClickable) score += 50
            if (exactMatch) score += 40
            if (phraseMatch) score += 30
            if (idMatch) score += 25
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }

    fun isAlreadyInLiveScreen(nodes: List<UiNodeSnapshot>, requireGeminiPackage: Boolean = true): Boolean {
        var sawLiveLabel = false
        var sawCallControl = false
        for (n in nodes) {
            if (!n.isVisibleToUser || !pkgAllowed(n, requireGeminiPackage)) continue
            val t = n.text.trim().lowercase()
            val d = n.contentDescription.trim().lowercase()
            val v = n.viewIdResourceName.trim().lowercase()
            val combined = "$t $d"
            if (ACTIVE_LIVE_DIRECT_MARKERS.any { combined.contains(it) }) return true
            if (t == "live" || d == "live" || combined.contains("gemini live")) sawLiveLabel = true
            if (combined.contains("静音") || t == "mute" || d == "mute" || combined.contains("unmute") ||
                combined.contains("挂断") || combined.contains("hang up") || v.contains("end_call")
            ) {
                sawCallControl = true
            }
        }
        return sawLiveLabel && sawCallControl
    }

    /** 仅匹配官方 Gemini Live 窗口内部的「共享屏幕」按钮，严格拒配系统 MediaProjection 权限窗（com.android.systemui）。 */
    fun findScreenShareButton(nodes: List<UiNodeSnapshot>, requireGeminiPackage: Boolean = true): UiNodeSnapshot? {
        var best: UiNodeSnapshot? = null
        var bestScore = Int.MIN_VALUE
        for (n in nodes) {
            if (!n.isVisibleToUser || !n.isEnabled) continue
            if (!pkgAllowed(n, requireGeminiPackage)) continue
            if (n.isEditable || n.className.contains("EditText", ignoreCase = true)) continue

            val t = n.text.trim().lowercase()
            val d = n.contentDescription.trim().lowercase()
            val v = n.viewIdResourceName.trim().lowercase()
            val combined = "$t $d $v"
            if (EXCLUDED_SHARE_TERMS.any { combined.contains(it) }) continue

            val exactMatch = t in SHARE_EXACT_LABELS || d in SHARE_EXACT_LABELS
            val phraseMatch = SHARE_PHRASES.any { t.contains(it) || d.contains(it) }
            val idMatch = v.contains("share_screen") || v.contains("screen_share") || v.contains("live_screen_share")
            if (!exactMatch && !phraseMatch && !idMatch) continue

            var score = 0
            if (n.isClickable) score += 50
            if (exactMatch) score += 40
            if (phraseMatch) score += 30
            if (idMatch) score += 25
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }

    fun hasReplyStarted(
        nodes: List<UiNodeSnapshot>,
        brief: String,
        requireGeminiPackage: Boolean = true,
    ): Boolean {
        if (isAlreadyInLiveScreen(nodes, requireGeminiPackage)) return true
        val briefHead = brief.trim().take(24)
        var hasStopOrStreaming = false
        var hasResponseText = false
        var inputCleared = false
        var sawInput = false
        for (n in nodes) {
            if (!n.isVisibleToUser || !pkgAllowed(n, requireGeminiPackage)) continue
            if (n.isEditable || n.className.contains("EditText", ignoreCase = true)) {
                sawInput = true
                if (n.text.isBlank()) inputCleared = true
                continue
            }
            val t = n.text.trim()
            val d = n.contentDescription.trim()
            val low = "$t $d ${n.viewIdResourceName}".lowercase()
            if (REPLY_ACTIVE_MARKERS.any { low.contains(it) } ||
                (t.lowercase() == "stop" || d.lowercase() == "stop" || t == "停止" || d == "停止")
            ) {
                hasStopOrStreaming = true
            }
            if (t.length >= 6 &&
                (briefHead.isEmpty() || !t.contains(briefHead)) &&
                !t.equals("Gemini", ignoreCase = true) &&
                !t.equals("Ask Gemini", ignoreCase = true) &&
                !t.equals("Live", ignoreCase = true)
            ) {
                hasResponseText = true
            }
        }
        if (hasStopOrStreaming) return true
        return sawInput && inputCleared && hasResponseText
    }
}

/**
 * 纯 JVM 可测 4 连击状态机策略：
 * WAIT_GEMINI_WINDOW -> PASTE_AND_SEND_BRIEF -> WAIT_REPLY_START -> CLICK_LIVE_BUTTON -> DONE / ABORTED
 */
object GeminiAutoStepPolicy {
    const val MAX_TIMEOUT_MS = 12_000L
    const val MAX_ROUNDS = 16
    const val LAUNCH_GRACE_ROUNDS = 6
    const val MAX_SHARE_WAIT_ROUNDS = 4

    fun decide(input: GeminiStepInput): GeminiStepOutput {
        if (input.state == GeminiAutoState.DONE || input.state == GeminiAutoState.ABORTED) {
            return GeminiStepOutput(
                nextState = input.state,
                action = if (input.state == GeminiAutoState.DONE) {
                    GeminiAutoAction.Finish("already_done")
                } else {
                    GeminiAutoAction.Abort("already_aborted")
                },
                briefWritten = input.briefWritten,
                sendClicked = input.sendClicked,
                replyWaitRounds = input.replyWaitRounds,
                sawGeminiWindow = input.sawGeminiWindow,
                liveClicked = input.liveClicked,
                shareWaitRounds = input.shareWaitRounds,
            )
        }
        if (input.emergencyActive) {
            return abort(input, "emergency_active")
        }
        if (input.elapsedMs >= MAX_TIMEOUT_MS) {
            return if (input.state == GeminiAutoState.CLICK_SCREEN_SHARE && input.liveClicked) {
                GeminiStepOutput(
                    nextState = GeminiAutoState.DONE,
                    action = GeminiAutoAction.Finish("live_active_no_share_btn"),
                    briefWritten = input.briefWritten,
                    sendClicked = input.sendClicked,
                    replyWaitRounds = input.replyWaitRounds,
                    sawGeminiWindow = input.sawGeminiWindow,
                    liveClicked = true,
                    shareWaitRounds = input.shareWaitRounds,
                )
            } else {
                abort(input, "timeout")
            }
        }
        if (input.round >= MAX_ROUNDS) {
            return if (input.state == GeminiAutoState.CLICK_SCREEN_SHARE && input.liveClicked) {
                GeminiStepOutput(
                    nextState = GeminiAutoState.DONE,
                    action = GeminiAutoAction.Finish("live_active_no_share_btn"),
                    briefWritten = input.briefWritten,
                    sendClicked = input.sendClicked,
                    replyWaitRounds = input.replyWaitRounds,
                    sawGeminiWindow = input.sawGeminiWindow,
                    liveClicked = true,
                    shareWaitRounds = input.shareWaitRounds,
                )
            } else {
                abort(input, "max_rounds")
            }
        }

        val front = input.frontPackage?.trim().orEmpty()
        val isGemini = GeminiNodeMatcher.isGeminiPackage(front)
        if (input.sawGeminiWindow && front.isNotEmpty() && !isGemini) {
            return if (input.state == GeminiAutoState.CLICK_SCREEN_SHARE && input.liveClicked) {
                GeminiStepOutput(
                    nextState = GeminiAutoState.DONE,
                    action = GeminiAutoAction.Finish("live_active_window_left"),
                    briefWritten = input.briefWritten,
                    sendClicked = input.sendClicked,
                    replyWaitRounds = input.replyWaitRounds,
                    sawGeminiWindow = true,
                    liveClicked = true,
                    shareWaitRounds = input.shareWaitRounds,
                )
            } else {
                abort(input, "foreground_switched")
            }
        }

        return when (input.state) {
            GeminiAutoState.WAIT_GEMINI_WINDOW -> {
                if (!isGemini) {
                    val isTransitionPkg = front.isEmpty() ||
                        front == input.ownPackage ||
                        front in SystemSafeAllowlist.SYSTEM_UI_AND_LAUNCHERS ||
                        front in SystemSafeAllowlist.PROXY_PACKAGES
                    if (!isTransitionPkg && input.round >= 2) {
                        return abort(input, "foreground_switched")
                    }
                    if (input.round >= LAUNCH_GRACE_ROUNDS) {
                        return abort(input, "gemini_window_timeout")
                    }
                    return GeminiStepOutput(
                        nextState = GeminiAutoState.WAIT_GEMINI_WINDOW,
                        action = GeminiAutoAction.Wait,
                        sawGeminiWindow = false,
                    )
                }
                if (GeminiNodeMatcher.isAlreadyInLiveScreen(input.nodes)) {
                    return if (input.requestScreenShare) {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.CLICK_SCREEN_SHARE,
                            action = GeminiAutoAction.Wait,
                            sawGeminiWindow = true,
                            liveClicked = true,
                            shareWaitRounds = 0,
                        )
                    } else {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.DONE,
                            action = GeminiAutoAction.Finish("already_in_live"),
                            sawGeminiWindow = true,
                        )
                    }
                }
                val next = if (input.directLive || input.brief.isBlank()) {
                    GeminiAutoState.CLICK_LIVE_BUTTON
                } else {
                    GeminiAutoState.PASTE_AND_SEND_BRIEF
                }
                GeminiStepOutput(
                    nextState = next,
                    action = GeminiAutoAction.Wait,
                    sawGeminiWindow = true,
                )
            }

            GeminiAutoState.PASTE_AND_SEND_BRIEF -> {
                if (input.brief.isBlank()) {
                    return GeminiStepOutput(
                        nextState = GeminiAutoState.CLICK_LIVE_BUTTON,
                        action = GeminiAutoAction.Wait,
                        sawGeminiWindow = true,
                    )
                }
                if (!input.briefWritten) {
                    val inputNode = GeminiNodeMatcher.findInputNode(input.nodes)
                        ?: return if (input.round >= 5 && GeminiNodeMatcher.findLiveButton(input.nodes) != null) {
                            GeminiStepOutput(
                                nextState = GeminiAutoState.CLICK_LIVE_BUTTON,
                                action = GeminiAutoAction.Wait,
                                briefWritten = false,
                                sawGeminiWindow = true,
                            )
                        } else {
                            GeminiStepOutput(
                                nextState = GeminiAutoState.PASTE_AND_SEND_BRIEF,
                                action = GeminiAutoAction.Wait,
                                briefWritten = false,
                                sawGeminiWindow = true,
                            )
                        }
                    val head = input.brief.trim().take(24)
                    val alreadyHasText = head.isNotEmpty() && inputNode.text.contains(head)
                    if (!alreadyHasText) {
                        return GeminiStepOutput(
                            nextState = GeminiAutoState.PASTE_AND_SEND_BRIEF,
                            action = GeminiAutoAction.PasteBrief(inputNode, input.brief),
                            briefWritten = true,
                            sawGeminiWindow = true,
                        )
                    }
                    // 输入框里已有简报：直接尝试点发送
                    val sendBtn = GeminiNodeMatcher.findSendButton(input.nodes)
                    return if (sendBtn != null) {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.WAIT_REPLY_START,
                            action = GeminiAutoAction.ClickSend(sendBtn),
                            briefWritten = true,
                            sendClicked = true,
                            replyWaitRounds = 0,
                            sawGeminiWindow = true,
                        )
                    } else {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.PASTE_AND_SEND_BRIEF,
                            action = GeminiAutoAction.Wait,
                            briefWritten = true,
                            sawGeminiWindow = true,
                        )
                    }
                }
                val sendBtn = GeminiNodeMatcher.findSendButton(input.nodes)
                if (sendBtn != null) {
                    GeminiStepOutput(
                        nextState = GeminiAutoState.WAIT_REPLY_START,
                        action = GeminiAutoAction.ClickSend(sendBtn),
                        briefWritten = true,
                        sendClicked = true,
                        replyWaitRounds = 0,
                        sawGeminiWindow = true,
                    )
                } else {
                    GeminiStepOutput(
                        nextState = GeminiAutoState.PASTE_AND_SEND_BRIEF,
                        action = GeminiAutoAction.Wait,
                        briefWritten = true,
                        sawGeminiWindow = true,
                    )
                }
            }

            GeminiAutoState.WAIT_REPLY_START -> {
                if (GeminiNodeMatcher.isAlreadyInLiveScreen(input.nodes)) {
                    return if (input.requestScreenShare) {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.CLICK_SCREEN_SHARE,
                            action = GeminiAutoAction.Wait,
                            briefWritten = input.briefWritten,
                            sendClicked = true,
                            replyWaitRounds = input.replyWaitRounds,
                            sawGeminiWindow = true,
                            liveClicked = true,
                            shareWaitRounds = 0,
                        )
                    } else {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.DONE,
                            action = GeminiAutoAction.Finish("already_in_live"),
                            briefWritten = input.briefWritten,
                            sendClicked = true,
                            replyWaitRounds = input.replyWaitRounds,
                            sawGeminiWindow = true,
                        )
                    }
                }
                val nextWait = input.replyWaitRounds + 1
                val replyStarted = GeminiNodeMatcher.hasReplyStarted(input.nodes, input.brief)
                val liveBtn = GeminiNodeMatcher.findLiveButton(input.nodes)
                val inputEmpty = GeminiNodeMatcher.findInputNode(input.nodes)?.text.isNullOrBlank()
                if (replyStarted || (liveBtn != null && ((inputEmpty && nextWait >= 2) || nextWait >= 4))) {
                    GeminiStepOutput(
                        nextState = GeminiAutoState.CLICK_LIVE_BUTTON,
                        action = GeminiAutoAction.Wait,
                        briefWritten = input.briefWritten,
                        sendClicked = true,
                        replyWaitRounds = nextWait,
                        sawGeminiWindow = true,
                    )
                } else {
                    GeminiStepOutput(
                        nextState = GeminiAutoState.WAIT_REPLY_START,
                        action = GeminiAutoAction.Wait,
                        briefWritten = input.briefWritten,
                        sendClicked = true,
                        replyWaitRounds = nextWait,
                        sawGeminiWindow = true,
                    )
                }
            }

            GeminiAutoState.CLICK_LIVE_BUTTON -> {
                if (GeminiNodeMatcher.isAlreadyInLiveScreen(input.nodes)) {
                    return if (input.requestScreenShare) {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.CLICK_SCREEN_SHARE,
                            action = GeminiAutoAction.Wait,
                            briefWritten = input.briefWritten,
                            sendClicked = input.sendClicked,
                            replyWaitRounds = input.replyWaitRounds,
                            sawGeminiWindow = true,
                            liveClicked = true,
                            shareWaitRounds = 0,
                        )
                    } else {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.DONE,
                            action = GeminiAutoAction.Finish("already_in_live"),
                            briefWritten = input.briefWritten,
                            sendClicked = input.sendClicked,
                            replyWaitRounds = input.replyWaitRounds,
                            sawGeminiWindow = true,
                        )
                    }
                }
                val liveBtn = GeminiNodeMatcher.findLiveButton(input.nodes)
                if (liveBtn != null) {
                    GeminiStepOutput(
                        nextState = if (input.requestScreenShare) GeminiAutoState.CLICK_SCREEN_SHARE else GeminiAutoState.DONE,
                        action = GeminiAutoAction.ClickLive(liveBtn),
                        briefWritten = input.briefWritten,
                        sendClicked = input.sendClicked,
                        replyWaitRounds = input.replyWaitRounds,
                        sawGeminiWindow = true,
                        liveClicked = true,
                        shareWaitRounds = 0,
                    )
                } else {
                    GeminiStepOutput(
                        nextState = GeminiAutoState.CLICK_LIVE_BUTTON,
                        action = GeminiAutoAction.Wait,
                        briefWritten = input.briefWritten,
                        sendClicked = input.sendClicked,
                        replyWaitRounds = input.replyWaitRounds,
                        sawGeminiWindow = true,
                    )
                }
            }

            GeminiAutoState.CLICK_SCREEN_SHARE -> {
                val shareBtn = GeminiNodeMatcher.findScreenShareButton(input.nodes)
                if (shareBtn != null) {
                    GeminiStepOutput(
                        nextState = GeminiAutoState.DONE,
                        action = GeminiAutoAction.ClickScreenShare(shareBtn),
                        briefWritten = input.briefWritten,
                        sendClicked = input.sendClicked,
                        replyWaitRounds = input.replyWaitRounds,
                        sawGeminiWindow = true,
                        liveClicked = true,
                        shareWaitRounds = input.shareWaitRounds,
                    )
                } else {
                    val nextShareWait = input.shareWaitRounds + 1
                    if (nextShareWait >= MAX_SHARE_WAIT_ROUNDS) {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.DONE,
                            action = GeminiAutoAction.Finish("live_active_no_share_btn"),
                            briefWritten = input.briefWritten,
                            sendClicked = input.sendClicked,
                            replyWaitRounds = input.replyWaitRounds,
                            sawGeminiWindow = true,
                            liveClicked = true,
                            shareWaitRounds = nextShareWait,
                        )
                    } else {
                        GeminiStepOutput(
                            nextState = GeminiAutoState.CLICK_SCREEN_SHARE,
                            action = GeminiAutoAction.Wait,
                            briefWritten = input.briefWritten,
                            sendClicked = input.sendClicked,
                            replyWaitRounds = input.replyWaitRounds,
                            sawGeminiWindow = true,
                            liveClicked = true,
                            shareWaitRounds = nextShareWait,
                        )
                    }
                }
            }

            GeminiAutoState.DONE, GeminiAutoState.ABORTED -> abort(input, "unreachable")
        }
    }

    private fun abort(input: GeminiStepInput, reason: String): GeminiStepOutput = GeminiStepOutput(
        nextState = GeminiAutoState.ABORTED,
        action = GeminiAutoAction.Abort(reason),
        briefWritten = input.briefWritten,
        sendClicked = input.sendClicked,
        replyWaitRounds = input.replyWaitRounds,
        sawGeminiWindow = input.sawGeminiWindow,
        liveClicked = input.liveClicked,
        shareWaitRounds = input.shareWaitRounds,
    )
}

/**
 * 手机操控 4 连击自动灌料并点进官方 Gemini Pro Live：
 * 1. 等待 Gemini 前台窗口就绪；
 * 2. 查找输入框，ACTION_SET_TEXT / ACTION_PASTE 写入简报并点击「发送」；
 * 3. 等待 Gemini 开始回复；
 * 4. 识别并点击「Live / Gemini Live / 开启 Live」按钮。
 * 全程受 12 秒总超时、最多 16 轮、前台切走即停与紧急放行中止护栏保护，失败则保底剪贴板 + Toast。
 */
object GeminiUiAutomator {
    private const val TAG = "DshGeminiAutomator"
    private const val POLL_INTERVAL_MS = 550L
    private const val MAX_TRAVERSE_NODES = 320
    private const val MAX_TRAVERSE_DEPTH = 30

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var activeToken = 0
    private var activeRunnable: Runnable? = null

    fun cancel() {
        activeToken++
        activeRunnable?.let { main.removeCallbacks(it) }
        activeRunnable = null
    }

    fun run(
        app: Context,
        brief: String,
        requestScreenShare: Boolean = false,
        directLive: Boolean = false,
        onResult: ((ok: Boolean, reason: String) -> Unit)? = null,
    ) {
        val appCtx = app.applicationContext ?: app
        val startTask = Runnable {
            startOnMain(appCtx, brief, requestScreenShare, directLive, onResult)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startTask.run()
        } else {
            main.post(startTask)
        }
    }

    private fun startOnMain(
        app: Context,
        brief: String,
        requestScreenShare: Boolean,
        directLive: Boolean,
        onResult: ((ok: Boolean, reason: String) -> Unit)?,
    ) {
        cancel()
        val token = activeToken
        val startElapsed = SystemClock.elapsedRealtime()
        var state = GeminiAutoState.WAIT_GEMINI_WINDOW
        var round = 0
        var briefWritten = false
        var sendClicked = false
        var replyWaitRounds = 0
        var sawGeminiWindow = false
        var liveClicked = false
        var shareWaitRounds = 0

        fun finishRun(ok: Boolean, reason: String) {
            if (token != activeToken) return
            activeRunnable = null
            if (!ok) {
                copyFallbackClipboard(app, brief)
                showFallbackToast(app, brief, reason)
            } else {
                Log.i(TAG, "Gemini Live automation done: reason=$reason rounds=$round")
            }
            onResult?.invoke(ok, reason)
        }

        val stepRunnable = object : Runnable {
            override fun run() {
                if (token != activeToken) return
                val service = PhoneA11yService.instance
                if (service == null) {
                    finishRun(false, "a11y_disconnected")
                    return
                }
                val emergency = runCatching {
                    ProactiveRuntime.inEmergencyPass() || LockSafety.disarmed(app)
                }.getOrDefault(false)

                val captured = captureWindowNodes(service)
                try {
                    val elapsed = SystemClock.elapsedRealtime() - startElapsed
                    val out = GeminiAutoStepPolicy.decide(
                        GeminiStepInput(
                            state = state,
                            round = round,
                            elapsedMs = elapsed,
                            frontPackage = captured.frontPackage,
                            emergencyActive = emergency,
                            brief = brief,
                            briefWritten = briefWritten,
                            sendClicked = sendClicked,
                            replyWaitRounds = replyWaitRounds,
                            sawGeminiWindow = sawGeminiWindow,
                            nodes = captured.snapshots,
                            ownPackage = app.packageName,
                            requestScreenShare = requestScreenShare,
                            liveClicked = liveClicked,
                            shareWaitRounds = shareWaitRounds,
                            directLive = directLive,
                        ),
                    )
                    round++
                    var nextState = out.nextState
                    var nextBriefWritten = out.briefWritten
                    var nextSendClicked = out.sendClicked
                    val nextReplyWait = out.replyWaitRounds
                    val nextSawGemini = out.sawGeminiWindow
                    var nextLiveClicked = out.liveClicked
                    var nextShareWait = out.shareWaitRounds

                    when (val act = out.action) {
                        is GeminiAutoAction.Wait -> Unit
                        is GeminiAutoAction.PasteBrief -> {
                            val node = captured.nativeNodes[act.node.id]
                            val wrote = node != null && writeBriefToNode(app, node, act.node, act.text)
                            if (!wrote) {
                                // 写入未确认：允许下一轮重试
                                nextBriefWritten = false
                            }
                        }
                        is GeminiAutoAction.ClickSend -> {
                            val node = captured.nativeNodes[act.node.id]
                            val clicked = node != null &&
                                GeminiNodeMatcher.isGeminiPackage(captured.frontPackage) &&
                                clickNodeOrAncestor(node)
                            if (!clicked) {
                                nextState = GeminiAutoState.PASTE_AND_SEND_BRIEF
                                nextSendClicked = false
                            }
                        }
                        is GeminiAutoAction.ClickLive -> {
                            val node = captured.nativeNodes[act.node.id]
                            val clicked = node != null &&
                                GeminiNodeMatcher.isGeminiPackage(captured.frontPackage) &&
                                clickNodeOrAncestor(node)
                            if (clicked) {
                                if (out.nextState == GeminiAutoState.DONE) {
                                    finishRun(true, "clicked_live")
                                    return
                                }
                                nextLiveClicked = true
                                nextShareWait = 0
                            } else {
                                // 没点中则留在 CLICK_LIVE_BUTTON 继续尝试直到超时
                                nextState = GeminiAutoState.CLICK_LIVE_BUTTON
                                nextLiveClicked = false
                            }
                        }
                        is GeminiAutoAction.ClickScreenShare -> {
                            val node = captured.nativeNodes[act.node.id]
                            val clicked = node != null &&
                                GeminiNodeMatcher.isGeminiPackage(captured.frontPackage) &&
                                clickNodeOrAncestor(node)
                            if (clicked) {
                                val spoken = VoicePlan.brief("已打开屏幕共享提示，点一下允许，让它盯着你")
                                    ?: "已打开屏幕共享提示，点一下允许"
                                runCatching {
                                    VoiceOut.install(app)
                                    VoiceOut.speak(VoicePlan(spoken, VoicePlan.DEFAULT_RATE, VoicePlan.DEFAULT_PITCH)) {}
                                }
                                finishRun(true, "clicked_screen_share_prompt")
                                return
                            } else {
                                nextState = GeminiAutoState.CLICK_SCREEN_SHARE
                            }
                        }
                        is GeminiAutoAction.Finish -> {
                            finishRun(true, act.reason)
                            return
                        }
                        is GeminiAutoAction.Abort -> {
                            finishRun(false, act.reason)
                            return
                        }
                    }

                    state = nextState
                    briefWritten = nextBriefWritten
                    sendClicked = nextSendClicked
                    replyWaitRounds = nextReplyWait
                    sawGeminiWindow = nextSawGemini
                    liveClicked = nextLiveClicked
                    shareWaitRounds = nextShareWait

                    val nextDelay = when (out.action) {
                        is GeminiAutoAction.PasteBrief -> 350L
                        is GeminiAutoAction.ClickSend -> 650L
                        is GeminiAutoAction.ClickLive -> 850L
                        else -> if (state == GeminiAutoState.PASTE_AND_SEND_BRIEF && !briefWritten) 350L else POLL_INTERVAL_MS
                    }
                    main.postDelayed(this, nextDelay)
                } finally {
                    captured.recycle()
                }
            }
        }
        activeRunnable = stepRunnable
        main.postDelayed(stepRunnable, 400L)
    }

    private data class CapturedNodes(
        val frontPackage: String?,
        val snapshots: List<UiNodeSnapshot>,
        val nativeNodes: Map<Int, AccessibilityNodeInfo>,
        private val allNatives: List<AccessibilityNodeInfo>,
    ) {
        @Suppress("DEPRECATION")
        fun recycle() {
            if (Build.VERSION.SDK_INT < 33) {
                allNatives.forEach { runCatching { it.recycle() } }
            }
        }
    }

    private fun captureWindowNodes(service: AccessibilityService): CapturedNodes {
        val natives = ArrayList<AccessibilityNodeInfo>(64)
        val snapshots = ArrayList<UiNodeSnapshot>(64)
        val nativeMap = HashMap<Int, AccessibilityNodeInfo>(64)
        var frontPkg: String? = null
        var targetRoot: AccessibilityNodeInfo? = null

        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        try {
            val rect = Rect()
            for (w in windows.sortedByDescending { it.layer }) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                w.getBoundsInScreen(rect)
                if (rect.isEmpty) continue
                val root = w.root ?: if (w.isActive || w.isFocused) runCatching { service.rootInActiveWindow }.getOrNull() else null
                if (root == null) continue
                val pkg = root.packageName?.toString()
                if (frontPkg == null && !pkg.isNullOrBlank()) {
                    frontPkg = pkg
                }
                if (GeminiNodeMatcher.isGeminiPackage(pkg)) {
                    targetRoot = root
                    break
                } else {
                    releaseNode(root)
                    if (frontPkg != null) break
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) {
                windows.forEach { runCatching { it.recycle() } }
            }
        }

        if (targetRoot == null) {
            val active = runCatching { service.rootInActiveWindow }.getOrNull()
            if (active != null) {
                val pkg = active.packageName?.toString()
                if (frontPkg == null && !pkg.isNullOrBlank()) frontPkg = pkg
                if (GeminiNodeMatcher.isGeminiPackage(pkg)) {
                    targetRoot = active
                } else {
                    releaseNode(active)
                }
            }
        }

        val root = targetRoot ?: return CapturedNodes(frontPkg, emptyList(), emptyMap(), emptyList())
        traverse(root, 0, snapshots, nativeMap, natives)
        return CapturedNodes(frontPkg, snapshots, nativeMap, natives)
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        snapshots: MutableList<UiNodeSnapshot>,
        nativeMap: MutableMap<Int, AccessibilityNodeInfo>,
        allNatives: MutableList<AccessibilityNodeInfo>,
    ) {
        if (depth > MAX_TRAVERSE_DEPTH || snapshots.size >= MAX_TRAVERSE_NODES) {
            releaseNode(node)
            return
        }
        val id = allNatives.size
        allNatives.add(node)
        if (!node.isVisibleToUser) return

        val actions = node.actionList
        val visibleText = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) {
            ""
        } else {
            node.text?.toString().orEmpty()
        }
        snapshots.add(
            UiNodeSnapshot(
                id = id,
                packageName = node.packageName?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                text = visibleText,
                contentDescription = node.contentDescription?.toString().orEmpty(),
                hintText = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else "",
                viewIdResourceName = node.viewIdResourceName.orEmpty(),
                isEditable = node.isEditable,
                isClickable = node.isClickable,
                isEnabled = node.isEnabled,
                isVisibleToUser = node.isVisibleToUser,
                isFocused = node.isFocused,
                isPassword = node.isPassword,
                supportsSetText = actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
                supportsPaste = actions.any { it.id == AccessibilityNodeInfo.ACTION_PASTE },
            ),
        )
        nativeMap[id] = node

        val count = node.childCount
        for (i in 0 until count) {
            if (snapshots.size >= MAX_TRAVERSE_NODES) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            traverse(child, depth + 1, snapshots, nativeMap, allNatives)
        }
    }

    private fun writeBriefToNode(
        app: Context,
        node: AccessibilityNodeInfo,
        snap: UiNodeSnapshot,
        brief: String,
    ): Boolean {
        if (!GeminiNodeMatcher.isGeminiPackage(node.packageName?.toString())) return false
        copyFallbackClipboard(app, brief)
        if (!node.isFocused) {
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        }
        if (snap.supportsSetText) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, brief)
            }
            val setOk = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            if (setOk) return true
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (!GeminiNodeMatcher.isGeminiPackage(node.packageName?.toString())) return false
        if (node.isEnabled && node.isClickable) {
            if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                return true
            }
        }
        var parent: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth < 4) {
            val nextParent = if (depth + 1 < 4) runCatching { parent.parent }.getOrNull() else null
            val clicked = parent.isEnabled &&
                parent.isClickable &&
                GeminiNodeMatcher.isGeminiPackage(parent.packageName?.toString()) &&
                runCatching { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            releaseNode(parent)
            if (clicked) {
                nextParent?.let(::releaseNode)
                return true
            }
            parent = nextParent
            depth++
        }
        parent?.let(::releaseNode)
        return false
    }

    private fun copyFallbackClipboard(app: Context, brief: String) {
        if (brief.isBlank()) return
        runCatching {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("大肥鱼 · Gemini 共脑简报", brief))
        }
    }

    private fun showFallbackToast(app: Context, brief: String, reason: String) {
        val msg = when {
            reason == "emergency_active" -> "紧急放行中，已暂停自动操作 Gemini"
            reason == "foreground_switched" -> if (brief.isNotBlank()) "已切离 Gemini，停止自动点击（简报在剪贴板）" else "已切离 Gemini，停止自动点击"
            brief.isNotBlank() -> "简报已复制到剪贴板，请直接粘贴发送并点 Live"
            else -> "已打开 Gemini，请手动点击 Live 开启语音"
        }
        runCatching { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
    }

    @Suppress("DEPRECATION")
    private fun releaseNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) runCatching { node.recycle() }
    }
}
