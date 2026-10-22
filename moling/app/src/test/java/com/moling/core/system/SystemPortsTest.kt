package com.moling.core.system

import com.moling.core.flow.Busy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第十三批 G4：系统工具只有接口/规则，没权限时明确拒绝，不伪造成功。 */
class SystemPortsTest {
    private val now = 1_790_000_000_000L

    @Test fun unavailableDefaultsRefuseExplicitly() {
        val tools = SystemTools()
        val failedCalendar = tools.calendarBusy(0, now)
        assertFalse(failedCalendar.ok)
        assertEquals(ToolError.PERMISSION_DENIED, failedCalendar.error)
        assertNull(failedCalendar.value)
        assertFalse(tools.calendarInsert(CalendarEventTemplate("会", now, now + 600_000), now).ok)
        assertFalse(tools.calendarWriteDirect(CalendarEventTemplate("会", now, now + 600_000), now).ok)

        assertFalse(tools.dndCurrent().ok)
        assertEquals(ToolError.UNSUPPORTED, tools.dndApply(DndMode.PRIORITY, "专注", now).error)
        assertFalse(tools.dndRestore(DndChange("c", DndMode.OFF, DndMode.PRIORITY, now), now).ok)

        assertEquals(CapabilityState.NOT_CONFIGURED, tools.playlistCapability("", "content://x").state)
        assertFalse(tools.openPlaylist("com.example.music", "content://x", now).ok)
        assertEquals(ToolError.NOT_CONFIGURED, tools.openPlaylist("com.example.music", "content://x", now).error)
    }

    @Test fun toolsAreIndependent() {
        val cal = ReadyCalendar()
        val dnd = FailingDnd()
        val music = MissingPlaylist()
        val tools = SystemTools(cal, dnd, music)
        assertEquals(CapabilityState.READY, tools.capabilities(now)[0].state)
        assertEquals(CapabilityState.PERMISSION_DENIED, tools.capabilities(now)[1].state)
        assertEquals(CapabilityState.APP_MISSING, tools.playlistCapability("com.example.music", "content://x").state)

        assertTrue(tools.calendarBusy(0, now).ok)
        assertFalse(tools.dndApply(DndMode.PRIORITY, "专注", now).ok)
        assertFalse(tools.openPlaylist("com.example.music", "content://x", now).ok)
    }

    @Test fun calendarMergeDedupesAndKeepsSyncTime() {
        val a = Busy(1_000, 2_000, "课")
        val b = Busy(3_000, 4_000, "会")
        val r = CalendarSync.merge(listOf(a), listOf(a, b, Busy(9_000, 8_000, "坏区间")), now)
        assertEquals(2, r.busy.size)
        assertEquals(1, r.added)
        assertEquals(1, r.duplicates)
        assertEquals(now, r.syncedAt)
    }

    @Test fun calendarWriteOnlyOpensEditorAndWaitsForUser() {
        val cal = ReadyCalendar()
        val r = cal.insertEvent(CalendarEventTemplate("写方案", now, now + 3_600_000), now)
        assertTrue(r.ok)
        assertTrue(r.value!!.opened)
        assertTrue("必须等用户保存", r.value!!.needsUserSave)
        assertEquals("android.intent.action.INSERT", r.value!!.action)
        assertFalse("没单独开启就不直接写", cal.writeDirect(CalendarEventTemplate("写方案", now, now + 3_600_000), now).ok)
    }

    @Test fun revokedPermissionReturnsNoStaleBusy() {
        val cal = RevokedCalendar()
        val r = cal.readBusy(0, now)
        assertFalse(r.ok)
        assertEquals(ToolError.REVOKED, r.error)
        assertNull(r.value)
    }

    @Test fun dndRestoreOnlyWhatWeChanged() {
        val change = DndChange("c1", DndMode.OFF, DndMode.PRIORITY, now)
        val restored = DndPolicy.plan(change, DndMode.PRIORITY, now + 60_000)
        assertEquals(DndRestoreKind.RESTORED, restored.kind)
        assertEquals(DndMode.OFF, restored.restoreTo)

        val userChanged = DndPolicy.plan(change, DndMode.ALARMS, now + 60_000)
        assertEquals(DndRestoreKind.USER_CHANGED, userChanged.kind)
        assertNull("不覆盖用户手动调整", userChanged.restoreTo)

        val same = DndChange("c2", DndMode.PRIORITY, DndMode.PRIORITY, now)
        assertEquals(DndRestoreKind.NOT_CHANGED, DndPolicy.plan(same, DndMode.PRIORITY, now + 60_000).kind)

        val expired = DndPolicy.plan(change, DndMode.PRIORITY, now + 7 * 60 * 60_000L)
        assertEquals(DndRestoreKind.EXPIRED, expired.kind)

        val alreadyBack = DndPolicy.plan(change, DndMode.OFF, now + 60_000)
        assertEquals(DndRestoreKind.NOT_CHANGED, alreadyBack.kind)
    }

    @Test fun dndApplyFailureHasNoChangeRecord() {
        val dnd = FailingDnd()
        val r = dnd.apply(DndMode.PRIORITY, "专注", now)
        assertFalse(r.ok)
        assertNull(r.value)
        assertEquals(ToolError.PERMISSION_DENIED, r.error)
    }

    @Test fun playlistNeedsVerifiableTarget() {
        assertEquals(CapabilityState.NOT_CONFIGURED, PlaylistTargets.check("", "content://x").state)
        assertEquals(CapabilityState.NOT_CONFIGURED, PlaylistTargets.check("com.example.music", "").state)
        assertEquals(CapabilityState.NOT_CONFIGURED, PlaylistTargets.check("com.example.music", "not a uri").state)
        assertEquals(CapabilityState.READY, PlaylistTargets.check("com.example.music", "content://media/playlist/1").state)
    }

    @Test fun missingMusicAppDegradesToConfig() {
        val music = MissingPlaylist()
        val r = music.open("com.example.music", "content://media/playlist/1", now)
        assertFalse(r.ok)
        assertEquals(ToolError.APP_MISSING, r.error)
        assertNull(r.value)
    }

    @Test fun openedPlaylistDoesNotClaimPlayback() {
        val music = ReadyPlaylist()
        val r = music.open("com.example.music", "content://media/playlist/1", now)
        assertTrue(r.ok)
        assertTrue(r.value!!.started)
        assertTrue("只说系统接受了打开", r.value!!.message.contains("不表示正在播放"))
    }

    private class ReadyCalendar : CalendarPort {
        private val cap = Capability(CapabilityKind.CALENDAR_READ, CapabilityState.READY, "测试用")
        override fun capability() = cap
        override fun readBusy(from: Long, to: Long): ToolResult<List<Busy>> =
            ToolResult.ok(listOf(Busy(1_000, 2_000, "课")), at = to)
        override fun insertEvent(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> =
            ToolResult.ok(CalendarHandoff("android.intent.action.INSERT", true, true, "在系统页面点保存才算写入"), at = now)
        override fun writeDirect(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> =
            ToolResult.fail(ToolError.NOT_CONFIGURED, "直接写入需要单独开启")
    }

    private class RevokedCalendar : CalendarPort {
        private val cap = Capability(CapabilityKind.CALENDAR_READ, CapabilityState.REVOKED, "权限被撤销")
        override fun capability() = cap
        override fun readBusy(from: Long, to: Long): ToolResult<List<Busy>> =
            ToolResult.fail(ToolError.REVOKED, "日历权限被撤销了", to)
        override fun insertEvent(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> =
            ToolResult.fail(ToolError.REVOKED, "日历权限被撤销了", now)
        override fun writeDirect(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> =
            ToolResult.fail(ToolError.REVOKED, "日历权限被撤销了", now)
    }

    private class FailingDnd : DndPort {
        private val cap = Capability(CapabilityKind.DND, CapabilityState.PERMISSION_DENIED, "测试用")
        override fun capability() = cap
        override fun current(): ToolResult<DndMode> = ToolResult.fail(ToolError.PERMISSION_DENIED, "没有通知策略访问", 0)
        override fun apply(mode: DndMode, reason: String, now: Long): ToolResult<DndChange> =
            ToolResult.fail(ToolError.PERMISSION_DENIED, "勿扰没有生效", now)
        override fun restore(change: DndChange, now: Long): ToolResult<DndRestorePlan> =
            ToolResult.fail(ToolError.PERMISSION_DENIED, "恢复不了", now)
    }

    private class MissingPlaylist : PlaylistPort {
        override fun capability(appPackage: String, uri: String): Capability =
            Capability(CapabilityKind.PLAYLIST, CapabilityState.APP_MISSING, "没装 $appPackage")
        override fun open(appPackage: String, uri: String, now: Long): ToolResult<PlaylistHandoff> =
            ToolResult.fail(ToolError.APP_MISSING, "没装这个音乐 App", now)
    }

    private class ReadyPlaylist : PlaylistPort {
        override fun capability(appPackage: String, uri: String): Capability = PlaylistTargets.check(appPackage, uri)
        override fun open(appPackage: String, uri: String, now: Long): ToolResult<PlaylistHandoff> =
            ToolResult.ok(PlaylistHandoff(true, appPackage, uri, "系统接受了打开；不表示正在播放"), at = now)
    }
}
