package com.zongkong.core

import com.zongkong.core.Pause.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 停顿分类：冻结 / 熄屏休眠 / 分不清 / 无声被清。 */
class PauseTest {
    private val min = 60_000L
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun shortGapsAreIgnored() {
        assertEquals(Kind.NONE, Pause.classify(3 * min, true, false, Pause.Usage(screenOnMs = 3 * min, known = true)))
    }

    @Test fun usingThePhoneWhileStoppedMeansFrozen() {
        assertEquals(Kind.FROZEN, Pause.classify(60 * min, true, false, Pause.Usage(screenOnMs = 20 * min, appSwitches = 0, known = true)))
        assertEquals(Kind.FROZEN, Pause.classify(60 * min, true, false, Pause.Usage(screenOnMs = 0, appSwitches = 5, known = true)))
    }

    @Test fun screenOffIsJustDoze() {
        assertEquals(Kind.DOZE, Pause.classify(8 * 60 * min, true, false, Pause.Usage(screenOnMs = 30_000, appSwitches = 1, known = true)))
        assertNull(Pause.text(Kind.DOZE, 0, 8 * 60 * min, Pause.Usage(known = true), zone))
    }

    @Test fun withoutUsageAccessItsUnsure() {
        assertEquals(Kind.UNSURE, Pause.classify(60 * min, true, false, Pause.Usage(known = false)))
    }

    @Test fun newProcessWithoutExitRecordIsSilentKill() {
        assertEquals(Kind.KILLED_SILENT, Pause.classify(60 * min, false, false, Pause.Usage(known = false)))
        // 有死因记录的，交给死因日志说
        assertEquals(Kind.NONE, Pause.classify(60 * min, false, true, Pause.Usage(known = false)))
    }

    @Test fun textsAreReadable() {
        val from = java.time.ZonedDateTime.of(2026, 11, 2, 8, 55, 0, 0, zone).toInstant().toEpochMilli()
        val t = Pause.text(Kind.FROZEN, from, from + 480 * min, Pause.Usage(screenOnMs = 95 * min, appSwitches = 40, known = true), zone)!!
        assertTrue(t, t.startsWith("进程没死，但 08:55–16:55（8 小时 0 分）一动不动"))
        assertTrue(t, t.contains("亮屏 1 小时 35 分、切换应用 40 次"))
        assertTrue(Pause.text(Kind.KILLED_SILENT, from, from + 30 * min, Pause.Usage(), zone)!!.contains("没有留下死因记录"))
    }
}
