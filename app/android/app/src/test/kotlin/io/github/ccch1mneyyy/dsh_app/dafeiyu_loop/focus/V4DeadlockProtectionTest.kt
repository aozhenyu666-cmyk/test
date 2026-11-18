package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus

import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionFacts
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionPolicy
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V4DeadlockProtectionTest {
    @Test
    fun `bbk launcher and dropdown windows never trigger focus block even in strict bounce`() {
        val now = 1_000_000L
        val state = FocusState(
            active = true,
            task = "考公行测",
            taskPackage = "md.obsidian",
            allowed = setOf("md.obsidian"),
            startedAt = now - 60_000L,
        )
        val safePkgs = listOf(
            "com.bbk.launcher2",
            "com.vivo.launcher",
            "com.vivo.smartmultiwindow",
            "com.android.systemui",
            "com.android.fileexplorer",
            "com.coloros.filemanager",
            "com.huawei.hidisk",
            "io.github.ccch1mneyyy.dsh_app",
            "io.github.ccch1mneyyy.dsh_app.fish",
        )
        for (pkg in safePkgs) {
            val dec = FocusPolicy.evaluate(
                state = state,
                packageName = pkg,
                now = now,
                deviationSince = now - 30_000L,
                ownPackage = "io.github.ccch1mneyyy.dsh_app",
                imePackages = emptySet(),
                bounceCount = 5,
                strict = true,
            )
            assertEquals(FocusDecision.None, dec, "safe package $pkg must not trigger FocusBlock")
        }
    }

    @Test
    fun `bbk launcher and file managers always allow in strict session policy`() {
        val safePkgs = listOf(
            "com.bbk.launcher2",
            "com.vivo.smartmultiwindow",
            "com.android.fileexplorer",
            "com.oplus.filemanager",
            "io.github.ccch1mneyyy.dsh_app.fish",
        )
        for (pkg in safePkgs) {
            val v = SessionPolicy.judge(
                pkg,
                SessionFacts(
                    allow = setOf("com.google.android.apps.bard"),
                    self = setOf("io.github.ccch1mneyyy.dsh_app", "io.github.ccch1mneyyy.dsh_app.fish"),
                    dwellMs = 120_000L,
                    graceMs = 0L,
                    strict = true,
                    bounceCount = 4,
                    systemBypass = SystemBypassClassifier.isBypassPackage(pkg),
                ),
            )
            assertEquals(SessionVerdict.ALLOW, v, "safe package $pkg must stay ALLOW in strict SessionPolicy")
        }
    }

    @Test
    fun `entertainment package locks immediately in strict session`() {
        val v = SessionPolicy.judge(
            "com.ss.android.ugc.aweme",
            SessionFacts(
                allow = setOf("md.obsidian"),
                entertainment = setOf("com.ss.android.ugc.aweme"),
                strict = true,
                bounceCount = 0,
            ),
        )
        assertTrue(v == SessionVerdict.LOCK)
    }
}
