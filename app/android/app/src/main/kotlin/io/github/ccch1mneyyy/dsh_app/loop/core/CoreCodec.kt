package io.github.ccch1mneyyy.dsh_app.loop.core

/** JSON maps for the kernel's records. Unknown or broken records read back as null and are skipped. */
internal object CoreCodec {
    fun request(r: EventRequest): Map<String, Any?> = linkedMapOf(
        "eventId" to r.eventId, "source" to r.source, "kind" to r.kind, "dedupeKey" to r.dedupeKey,
        "priority" to r.priority.name, "createdAt" to r.createdAt, "ttlMs" to r.ttlMs,
        "needsAgent" to r.needsAgent, "fallbackText" to r.fallbackText, "payload" to r.payload,
    )

    fun request(m: Map<*, *>): EventRequest? = runCatching {
        EventRequest(
            eventId = M.str(m, "eventId")!!, source = M.str(m, "source")!!, kind = M.str(m, "kind")!!,
            dedupeKey = M.str(m, "dedupeKey")!!, priority = M.enum<Priority>(m, "priority") ?: Priority.NORMAL,
            createdAt = M.long(m, "createdAt"), ttlMs = M.long(m, "ttlMs"), needsAgent = M.bool(m, "needsAgent"),
            fallbackText = M.str(m, "fallbackText"),
            payload = (M.map(m, "payload") ?: emptyMap<String, String>()).entries
                .mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap(),
        )
    }.getOrNull()

    fun event(e: ProactiveEvent): Map<String, Any?> = linkedMapOf(
        "request" to request(e.request), "stage" to e.stage.name, "outcome" to e.outcome?.name, "reason" to e.reason,
        "expiresAt" to e.expiresAt, "priority" to e.priority.name, "mergedCount" to e.mergedCount, "mergedInto" to e.mergedInto,
        "yields" to e.yields, "agentRequestId" to e.agentRequestId, "agentAttempts" to e.agentAttempts,
        "agentDeadline" to e.agentDeadline, "agentRequestedAt" to e.agentRequestedAt, "agentAcceptedAt" to e.agentAcceptedAt, "agentClaimedAt" to e.agentClaimedAt,
        "generatedAt" to e.generatedAt, "agentError" to e.agentError, "lateResults" to e.lateResults,
        "content" to e.content?.let { linkedMapOf("text" to it.text, "source" to it.source.name, "agentTurnId" to it.agentTurnId) },
        "surface" to e.surface?.name, "token" to e.token, "presentDeadline" to e.presentDeadline,
        "presentedAt" to e.presentedAt, "deliveredAt" to e.deliveredAt, "shownAt" to e.shownAt,
        "interactionDeadline" to e.interactionDeadline, "acknowledgedAt" to e.acknowledgedAt,
        "replyDeadline" to e.replyDeadline, "respondedAt" to e.respondedAt, "answer" to e.answer,
        "closedAt" to e.closedAt, "avoidActivity" to e.avoidActivity,
    )

    fun event(m: Map<*, *>): ProactiveEvent? = runCatching {
        val req = request(M.map(m, "request")!!)!!
        val c = M.map(m, "content")
        ProactiveEvent(
            request = req, stage = M.enum<Stage>(m, "stage")!!, outcome = M.enum<Outcome>(m, "outcome"),
            reason = M.str(m, "reason"), expiresAt = M.long(m, "expiresAt"),
            priority = M.enum<Priority>(m, "priority") ?: req.priority, mergedCount = M.int(m, "mergedCount"),
            mergedInto = M.str(m, "mergedInto"), yields = M.int(m, "yields"), agentRequestId = M.str(m, "agentRequestId"),
            agentAttempts = M.int(m, "agentAttempts"), agentDeadline = M.long(m, "agentDeadline"),
            agentRequestedAt = M.longOrNull(m, "agentRequestedAt"), agentAcceptedAt = M.longOrNull(m, "agentAcceptedAt"),
            agentClaimedAt = M.longOrNull(m, "agentClaimedAt"),
            generatedAt = M.longOrNull(m, "generatedAt"), agentError = M.str(m, "agentError"), lateResults = M.int(m, "lateResults"),
            content = c?.let { Content(M.str(it, "text")!!, M.enum<ContentSource>(it, "source")!!, M.str(it, "agentTurnId")) },
            surface = M.enum<Surface>(m, "surface"), token = M.str(m, "token"), presentDeadline = M.long(m, "presentDeadline"),
            presentedAt = M.longOrNull(m, "presentedAt"), deliveredAt = M.longOrNull(m, "deliveredAt"),
            shownAt = M.longOrNull(m, "shownAt"), interactionDeadline = M.long(m, "interactionDeadline"),
            acknowledgedAt = M.longOrNull(m, "acknowledgedAt"), replyDeadline = M.long(m, "replyDeadline"),
            respondedAt = M.longOrNull(m, "respondedAt"), answer = M.str(m, "answer"), closedAt = M.longOrNull(m, "closedAt"),
            avoidActivity = M.bool(m, "avoidActivity"),
        )
    }.getOrNull()
}

/** Receipts as plain maps for the settings page and diagnostics. */
object CoreCodecPublic {
    fun event(e: ProactiveEvent): Map<String, Any?> = CoreCodec.event(e) + mapOf("engagement" to e.engagement.name)
}
