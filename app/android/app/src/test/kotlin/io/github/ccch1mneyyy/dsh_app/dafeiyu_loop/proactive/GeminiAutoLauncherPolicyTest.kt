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

    @Test
    fun geminiDeferSharesDailyDisarmCapAndGeneratesIdempotentIds() {
        val day = "2026-11-19"
        // 1st action: notification disarm uses slot 1/3
        val firstNotif = DisarmCapPolicy.planDisarm(
            day = day,
            storedDay = day,
            storedUsed = 0,
            storedSeq = 0,
            cap = DisarmCapPolicy.DEFAULT_DAILY_CAP,
            reason = "先处理电话",
            counted = true,
            source = "notification",
        )
        assertEquals("first disarm must succeed", true, firstNotif.allowed)
        assertEquals("first disarm count must be 1", 1, firstNotif.nextUsed)
        assertEquals("first disarmId must match format", "disarm-$day-1", firstNotif.disarmId)

        // 2nd & 3rd actions: Gemini defer shares the same 3/day cap
        val secondDefer = GeminiLaunchPolicy.planDefer10m(
            day = day,
            storedDay = day,
            storedUsed = firstNotif.nextUsed,
            storedSeq = firstNotif.nextSeq,
            cap = DisarmCapPolicy.DEFAULT_DAILY_CAP,
        )
        assertEquals("second defer must succeed", true, secondDefer.allowed)
        assertEquals("shared count must advance to 2", 2, secondDefer.nextUsed)
        assertEquals("second disarmId must match format", "disarm-$day-2", secondDefer.disarmId)

        val thirdDefer = GeminiLaunchPolicy.planDefer10m(
            day = day,
            storedDay = day,
            storedUsed = secondDefer.nextUsed,
            storedSeq = secondDefer.nextSeq,
            cap = DisarmCapPolicy.DEFAULT_DAILY_CAP,
        )
        assertEquals("third defer must succeed", true, thirdDefer.allowed)
        assertEquals("shared count must advance to 3", 3, thirdDefer.nextUsed)
        assertEquals("third disarmId must match format", "disarm-$day-3", thirdDefer.disarmId)

        // 4th attempt on same day is refused (no uncapped bypass!)
        val fourthDefer = GeminiLaunchPolicy.planDefer10m(
            day = day,
            storedDay = day,
            storedUsed = thirdDefer.nextUsed,
            storedSeq = thirdDefer.nextSeq,
            cap = DisarmCapPolicy.DEFAULT_DAILY_CAP,
        )
        assertEquals("fourth defer must be refused at shared cap=3", false, fourthDefer.allowed)
        assertEquals("refused count must stay at 3", 3, fourthDefer.nextUsed)

        // Strict tier (cap=2) blocks 3rd defer
        val strictBlocked = GeminiLaunchPolicy.planDefer10m(
            day = day,
            storedDay = day,
            storedUsed = 2,
            storedSeq = 2,
            cap = 2,
        )
        assertEquals("strict tier cap=2 must refuse 3rd defer", false, strictBlocked.allowed)

        // Next day resets counter & seq cleanly
        val nextDay = GeminiLaunchPolicy.planDefer10m(
            day = "2026-11-20",
            storedDay = day,
            storedUsed = 3,
            storedSeq = 3,
            cap = DisarmCapPolicy.DEFAULT_DAILY_CAP,
        )
        assertEquals("next day must reset cap and allow defer", true, nextDay.allowed)
        assertEquals("next day count must start at 1", 1, nextDay.nextUsed)
        assertEquals("next day disarmId must reset seq to 1", "disarm-2026-11-20-1", nextDay.disarmId)
    }
}
