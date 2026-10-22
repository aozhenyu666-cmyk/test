package com.moling.app.system

import com.moling.core.system.CapabilityKind
import com.moling.core.system.CapabilityState
import com.moling.core.mind.BEIJING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * B5：三项系统工具的能力状态只由“开关 + 权限 + 配置”决定。
 * 重点：没权限不能装成 READY；一项没配置不影响另一项；歌单 URI 不可验证就不算配置好。
 */
class SystemToolRulesTest {

    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 10, 22, hour, minute, 0, 0, BEIJING).toInstant().toEpochMilli()

    @Test fun calendarNeverReadyWithoutPermission() {
        val off = SystemToolRules.calendarCapability(enabled = false, permissionGranted = true, hasSelection = true)
        assertEquals(CapabilityKind.CALENDAR_READ, off.kind)
        assertEquals(CapabilityState.NOT_CONFIGURED, off.state)
        assertFalse(off.usable)

        val denied = SystemToolRules.calendarCapability(enabled = true, permissionGranted = false, hasSelection = false)
        assertEquals(CapabilityState.PERMISSION_DENIED, denied.state)
        assertFalse(denied.usable)

        val selectedBefore = SystemToolRules.calendarCapability(enabled = true, permissionGranted = false, hasSelection = true)
        assertEquals("选过日历说明以前给过权限，现在没了是撤销", CapabilityState.REVOKED, selectedBefore.state)
        assertFalse(selectedBefore.usable)
    }

    @Test fun calendarNeedsSelectionAfterPermission() {
        val noSelection = SystemToolRules.calendarCapability(enabled = true, permissionGranted = true, hasSelection = false)
        assertEquals(CapabilityState.NOT_CONFIGURED, noSelection.state)
        assertFalse(noSelection.usable)

        val ready = SystemToolRules.calendarCapability(enabled = true, permissionGranted = true, hasSelection = true)
        assertEquals(CapabilityState.READY, ready.state)
        assertTrue(ready.usable)
    }

    @Test fun dndStatesAreExplicit() {
        assertEquals(
            CapabilityState.NOT_CONFIGURED,
            SystemToolRules.dndCapability(enabled = false, supported = true, accessGranted = true).state,
        )
        assertEquals(
            CapabilityState.UNSUPPORTED,
            SystemToolRules.dndCapability(enabled = true, supported = false, accessGranted = true).state,
        )
        assertEquals(
            CapabilityState.PERMISSION_DENIED,
            SystemToolRules.dndCapability(enabled = true, supported = true, accessGranted = false).state,
        )
        val ready = SystemToolRules.dndCapability(enabled = true, supported = true, accessGranted = true)
        assertEquals(CapabilityState.READY, ready.state)
        assertTrue(ready.detail.contains("只恢复"))
    }

    @Test fun playlistNeedsUserSelectedVerifiableTarget() {
        assertEquals(
            CapabilityState.NOT_CONFIGURED,
            SystemToolRules.playlistCapability(enabled = false, appPackage = "com.example.music", uri = "content://x").state,
        )
        assertEquals(
            CapabilityState.NOT_CONFIGURED,
            SystemToolRules.playlistCapability(enabled = true, appPackage = "", uri = "content://x").state,
        )
        assertEquals(
            CapabilityState.NOT_CONFIGURED,
            SystemToolRules.playlistCapability(enabled = true, appPackage = "com.example.music", uri = "").state,
        )
        assertEquals(
            CapabilityState.NOT_CONFIGURED,
            SystemToolRules.playlistCapability(enabled = true, appPackage = "com.example.music", uri = "not a uri").state,
        )
        val ready = SystemToolRules.playlistCapability(
            enabled = true,
            appPackage = "com.example.music",
            uri = "content://media/playlist/1",
        )
        assertEquals(CapabilityState.READY, ready.state)
    }

    @Test fun missingMusicAppFallsBackToConfig() {
        val missing = SystemToolRules.playlistCapability(
            enabled = true,
            appPackage = "com.example.music",
            uri = "content://media/playlist/1",
            lastMissing = true,
        )
        assertEquals(CapabilityState.APP_MISSING, missing.state)
        assertFalse(missing.usable)
    }

    @Test fun toolsAreIndependent() {
        val calendar = SystemToolRules.calendarCapability(enabled = false, permissionGranted = false, hasSelection = false)
        val dnd = SystemToolRules.dndCapability(enabled = true, supported = true, accessGranted = true)
        val playlist = SystemToolRules.playlistCapability(
            enabled = false,
            appPackage = "com.example.music",
            uri = "content://media/playlist/1",
        )
        assertEquals(CapabilityState.NOT_CONFIGURED, calendar.state)
        assertEquals(CapabilityState.READY, dnd.state)
        assertEquals(CapabilityState.NOT_CONFIGURED, playlist.state)
    }

    @Test fun timeParsingAndTodayRange() {
        assertEquals(20 * 60 + 30, SystemToolTimes.parseMinute("20:30"))
        assertEquals(20 * 60 + 30, SystemToolTimes.parseMinute("2030"))
        assertEquals(8 * 60 + 5, SystemToolTimes.parseMinute("08：05"))
        assertNull(SystemToolTimes.parseMinute("24:00"))
        assertNull(SystemToolTimes.parseMinute("20:60"))
        assertNull(SystemToolTimes.parseMinute(""))
        assertNull(SystemToolTimes.parseMinute("abc"))

        val now = at(13, 29)
        val (from, to) = SystemToolTimes.todayRange(now)
        assertEquals(24 * 60 * 60_000L, to - from)
        assertTrue(now in from until to)
        assertEquals(from, SystemToolTimes.dayStart(now))
        assertEquals(from + 20 * 60 * 60_000L + 30 * 60_000L, SystemToolTimes.at(now, 20 * 60 + 30))
    }

    @Test fun capabilityKindsMatchTools() {
        assertEquals(SystemToolKind.CALENDAR.capabilityKind, CapabilityKind.CALENDAR_READ)
        assertEquals(SystemToolKind.DND.capabilityKind, CapabilityKind.DND)
        assertEquals(SystemToolKind.PLAYLIST.capabilityKind, CapabilityKind.PLAYLIST)
        assertNotNull(SystemToolKind.CALENDAR.capabilityKind)
    }
}
