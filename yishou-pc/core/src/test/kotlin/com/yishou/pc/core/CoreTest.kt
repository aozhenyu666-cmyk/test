package com.yishou.pc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

class CoreTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val prefs = Prefs()
    private val t0 = LocalDate.of(2026, 10, 9).atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    @Test
    fun classifier() {
        assertEquals(Prefs.WEB, Classifier.categoryOf(Foreground("Quark.exe", "【4K】某某 - 哔哩哔哩 - 夸克"), prefs))
        assertNull(Classifier.categoryOf(Foreground("WINWORD.EXE", "哔哩哔哩学习笔记.docx"), prefs))
        assertNull(Classifier.categoryOf(Foreground("quark.exe", "资料分析 增长率 - 夸克"), prefs))
        assertEquals(Prefs.GAME, Classifier.categoryOf(Foreground("WeGame.exe", "WeGame"), prefs))
        assertEquals(Prefs.GAME, Classifier.categoryOf(Foreground("DeltaForce.exe", "三角洲行动", listOf("launcher.exe", "WeGame.exe")), prefs))
        assertEquals(Prefs.EMU, Classifier.categoryOf(Foreground("dnplayer.exe", "雷电模拟器"), prefs))
        assertNull(Classifier.categoryOf(Foreground("code.exe", "bilibili.kt"), prefs))
    }

    @Test
    fun prefsRoundTripAndClamp() {
        val p = prefs.copy(focusSlots = listOf(FocusSlot(20, 0, 45)), speak = false, welcomed = true)
        assertEquals(p, Prefs.fromJson(p.toJson()))
        val bad = Prefs.fromJson("""{"categories":[{"id":"x","name":"X","daily":9999,"max":0}],"rules":[{"kind":"NOPE","pattern":"a","category":"x"},{"kind":"PROCESS","pattern":"a.exe","category":"missing"}]}""")
        assertEquals(600, bad.categories.single().dailyMinutes)
        assertEquals(1, bad.categories.single().maxPass)
        assertTrue(bad.rules.isEmpty())
        assertEquals(Prefs(), Prefs.fromJson("garbage"))
    }

    @Test
    fun gateThenGrantThenTimeUpThenFollowUp() {
        var day = DayState.from(emptyList())
        val first = Gatekeeper.check(Prefs.WEB, t0, day, prefs, focus = false) as Verdict.Gate
        assertEquals(15, first.info.maxGrant)
        assertEquals(10, first.info.cooldownSec)
        assertEquals(listOf(5, 10, 15), first.info.choices)
        assertNull(first.info.followUp)

        val events = mutableListOf(
            Event(t0, EventType.GATE, Prefs.WEB),
            Event(t0 + 1000, EventType.GRANT, Prefs.WEB, minutes = 15, what = "看一集", then = "做两道资料分析"),
        )
        day = DayState.from(events)
        assertTrue(Gatekeeper.check(Prefs.WEB, t0 + 10 * min, day, prefs, false) is Verdict.Allow)
        // 专注时段里之前放的行不算
        assertTrue(Gatekeeper.check(Prefs.WEB, t0 + 10 * min, day, prefs, true) is Verdict.Gate)

        val after = Gatekeeper.check(Prefs.WEB, t0 + 16 * min, day, prefs, false) as Verdict.Gate
        assertEquals("做两道资料分析", after.info.timeUp?.then)
        assertEquals("做两道资料分析", after.info.followUp?.then)
        assertEquals(15, after.info.used)
        assertEquals(20, after.info.cooldownSec)
        assertEquals(2, after.info.attempt)

        events += Event(t0 + 17 * min, EventType.FOLLOWUP, done = true)
        day = DayState.from(events)
        assertNull(day.pendingFollowUp(t0 + 18 * min))
        assertEquals(1, day.kept)
        // 过了一刻钟就不算“刚到时间”了
        assertNull((Gatekeeper.check(Prefs.WEB, t0 + 40 * min, day, prefs, false) as Verdict.Gate).info.timeUp)
    }

    @Test
    fun budgetRunsOutAndEmergencyDoesNotCount() {
        val events = listOf(
            Event(t0, EventType.GRANT, Prefs.WEB, minutes = 15, then = "回来做题"),
            Event(t0 + 20 * min, EventType.GRANT, Prefs.WEB, minutes = 15, then = "回来做题"),
            Event(t0 + 40 * min, EventType.GRANT, Prefs.WEB, minutes = 10, then = "回来做题"),
            Event(t0 + 55 * min, EventType.EMERGENCY, Prefs.WEB, minutes = 5, note = "x"),
        )
        val day = DayState.from(events)
        assertEquals(40, day.used[Prefs.WEB])
        assertTrue(Gatekeeper.check(Prefs.WEB, t0 + 57 * min, day, prefs, false) is Verdict.Allow)
        val g = (Gatekeeper.check(Prefs.WEB, t0 + 61 * min, day, prefs, false) as Verdict.Gate).info
        assertEquals(0, g.maxGrant)
        assertFalse(g.canGrant)
        assertTrue(g.choices.isEmpty())
        assertEquals(1, g.emergencyLeft)
        assertEquals(40, g.cooldownSec)
    }

    @Test
    fun cooldownCapsAndChoices() {
        assertEquals(60, Gatekeeper.cooldown(10, prefs))
        val info = GateInfo(prefs.category(Prefs.GAME)!!, false, 0, 60, 25, 10, 2, 1, null, null)
        assertEquals(listOf(5, 10, 15, 20, 25), info.choices)
        assertTrue(Gatekeeper.intentionOk("看视频", "做三道题"))
        assertFalse(Gatekeeper.intentionOk("看", "做三道题"))
        assertFalse(Gatekeeper.intentionOk("看视频", "做题"))
    }

    @Test
    fun focusSlotsManualAndStop() {
        val p = prefs.copy(focusSlots = listOf(FocusSlot(20, 0, 45), FocusSlot(23, 30, 60)))
        val at = { h: Int, m: Int -> LocalDate.of(2026, 10, 9).atTime(h, m).atZone(zone).toInstant().toEpochMilli() }
        assertNull(Focus.active(at(19, 59), p, zone))
        assertEquals(at(20, 45), Focus.active(at(20, 10), p, zone)?.end)
        assertEquals(at(20, 0), Focus.next(at(19, 0), p, zone))
        // 跨午夜
        val tomorrow0010 = LocalDate.of(2026, 10, 10).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
        assertTrue(Focus.active(tomorrow0010, p, zone) != null)
        // 提前结束
        assertNull(Focus.active(at(20, 30), p.copy(focusStoppedAt = at(20, 20)), zone))
        // 手动开始
        val manual = p.copy(manualFocusStart = at(15, 0), manualFocusMinutes = 30)
        assertEquals(at(15, 30), Focus.active(at(15, 10), manual, zone)?.end)
    }

    @Test
    fun eventStoreAndHeartbeat() {
        val dir = Files.createTempDirectory("pc").toFile()
        val store = EventStore(dir, { zone })
        store.append(Event(t0, EventType.GATE, Prefs.GAME, target = "WeGame.exe"))
        store.append(Event(t0 + 1, EventType.DECLINE, Prefs.GAME))
        dir.resolve("events-2026-10-09.jsonl").appendText("broken line\n")
        val read = store.read(LocalDate.of(2026, 10, 9))
        assertEquals(listOf(EventType.GATE, EventType.DECLINE), read.map { it.type })
        assertEquals("WeGame.exe", read.first().target)

        val hb = Heartbeat(dir.resolve("hb.json"))
        assertNull(hb.lastUnclean())
        hb.beat(123)
        assertEquals(123L, hb.lastUnclean())
        hb.markClean(456)
        assertNull(hb.lastUnclean())
    }
}
