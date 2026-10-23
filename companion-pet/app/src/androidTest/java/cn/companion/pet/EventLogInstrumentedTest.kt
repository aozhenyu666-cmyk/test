package cn.companion.pet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** 设备上的文件系统验证：事件日志能真实落盘、跨进程读取、按天归档。 */
@RunWith(AndroidJUnit4::class)
class EventLogInstrumentedTest {

    @Test
    fun eventsSurviveRealFileRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "eventlog-" + UUID.randomUUID())
        try {
            val log = EventLog(root)
            val day = LocalDate.now()
            log.append(EventKind.USER_INPUT, "设备上写的一条", source = "instrumented")
            assertEquals(1, log.forDay(day).size)
            assertEquals(1, log.pendingForDay(day).size)

            // 重新构造一个实例，模拟进程重启后从磁盘读取
            val reopened = EventLog(root)
            assertEquals(1, reopened.forDay(day).size)
            reopened.markArchived(day, reopened.forDay(day).map { it.id })
            assertTrue(reopened.pendingForDay(day).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
