package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The whole chain the round-2 task names, in pure logic:
 * random schedule → local rules → queue → DSH arbitration → agent (plugin answers as recorded
 * live) → interaction → answer or timeout → persisted receipt → next schedule.
 * The adapter's job is exactly what this test's `drive` does.
 */
class KernelChainTest {
    private var wall = 1_793_340_000_000L
    private var elapsed = 3_600_000L
    private val clocks get() = Clocks(wall, elapsed, 1)
    private var seq = 0
    private val coordStore = MemorySnapshotStore()
    private val schedStore = MemorySnapshotStore()
    private fun minuteOfDay(w: Long) = (((w + 8 * 3_600_000L) % 86_400_000L) / 60_000L).toInt()
    private fun dayOf(w: Long) = Math.floorDiv(w + 8 * 3_600_000L, 86_400_000L)
    private fun advance(ms: Long) { wall += ms; elapsed += ms }

    private var coordinator = Coordinator(coordStore, CoordinatorConfig(), { wall }, { "c${++seq}" })
    private var scheduler = ProactiveScheduler(schedStore, ::minuteOfDay, ::dayOf, Random(1), { "evt-${++seq}-0000" })
    private var gate = Gate()

    /** Agent requests the "engine" has open: requestId → answers it will give. */
    private val agentCalls = mutableListOf<Command.RequestAgent>()
    private val presented = mutableListOf<Command.Present>()

    /** What the Android adapter does with a step. */
    private fun drive(step: Step) {
        for (c in step.commands) when (c) {
            is Command.RequestAgent -> agentCalls += c
            is Command.Present -> presented += c
            else -> Unit
        }
        for (closed in step.closed) scheduler.onOutcome(closed.eventId, closed.engagement, clocks)
    }

    private fun scheduleTick() {
        when (val d = scheduler.evaluate(clocks)) {
            is ProactiveScheduler.Decision.Fire -> {
                val (r, step) = coordinator.submit(d.request)
                if (r is Submit.Rejected) scheduler.onRejected(r.reason, clocks) else scheduler.onSubmitted(d.request.eventId, clocks)
                drive(step)
            }
            is ProactiveScheduler.Decision.Wait -> if (d.untilWall > wall) advance(d.untilWall - wall)
        }
    }

    /** Plugin answers as recorded against DSH 0.2.1-rc.4 by e2e-live.mjs. */
    private fun plugin(status: Int, body: Map<String, Any?>) {
        val rq = agentCalls.last()
        val d = AgentWire.decode(status, body) as AgentWire.Decoded.Reply
        drive(coordinator.onAgent(rq.requestId, d.reply))
    }

    private fun boot() {
        drive(coordinator.recover(gate))
        scheduler.recover(clocks)
        scheduler.setConfig(SchedulerConfig(enabled = true, minIntervalMs = 30 * 60_000L, maxIntervalMs = 60 * 60_000L,
            quietStartMinute = 0, quietEndMinute = 0, dailyMax = 10), clocks)
    }

    @Test fun aScheduledChatGoesThroughTheAgentToTheScreenAndShapesTheNextGap() {
        boot()
        scheduleTick(); scheduleTick()
        val rq = agentCalls.single()
        assertNull("nothing on screen before the model answers", presented.firstOrNull())
        plugin(202, mapOf("v" to 1L, "state" to "accepted"))
        plugin(200, mapOf("v" to 1L, "state" to "claimed", "turn" to 1L))
        plugin(200, mapOf("v" to 1L, "state" to "generated", "text" to "MOCK_REPLY: 你好", "turn" to 1L))
        val p = presented.single()
        assertEquals("MOCK_REPLY: 你好", p.content.text)
        assertEquals(ContentSource.AGENT, p.content.source)
        drive(coordinator.onSurface(p.token, SurfaceReport.Posted))
        drive(coordinator.onSurface(p.token, SurfaceReport.Shown))
        drive(coordinator.onSurface(p.token, SurfaceReport.Answered("reply")))
        val r = coordinator.find(rq.eventId)!!
        assertEquals(Outcome.RESPONDED, r.outcome)
        listOf(r.agentRequestedAt, r.agentAcceptedAt, r.agentClaimedAt, r.generatedAt, r.presentedAt, r.deliveredAt, r.shownAt, r.respondedAt)
            .forEach { assertNotNull("every fact recorded separately", it) }
        assertNull(scheduler.state.pendingEventId)
        assertEquals(1, scheduler.state.countToday)
        assertTrue(scheduler.state.nextDueWall > wall)
    }

    @Test fun aScheduledChatWaitsForDshThenFallsBackWhenTheAgentIsBusy() {
        boot()
        gate = Gate(dshTaskActive = true)
        drive(coordinator.setGate(gate))
        scheduleTick(); scheduleTick()
        assertTrue("no model call while DSH operates the phone", agentCalls.isEmpty())
        gate = Gate()
        drive(coordinator.setGate(gate))
        plugin(409, mapOf("v" to 1L, "error" to mapOf("code" to "agent-busy")))
        // Scheduler attempts have no canned text: a busy agent means no message, not a fake one.
        val r = coordinator.receipts().first()
        assertEquals(Outcome.FAILED to "agent_agent-busy", r.outcome to r.reason)
        assertEquals(Engagement.NOT_DELIVERED, r.engagement)
        assertTrue(presented.isEmpty())
        assertEquals(0, scheduler.state.countToday)
        assertTrue("retry soon, not at the full gap", scheduler.state.nextDueWall - wall <= 15 * 60_000L)
    }

    @Test fun processDeathMidTurnResumesThePollAndKeepsOneSchedule() {
        boot()
        scheduleTick(); scheduleTick()
        val rq = agentCalls.single()
        plugin(202, mapOf("v" to 1L, "state" to "accepted"))
        // Process dies; a new one rebuilds both from disk.
        coordinator = Coordinator(coordStore, CoordinatorConfig(), { wall }, { "c${++seq}" })
        scheduler = ProactiveScheduler(schedStore, ::minuteOfDay, ::dayOf, Random(2), { "evt-${++seq}-0000" })
        advance(5_000)
        val rec = coordinator.recover(gate)
        scheduler.recover(clocks)
        assertEquals(rq.requestId, rec.commands.filterIsInstance<Command.ResumeAgent>().single().requestId)
        assertEquals(rq.eventId, scheduler.state.pendingEventId)
        assertTrue(scheduler.evaluate(clocks) is ProactiveScheduler.Decision.Wait)
        drive(coordinator.onAgent(rq.requestId, AgentReply.Generated("after restart", "turn:1")))
        assertEquals("after restart", presented.single().content.text)
    }
}
