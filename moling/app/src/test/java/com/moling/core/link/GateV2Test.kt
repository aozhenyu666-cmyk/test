package com.moling.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第十三批 G2/G3：v2 外壳不破坏 v1，能力协商/错误码可测试。 */
class GateV2Test {
    @Test fun v1VersionAndStatusStayUntouched() {
        assertEquals(1, ZK_LINK_VERSION)
        val c = ZkCommand.SetMain("写申论", 1_000, "交出一段", "计划")
        val m = ZkWire.toMap(c)
        assertEquals("set_main", m["cmd"])
        assertEquals("1", m["v"])
        assertTrue("v2 方法不塞进 v1 指令", m.none { it.key.startsWith("v2.") })
        assertEquals(c, ZkWire.fromMap(m))
    }

    @Test fun capabilitiesRoundTrip() {
        val caps = GateCapabilities(listOf(1, 2), training = true, capture = false, protocolRevision = 7)
        val payload = GateV2Wire.capabilitiesPayload(caps)
        assertEquals(caps, GateV2Wire.capabilities(payload))
        assertTrue(caps.supportsTraining())
        assertFalse(caps.copy(training = false).supportsTraining())
        assertFalse(GateCapabilities(listOf(1)).supportsTraining())
        assertNull(GateV2Wire.capabilities("not json"))
    }

    @Test fun requestShellHasOnlyStringsAndVersionTwo() {
        val req = GateV2Wire.request(GateV2Method.CHALLENGE_ISSUE, "req-1", 100, 200, "{\"policyId\":\"p\"}")
        assertEquals("2", req["version"])
        assertEquals("req-1", req["requestId"])
        assertEquals(GateV2Method.CHALLENGE_ISSUE, req["method"])
        assertEquals("100", req["createdAt"])
        assertEquals("200", req["expiresAt"])
        assertTrue(req.values.all { it is String })
    }

    @Test fun responseErrorCodes() {
        val denied = GateV2Wire.response("req-1", ok = false, code = "POLICY_DENIED")
        assertEquals("POLICY_DENIED", GateV2Wire.errorOf(denied))
        assertEquals(GateError.POLICY_DENIED, GateError.of("policy_denied"))
        assertEquals(GateError.UNKNOWN, GateError.of("SOMETHING_NEW"))
        assertFalse(GateError.retryable("POLICY_DENIED"))
        assertFalse("结果未知不能自动重试", GateError.retryable("UNKNOWN"))
        assertTrue(GateError.retryable("RATE_LIMITED"))
        assertEquals("", GateV2Wire.errorOf(GateV2Wire.response("req-1", ok = true, payloadJson = "{}")))
    }

    @Test fun newMethodsAreDistinctFromV1MethodNames() {
        setOf(GateV2Method.CAPABILITIES, GateV2Method.CHALLENGE_ISSUE, GateV2Method.CHALLENGE_SUBMIT, GateV2Method.RECEIPT).forEach {
            assertTrue(it.startsWith("v2."))
            assertFalse(it == ZK_STATUS || it == ZK_COMMAND || it == ZK_RESULT)
        }
    }
}
