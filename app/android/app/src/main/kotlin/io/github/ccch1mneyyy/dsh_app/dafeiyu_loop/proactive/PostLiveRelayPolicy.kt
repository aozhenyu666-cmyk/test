package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist

/**
 * Pure JVM-testable policy for popping the 3-button Post-Live Hang-Up Relay card
 * right after a real Gemini Live voice session ends.
 */
object PostLiveRelayPolicy {
    const val MIN_LIVE_DURATION_SEC = 45L
    const val COOLDOWN_MS = 5 * 60_000L

    private val BUILTIN_STUDY_OR_QUIZ_PACKAGES = setOf(
        "md.obsidian",
        "com.fenbi.android.solar",
        "com.huatu.handwriting",
        "com.huatu.online",
        "com.offcn.zgz",
        "com.ichi2.anki",
    )

    fun shouldPrompt(
        durationSec: Long,
        nowMs: Long,
        lastPromptAtMs: Long,
        interceptHeld: Boolean,
        planModalShowing: Boolean,
        emergencyActive: Boolean,
        inNightWindow: Boolean,
        frontPkg: String?,
        selfPkg: String,
        workOrQuizPackages: Set<String> = emptySet(),
    ): Boolean {
        if (durationSec < MIN_LIVE_DURATION_SEC) return false
        if (interceptHeld || planModalShowing || emergencyActive || inNightWindow) return false
        if (lastPromptAtMs > 0L && nowMs - lastPromptAtMs < COOLDOWN_MS) return false
        val pkg = frontPkg?.trim().orEmpty()
        if (pkg.isNotEmpty()) {
            if (pkg in SystemSafeAllowlist.EMERGENCY_PACKAGES) return false
            if (pkg == selfPkg) return false
            if (pkg in BUILTIN_STUDY_OR_QUIZ_PACKAGES) return false
            if (pkg in workOrQuizPackages) return false
        }
        return true
    }

    fun formatDuration(durationSec: Long): String {
        val sec = durationSec.coerceAtLeast(0L)
        val m = sec / 60L
        val s = sec % 60L
        return when {
            m <= 0L -> "${s} 秒"
            s == 0L -> "${m} 分钟"
            else -> "${m} 分 ${s} 秒"
        }
    }
}
