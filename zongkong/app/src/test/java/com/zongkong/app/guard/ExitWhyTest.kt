package com.zongkong.app.guard

import com.zongkong.app.guard.ExitWhy.Culprit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 死因翻译：系统给的原因码 + 说明 → 该修哪里。 */
class ExitWhyTest {
    private val main = "com.zongkong.app"

    private fun exit(reason: Int, desc: String = "", at: Long = 1L, process: String = main, status: Int = 0) =
        ExitWhy.Exit(at = at, process = process, reason = reason, status = status, importance = 230, description = desc)

    @Test fun directCodes() {
        assertEquals(Culprit.OWN_CODE, ExitWhy.culprit(exit(ExitWhy.CRASH)))
        assertEquals(Culprit.OWN_CODE, ExitWhy.culprit(exit(ExitWhy.CRASH_NATIVE)))
        assertEquals(Culprit.STUCK, ExitWhy.culprit(exit(ExitWhy.ANR)))
        assertEquals(Culprit.MEMORY, ExitWhy.culprit(exit(ExitWhy.LOW_MEMORY)))
        assertEquals(Culprit.CHAINED, ExitWhy.culprit(exit(ExitWhy.DEPENDENCY_DIED)))
        assertEquals(Culprit.FORCE_STOP, ExitWhy.culprit(exit(ExitWhy.USER_REQUESTED)))
        assertEquals(Culprit.FROZEN, ExitWhy.culprit(exit(ExitWhy.FREEZER)))
        assertEquals(Culprit.UPDATE, ExitWhy.culprit(exit(ExitWhy.PACKAGE_UPDATED)))
        assertEquals(Culprit.NORMAL, ExitWhy.culprit(exit(ExitWhy.EXIT_SELF)))
        assertEquals(Culprit.OWN_CODE, ExitWhy.culprit(exit(ExitWhy.EXIT_SELF, status = 10)))
    }

    @Test fun vendorKillsReadTheDescription() {
        assertEquals(Culprit.SWIPED, ExitWhy.culprit(exit(ExitWhy.OTHER, "remove task")))
        assertEquals(Culprit.FORCE_STOP, ExitWhy.culprit(exit(ExitWhy.OTHER, "stop com.zongkong.app due to from pid 1234")))
        assertEquals(Culprit.CHAINED, ExitWhy.culprit(exit(ExitWhy.OTHER, "depends on provider com.zongkong.sentinel/.X in dying proc com.zongkong.sentinel")))
        assertEquals(Culprit.MEMORY, ExitWhy.culprit(exit(ExitWhy.SIGNALED, "lmk", status = 9)))
        assertEquals(Culprit.VENDOR, ExitWhy.culprit(exit(ExitWhy.SIGNALED, "", status = 9)))
        assertEquals(Culprit.VENDOR, ExitWhy.culprit(exit(ExitWhy.OTHER, "SmartPower kill")))
        assertEquals(Culprit.UNKNOWN, ExitWhy.culprit(exit(ExitWhy.UNKNOWN)))
    }

    @Test fun lineCarriesTheFix() {
        val l = ExitWhy.line(exit(ExitWhy.SIGNALED, status = 9).copy(pssKb = 300 * 1024))
        assertTrue(l, l.startsWith("系统 / 厂商后台管理杀的"))
        assertTrue(l, "信号 9" in l)
        assertTrue(l, "300MB" in l)
        assertTrue(l, "可感知" in l)
        assertTrue(l, "怎么修" in l)
    }

    @Test fun verdictPicksTheMainCulpritOfTheGuardProcess() {
        val list = listOf(
            exit(ExitWhy.SIGNALED, at = 1),
            exit(ExitWhy.SIGNALED, at = 2),
            exit(ExitWhy.CRASH, at = 3),
            exit(ExitWhy.PACKAGE_UPDATED, at = 4),
            exit(ExitWhy.CRASH, at = 5, process = "$main:other"),
        )
        val v = ExitWhy.verdict(list, main)
        assertTrue(v, v.startsWith("最近 3 次进程死亡，主要是「系统 / 厂商后台管理杀的」(2 次)"))
        assertTrue(v, "总控自己崩了 1 次" in v)
        assertEquals("", ExitWhy.verdict(listOf(exit(ExitWhy.PACKAGE_UPDATED)), main))
    }

    @Test fun freshIsDedupedAndSorted() {
        val f = ExitWhy.fresh(listOf(exit(ExitWhy.ANR, at = 30), exit(ExitWhy.ANR, at = 10), exit(ExitWhy.ANR, at = 20)), 10)
        assertEquals(listOf(20L, 30L), f.map { it.at })
    }

    @Test fun anrTracePointsAtOwnCode() {
        val trace = """
            ----- pid 123 at 2026-10-31 -----
            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1
              at java.lang.Object.wait(Native method)
              at android.os.BinderProxy.transact(BinderProxy.java:584)
              at com.zongkong.app.guard.SentinelClient.ping(Sentinel.kt:299)
              at com.zongkong.app.guard.GuardService.tick(GuardService.kt:280)

            "Signal Catcher" daemon prio=5 tid=2 Runnable
              at com.zongkong.app.Other.x(Other.kt:1)
        """.trimIndent()
        assertEquals("主线程卡在 com.zongkong.app.guard.SentinelClient.ping(Sentinel.kt:299)", ExitWhy.mainThreadHint(trace))
        assertEquals("", ExitWhy.mainThreadHint("no threads"))
    }
}
