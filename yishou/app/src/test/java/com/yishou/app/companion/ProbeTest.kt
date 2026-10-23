package com.yishou.app.companion

import com.yishou.app.companion.core.Capability
import com.yishou.app.companion.core.ProbeReport
import com.yishou.app.companion.core.ProbeResult
import com.yishou.app.companion.core.ProbeVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 探针结论的硬规矩：没证据不许写成可用。 */
class ProbeTest {

    @Test
    fun `没有证据的可用会降级成未验证`() {
        val r = ProbeResult.of(Capability.CAPTURE_AUDIO, ProbeVerdict.OK, "   ", at = 1L)
        assertEquals(ProbeVerdict.UNKNOWN, r.verdict)
        assertTrue(r.evidence.isNotBlank())
    }

    @Test
    fun `有证据的可用原样保留`() {
        val r = ProbeResult.of(Capability.CAPTURE_FRAME, ProbeVerdict.OK, "3 秒留下 2 帧", at = 1L)
        assertEquals(ProbeVerdict.OK, r.verdict)
        assertEquals("3 秒留下 2 帧", r.evidence)
    }

    @Test
    fun `静音不算不可用`() {
        val r = ProbeResult.of(Capability.CAPTURE_AUDIO, ProbeVerdict.SILENT, "读到 3 秒静音", at = 1L)
        assertEquals(ProbeVerdict.SILENT, r.verdict)
        assertEquals("没有有效声音", r.verdict.label)
    }

    @Test
    fun `同一项能力只留最新一条`() {
        val report = ProbeReport()
            .with(ProbeResult.of(Capability.CAPTURE_AUDIO, ProbeVerdict.FAILED, "第一次失败", 1L))
            .with(ProbeResult.of(Capability.CAPTURE_AUDIO, ProbeVerdict.OK, "第二次读到 2 秒", 2L))
        assertEquals(1, report.results.size)
        assertEquals(ProbeVerdict.OK, report.of(Capability.CAPTURE_AUDIO)?.verdict)
    }

    @Test
    fun `统计可用项与是否需要重试`() {
        val report = ProbeReport()
            .with(ProbeResult.of(Capability.CAPTURE_AUDIO, ProbeVerdict.OK, "读到 2 秒", 1L))
            .with(ProbeResult.of(Capability.CAPTURE_FRAME, ProbeVerdict.UNSUPPORTED, "没有投屏授权", 1L))
        assertEquals(1, report.usableCount())
        assertTrue(report.needsRetry())
        assertNull(report.of(Capability.PLAYBACK_CONTROL))
        assertFalse(ProbeReport().needsRetry())
    }
}
