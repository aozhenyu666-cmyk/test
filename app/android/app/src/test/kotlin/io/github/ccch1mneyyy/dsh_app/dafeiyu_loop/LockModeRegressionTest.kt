package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptConfig
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptGuard
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptLedgerBook
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptRules
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.MemorySnapshotStore
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionFacts
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionPolicy
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class LockModeRegressionTest {
    @Test
    fun trainingModeNeverEscalatesToStrictEvenWhenStrictLinked() {
        var now = 1_793_340_000_000L
        val store = MemorySnapshotStore()
        val guard = InterceptGuard(store, InterceptLedgerBook(store).also { it.recover() }, { now }) { "e-1" }
            .also { it.recover(now) }
        guard.withStrictLink { true }
        guard.setConfig(InterceptConfig(enabled = true, lockMode = InterceptRules.MODE_TRAINING), now)
        assertEquals(InterceptRules.MODE_TRAINING, guard.effectiveLockMode())

        guard.setConfig(InterceptConfig(enabled = true, lockMode = InterceptRules.MODE_LINKED), now)
        assertEquals(InterceptRules.MODE_STRICT, guard.effectiveLockMode())

        guard.withStrictLink { false }
        assertEquals(InterceptRules.MODE_TRAINING, guard.effectiveLockMode())
    }

    @Test
    fun geminiTrainingSessionAllowsGeminiAndBreakGlassBypassesLock() {
        val geminiPkg = "com.google.android.apps.bard"
        val facts = SessionFacts(
            allow = setOf(geminiPkg, "com.google.android.googlequicksearchbox"),
            self = setOf("io.github.ccch1mneyyy.dsh_app"),
            entertainment = setOf("tv.danmaku.bili"),
            graceMs = 45_000L,
            strict = false,
            breakGlass = false,
        )
        assertEquals(SessionVerdict.ALLOW, SessionPolicy.judge(geminiPkg, facts))
        assertEquals(SessionVerdict.LOCK, SessionPolicy.judge("tv.danmaku.bili", facts))
        assertEquals(SessionVerdict.ALLOW, SessionPolicy.judge("tv.danmaku.bili", facts.copy(breakGlass = true)))
    }
}
