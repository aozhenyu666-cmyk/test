package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRecorderTest {
    @Test
    fun onlyAWindowStateChangeIsRecordedAndOnlyWhileSwitchedOn() {
        assertTrue(ForegroundRecorder.shouldRecord(true, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(ForegroundRecorder.shouldRecord(false, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(ForegroundRecorder.shouldRecord(true, AccessibilityEvent.TYPE_VIEW_SCROLLED))
        assertFalse(ForegroundRecorder.shouldRecord(true, null))
    }

    @Test
    fun lineIsCompactJsonWithTheThreeFields() {
        assertEquals(
            "{\"at\":1762570000000,\"app\":\"com.tencent.mm\",\"activity\":\"com.tencent.mm.ui.LauncherUI\"}",
            ForegroundRecorder.line("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", 1762570000000),
        )
    }

    @Test
    fun aMissingActivityIsAnEmptyString() {
        assertEquals(
            "{\"at\":5,\"app\":\"com.android.systemui\",\"activity\":\"\"}",
            ForegroundRecorder.line("com.android.systemui", null, 5),
        )
    }

    @Test
    fun quotesAndControlCharactersAreEscaped() {
        assertEquals(
            "{\"at\":1,\"app\":\"a\\\"b\",\"activity\":\"c\\\\d\\n\\t\"}",
            ForegroundRecorder.line("a\"b", "c\\d\n\t", 1),
        )
    }

    @Test
    fun watchdogLineSaysWhatTheCheckSaw() {
        assertEquals(
            "{\"at\":9,\"listed\":true,\"ageMs\":120000,\"granted\":false,\"nowListed\":true,\"action\":\"check\"}",
            A11yWatchdogLog.line(true, 120_000, false, true, "check", 9),
        )
        assertEquals(
            "{\"at\":9,\"listed\":false,\"ageMs\":null,\"granted\":null,\"nowListed\":null,\"action\":\"ok\"}",
            A11yWatchdogLog.line(false, null, null, null, "ok", 9),
        )
    }
}
