package cn.companion.pet.core.focus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 侧集成测试：只验证可在设备上跑的持久化与恢复语义，不抢前台、不启动 App。
 * 真实前台/覆盖层/拉回仍需真机手动验收。
 */
@RunWith(AndroidJUnit4::class)
class FocusGuardInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun contract() = FocusContract(
        sessionId = "inst", targetPackage = "com.gemini", targetLabel = "Gemini",
        startedAt = 0, expiresAt = 10_000_000, mode = FocusMode.TEXT,
        enforcement = FocusEnforcement.STRICT, graceMillis = 1_000,
        allowedGraceMillis = 2_000, maxReturnAttempts = 2,
    )

    @Test
    fun snapshotSurvivesStoreRoundTrip() {
        val store = FocusStore(context.filesDir)
        val snap = FocusSnapshot(contract = contract(), state = FocusState.IN_TARGET,
            lastConfirmedForeground = "com.gemini", updatedAt = 1_000)
        assertTrue(store.save(snap))
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(FocusState.IN_TARGET, loaded!!.state)
        assertEquals("com.gemini", loaded.contract.targetPackage)
        store.clear()
    }

    @Test
    fun completedSessionIsTerminalAfterReload() {
        val store = FocusStore(context.filesDir)
        store.save(FocusSnapshot(contract = contract(), state = FocusState.COMPLETED, updatedAt = 2_000))
        val loaded = store.load()!!
        assertTrue(loaded.state in FocusGuard.TERMINAL)
        assertEquals(FocusDecision.None, FocusGuard(loaded).onLeave(3_000, "com.other"))
        store.clear()
    }
}