package com.zongkong.core

import org.junit.Assert.*
import org.junit.Test

class AiFocusTest {
    private val picker = "com.android.documentsui/com.android.documentsui.picker.PickActivity"
    private val session = AiFocusSession(1_201_000, 7, 1_200_000, 20, true, setOf(picker), "com.vivo.dialer")
    private fun allowed(pkg: String, cls: String = "Main", grant: Boolean = false, s: AiFocusSession = session) =
        AiFocus.allows(s, pkg, cls, "com.zongkong.app", "test.keyboard", grant)

    @Test fun `AI and necessary communication work while arbitrary apps including new installs are blocked`() {
        listOf(AiFocus.GPT, AiFocus.GEMINI, AiFocus.NEKOBOX, AiFocus.WECHAT, "com.vivo.dialer", "test.keyboard", "com.android.incallui", "com.android.permissioncontroller", "com.zongkong.app").forEach {
            assertTrue(it, allowed(it))
        }
        listOf("tv.danmaku.bili", "com.new.game", "com.android.settings", "com.android.launcher", "com.android.chrome", "com.vivo.gallery", "com.android.filemanager", "another.enabled.keyboard").forEach {
            assertFalse(it, allowed(it))
        }
        assertFalse(allowed(AiFocus.WECHAT, s = session.copy(wechat = false)))
    }

    @Test fun `Gemini handoff does not grant Google search feed or custom tabs`() {
        val cls = AiFocus.geminiActivities.first().substringAfter('/')
        assertTrue(allowed(AiFocus.GOOGLE, cls))
        assertFalse(allowed(AiFocus.GOOGLE, "com.google.android.apps.gsa.searchnow.SearchNowActivity"))
        assertFalse(allowed(AiFocus.GOOGLE, "CustomTabActivity"))
    }

    @Test fun `picker access requires the exact component and a conversation grant`() {
        val cls = picker.substringAfter('/')
        assertFalse(allowed("com.android.documentsui", cls))
        assertTrue(allowed("com.android.documentsui", cls, grant = true))
        assertFalse(allowed("com.android.documentsui", "com.android.documentsui.files.FilesActivity", grant = true))
        assertEquals("p/p.Picker", AiFocus.component("p", ".Picker"))
    }

    @Test fun `picker cannot be reached through launcher wechat or a denied app`() {
        val g = AiPickerGrant()
        val cls = picker.substringAfter('/')
        assertFalse(g.observe(session, "com.android.documentsui", cls, 10, false))
        g.observe(session, AiFocus.GPT, "Main", 20, false)
        g.observe(session, "com.android.launcher", "Home", 30, false)
        assertFalse(g.observe(session, "com.android.documentsui", cls, 40, false))
        g.observe(session, AiFocus.GPT, "Main", 50, false)
        g.observe(session, AiFocus.WECHAT, "Main", 60, false)
        assertFalse(g.observe(session, "com.android.documentsui", cls, 70, false))
    }

    @Test fun `permission and keyboard overlays preserve attachment flow but cannot extend picker timeout`() {
        val g = AiPickerGrant()
        val cls = picker.substringAfter('/')
        g.observe(session, AiFocus.GPT, "Main", 10, false)
        g.observe(session, "com.android.permissioncontroller", "GrantPermissionsActivity", 20, true)
        assertTrue(g.observe(session, "com.android.documentsui", cls, 30, false))
        g.observe(session, "test.keyboard", "Keyboard", 40, true)
        assertTrue(g.observe(session, "com.android.documentsui", cls, 300_029, false))
        assertFalse(g.observe(session, "com.android.documentsui", cls, 300_030, false))
        assertFalse(g.observe(session, "com.android.documentsui", cls, 300_031, false))
        g.observe(session, AiFocus.GEMINI, "Main", 300_040, false)
        assertTrue(g.observe(session, "com.android.documentsui", cls, 300_050, false))
    }

    @Test fun `elapsed time includes sleep expires exactly and survives process restart`() {
        assertEquals(1_200_000L, session.remaining(1_000))
        assertEquals(1L, session.remaining(1_200_999))
        assertEquals(0L, session.remaining(1_201_000))
        val decoded = ZkJson.decodeFromString(AiFocusSession.serializer(), ZkJson.encodeToString(AiFocusSession.serializer(), session))
        assertEquals(600_000L, decoded.restore(601_000, 7).remaining(601_000))
    }

    @Test fun `reboot continues checkpoint time without consulting adjustable wall clock`() {
        val saved = session.copy(remainingAtSave = 300_000)
        val restored = saved.restore(12_000, 8)
        assertEquals(300_000L, restored.remaining(12_000))
        assertEquals(0L, restored.remaining(312_000))
        assertEquals(8, restored.bootCount)
    }
}
