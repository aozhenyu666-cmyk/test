package io.github.ccch1mneyyy.dsh_app.loop.core

/**
 * T8, App side of the companion proactive protocol (app/assets/engine/plugins/dsh-go-companion).
 *
 * The HTTP itself runs in Dart, which already holds the engine connection and its cookie; Dart
 * passes (status, parsed JSON body) through unchanged and this object decides what it means.
 * Keeping the meaning here keeps it in one place and testable on the JVM.
 *
 *   App                              plugin (engine)                       DSH agent
 *   RequestAgent ── POST /request ──▶ topic prompt as followup  ──────────▶ inbox
 *                ◀── 202 accepted ─┤
 *   poll ───────── GET /result?waitMs=20000 (long poll) ─────────────────▶ turn/start, user/message
 *                ◀── claimed ──────┤                                      assistant/message
 *                ◀── generated {text} | failed {code} ◀──────────────── turn/end
 *   CancelAgent ── POST /cancel ───▶ record cancelled; later text dropped (turn is not aborted)
 */
object AgentWire {
    const val PREFIX = "/dsh-go-companion/v1"
    const val POLL_WAIT_MS = 20_000L

    /** What one HTTP answer means. */
    sealed interface Decoded {
        /** Tell the coordinator; [terminal] means stop polling this requestId. */
        data class Reply(val reply: AgentReply, val terminal: Boolean) : Decoded
        /** Nothing learned; try the same call again after [afterMs] (still bounded by the event's deadline). */
        data class Retry(val afterMs: Long, val reason: String) : Decoded
        /**
         * The plugin said "not this time" (409 no-topic / agent-busy / in-conversation / desk-busy).
         * That is a decision, not a delivery failure: close the attempt quietly instead of feeding
         * the not-delivered streak and retrying within the minute.
         */
        data class Skip(val code: String) : Decoded
    }

    /** 409 codes that mean "he is talking / there is nothing to say now", not "it broke". */
    // skip-no-content：B1 的信息增量守卫判这一句是套话，重写过一次还不过——这一轮不发是决定。
    private val SKIP_CODES = setOf("no-topic", "agent-busy", "in-conversation", "desk-busy", "skip-no-content")

    /**
     * Body of POST /request. Replaying it with the same requestId is safe: the plugin never sends twice.
     * [phone] is what the phone knows right now (front app, minutes, today's totals; never screen
     * text); null means unknown, and the plugin then writes nothing about it.
     */
    fun requestBody(cmd: Command.RequestAgent, now: Long, phone: Map<String, Any?>? = null): Map<String, Any?> = linkedMapOf<String, Any?>(
        "v" to 1, "requestId" to cmd.requestId, "eventId" to cmd.eventId, "kind" to cmd.request.kind,
        "payload" to cmd.request.payload, "deadlineMs" to (cmd.deadline - now).coerceIn(5_000, 120_000),
    ).also { if (phone != null) it["phone"] = phone }

    fun resultPath(requestId: String, waitMs: Long = POLL_WAIT_MS) = "$PREFIX/result?requestId=$requestId&waitMs=$waitMs"

    fun cancelBody(requestId: String): Map<String, Any?> = linkedMapOf("requestId" to requestId)

    /**
     * [status] 0 means the request never got an HTTP answer (connection refused, reset, timeout).
     * [body] is the parsed JSON (Map) or null.
     */
    fun decode(status: Int, body: Any?): Decoded {
        val m = body as? Map<*, *>
        val code = (m?.get("error") as? Map<*, *>)?.get("code") as? String
        return when {
            status == 0 -> Decoded.Retry(2_000, "transport")
            status == 401 || status == 403 -> Decoded.Reply(AgentReply.Failed("auth"), true)
            status == 404 && code == "not-found" -> Decoded.Reply(AgentReply.Failed("lost"), true)
            status == 404 -> Decoded.Reply(AgentReply.Failed("plugin_missing"), true)
            status == 429 || status == 503 || status >= 500 -> Decoded.Retry(3_000, "http_$status")
            status == 409 && code in SKIP_CODES -> Decoded.Skip(code!!)
            status == 409 -> Decoded.Reply(AgentReply.Failed(code ?: "conflict"), true)
            status in 400..499 -> Decoded.Reply(AgentReply.Failed("protocol_${code ?: status}"), true)
            status == 200 || status == 202 -> state(m)
            else -> Decoded.Retry(3_000, "http_$status")
        }
    }

    private fun state(m: Map<*, *>?): Decoded {
        if (m?.get("v") != 1L && m?.get("v") != 1) return Decoded.Reply(AgentReply.Failed("protocol_version"), true)
        return when (m["state"]) {
            "accepted" -> Decoded.Reply(AgentReply.Accepted, false)
            "claimed" -> Decoded.Reply(AgentReply.Claimed((m["turn"] as? Number)?.toLong()), false)
            "generated" -> {
                val text = (m["text"] as? String)?.trim().orEmpty()
                if (text.isEmpty()) Decoded.Reply(AgentReply.Failed("no_text"), true)
                else Decoded.Reply(
                    AgentReply.Generated(
                        text.take(2_000),
                        (m["turn"] as? Number)?.let { "turn:$it" },
                        // B4：连发计划随 result body 一起下来，这里只解析，投递由手机侧排队。
                        BurstPlan.of(m["extra"], text.take(2_000)),
                    ),
                    true,
                )
            }
            "failed" -> Decoded.Reply(AgentReply.Failed(((m["failure"] as? Map<*, *>)?.get("code") as? String) ?: "failed"), true)
            "cancelled" -> Decoded.Reply(AgentReply.Failed("cancelled"), true)
            else -> Decoded.Reply(AgentReply.Failed("protocol_state"), true)
        }
    }
}
