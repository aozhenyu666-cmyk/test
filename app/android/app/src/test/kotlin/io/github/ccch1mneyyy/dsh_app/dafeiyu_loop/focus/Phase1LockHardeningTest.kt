package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus

import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionFacts
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionPolicy
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1LockHardeningTest {
    @Test fun systemBypassesAreClassifiedButEssentialComponentsStaySafe() {
        assertTrue(SystemBypassClassifier.isBypassPackage("com.miui.browser"))
        assertTrue(SystemBypassClassifier.isBypassPackage("com.heytap.market"))
        assertTrue(SystemBypassClassifier.isBypassPackage("com.miui.video"))
        assertFalse(SystemBypassClassifier.isBypassPackage("com.android.permissioncontroller"))
        assertFalse(SystemBypassClassifier.isBypassPackage("com.android.camera"))
        assertFalse(SystemBypassClassifier.isBypassPackage("com.android.documentsui"))
    }

    @Test fun antiBounceTwoSwitchesTriggerZeroMsLockEvenWithCooldown() {
        val t0 = 1_800_000_000_000L
        val state = FocusState(
            active = true,
            task = "只看 Obsidian",
            taskPackage = "md.obsidian",
            allowed = setOf("md.obsidian"),
            startedAt = t0 - 60_000L,
            lastBlockPkg = "com.miui.browser",
            lastBlockAt = t0 - 10_000L, // Inside old 60s REBLOCK_MS!
        )
        // After 2 bounces within 30s and passed STRICT_REBLOCK_MS (3s), 0s grace locks immediately
        val d = FocusPolicy.evaluate(
            state = state,
            packageName = "com.miui.browser",
            now = t0,
            deviationSince = t0, // 0ms dwell!
            ownPackage = "io.github.ccch1mneyyy.dsh_app",
            imePackages = emptySet(),
            bounceCount = 2,
            strict = true,
        )
        assertTrue(d is FocusDecision.Block)
    }

    @Test fun sessionPolicyBlocksSystemBrowsersAndBouncingInStrictMode() {
        val strictFacts = SessionFacts(
            allow = setOf("md.obsidian"),
            whitelist = setOf("com.zhihu.android"),
            home = SessionPolicy.NEUTRAL,
            systemApp = false,
            systemBypass = true,
            strict = true,
            dwellMs = 0L,
            graceMs = 45_000L,
        )
        assertEquals(SessionVerdict.LOCK, SessionPolicy.judge("com.miui.browser", strictFacts))
        // Strict mode also ignores non-task user whitelist unless inside allow
        assertEquals(
            SessionVerdict.LOCK,
            SessionPolicy.judge("com.zhihu.android", strictFacts.copy(systemBypass = false, graceMs = 0L)),
        )
        // Safe system permission / gallery picker remains ALLOWED
        assertEquals(
            SessionVerdict.ALLOW,
            SessionPolicy.judge(
                "com.android.documentsui",
                strictFacts.copy(systemApp = true, systemBypass = false),
            ),
        )
        // Bounce >= 2 wipes 45s grace to 0ms LOCK
        assertEquals(
            SessionVerdict.LOCK,
            SessionPolicy.judge(
                "com.example.random",
                SessionFacts(
                    allow = setOf("md.obsidian"),
                    home = SessionPolicy.NEUTRAL,
                    dwellMs = 500L,
                    graceMs = 45_000L,
                    bounceCount = 2,
                ),
            ),
        )
    }
}
