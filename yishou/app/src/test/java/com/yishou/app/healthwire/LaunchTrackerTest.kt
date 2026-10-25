package com.yishou.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T18：`startActivity` 返回 true 不算成功。必须观测到「目标页真的在前台」才算。
 */
class LaunchTrackerTest {

    @Before
    fun setUp() = LaunchTracker.reset()

    @Test
    fun `开始只记 REQUESTED 不是成功`() {
        val id = LaunchTracker.begin("GateActivity", "com.ss.android.ugc.aweme", "短视频与社区", 1_000)
        val r = LaunchTracker.all().single { it.launchId == id }
        assertEquals(LaunchTracker.REQUESTED, r.state)
        assertTrue(r.awaitingObservation)
        assertFalse(r.verified)
    }

    @Test
    fun `系统接受请求只是 API_ACCEPTED 仍然不算成功`() {
        val id = LaunchTracker.begin("GateActivity", "com.x", "g", 1_000)
        val r = LaunchTracker.onApiResult(id, accepted = true, nowMono = 1_050)!!
        assertEquals(LaunchTracker.API_ACCEPTED, r.state)
        assertTrue(r.awaitingObservation)
        assertFalse("startActivity 返回 true 不能被当成成功", r.verified)
        assertTrue(r.note.contains("等待实际前台"))
    }

    @Test
    fun `目标页自己报前台才算 VERIFIED_FOREGROUND`() {
        val id = LaunchTracker.begin("GateActivity", "com.x", "g", 1_000)
        LaunchTracker.onApiResult(id, accepted = true, nowMono = 1_050)
        val claimed = LaunchTracker.claimAwaiting("GateActivity", "com.x", 1_400)!!
        assertEquals(LaunchTracker.VERIFIED_FOREGROUND, claimed.state)
        assertTrue(claimed.verified)
        assertFalse(claimed.awaitingObservation)
    }

    @Test
    fun `没等到前台就记 NOT_FOREGROUND`() {
        val id = LaunchTracker.begin("WindowActivity", "", "", 1_000)
        LaunchTracker.onApiResult(id, accepted = true, nowMono = 1_010)
        val overdue = LaunchTracker.overdue(nowMono = 4_100, verifyWithinMs = 3_000)
        assertEquals(1, overdue.size)
        val r = LaunchTracker.onObserved(id, foreground = false, nowMono = 4_100)!!
        assertEquals(LaunchTracker.NOT_FOREGROUND, r.state)
        assertFalse(r.verified)
    }

    @Test
    fun `未到预算时不算 overdue`() {
        val id = LaunchTracker.begin("GateActivity", "com.x", "g", 1_000)
        LaunchTracker.onApiResult(id, accepted = true, nowMono = 1_010)
        assertTrue(LaunchTracker.overdue(nowMono = 2_000, verifyWithinMs = 3_000).isEmpty())
        assertFalse(LaunchTracker.overdue(nowMono = 4_000, verifyWithinMs = 3_000).isEmpty())
    }

    @Test
    fun `startActivity 抛异常记 API_REJECTED`() {
        val id = LaunchTracker.begin("GateActivity", "com.x", "g", 1_000)
        val r = LaunchTracker.onApiResult(id, accepted = false, nowMono = 1_020, note = "startActivity 抛异常")!!
        assertEquals(LaunchTracker.API_REJECTED, r.state)
        assertFalse(r.awaitingObservation)
        assertFalse(r.verified)
    }

    @Test
    fun `claim 优先匹配同一个应用`() {
        LaunchTracker.begin("GateActivity", "com.a", "g", 1_000)
        val idB = LaunchTracker.begin("GateActivity", "com.b", "g", 1_100)
        val r = LaunchTracker.claimAwaiting("GateActivity", "com.b", 1_500)!!
        assertEquals(idB, r.launchId)
    }

    @Test
    fun `没有等待中的记录时 claim 返回 null`() {
        LaunchTracker.begin("GateActivity", "com.a", "g", 1_000)
        LaunchTracker.claimAwaiting("GateActivity", "com.a", 1_100)
        assertNull(LaunchTracker.claimAwaiting("GateActivity", "com.a", 1_200))
    }

    @Test
    fun `last 与 lastState 反映最新一条`() {
        LaunchTracker.begin("GateActivity", "com.a", "g", 1_000)
        val id = LaunchTracker.begin("WindowActivity", "", "", 1_100)
        LaunchTracker.onApiResult(id, accepted = true, nowMono = 1_150)
        assertEquals(id, LaunchTracker.last()?.launchId)
        assertEquals(LaunchTracker.API_ACCEPTED, LaunchTracker.lastState())
        assertEquals(1_150L, LaunchTracker.lastAtMono())
    }

    @Test
    fun `记录数量有上限 不会无限增长`() {
        repeat(200) { i ->
            val id = LaunchTracker.begin("GateActivity", "com.p$i", "g", 1_000L + i)
            LaunchTracker.onObserved(id, foreground = true, nowMono = 1_001L + i)
        }
        assertTrue("实际 ${LaunchTracker.all().size} 条", LaunchTracker.all().size <= 40)
        // 最新的还在
        assertEquals("com.p199", LaunchTracker.last()?.pkg)
    }

    @Test
    fun `不存在的时间线不会凭空产生回执`() {
        assertNull(LaunchTracker.onApiResult("不存在", accepted = true, nowMono = 1))
        assertNull(LaunchTracker.onObserved("不存在", foreground = true, nowMono = 1))
        assertNotNull(LaunchTracker.lastState())
    }
}
