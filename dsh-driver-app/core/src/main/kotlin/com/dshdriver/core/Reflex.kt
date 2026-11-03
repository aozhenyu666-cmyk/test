package com.dshdriver.core

/**
 * dsh-driver-core 的手机端"反射"：锁定期间，一次前台读数该怎么即时反应。
 *
 * 纯逻辑，不依赖 Android，也不等插件回答 —— 切屏拦截必须在 350ms 去抖之后立刻发生，
 * 不能卡在 proot 里的引擎或模型上。插件随后收到同一条 foreground 信号，再决定升级到第几级。
 */
object DriverReflex {

    sealed interface Verdict {
        /** 读不到、或者没在锁定：什么都不做，也不上报。 */
        data object Ignore : Verdict

        /** 在允许的应用里（Live 本身、DSH Go、输入法、来电等）。 */
        data class Allow(val packageName: String, val anchor: Boolean) : Verdict

        /** 切出去了：立刻把断点卡盖上来。 */
        data class Escape(val packageName: String) : Verdict
    }

    /**
     * 永远放行：系统界面、来电与紧急呼叫。**桌面不在这里** —— 回桌面就是切走。
     * 设置也不在这里：从设置里关掉无障碍是最常见的逃逸路径；真有急事走卡片上的「紧急结束」。
     */
    val ALWAYS_ALLOWED: Set<String> = setOf(
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "android",
    )

    /**
     * @param className 这次窗口事件的类名（`AccessibilityEvent.className`）。给了且不像一个 Activity 窗口
     *   （弹窗、Toast、输入法、`android.widget.*` 等）就不做判断——这些短暂窗口不代表你切走了。
     *   不给（null）时只看包名，行为和以前一样。
     */
    fun judge(
        packageName: String?,
        locked: Boolean,
        anchors: Set<String>,
        selfPackage: String,
        inputMethods: Set<String>,
        className: String? = null,
    ): Verdict {
        if (!locked || packageName.isNullOrBlank()) return Verdict.Ignore
        if (className != null && !isActivityWindowClass(className)) return Verdict.Ignore
        if (packageName in anchors) return Verdict.Allow(packageName, anchor = true)
        if (packageName == selfPackage || packageName in inputMethods || packageName in ALWAYS_ALLOWED) {
            return Verdict.Allow(packageName, anchor = false)
        }
        return Verdict.Escape(packageName)
    }

    /**
     * 这个窗口类名像不像一个真正的 Activity。规则思路来自 closepaw 的 OverlayLocationPolicy（Apache-2.0），
     * 按本项目重写：系统控件包、androidx、输入法一律不算；没有包名的类名也不算。
     */
    fun isActivityWindowClass(className: String?): Boolean {
        val name = className?.substringBefore('$')?.takeIf { it.isNotBlank() } ?: return false
        if (NON_ACTIVITY_PREFIXES.any { name.startsWith(it) }) return false
        if (name.contains("inputmethod", ignoreCase = true)) return false
        return name.contains('.')
    }

    private val NON_ACTIVITY_PREFIXES = listOf(
        "android.widget.", "android.view.", "android.app.", "android.inputmethodservice.", "androidx.",
    )
}

/**
 * 从系统音频状态推断 Live 是否还开着、Gemini 是否在说话。只看"有没有在录 / 在播"，不碰声音内容。
 *
 *  - Live 开着时麦克风一直被占用；录音连续消失 [liveGoneMs] 视为 Live 被关掉。
 *  - 我们自己不录音，所以"有人在录"≈ Live 在听。
 *  - 锁定期间只允许 Live 类应用在前台，所以"有助手/语音类播放"≈ Gemini 在说话（它只在你说完后才说）。
 *    我们自己播 TTS 时调用方要先把 [selfSpeaking] 置真，避免把自己当成 Gemini。
 *
 * 这些推断都还没有真机证据（见 docs/02 的"待真机核实"），所以插件把 liveActive=null 当"不知道"，不当逃逸。
 */
class LiveInference(private val liveGoneMs: Long = 8_000L, private val minSpeechMs: Long = 1_500L) {
    sealed interface Change {
        data class Live(val active: Boolean) : Change
        data object AssistantStartedSpeaking : Change
        /** 说完一段（说了至少 minSpeechMs）。插件据此开"轮到你"窗口。 */
        data object AssistantStoppedSpeaking : Change
    }

    var selfSpeaking: Boolean = false
    private var recording = false
    private var lastRecordingAt = 0L
    private var playing = false
    private var playingSince = 0L
    private var reportedLive: Boolean? = null

    fun onAudio(recordingNow: Boolean, assistantPlayingNow: Boolean, now: Long): List<Change> {
        val out = mutableListOf<Change>()
        if (recordingNow) lastRecordingAt = now
        recording = recordingNow
        val speaking = assistantPlayingNow && !selfSpeaking
        if (speaking && !playing) {
            out += Change.AssistantStartedSpeaking
            playingSince = now
        }
        if (!speaking && playing && now - playingSince >= minSpeechMs) out += Change.AssistantStoppedSpeaking
        playing = speaking
        out += poll(now)
        return out
    }

    /** 定时调用：录音消失够久才判 Live 结束，避免一次短暂的音频焦点切换被当成退出。 */
    fun poll(now: Long): List<Change> {
        val live = when {
            recording -> true
            lastRecordingAt == 0L -> null
            now - lastRecordingAt >= liveGoneMs -> false
            else -> reportedLive
        }
        if (live == null || live == reportedLive) return emptyList()
        reportedLive = live
        return listOf(Change.Live(live))
    }
}
