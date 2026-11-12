package io.github.ccch1mneyyy.dsh_app.loop.core

data class CoordinatorConfig(
    /** Events waiting behind the active one. */
    val maxQueue: Int = 4,
    val agentTimeoutMs: Long = 45_000,
    /** A started page must report Posted/Shown within this, or it counts as not shown. */
    val presentAckMs: Long = 3_000,
    /** How long a confirmed page waits for an answer. */
    val interactionTimeoutMs: Long = 60_000,
    /**
     * After an explicit 「我看到了」, how long the person still has to answer before the message
     * counts as ignored. The kernel owns this policy; pages only report that they were acked.
     */
    val replyWindowMs: Long = ReplyWindow.DEFAULT_MS,
    /** How long a posted notification waits for an answer (it may never be seen). */
    val notificationTimeoutMs: Long = 10 * 60_000,
    /** An agent reply older than this is not shown any more. */
    val replyFreshMs: Long = 2 * 60_000,
    /** How often an event may give way to DSH and come back. */
    val maxYields: Int = 2,
    val receiptCap: Int = 200,
    val seenIdCap: Int = 512,
)

/**
 * T4: the one place that decides what proactive thing happens next.
 *
 * - One active event at a time; the rest wait in a small priority queue with merge / drop rules.
 * - It never shows anything while DSH is operating the phone, a DSH question is open, or a
 *   system permission page is in front; a page already up is withdrawn and the event waits.
 *   DSH's own PhoneTasks / PromptArbiter are only read (through [Gate]), never owned.
 * - Every agent request has its own requestId and every surface its own token; replies and
 *   answers carrying anything else are stale and change nothing.
 * - Every change is persisted before the call returns; [recover] rebuilds after process death.
 *
 * Pure and synchronous: the adapter calls in, executes the returned [Command]s, and reports back.
 */
class Coordinator(
    private val store: SnapshotStore,
    private val config: CoordinatorConfig = CoordinatorConfig(),
    private val clock: () -> Long,
    private val ids: () -> String,
) {
    private val lock = Any()
    private val queue = mutableListOf<ProactiveEvent>()
    private var active: ProactiveEvent? = null
    private val receipts = ArrayDeque<ProactiveEvent>()
    private val seen = LinkedHashSet<String>()
    /** Tokens taken off screen, so a page that shows up late can still be told to go away. */
    private val retired = LinkedHashMap<String, Pair<String, Surface>>()
    private var gate = Gate()
    private var lastNow = 0L

    /** Counters for diagnostics: things that were refused because they were stale. */
    var staleReports = 0
        private set
    var lateAgentReplies = 0
        private set

    @Volatile var storageError: String? = null
        private set
    @Volatile var recoveryIssue: String? = null
        private set

    // ---------------------------------------------------------------- public API

    /** Process start. Closes what died with the old process, resumes agent requests, clears leftovers. */
    fun recover(gateNow: Gate): Step = synchronized(lock) {
        gate = gateNow
        val now = clock()
        val out = Out()
        out.commands += Command.ClearLeftovers
        when (val r = store.read()) {
            SnapshotStore.Read.Empty -> Unit
            is SnapshotStore.Read.Unreadable -> recoveryIssue = "state_unreadable: ${r.error}"
            is SnapshotStore.Read.Loaded -> {
                M.list(r.map, "queue").mapNotNull { (it as? Map<*, *>)?.let(CoreCodec::event) }.filter { it.stage == Stage.QUEUED }.let(queue::addAll)
                M.list(r.map, "receipts").mapNotNull { (it as? Map<*, *>)?.let(CoreCodec::event) }.let(receipts::addAll)
                M.list(r.map, "seen").filterIsInstance<String>().let(seen::addAll)
                lastNow = M.long(r.map, "lastNow")
                active = (M.map(r.map, "active"))?.let(CoreCodec::event)
            }
        }
        active?.let { a ->
            when (a.stage) {
                Stage.AWAITING_AGENT ->
                    if (now < a.agentDeadline) out.commands += Command.ResumeAgent(a.eventId, a.agentRequestId!!, a.agentDeadline)
                    else agentFailed(a, "timeout_after_restart", now, out)
                Stage.PRESENTING, Stage.DELIVERED, Stage.SHOWN -> close(a, Outcome.ABANDONED, "process_restart", now, out)
                else -> Unit
            }
        }
        reconcile(now, out)
        out.step()
    }

    fun setGate(next: Gate): Step = synchronized(lock) {
        gate = next
        val out = Out()
        reconcile(clock(), out)
        out.step()
    }

    /** Periodic / alarm / event-driven check of deadlines. Cheap; no loop needed. */
    fun tick(): Step = synchronized(lock) {
        val out = Out()
        reconcile(clock(), out)
        out.step()
    }

    fun submit(req: EventRequest): Pair<Submit, Step> = synchronized(lock) {
        val now = clock()
        val out = Out()
        val result = submitLocked(req, now, out)
        reconcile(now, out)
        result to out.step()
    }

    /** A reply from the agent side, matched by requestId only. */
    fun onAgent(requestId: String, reply: AgentReply): Step = synchronized(lock) {
        val now = clock()
        val out = Out()
        val a = active
        if (a != null && a.stage == Stage.AWAITING_AGENT && a.agentRequestId == requestId) {
            when (reply) {
                AgentReply.Accepted -> active = a.copy(agentAcceptedAt = a.agentAcceptedAt ?: now)
                is AgentReply.Claimed -> active = a.copy(agentAcceptedAt = a.agentAcceptedAt ?: now, agentClaimedAt = a.agentClaimedAt ?: now)
                is AgentReply.Generated -> active = a.copy(
                    stage = Stage.READY, generatedAt = now,
                    content = Content(reply.text, ContentSource.AGENT, reply.turnId, reply.burst, reply.voice),
                )
                is AgentReply.Failed -> agentFailed(a, reply.code, now, out)
            }
            out.dirty = true
        } else {
            // A reply for an attempt that is no longer current: never shown, only counted.
            lateAgentReplies++
            val owner = findByRequestId(requestId)
            if (owner != null) {
                replaceAnywhere(owner.copy(lateResults = owner.lateResults + 1))
                out.dirty = true
                // The engine still thinks this request matters: tell it again to stop.
                if (reply == AgentReply.Accepted || reply is AgentReply.Claimed) out.commands += Command.CancelAgent(owner.eventId, requestId, "stale")
            }
        }
        reconcile(now, out)
        out.step()
    }

    /** A report from a surface, matched by token only. Old pages and old buttons are refused. */
    fun onSurface(token: String, report: SurfaceReport): Step = synchronized(lock) {
        val now = clock()
        val out = Out()
        val a = active
        val live = a != null && a.token == token && a.stage in SURFACE_STAGES
        if (!live) {
            staleReports++
            // A late page from a closed or replaced presentation must not stay on screen.
            val owner = retired[token]
            if (owner != null && (report == SurfaceReport.Posted || report == SurfaceReport.Shown)) {
                out.commands += Command.Withdraw(owner.first, token, owner.second, "stale")
            }
            return@synchronized out.step()
        }
        when (report) {
            SurfaceReport.Posted -> active = when (a.surface) {
                Surface.NOTIFICATION -> a.copy(
                    stage = Stage.DELIVERED, presentedAt = a.presentedAt ?: now, deliveredAt = now,
                    interactionDeadline = now + config.notificationTimeoutMs,
                )
                else -> a.copy(presentedAt = a.presentedAt ?: now)
            }
            SurfaceReport.Shown -> if (a.surface == Surface.ACTIVITY && a.stage == Stage.PRESENTING) {
                active = a.copy(
                    stage = Stage.SHOWN, presentedAt = a.presentedAt ?: now, deliveredAt = a.deliveredAt ?: now,
                    shownAt = now, interactionDeadline = now + config.interactionTimeoutMs,
                )
            }
            // 「我看到了」: attention is confirmed, an answer may still come. The reply window is the
            // kernel's own policy and is stamped here, once; a repeated report moves nothing.
            // Only a page can be acknowledged: a posted notification proves nothing about attention.
            SurfaceReport.Acked -> active = if (a.surface != Surface.ACTIVITY || a.acknowledgedAt != null) {
                a
            } else {
                val window = now + config.replyWindowMs
                a.copy(
                    stage = Stage.SHOWN, presentedAt = a.presentedAt ?: now, deliveredAt = a.deliveredAt ?: now,
                    shownAt = a.shownAt ?: now, acknowledgedAt = now, replyDeadline = window,
                    // The person asked for more time than the page's own deadline allows.
                    interactionDeadline = maxOf(a.interactionDeadline, window),
                )
            }
            is SurfaceReport.Answered -> close(
                a.copy(respondedAt = now, answer = report.choice.take(64), shownAt = a.shownAt ?: now, deliveredAt = a.deliveredAt ?: now),
                Outcome.RESPONDED, null, now, out,
            )
            // Swiping a notification away or leaving the page is an act: the person saw it.
            SurfaceReport.Dismissed -> close(
                a.copy(shownAt = a.shownAt ?: now, deliveredAt = a.deliveredAt ?: now), Outcome.DISMISSED, null, now, out,
            )
            is SurfaceReport.Failed -> surfaceFailed(a, report.code, now, out)
        }
        out.dirty = true
        reconcile(now, out)
        out.step()
    }

    /** The producer withdrew the event (e.g. the person left the app it was about). */
    fun cancel(eventId: String, reason: String): Step = synchronized(lock) {
        val now = clock()
        val out = Out()
        queue.firstOrNull { it.eventId == eventId }?.let { queue.remove(it); close(it, Outcome.CANCELLED, reason, now, out) }
        active?.takeIf { it.eventId == eventId }?.let { abortActive(it, Outcome.CANCELLED, reason, now, out) }
        reconcile(now, out)
        out.step()
    }

    /** The earliest time [tick] has something to do; null when nothing is waiting on the clock. */
    fun nextDeadline(): Long? = synchronized(lock) {
        val a = active
        val times = mutableListOf<Long>()
        queue.mapTo(times) { it.expiresAt }
        when (a?.stage) {
            Stage.AWAITING_AGENT -> times += a.agentDeadline
            Stage.READY -> { times += a.expiresAt; a.generatedAt?.let { times += it + config.replyFreshMs } }
            Stage.PRESENTING -> times += a.presentDeadline
            Stage.DELIVERED, Stage.SHOWN -> {
                times += a.interactionDeadline
                if (a.acknowledgedAt != null && a.replyDeadline > 0) times += a.replyDeadline
            }
            else -> Unit
        }
        times.filter { it > 0 }.minOrNull()
    }

    /**
     * One producer was switched off: cancel its queued and active events, leave the others alone.
     * Unlike [Gate.featureEnabled] (everything off), this keeps the other feature working.
     */
    fun cancelSource(source: String, reason: String): Step = synchronized(lock) {
        val now = clock()
        val out = Out()
        queue.filter { it.request.source == source }.forEach { queue.remove(it); close(it, Outcome.CANCELLED, reason, now, out) }
        active?.takeIf { it.request.source == source }?.let { abortActive(it, Outcome.CANCELLED, reason, now, out) }
        if (out.closed.isNotEmpty()) reconcile(now, out)
        out.step()
    }

    fun active(): ProactiveEvent? = synchronized(lock) { active }
    fun queued(): List<ProactiveEvent> = synchronized(lock) { queue.toList() }
    /** Closed events, newest first. */
    fun receipts(limit: Int = 50): List<ProactiveEvent> = synchronized(lock) { receipts.reversed().take(limit) }
    fun find(eventId: String): ProactiveEvent? = synchronized(lock) {
        active?.takeIf { it.eventId == eventId } ?: queue.firstOrNull { it.eventId == eventId } ?: receipts.lastOrNull { it.eventId == eventId }
    }

    // ---------------------------------------------------------------- internals

    private class Out {
        val commands = mutableListOf<Command>()
        val closed = mutableListOf<ProactiveEvent>()
        var dirty = false
        fun step() = Step(commands.toList(), closed.toList())
    }

    private fun submitLocked(req: EventRequest, now: Long, out: Out): Submit {
        if (req.eventId.isBlank() || req.eventId in seen || findAnywhere(req.eventId) != null) return Submit.Rejected("duplicate")
        remember(req.eventId)
        out.dirty = true
        val e = ProactiveEvent(req)
        if (!gate.featureEnabled) { close(e, Outcome.CANCELLED, "disabled", now, out); return Submit.Rejected("disabled") }
        if (now >= e.expiresAt) { close(e, Outcome.EXPIRED, "ttl", now, out); return Submit.Rejected("expired") }
        // Same intent already on its way: fold this one in, never show twice.
        active?.takeIf { it.request.dedupeKey == req.dedupeKey }?.let { a ->
            active = a.copy(mergedCount = a.mergedCount + 1)
            close(e, Outcome.MERGED, "into_active", now, out, mergedInto = a.eventId)
            return Submit.Merged(a.eventId)
        }
        queue.indexOfFirst { it.request.dedupeKey == req.dedupeKey }.takeIf { it >= 0 }?.let { i ->
            val q = queue[i]
            // The survivor keeps its id and place; it takes the newer facts and the stronger claim.
            queue[i] = q.copy(
                request = req.copy(eventId = q.eventId, createdAt = q.request.createdAt),
                priority = maxOf(q.priority, req.priority, compareBy { it.rank }),
                expiresAt = maxOf(q.expiresAt, e.expiresAt), mergedCount = q.mergedCount + 1,
            )
            close(e, Outcome.MERGED, "into_queued", now, out, mergedInto = q.eventId)
            return Submit.Merged(q.eventId)
        }
        if (queue.size >= config.maxQueue) {
            val weakest = queue.minWith(compareBy<ProactiveEvent> { it.priority.rank }.thenBy { it.request.createdAt })
            if (e.priority.rank <= weakest.priority.rank) { close(e, Outcome.DROPPED, "queue_full", now, out); return Submit.Rejected("queue_full") }
            queue.remove(weakest)
            close(weakest, Outcome.DROPPED, "displaced", now, out)
        }
        queue += e
        return Submit.Queued(e.eventId)
    }

    /** Brings everything in line with the clock and the gate. Called at the end of every call. */
    private fun reconcile(now: Long, out: Out) {
        rebaseIfClockWentBack(now, out)
        if (!gate.featureEnabled) {
            queue.toList().forEach { queue.remove(it); close(it, Outcome.CANCELLED, "disabled", now, out) }
            active?.let { abortActive(it, Outcome.CANCELLED, "disabled", now, out) }
        }
        queue.toList().filter { now >= it.expiresAt }.forEach { queue.remove(it); close(it, Outcome.EXPIRED, "ttl", now, out) }

        var guard = 0
        while (guard++ < 8) {
            val a = active
            if (a == null) {
                if (!activateNext(now, out)) break
                continue
            }
            if (!advance(a, now, out)) break
        }
        if (out.dirty || out.closed.isNotEmpty() || out.commands.isNotEmpty()) persist()
    }

    /** One step for the active event. True when something changed and another pass may help. */
    private fun advance(a: ProactiveEvent, now: Long, out: Out): Boolean {
        when (a.stage) {
            Stage.AWAITING_AGENT -> if (now >= a.agentDeadline) {
                out.commands += Command.CancelAgent(a.eventId, a.agentRequestId!!, "timeout")
                agentFailed(a, "timeout", now, out)
                return true
            }
            Stage.READY -> {
                val c = a.content
                val stale = c?.source == ContentSource.AGENT && a.generatedAt != null && now - a.generatedAt >= config.replyFreshMs
                when {
                    now >= a.expiresAt -> { close(a, Outcome.EXPIRED, "ttl", now, out); return true }
                    stale -> { close(a, Outcome.EXPIRED, "stale_reply", now, out); return true }
                    c == null -> { close(a, Outcome.FAILED, "no_content", now, out); return true }
                }
                val surface = gate.surface()?.let { if (it == Surface.ACTIVITY && a.avoidActivity) notificationOrNull() else it }
                    ?: return false
                val token = ids()
                active = a.copy(
                    stage = Stage.PRESENTING, surface = surface, token = token, presentDeadline = now + config.presentAckMs,
                )
                out.commands += Command.Present(a.eventId, token, surface, c, minOf(a.expiresAt, now + interactionFor(surface)))
                out.dirty = true
                return false
            }
            Stage.PRESENTING, Stage.DELIVERED, Stage.SHOWN -> {
                if (gate.dshNeedsScreen || !gate.featureEnabled) { yieldActive(a, now, out); return true }
                if (a.stage == Stage.PRESENTING && now >= a.presentDeadline) {
                    out.commands += Command.Withdraw(a.eventId, a.token!!, a.surface!!, "not_acknowledged")
                    surfaceFailed(a, "not_acknowledged", now, out, withdrawn = true)
                    return true
                }
                if (a.stage != Stage.PRESENTING && now >= a.interactionDeadline) {
                    out.commands += Command.Withdraw(a.eventId, a.token!!, a.surface!!, "timeout")
                    close(a, Outcome.TIMEOUT, null, now, out)
                    return true
                }
            }
            Stage.QUEUED, Stage.CLOSED -> { active = null; return true }
        }
        return false
    }

    private fun interactionFor(s: Surface) = if (s == Surface.NOTIFICATION) config.notificationTimeoutMs else config.interactionTimeoutMs

    private fun notificationOrNull(): Surface? = if (gate.notificationsAllowed) Surface.NOTIFICATION else null

    /** Takes the best queued event, but only when something could be shown now (no tokens spent otherwise). */
    private fun activateNext(now: Long, out: Out): Boolean {
        if (queue.isEmpty() || gate.surface() == null) return false
        val next = queue.sortedWith(compareByDescending<ProactiveEvent> { it.priority.rank }.thenBy { it.request.createdAt }).first()
        queue.remove(next)
        out.dirty = true
        active = if (next.request.needsAgent) {
            val requestId = ids()
            val deadline = minOf(next.expiresAt, now + config.agentTimeoutMs)
            out.commands += Command.RequestAgent(next.eventId, requestId, deadline, next.request)
            next.copy(
                stage = Stage.AWAITING_AGENT, agentRequestId = requestId, agentAttempts = next.agentAttempts + 1,
                agentDeadline = deadline, agentRequestedAt = now,
            )
        } else {
            next.copy(stage = Stage.READY, content = next.request.fallbackText?.let { Content(it, ContentSource.LOCAL) })
        }
        return true
    }

    private fun agentFailed(a: ProactiveEvent, code: String, now: Long, out: Out) {
        val fb = a.request.fallbackText
        if (fb != null) {
            active = a.copy(stage = Stage.READY, agentError = code, content = Content(fb, ContentSource.FALLBACK))
            out.dirty = true
        } else {
            close(a.copy(agentError = code), Outcome.FAILED, "agent_$code", now, out)
        }
    }

    /** A surface failed once: an activity falls back to a notification; otherwise the event is unavailable. */
    private fun surfaceFailed(a: ProactiveEvent, code: String, now: Long, out: Out, withdrawn: Boolean = false) {
        retire(a)
        if (!withdrawn && a.token != null && a.surface != null) out.commands += Command.Withdraw(a.eventId, a.token, a.surface, code)
        if (a.surface == Surface.ACTIVITY && !a.avoidActivity && gate.notificationsAllowed) {
            active = a.copy(stage = Stage.READY, token = null, surface = null, avoidActivity = true, reason = code)
            out.dirty = true
        } else {
            close(a, Outcome.UNAVAILABLE, code, now, out)
        }
    }

    /** DSH needs the screen: take the surface down; come back later unless it already yielded enough. */
    private fun yieldActive(a: ProactiveEvent, now: Long, out: Out) {
        retire(a)
        out.commands += Command.Withdraw(a.eventId, a.token!!, a.surface!!, gateReason())
        val y = a.yields + 1
        if (y > config.maxYields || now >= a.expiresAt) {
            close(a.copy(yields = y), Outcome.YIELDED, gateReason(), now, out)
        } else {
            active = a.copy(stage = Stage.READY, token = null, surface = null, yields = y, reason = "yielded:${gateReason()}")
            out.dirty = true
        }
    }

    private fun gateReason() = gate.holdReason() ?: "dsh"

    private fun abortActive(a: ProactiveEvent, outcome: Outcome, reason: String, now: Long, out: Out) {
        if (a.stage == Stage.AWAITING_AGENT) out.commands += Command.CancelAgent(a.eventId, a.agentRequestId!!, reason)
        if (a.stage in SURFACE_STAGES && a.token != null) out.commands += Command.Withdraw(a.eventId, a.token, a.surface!!, reason)
        close(a, outcome, reason, now, out)
    }

    private fun close(e: ProactiveEvent, outcome: Outcome, reason: String?, now: Long, out: Out, mergedInto: String? = null) {
        val c = e.copy(stage = Stage.CLOSED, outcome = outcome, reason = reason ?: e.reason?.takeIf { outcome != Outcome.RESPONDED },
            closedAt = now, mergedInto = mergedInto ?: e.mergedInto)
        if (active?.eventId == e.eventId) active = null
        retire(e)
        receipts.addLast(c)
        while (receipts.size > config.receiptCap) receipts.removeFirst()
        remember(e.eventId)
        out.closed += c
        out.dirty = true
    }

    /** The wall clock went back: keep every remaining duration instead of stretching it. */
    private fun rebaseIfClockWentBack(now: Long, out: Out) {
        if (lastNow != 0L && now < lastNow - CLOCK_SLACK_MS) {
            val d = now - lastNow
            fun ProactiveEvent.shift() = copy(
                expiresAt = expiresAt + d, agentDeadline = if (agentDeadline > 0) agentDeadline + d else 0,
                presentDeadline = if (presentDeadline > 0) presentDeadline + d else 0,
                interactionDeadline = if (interactionDeadline > 0) interactionDeadline + d else 0,
                replyDeadline = if (replyDeadline > 0) replyDeadline + d else 0,
                acknowledgedAt = acknowledgedAt?.plus(d),
                generatedAt = generatedAt?.plus(d),
            )
            for (i in queue.indices) queue[i] = queue[i].shift()
            active = active?.shift()
            out.dirty = true
        }
        if (now > lastNow || now < lastNow - CLOCK_SLACK_MS) lastNow = now
    }

    private fun remember(id: String) {
        seen.remove(id)
        seen.add(id)
        while (seen.size > config.seenIdCap) seen.remove(seen.first())
    }

    private fun findAnywhere(id: String) = active?.takeIf { it.eventId == id } ?: queue.firstOrNull { it.eventId == id }
    private fun findByRequestId(rid: String) = (listOfNotNull(active) + receipts.reversed()).firstOrNull { it.agentRequestId == rid }
    private fun retire(e: ProactiveEvent) {
        val t = e.token ?: return
        retired[t] = e.eventId to (e.surface ?: Surface.ACTIVITY)
        while (retired.size > 64) retired.remove(retired.keys.first())
    }

    private fun replaceAnywhere(e: ProactiveEvent) {
        if (active?.eventId == e.eventId) { active = e; return }
        val i = receipts.indexOfLast { it.eventId == e.eventId }
        if (i >= 0) receipts[i] = e
    }

    private fun persist() {
        storageError = try {
            store.write(linkedMapOf(
                "version" to 1, "lastNow" to lastNow, "active" to active?.let(CoreCodec::event),
                "queue" to queue.map(CoreCodec::event), "receipts" to receipts.map(CoreCodec::event), "seen" to seen.toList(),
            ))
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}".take(300)
        }
    }

    companion object {
        val SURFACE_STAGES = setOf(Stage.PRESENTING, Stage.DELIVERED, Stage.SHOWN)
        /** Small wall-clock corrections (NTP) are ignored. */
        const val CLOCK_SLACK_MS = 5_000L
    }
}
