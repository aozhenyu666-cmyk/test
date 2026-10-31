package com.zongkong.core

import kotlinx.serialization.Serializable

/** A separate, immutable whitelist for one timed AI conversation. */
@Serializable
data class AiFocusSession(
    val deadlineElapsed: Long,
    val bootCount: Int,
    val remainingAtSave: Long,
    val minutes: Int,
    val wechat: Boolean,
    val pickerComponents: Set<String> = emptySet(),
    val dialer: String = "",
) {
    fun remaining(elapsed: Long): Long = (deadlineElapsed - elapsed).coerceAtLeast(0)

    // Reboot does not consume time we cannot verify. Changing wall time never unlocks a session.
    fun restore(elapsed: Long, boot: Int): AiFocusSession =
        if (boot == bootCount) this else copy(deadlineElapsed = elapsed + remainingAtSave, bootCount = boot)
}

object AiFocus {
    const val GPT = "com.openai.chatgpt"
    const val GEMINI = "com.google.android.apps.bard"
    const val GOOGLE = "com.google.android.googlequicksearchbox"
    const val NEKOBOX = "io.nekohasekai.sagernet"
    const val WECHAT = "com.tencent.mm"

    // Gemini's shell may hand off to Google. Do not grant the Google search/feed package.
    val geminiActivities = setOf(
        "$GOOGLE/com.google.android.apps.search.assistant.surfaces.voice.robin.main.RobinMainActivity",
        "$GOOGLE/com.google.android.apps.search.assistant.surfaces.roti.activity.RotiActivity",
    )

    fun component(pkg: String, cls: String): String = "$pkg/" + if (cls.startsWith('.')) pkg + cls else cls

    fun isAi(pkg: String, cls: String): Boolean = pkg == GPT || pkg == GEMINI || component(pkg, cls) in geminiActivities

    fun allows(s: AiFocusSession, pkg: String, cls: String, own: String, ime: String, pickerGranted: Boolean): Boolean =
        pkg == own || isAi(pkg, cls) || pkg == NEKOBOX ||
            (s.wechat && pkg == WECHAT) ||
            pkg in com.zongkong.core.hq.ALWAYS_ALLOWED ||
            (s.dialer.isNotEmpty() && pkg == s.dialer) ||
            (ime.isNotEmpty() && pkg == ime) ||
            (pickerGranted && component(pkg, cls) in s.pickerComponents)
}

/** A picker grant starts only from AI, expires after five minutes, and ends on leaving the chain. */
class AiPickerGrant {
    private var fromAi = false
    private var until = 0L

    fun observe(s: AiFocusSession, pkg: String, cls: String, elapsed: Long, transient: Boolean): Boolean {
        if (transient) return false // Keyboard/permission overlays do not change the underlying activity.
        if (AiFocus.isAi(pkg, cls)) {
            fromAi = true
            until = 0
            return false
        }
        if (AiFocus.component(pkg, cls) in s.pickerComponents && fromAi) {
            if (until == 0L) until = elapsed + 5 * 60_000L
            if (elapsed < until) return true
        }
        fromAi = false
        until = 0
        return false
    }
}
