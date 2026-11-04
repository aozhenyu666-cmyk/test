package com.dshdriver.core

import kotlin.test.*

class AwarenessTest {
    @Test fun fiveTenAndFinalDeepDoNotDoubleFire() {
        var s = Supervision.start(1000, 10)
        assertNull(s.poll(300999))
        val micro = s.poll(301000)!!
        assertEquals("micro", micro.kind); s = micro.state
        assertNull(s.poll(301001))
        val deep = s.poll(601000)!!
        assertEquals("deep", deep.kind)
        assertEquals(301000, deep.from)
        assertFalse(deep.state.active)
        assertNull(deep.state.poll(901000))
    }
    @Test fun lateRestartCoalescesMissedDeadlinesAndClockRollbackDoesNotInventUsage() {
        val s = Supervision.start(0, 60)
        assertNull(s.poll(-1))
        val late = s.poll(21 * 60_000)!!
        assertEquals("deep", late.kind)
        assertEquals(25 * 60_000, late.state.nextMicro)
        assertEquals(30 * 60_000, late.state.nextDeep)
        assertNull(late.state.poll(21 * 60_000 + 1))
        assertEquals(s, s.copy())
    }
    @Test fun clippedTimelineKeepsBoundaryAndSeparatesUnobservedTime() {
        val events = listOf(AppTimeline.Event(0, "gemini"), AppTimeline.Event(180000, "bili"), AppTimeline.Event(240000, null))
        val r = AppTimeline.summarize(events, 60000, 300000)
        assertEquals(listOf(AppTimeline.Usage("gemini",120000), AppTimeline.Usage("bili",60000)), r.apps)
        assertEquals(60000, r.unknownMs)
        assertEquals(240000, r.unknownMs + r.apps.sumOf { it.ms })
        assertEquals(events, AppTimeline.retain(events, 60000))
    }
    @Test fun noEventsAndSameTimestampTransitionsDoNotClaimData() {
        assertEquals(300000, AppTimeline.summarize(emptyList(),0,300000).unknownMs)
        val r = AppTimeline.summarize(listOf(AppTimeline.Event(0,"a"),AppTimeline.Event(0,"b")),0,1000)
        assertEquals(listOf(AppTimeline.Usage("b",1000)),r.apps)
        assertTrue(AppTimeline.summarize(emptyList(),10,0).apps.isEmpty())
    }
}
