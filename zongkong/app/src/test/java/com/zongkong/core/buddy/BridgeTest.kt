package com.zongkong.core.buddy

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外接大脑：手机看到什么就推什么，不重复、不补推老事件。 */
class BridgeTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000L

    private fun kinds(r: Pair<List<BridgeEvent>, BridgeState>) = r.first.map { it.kind }

    @Test fun unlockOnlyWhenFresh() {
        val st = BridgeState()
        assertEquals(listOf(Bridge.UNLOCK), kinds(Bridge.events(st, Snapshot(now = t0, unlockedAt = t0 - 10_000))))
        // 老的解锁（进程刚起来读到的）不推
        assertEquals(emptyList<String>(), kinds(Bridge.events(st, Snapshot(now = t0, unlockedAt = t0 - 10 * min))))
        // 同一次解锁不推第二遍
        val (_, after) = Bridge.events(st, Snapshot(now = t0, unlockedAt = t0 - 10_000))
        assertEquals(emptyList<String>(), kinds(Bridge.events(after, Snapshot(now = t0 + 20_000, unlockedAt = t0 - 10_000))))
    }

    @Test fun enterPlayAppThenBingeThresholds() {
        var st = BridgeState(strict = false)
        val enter = Snapshot(now = t0, pkg = "com.ss.android.ugc.aweme", appName = "抖音", since = t0, play = true)
        var r = Bridge.events(st, enter)
        assertEquals(listOf(Bridge.APP_ENTER), kinds(r))
        assertTrue(r.first[0].text.contains("抖音"))
        st = r.second
        r = Bridge.events(st, enter.copy(now = t0 + 10 * min)); assertEquals(emptyList<String>(), kinds(r)); st = r.second
        r = Bridge.events(st, enter.copy(now = t0 + 16 * min)); assertEquals(listOf(Bridge.BINGE_KIND), kinds(r)); st = r.second
        assertTrue(r.first[0].text.contains("15 分钟"))
        r = Bridge.events(st, enter.copy(now = t0 + 17 * min)); assertEquals(emptyList<String>(), kinds(r)); st = r.second
        // 跳过好几个阈值时只推最大的那个一次
        r = Bridge.events(st, enter.copy(now = t0 + 65 * min)); assertEquals(1, r.first.size); st = r.second
        assertTrue(r.first[0].text.contains("60 分钟"))
        // 换了应用：重新计
        r = Bridge.events(st, Snapshot(now = t0 + 66 * min, pkg = "com.android.launcher", since = t0 + 66 * min))
        assertEquals(emptyList<String>(), kinds(r))
        assertEquals(emptySet<Int>(), r.second.bingeSent)
    }

    @Test fun workAppsAndScreenOffArentPushed() {
        val st = BridgeState()
        assertEquals(emptyList<String>(), kinds(Bridge.events(st, Snapshot(now = t0, pkg = "notion.id", since = t0, play = false))))
        assertEquals(emptyList<String>(), kinds(Bridge.events(st, Snapshot(now = t0 + 40 * min, pkg = "x", since = t0, play = true, screenOn = false))))
    }

    @Test fun missAndStrictChanges() {
        var r = Bridge.events(BridgeState(), Snapshot(now = t0, strict = true, missAt = t0 - min, miss = "论证缺了哪一层"))
        // 第一次看到严管不算「变化」；刚没接住的题要推
        assertEquals(listOf(Bridge.MISS), kinds(r))
        r = Bridge.events(r.second, Snapshot(now = t0 + min, strict = false, missAt = t0 - min, miss = "论证缺了哪一层"))
        assertEquals(listOf(Bridge.STRICT_OFF), kinds(r))
        r = Bridge.events(r.second, Snapshot(now = t0 + 2 * min, strict = true, headline = "欠账"))
        assertEquals(listOf(Bridge.STRICT_ON), kinds(r))
        // 老的失手不推
        assertEquals(emptyList<String>(), kinds(Bridge.events(BridgeState(strict = false), Snapshot(now = t0, missAt = t0 - 3 * 60 * min, miss = "旧题"))))
    }

    @Test fun payloadShape() {
        val s = Snapshot(now = t0 + 20 * min, pkg = "p", appName = "抖音", since = t0, play = true, mainTask = "写提纲", strict = true)
        val j = Bridge.payload(BridgeEvent(Bridge.BINGE_KIND, "x"), s)
        assertEquals("zongkong", j["source"]!!.jsonPrimitive.content)
        assertEquals("binge", j["kind"]!!.jsonPrimitive.content)
        assertEquals("20", j["app"]!!.jsonObject["minutes"]!!.jsonPrimitive.content)
        assertEquals("写提纲", j["day"]!!.jsonObject["mainTask"]!!.jsonPrimitive.content)
        assertEquals("true", j["zongkong"]!!.jsonObject["strict"]!!.jsonPrimitive.content)
    }
}
