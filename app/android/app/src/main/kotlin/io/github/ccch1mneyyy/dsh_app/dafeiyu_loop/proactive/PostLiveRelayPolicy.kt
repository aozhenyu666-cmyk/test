package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist

/**
 * Pure JVM-testable policy for Always-On Live Keeper & early-bailout protection.
 */
object LiveKeeperPolicy {
    const val STANDARD_PULL_COOLDOWN_MS = 5 * 60_000L
    const val URGENT_KEEPER_COOLDOWN_MS = 25_000L
    const val EARLY_BAILOUT_SEC = 45L

    private val BUILTIN_STUDY_OR_QUIZ_PACKAGES = setOf(
        "md.obsidian",
        "com.fenbi.android.solar",
        "com.huatu.handwriting",
        "com.huatu.online",
        "com.offcn.zgz",
        "com.ichi2.anki",
    )

    fun effectivePullCooldownMs(urgent: Boolean): Long =
        if (urgent) URGENT_KEEPER_COOLDOWN_MS else STANDARD_PULL_COOLDOWN_MS

    fun isScreenShareText(screenText: String?): Boolean {
        val raw = screenText?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return raw.contains("停止共享") ||
            raw.contains("正在共享屏幕") ||
            raw.contains("停止分享") ||
            raw.contains("正在分享屏幕") ||
            raw.contains("Stop sharing", ignoreCase = true) ||
            raw.contains("Screen sharing", ignoreCase = true) ||
            raw.contains("Sharing your screen", ignoreCase = true)
    }

    fun shouldTriggerEarlyBailoutGuard(
        alwaysOnLive: Boolean,
        strict: Boolean,
        durationSec: Long,
        inTextGrace: Boolean,
        emergencyActive: Boolean,
        inNightWindow: Boolean,
        interceptHeld: Boolean,
        planModalShowing: Boolean,
        frontPkg: String?,
        selfPkg: String,
        workOrQuizPackages: Set<String> = emptySet(),
    ): Boolean {
        if (!alwaysOnLive || !strict) return false
        if (durationSec <= 0L || durationSec >= EARLY_BAILOUT_SEC) return false
        if (inTextGrace || emergencyActive || inNightWindow || interceptHeld || planModalShowing) return false
        val pkg = frontPkg?.trim().orEmpty()
        if (pkg.isNotEmpty()) {
            if (pkg in SystemSafeAllowlist.EMERGENCY_PACKAGES) return false
            if (pkg in SystemSafeAllowlist.SYSTEM_UI_AND_LAUNCHERS && pkg != "com.bbk.launcher2" && !pkg.contains("launcher")) {
                return false
            }
            if (pkg == selfPkg) return false
            if (pkg in BUILTIN_STUDY_OR_QUIZ_PACKAGES) return false
            if (pkg in workOrQuizPackages) return false
        }
        return true
    }
}

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
