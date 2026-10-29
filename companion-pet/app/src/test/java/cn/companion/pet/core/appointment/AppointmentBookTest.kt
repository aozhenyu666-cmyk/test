package cn.companion.pet.core.appointment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 约定账本：创建、改期、取消、结算、过期。
 *
 * 关键要钉住的：
 * - 改期/取消后**旧版本的异步结果必须作废**（不能取消完了还发出去）；
 * - 只能结算相关的那一件，**绝不批量结束**；
 * - 过期不补发。
 */
class AppointmentBookTest {
    @get:Rule val tmp = TemporaryFolder()

    private var t = 1_000_000L
    private val minute = 60_000L

    private fun book() = AppointmentBook(AppointmentStore(tmp.newFolder())) { t }

    @Test
    fun `创建后能查到并且是等待中`() {
        val b = book()
        val a = b.create("项目进度", 10 * minute, "十分钟后问问我项目进度")
        assertNotNull(a)
        assertEquals(1, b.upcoming().size)
        assertEquals(AppointmentStatus.PENDING, a!!.status)
        assertEquals(t + 10 * minute, a.dueAtMillis)
    }

    @Test
    fun `没有话题或时间时拒绝创建`() {
        val b = book()
        assertNull("空话题不该建", b.create("", 10 * minute, "x"))
        assertNull("零时间不该建", b.create("进度", 0L, "x"))
    }

    @Test
    fun `改期会推进版本并且旧版本失效`() {
        val b = book()
        val a = b.create("项目进度", 10 * minute, "basis")!!
        val oldVersion = a.version
        val r = b.reschedule(a.id, 30 * minute)
        assertNotNull(r)
        assertTrue("版本必须 +1", r!!.version > oldVersion)
        assertEquals(t + 30 * minute, r.dueAtMillis)

        // 用旧版本号去开始投递必须被拒绝
        assertNull("旧版本不能继续投递（防止改期前生成的话被发出）", b.beginDelivery(a.id, oldVersion))
    }

    @Test
    fun `取消后不能再投递`() {
        val b = book()
        val a = b.create("项目进度", 10 * minute, "basis")!!
        val v = a.version
        b.cancel(a.id)
        assertEquals(AppointmentStatus.CANCELLED, b.find(a.id)!!.status)
        assertNull("取消了就不该还能投递", b.beginDelivery(a.id, v))
        assertTrue("取消后不该出现在待办列表", b.upcoming().isEmpty())
    }

    @Test
    fun `投递成功进入等回答`() {
        val b = book()
        val a = b.create("项目进度", 10 * minute, "basis")!!
        val delivering = b.beginDelivery(a.id, a.version)
        assertNotNull(delivering)
        assertEquals(AppointmentStatus.DELIVERING, delivering!!.status)
        val awaiting = b.markDelivered(a.id, a.version)
        assertEquals(AppointmentStatus.AWAITING, awaiting!!.status)
    }

    @Test
    fun `投递失败退回等待`() {
        val b = book()
        val a = b.create("项目进度", 10 * minute, "basis")!!
        b.beginDelivery(a.id, a.version)
        val back = b.markDeliveryFailed(a.id, a.version)
        assertEquals(AppointmentStatus.PENDING, back!!.status)
    }

    @Test
    fun `只能结算指定的那一件不批量结束`() {
        val b = book()
        val a1 = b.create("项目进度", 10 * minute, "b1")!!
        val a2 = b.create("复习情况", 20 * minute, "b2")!!
        b.complete(a1.id)
        assertEquals(AppointmentStatus.DONE, b.find(a1.id)!!.status)
        assertEquals("另一件绝不能被动到", AppointmentStatus.PENDING, b.find(a2.id)!!.status)
    }

    @Test
    fun `无法判断对应哪件时不动任何事项`() {
        val b = book()
        b.create("项目进度", 10 * minute, "b1")
        b.create("复习情况", 20 * minute, "b2")
        val r = b.complete(null)   // 调用方说不清对应哪件
        assertNull(r)
        assertEquals("说不清就全部保留", 2, b.active().size)
    }

    @Test
    fun `只有到点的才会被取出`() {
        val b = book()
        b.create("马上", 1 * minute, "b")
        b.create("很久以后", 60 * minute, "b")
        assertEquals("还没到点，不该取出来", 0, b.dueNow().size)
        t += 2 * minute
        assertEquals("到点了应当取出来", 1, b.dueNow().size)
    }

    @Test
    fun `过期太久不补发`() {
        val b = book()
        val a = b.create("项目进度", 1 * minute, "basis")!!
        // 直接跳到远超迟到容忍的时刻（默认 10 分钟）
        t += 60 * minute
        val expired = b.expireOvertime()
        assertEquals(1, expired.size)
        assertEquals(AppointmentStatus.EXPIRED, b.find(a.id)!!.status)
        assertEquals("过期的不该再被取出", 0, b.dueNow().size)
    }

    @Test
    fun `迟到容忍内仍然可以发`() {
        val b = book()
        b.create("项目进度", 1 * minute, "basis")
        t += 1 * minute + 5 * minute   // 迟到 5 分钟，在 10 分钟容忍内
        assertEquals("容忍内仍应发出", 1, b.dueNow().size)
        assertEquals("不该被标过期", 0, b.expireOvertime().size)
    }

    @Test
    fun `重启后约定还在`() {
        val dir = tmp.newFolder()
        val b1 = AppointmentBook(AppointmentStore(dir)) { t }
        b1.create("项目进度", 10 * minute, "basis")
        // 模拟重启：用同一个目录新建一个账本
        val b2 = AppointmentBook(AppointmentStore(dir)) { t }
        assertEquals("重启后约定必须还在", 1, b2.upcoming().size)
    }

    @Test
    fun `人话描述带时间和话题`() {
        val b = book()
        val a = b.create("项目进度", 10 * minute, "basis")!!
        val text = a.describe(t)
        assertTrue("要显示时间", text.contains("10 分钟后"))
        assertTrue("要显示话题", text.contains("项目进度"))
    }
}
