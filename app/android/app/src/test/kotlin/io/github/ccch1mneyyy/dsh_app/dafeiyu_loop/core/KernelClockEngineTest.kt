package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class KernelClockEngineTest {
    private val min = 60_000L
    private val hour = 60 * min

    private fun bjWall(year: Int, month: Int, day: Int, h: Int, m: Int, s: Int = 0): Long {
        val c = Calendar.getInstance(BeijingClock.ZONE)
        c.set(year, month - 1, day, h, m, s)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test fun cron5And6FieldInBeijingTimezone() {
        val cron5 = BeijingCronExpression.parse("30 8 * * 1-5")
        assertNotNull(cron5)
        // 2026-11-16 is Monday; 08:29:10 -> 08:30:00
        val t1 = bjWall(2026, 11, 16, 8, 29, 10)
        assertEquals(bjWall(2026, 11, 16, 8, 30, 0), cron5!!.nextAfter(t1))

        // Friday 08:30:00 -> next weekday is Monday 2026-11-23 08:30:00
        val fri = bjWall(2026, 11, 20, 8, 30, 0)
        assertEquals(bjWall(2026, 11, 23, 8, 30, 0), cron5.nextAfter(fri))

        // 6-field high-frequency cron: every 15 seconds
        val cron6 = BeijingCronExpression.parse("*/15 * * * * *")
        assertNotNull(cron6)
        val t2 = bjWall(2026, 11, 16, 10, 0, 2)
        assertEquals(bjWall(2026, 11, 16, 10, 0, 15), cron6!!.nextAfter(t2))
    }

    @Test fun fixedRateDriftCompensationDoesNotAccumulateJitter() {
        val store = MemorySnapshotStore()
        val eng = KernelClockEngine(store, BeijingClock.ZONE)
        var wall = bjWall(2026, 11, 16, 9, 0, 0)
        var elapsed = 10_000L
        val clocks = { Clocks(wall, elapsed, 1) }

        eng.upsert(
            ClockTask(
                id = "hf-ping",
                label = "5秒高频检查",
                kind = ClockTaskKind.FIXED_RATE,
                intervalMs = 5_000L,
                anchorWall = wall,
                nextFireWall = wall + 5_000L,
                action = "notify",
            ),
            clocks(),
        )

        // Jitter: arrive 800ms late on first tick -> next fire MUST remain anchored at +10_000ms (09:00:10), NOT +10_800ms
        wall += 5_800L
        elapsed += 5_800L
        val r1 = eng.tick(clocks())
        assertEquals(1, r1.fires.size)
        assertEquals(bjWall(2026, 11, 16, 9, 0, 10), eng.get("hf-ping")?.nextFireWall)
        assertNotNull("sub-15s fixed rate triggers highFreqIntervalMs hint", r1.highFreqIntervalMs)

        wall += 4_300L
        elapsed += 4_300L
        val r2 = eng.tick(clocks())
        assertEquals(1, r2.fires.size)
        assertEquals(bjWall(2026, 11, 16, 9, 0, 15), eng.get("hf-ping")?.nextFireWall)
    }

    @Test fun cyclePhasePomodoroLoopsPauseResumeSkipAndCompletes() {
        val store = MemorySnapshotStore()
        val eng = KernelClockEngine(store, BeijingClock.ZONE)
        var wall = bjWall(2026, 11, 16, 14, 0, 0)
        var elapsed = 100_000L
        val clocks = { Clocks(wall, elapsed, 1) }

        eng.upsert(
            ClockTask(
                id = "pomodoro",
                label = "深度双循环",
                kind = ClockTaskKind.CYCLE_PHASE,
                maxRuns = 2,
                phases = listOf(
                    ClockPhase(name = "专注", durationMs = 25 * min, strictLock = true, allowApps = listOf("md.obsidian")),
                    ClockPhase(name = "休息", durationMs = 5 * min, strictLock = false),
                ),
            ),
            clocks(),
        )

        // Pause at 10m in -> 15m left
        wall += 10 * min; elapsed += 10 * min
        val paused = eng.pause("pomodoro", clocks())
        assertTrue(paused!!.paused)
        assertEquals(15 * min, paused.pausedRemainingMs)

        // Wait 20m while paused -> must NOT fire
        wall += 20 * min; elapsed += 20 * min
        assertTrue(eng.tick(clocks()).fires.isEmpty())

        // Resume -> fires 15m later, transitioning to 休息
        eng.resume("pomodoro", clocks())
        wall += 15 * min; elapsed += 15 * min
        val rBreak = eng.tick(clocks())
        assertEquals(1, rBreak.fires.size)
        assertEquals("休息", rBreak.fires[0].nextPhase?.name)
        assertFalse(rBreak.fires[0].finished)
        assertEquals(1, eng.get("pomodoro")?.cycleIndex)

        // Skip rest phase -> moves directly to cycle 2 专注
        val ev2 = eng.skipCyclePhase("pomodoro", clocks())
        assertEquals("专注", ev2?.nextPhase?.name)
        assertEquals(2, eng.get("pomodoro")?.cycleIndex)

        // Complete cycle 2 focus & rest
        wall += 25 * min; elapsed += 25 * min
        assertEquals("休息", eng.tick(clocks()).fires[0].nextPhase?.name)
        wall += 5 * min; elapsed += 5 * min
        val endRes = eng.tick(clocks())
        assertTrue(endRes.fires[0].finished)
        assertNull(endRes.fires[0].nextPhase)
        assertFalse(eng.get("pomodoro")!!.enabled)

        // Persistence round-trip check
        val recovered = KernelClockEngine(store, BeijingClock.ZONE).apply { recover(clocks()) }
        assertFalse(recovered.get("pomodoro")!!.enabled)
    }

    @Test fun clockJumpRebasesCronAndElapsedTasksSafely() {
        val store = MemorySnapshotStore()
        val eng = KernelClockEngine(store, BeijingClock.ZONE)
        var wall = bjWall(2026, 11, 16, 12, 0, 0)
        var elapsed = 50_000L
        val clocks = { Clocks(wall, elapsed, 1) }

        eng.upsert(
            ClockTask(
                id = "one-shot-10m",
                label = "10分钟闹钟",
                kind = ClockTaskKind.ONE_SHOT,
                intervalMs = 10 * min,
                urgent = true,
            ),
            clocks(),
        )
        eng.upsert(
            ClockTask(
                id = "daily-2130",
                label = "晚间复盘",
                kind = ClockTaskKind.CRON,
                cronExpr = "30 21 * * *",
            ),
            clocks(),
        )

        // User sets device wall clock backward by 2 hours, elapsed moves +3 min
        wall -= 2 * hour
        elapsed += 3 * min
        eng.onClockJump(clocks())

        val os = eng.get("one-shot-10m")!!
        // Should preserve ~7 minutes remaining on wall clock relative to adjusted wall
        assertEquals(wall + 7 * min, os.nextFireWall)
        val daily = eng.get("daily-2130")!!
        assertEquals(bjWall(2026, 11, 16, 21, 30, 0), daily.nextFireWall)
    }
}
