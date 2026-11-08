package io.github.ccch1mneyyy.dsh_app.loop

import io.github.ccch1mneyyy.dsh_app.phone.a11y.ProtectedPages

// EventObserver, platform-free half: what a verified foreground package means for the loop.
// The Android half (LoopRuntime) debounces accessibility events and re-reads the real
// foreground; only that verified package reaches [ForegroundTracker].

/** What a foreground package is to the loop. */
enum class PackageClass {
    /** The test app the person picked. */
    TARGET,
    /** DSH itself (its chat, its interaction page). Never ends a visit, never triggers. */
    SELF,
    /** System UI, keyboards, permission / install / security pages. Never ends a visit. */
    NEUTRAL,
    /** Any other app: ends a visit. */
    OTHER,
}

class PackageFilter(
    private val selfPackage: String,
    /** Package names of the enabled input methods (read from the system by the caller). */
    private val inputMethods: () -> Set<String> = { emptySet() },
) {
    fun classify(pkg: String, target: String?): PackageClass = when {
        pkg == selfPackage -> PackageClass.SELF
        pkg == target -> PackageClass.TARGET
        isNeutral(pkg) -> PackageClass.NEUTRAL
        else -> PackageClass.OTHER
    }

    fun isNeutral(pkg: String): Boolean =
        pkg in NEUTRAL || pkg in ProtectedPages.packages || pkg in runCatching(inputMethods).getOrDefault(emptySet()) ||
            pkg.contains(".inputmethod") || pkg.endsWith(".ime")

    companion object {
        /** Shades, dialogs, call screens: things that sit on top of an app rather than replace it. */
        val NEUTRAL: Set<String> = setOf(
            "android", "com.android.systemui", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.android.incallui", "com.android.server.telecom",
            "com.android.internal.systemui.navbar.gestural", "com.miui.systemui", "com.coloros.systemui",
            "com.oplus.systemui", "com.vivo.systemui", "com.huawei.systemui", "com.hihonor.systemui",
            "com.samsung.android.systemui",
        )
    }
}

/**
 * Visit tracking: entering the target from a real other app opens a visit; only another real app
 * closes it. DSH's own page, keyboards, the shade and permission dialogs are transparent, so
 * returning from the interaction page (or a keyboard popping up) never looks like a new entry.
 */
class ForegroundTracker(private val filter: PackageFilter) {
    data class State(val insideTarget: Boolean = false, val visitId: String? = null, val target: String? = null) {
        fun toMap(): Map<String, Any?> = linkedMapOf("insideTarget" to insideTarget, "visitId" to visitId, "target" to target)

        companion object {
            fun fromMap(m: Map<*, *>?): State = if (m == null) State() else State(
                m["insideTarget"] == true, m["visitId"] as? String, m["target"] as? String,
            )
        }
    }

    sealed interface Step {
        /** A new visit to the target began: a trigger candidate. */
        data class Entered(val visitId: String) : Step
        /** Still inside the same visit. */
        data object Stayed : Step
        /** A real other app took over: the visit ended. */
        data object Exited : Step
        /** Changes nothing (unknown, self, neutral, or another app while already outside). */
        data class Ignored(val why: String) : Step
    }

    var state = State()
        private set

    fun restore(saved: State) { state = saved }

    /** [verified]: the foreground package read back from the window list, or null if unreadable. */
    fun onForeground(verified: String?, target: String?, newVisitId: () -> String): Step {
        if (state.target != target) state = State(target = target)
        if (target == null) return Step.Ignored("no_target")
        if (verified.isNullOrBlank()) return Step.Ignored("unknown")
        return when (filter.classify(verified, target)) {
            PackageClass.SELF -> Step.Ignored("self")
            PackageClass.NEUTRAL -> Step.Ignored("neutral")
            PackageClass.TARGET -> if (state.insideTarget) Step.Stayed else {
                val id = newVisitId()
                state = state.copy(insideTarget = true, visitId = id)
                Step.Entered(id)
            }
            PackageClass.OTHER -> if (!state.insideTarget) Step.Ignored("other") else {
                state = state.copy(insideTarget = false, visitId = null)
                Step.Exited
            }
        }
    }
}
