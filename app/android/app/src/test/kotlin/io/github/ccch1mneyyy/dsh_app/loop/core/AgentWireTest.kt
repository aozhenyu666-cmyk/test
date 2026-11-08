package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8 wire decoding. The bodies are the plugin's real answers as recorded by
 * runtime/proactive/dsh-go-proactive/test/e2e-live.mjs against DSH 0.2.1-rc.4.
 */
class AgentWireTest {
    private fun ok(state: String, extra: Map<String, Any?> = emptyMap()) = mapOf("v" to 1L, "state" to state) + extra
    private fun err(code: String) = mapOf("v" to 1L, "error" to mapOf("code" to code, "message" to code))
    private fun reply(d: AgentWire.Decoded) = (d as AgentWire.Decoded.Reply)

    @Test fun eachStateMeansOnlyWhatHappened() {
        assertEquals(AgentWire.Decoded.Reply(AgentReply.Accepted, false), AgentWire.decode(202, ok("accepted")))
        assertEquals(AgentWire.Decoded.Reply(AgentReply.Claimed(3), false), AgentWire.decode(200, ok("claimed", mapOf("turn" to 3L))))
        assertEquals(
            AgentWire.Decoded.Reply(AgentReply.Generated("你好", "turn:1"), true),
            AgentWire.decode(200, ok("generated", mapOf("text" to " 你好 ", "turn" to 1L))),
        )
        assertEquals(AgentReply.Failed("no_text"), reply(AgentWire.decode(200, ok("generated", mapOf("text" to "  ")))).reply)
        assertEquals(AgentReply.Failed("deadline"), reply(AgentWire.decode(200, ok("failed", mapOf("failure" to mapOf("code" to "deadline"))))).reply)
        assertEquals(AgentReply.Failed("cancelled"), reply(AgentWire.decode(200, ok("cancelled"))).reply)
        assertEquals(AgentReply.Failed("protocol_state"), reply(AgentWire.decode(200, ok("weird"))).reply)
        assertEquals(AgentReply.Failed("protocol_version"), reply(AgentWire.decode(200, mapOf("v" to 2L, "state" to "generated", "text" to "x"))).reply)
    }

    @Test fun refusalsAreTerminalAndNamed() {
        for (code in listOf("no-session", "no-agent", "event-in-flight", "archived", "followup-failed")) {
            assertEquals(AgentWire.Decoded.Reply(AgentReply.Failed(code), true), AgentWire.decode(409, err(code)))
        }
        assertEquals(AgentReply.Failed("auth"), reply(AgentWire.decode(401, null)).reply)
        assertEquals(AgentReply.Failed("auth"), reply(AgentWire.decode(403, null)).reply)
        assertEquals(AgentReply.Failed("lost"), reply(AgentWire.decode(404, err("not-found"))).reply)
        assertEquals(AgentReply.Failed("plugin_missing"), reply(AgentWire.decode(404, "Not Found")).reply)
        assertEquals(AgentReply.Failed("protocol_bad-request-id"), reply(AgentWire.decode(400, err("bad-request-id"))).reply)
    }

    /** P2: "not this time" is a decision, not a delivery failure — no streak, no quick retry. */
    @Test fun notThisTimeIsASkipNotAFailure() {
        for (code in listOf("no-topic", "agent-busy", "in-conversation", "desk-busy")) {
            assertEquals(AgentWire.Decoded.Skip(code), AgentWire.decode(409, err(code)))
        }
    }

    @Test fun transientProblemsRetryTheSameIdempotentCall() {
        for (s in listOf(0, 429, 500, 502, 503)) assertTrue("$s", AgentWire.decode(s, null) is AgentWire.Decoded.Retry)
    }

    @Test fun requestBodyCarriesFactsOnlyAndABoundedDeadline() {
        val req = EventRequest("evt-00000001", "SCHEDULER", "random_chat", "k", createdAt = 0, needsAgent = true, payload = mapOf("countToday" to "1"))
        val body = AgentWire.requestBody(Command.RequestAgent("evt-00000001", "req-00000001", 1_000_000, req), 990_000)
        assertEquals(10_000L, body["deadlineMs"])
        assertEquals(setOf("v", "requestId", "eventId", "kind", "payload", "deadlineMs"), body.keys)
        assertEquals(5_000L, AgentWire.requestBody(Command.RequestAgent("e", "r", 10, req), 9)["deadlineMs"])
    }

    @Test fun requestBodyCarriesThePhoneOnlyWhenKnown() {
        val req = EventRequest("evt-00000001", "SCHEDULER", "random_chat", "k", createdAt = 0, needsAgent = true)
        val cmd = Command.RequestAgent("evt-00000001", "req-00000001", 1_000_000, req)
        val phone = mapOf("app" to "tv.danmaku.bili", "minutes" to 23)
        assertEquals(phone, AgentWire.requestBody(cmd, 990_000, phone)["phone"])
        assertFalse("unknown stays out of the body", "phone" in AgentWire.requestBody(cmd, 990_000, null))
    }
}
