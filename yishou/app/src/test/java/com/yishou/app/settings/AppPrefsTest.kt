package com.yishou.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPrefsTest {

    @Test
    fun roundTrip() {
        val p = AppPrefs(
            groups = listOf(AppGroup("短视频", 15), AppGroup("游戏", 5)),
            watched = mapOf("a.b" to "短视频", "c.d" to "游戏"),
            windowEnabled = true,
            windowHour = 7,
            windowMinute = 5,
            manualWindowStart = 123,
            manualChecks = setOf("autostart"),
        )
        assertEquals(p, AppPrefs.fromJson(p.toJson()))
    }

    @Test
    fun emptyOrBrokenGivesDefaults() {
        assertEquals(AppPrefs(), AppPrefs.fromJson(null))
        assertEquals(AppPrefs(), AppPrefs.fromJson(""))
        assertEquals(AppPrefs(), AppPrefs.fromJson("{不是 JSON"))
    }

    @Test
    fun missingKeysUseDefaults() {
        val p = AppPrefs.fromJson("""{"ttsEnabled": false}""")
        assertEquals(false, p.ttsEnabled)
        assertEquals(AppPrefs.DEFAULT_WATCHED, p.watched)
        assertEquals(45, p.windowMinutes)
    }

    @Test
    fun clampsAndRepairsGroups() {
        val p = AppPrefs.fromJson(
            """{"groups":[{"name":"A","minutes":99},{"name":"","minutes":10}],
               "watched":{"x":"A","y":"已删除的组"}}""",
        )
        assertEquals(listOf(AppGroup("A", 30)), p.groups)
        assertEquals(mapOf("x" to "A", "y" to "A"), p.watched)
        assertEquals(30, p.passMinutesFor("A"))
        assertEquals(AppPrefs.DEFAULT_PASS_MINUTES, p.passMinutesFor("不存在"))
    }
}
