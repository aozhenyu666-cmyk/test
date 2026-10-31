package com.zongkong.app.healthwire

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * P0-6a/6b：客户端规则落盘的**诚实性**。
 *
 * 两条断言最要紧：
 *  1. 落盘失败时 `save`/`markEmergencyStopped` 必须回 `ok=false` 且 **revision 不前进**
 *     （哨兵据此记「未确认」，而不是「已确认暂停」）；
 *  2. 紧急暂停到期后 `emergencyStopped()` 必须回 false，并把磁盘上的 STOPPED 清掉。
 */
class ClientPolicyStorePersistTest {

    private lateinit var dir: File
    private val now = 1_700_000_000_000L
    private val min = 60_000L

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "zk-store-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun policy(ends: Long = now + 3 * 3_600_000L, stopped: Boolean = false) = LocalPolicy(
        policyId = "p1",
        startsAtWall = now - min,
        endsAtWall = ends,
        enabled = true,
        emergencyState = if (stopped) LocalPolicy.EMERGENCY_STOPPED else LocalPolicy.EMERGENCY_NONE,
    )

    // ---------- 写成功路径 ----------

    @Test
    fun `保存成功后版本前进 且能重新读回`() {
        val store = ClientPolicyStore(dir)
        assertEquals(0, store.revision)
        val out = store.save(listOf(policy()))
        assertTrue(out.ok)
        assertEquals(1, out.revision)
        assertEquals(1, store.revision)

        // 换一个实例（模拟进程重启）：规则必须还在。
        val re = ClientPolicyStore(dir)
        assertEquals(1, re.revision)
        assertEquals(1, re.policies.size)
    }

    // ---------- 写失败路径（P0-6b 核心） ----------

    @Test
    fun `落盘失败时不许返回新版本 也不许假装保存`() {
        val notADir = File(dir, "blocked").apply { writeText("我是文件，不是目录") }
        val store = ClientPolicyStore(notADir)
        val out = store.save(listOf(policy()))
        assertFalse("写不进磁盘就必须 ok=false", out.ok)
        assertEquals("revision 不许前进", 0, out.revision)
        assertEquals(0, store.revision)
        assertTrue("要给出人话原因", out.error.contains("没写进磁盘"))
        assertTrue("内存里也要留下错误供界面显示", store.lastError.isNotBlank())
        assertTrue("内存规则不能被替换成「已保存」的那份", store.policies.isEmpty())
    }

    @Test
    fun `紧急暂停落盘失败时同样回失败 哨兵才不会记成已确认`() {
        val notADir = File(dir, "blocked2").apply { writeText("x") }
        val store = ClientPolicyStore(notADir)
        val out = store.markEmergencyStopped("哨兵要求紧急暂停", now)
        assertFalse(out.ok)
        assertEquals(0, out.revision)
        assertNull("没有落盘就不该有记录", store.emergency)
        assertFalse("没落盘的暂停不能对外报「已暂停」", store.emergencyStopped(now))
    }

    // ---------- 紧急暂停的到期（P0-6a） ----------

    @Test
    fun `按下暂停时写入到期时刻 取窗口与上限的较小值`() {
        val store = ClientPolicyStore(dir)
        store.save(listOf(policy(ends = now + 30 * min)))
        val out = store.markEmergencyStopped("临时有事", now)
        assertTrue(out.ok)
        val rec = store.emergency
        assertNotNull(rec)
        assertEquals("窗口只剩 30 分钟 → 到期时刻就是窗口结束", now + 30 * min, rec!!.untilWall)
        assertTrue(store.emergencyStopped(now))
    }

    @Test
    fun `窗口很长时按上限封顶 两小时后自动解除`() {
        val store = ClientPolicyStore(dir)
        store.save(listOf(policy(ends = now + 12 * 3_600_000L)))
        store.markEmergencyStopped("临时有事", now)
        val until = store.emergency!!.untilWall
        assertEquals(now + EmergencyLapse.DEFAULT_MAX_MS, until)
        assertTrue("还没到", store.emergencyStopped(until - 1))
        assertFalse("到点就不许再算暂停", store.emergencyStopped(until))
    }

    @Test
    fun `到期后自动清掉磁盘上的暂停状态 界面不再显示已暂停`() {
        val store = ClientPolicyStore(dir)
        store.save(listOf(policy(ends = now + 30 * min)))
        val revBefore = store.markEmergencyStopped("临时有事", now).revision
        assertTrue(store.emergencyStopped(now))

        // 过期后再问一次：必须 false，并且顺手清盘（记录与 STOPPED 标记都清）。
        assertFalse(store.emergencyStopped(now + 31 * min))
        assertNull(store.emergency)
        assertTrue(store.revision > revBefore)
        assertFalse(store.policies.any { it.stopped() })

        // 换个实例读盘：清盘结果必须真的落到了磁盘上。
        val re = ClientPolicyStore(dir)
        assertNull(re.emergency)
        assertFalse(re.policies.any { it.stopped() })
    }

    @Test
    fun `到期不等于放开规则 规则本身仍然生效`() {
        val store = ClientPolicyStore(dir)
        // 窗口 3 小时、上限 2 小时 → 暂停在 2 小时到期，而窗口还没结束。
        val p = policy(ends = now + 3 * 3_600_000L)
        store.save(listOf(p))
        store.markEmergencyStopped("临时有事", now)
        val afterExpiry = now + 2 * 3_600_000L + min
        assertFalse(store.emergencyStopped(afterExpiry))
        val eff = store.effective(afterExpiry)
        assertEquals("规则还在窗口内、还启用", 1, eff.size)
        assertTrue("该锁还得锁", eff.first().failureLock())
    }

    @Test
    fun `解除暂停后可以重新按下`() {
        val store = ClientPolicyStore(dir)
        store.save(listOf(policy()))
        store.markEmergencyStopped("第一次", now)
        assertTrue(store.clearEmergency(now + min).ok)
        assertFalse(store.emergencyStopped(now + 2 * min))
        assertTrue(store.markEmergencyStopped("第二次", now + 3 * min).ok)
        assertTrue(store.emergencyStopped(now + 4 * min))
        assertEquals(now + 3 * min, store.emergency!!.atWall)
    }
}
