package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiAutoLauncherPolicyTest {
    private val homePackages = setOf("com.bbk.launcher2", "com.miui.home")

    @Test
    fun emergencyActiveDefersEvenWhenInstant() {
        val decision = GeminiLaunchPolicy.decide(
            frontPkg = "tv.danmaku.bili",
            emergencyActive = true,
            pullMode = "instant",
            homePackages = homePackages,
        )
        assertEquals(
            "active emergency pass must defer instant Gemini pull",
            LaunchDecision.Defer("safe_or_emergency"),
            decision,
        )
    }

    @Test
    fun emergencyPackagesAlwaysDefer() {
        val safeCritical = listOf(
            "com.android.incallui",
            "com.android.mms",
            "com.autonavi.minimap",
            "com.eg.android.AlipayGphone",
        )
        for (pkg in safeCritical) {
            val decision = GeminiLaunchPolicy.decide(
                frontPkg = pkg,
                emergencyActive = false,
                pullMode = "instant",
                homePackages = homePackages,
            )
            assertEquals(
                "critical safe package $pkg must never be interrupted",
                LaunchDecision.Defer("safe_or_emergency"),
                decision,
            )
        }
    }

    @Test
    fun instantPullModeLaunchesImmediatelyOnNormalOrHomeApp() {
        val fromEntertainment = GeminiLaunchPolicy.decide(
            frontPkg = "tv.danmaku.bili",
            emergencyActive = false,
            pullMode = "instant",
            homePackages = homePackages,
        )
        assertEquals(
            "instant pull on entertainment app must launch immediately",
            LaunchDecision.LaunchNow,
            fromEntertainment,
        )

        val fromHome = GeminiLaunchPolicy.decide(
            frontPkg = "com.bbk.launcher2",
            emergencyActive = false,
            pullMode = "instant",
            homePackages = homePackages,
        )
        assertEquals(
            "instant pull on home launcher must launch immediately",
            LaunchDecision.LaunchNow,
            fromHome,
        )
    }

    @Test
    fun defaultCountdownModeReturnsThreeSecondOverlay() {
        val explicit = GeminiLaunchPolicy.decide(
            frontPkg = "com.tencent.mm",
            emergencyActive = false,
            pullMode = "countdown_3s",
            homePackages = homePackages,
        )
        assertEquals(
            "countdown_3s mode must return 3-second overlay decision",
            LaunchDecision.CountdownOverlay(seconds = 3),
            explicit,
        )

        val unknownFallback = GeminiLaunchPolicy.decide(
            frontPkg = "com.bbk.launcher2",
            emergencyActive = false,
            pullMode = "",
            homePackages = homePackages,
        )
        assertEquals(
            "blank pullMode must default to 3-second countdown overlay",
            LaunchDecision.CountdownOverlay(seconds = 3),
            unknownFallback,
        )
    }

    @Test
    fun gentleModeDefersWithoutHijackingScreen() {
        val decision = GeminiLaunchPolicy.decide(
            frontPkg = "com.zhihu.android",
            emergencyActive = false,
            pullMode = "gentle",
            homePackages = homePackages,
        )
        assertEquals(
            "gentle mode must defer instead of forced activity launch",
            LaunchDecision.Defer("gentle_mode"),
            decision,
        )
    }

    @Test
    fun nullFrontPackageRespectsPullModeWhenNotEmergency() {
        val instant = GeminiLaunchPolicy.decide(
            frontPkg = null,
            emergencyActive = false,
            pullMode = "instant",
            homePackages = SystemSafeAllowlist.SYSTEM_UI_AND_LAUNCHERS,
        )
        assertEquals(
            "null foreground in instant mode should launch now",
            LaunchDecision.LaunchNow,
            instant,
        )

        val countdown = GeminiLaunchPolicy.decide(
            frontPkg = null,
            emergencyActive = false,
            pullMode = "countdown_3s",
            homePackages = SystemSafeAllowlist.SYSTEM_UI_AND_LAUNCHERS,
        )
        assertEquals(
            "null foreground in countdown mode should use 3s overlay",
            LaunchDecision.CountdownOverlay(seconds = 3),
            countdown,
        )
    }
}
