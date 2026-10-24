package cn.companion.pet.core.device

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuCapabilityTest {
    private class Probe(private val status: ShizukuStatus) : ShizukuProbe {
        override fun diagnostic() = ShizukuDiagnostic(status, status.name)
        override fun requestPermission() = false
    }

    private class Port(private val result: CapabilityResult) : ShizukuCommandPort {
        var calls = 0
        override fun readSdkLevel(): CapabilityResult { calls++; return result }
    }

    @Test
    fun readyProbeUsesFixedReadOnlyCapability() {
        val port = Port(CapabilityResult(CapabilityStatus.READY, "36", elapsedMs = 5))
        val result = ShizukuCapabilityRunner(Probe(ShizukuStatus.READY), port).readSdkDiagnostic()
        assertEquals(CapabilityStatus.READY, result.status)
        assertEquals("36", result.value)
        assertEquals(1, port.calls)
    }

    @Test
    fun notReadyDoesNotCallPort() {
        val port = Port(CapabilityResult(CapabilityStatus.READY, "36"))
        val result = ShizukuCapabilityRunner(Probe(ShizukuStatus.NOT_RUNNING), port).readSdkDiagnostic()
        assertEquals(CapabilityStatus.UNAVAILABLE, result.status)
        assertEquals(0, port.calls)
    }

    @Test
    fun portFailureIsReported() {
        val result = ShizukuCapabilityRunner(Probe(ShizukuStatus.READY),
            Port(CapabilityResult(CapabilityStatus.FAILED, reason = "exit=1"))).readSdkDiagnostic()
        assertEquals(CapabilityStatus.FAILED, result.status)
        assertEquals("exit=1", result.reason)
    }
}
