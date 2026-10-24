package cn.companion.pet.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun reportIncludesStatusButNoSecrets() {
        val text = DiagnosticReport.render(DiagnosticInputs(
            versionName = "0.5.1", versionCode = 19, now = 1_000,
            accessibilityConnected = true, screenshotStatus = "OK", screenshotReason = "1280x720",
            visionConfigured = true, visionUploadEnabled = false, budgetRemaining = 7,
            foregroundPackage = "com.test", foregroundAllowed = true,
            shizukuStatus = "READY", capabilityStatus = "READY", capabilityReason = "version",
            autonomyEnabled = true, goalStatus = "OK", pendingCount = 2, lastAction = "READ_MEMORY SUCCESS",
        ))
        assertTrue(text.contains("0.5.1"))
        assertTrue(text.contains("com.test"))
        assertTrue(text.contains("READY"))
        assertTrue(text.contains("脱敏"))
        assertFalse(text.contains("data:image"))
        assertFalse(text.contains("sk-"))
    }
}
