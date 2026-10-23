package com.yishou.app.scroll

import com.yishou.app.companion.core.CompanionChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开不起来的时候必须说得出为什么——这几条就是「别让人对着空白猜」。 */
class PreflightTest {

    @Test
    fun `刷着练缺识图就不让开`() {
        val r = ScrollChecks.preflight(visionReady = false, watchedCount = 3, notifyAllowed = true)
        assertFalse(r.canStart)
        assertTrue(r.blockers.first().contains("识图"))
    }

    @Test
    fun `刷着练没通知也不让开，因为入口就在通知里`() {
        val r = ScrollChecks.preflight(visionReady = true, watchedCount = 3, notifyAllowed = false)
        assertFalse(r.canStart)
        assertTrue(r.blockers.first().contains("通知"))
    }

    @Test
    fun `没勾关注的应用可以开，但要当场说清不会自动问`() {
        val r = ScrollChecks.preflight(visionReady = true, watchedCount = 0, notifyAllowed = true)
        assertTrue(r.canStart)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.first().contains("不会触发"))
    }

    @Test
    fun `都齐了就没什么好说的`() {
        val r = ScrollChecks.preflight(visionReady = true, watchedCount = 2, notifyAllowed = true)
        assertTrue(r.canStart)
        assertTrue(r.warnings.isEmpty())
        assertEquals("", r.firstMessage())
    }

    @Test
    fun `开着不自动问要说清是哪一条`() {
        assertTrue(ScrollChecks.idleHint(true, 0, null, 5)!!.contains("没勾关注"))
        assertTrue(ScrollChecks.idleHint(true, 2, false, 5)!!.contains("不在关注名单"))
        assertTrue(ScrollChecks.idleHint(true, 2, null, 0)!!.contains("不自动"))
    }

    @Test
    fun `不知道当前是哪个 App 时不乱说`() {
        assertNull(ScrollChecks.idleHint(true, 2, null, 5))
    }

    @Test
    fun `没开的时候不提示`() {
        assertNull(ScrollChecks.idleHint(false, 0, false, 0))
    }

    @Test
    fun `视频陪练缺文字模型就不开`() {
        val r = CompanionChecks.startup(llmReady = false, sttReady = true, canRecord = true, notifyAllowed = true)
        assertFalse(r.canStart)
        assertTrue(r.blockers.first().contains("文字模型"))
    }

    @Test
    fun `视频陪练没录音权限就不开`() {
        val r = CompanionChecks.startup(llmReady = true, sttReady = true, canRecord = false, notifyAllowed = true)
        assertFalse(r.canStart)
        assertTrue(r.blockers.first().contains("录音"))
    }

    @Test
    fun `没听写接口能开，但要说明听不到内容`() {
        val r = CompanionChecks.startup(llmReady = true, sttReady = false, canRecord = true, notifyAllowed = true)
        assertTrue(r.canStart)
        assertTrue(r.notes.any { it.contains("听不到内容") })
    }

    @Test
    fun `没通知能开，但要说明切走以后看不到停`() {
        val r = CompanionChecks.startup(llmReady = true, sttReady = true, canRecord = true, notifyAllowed = false)
        assertTrue(r.canStart)
        assertTrue(r.notes.any { it.contains("停") })
    }
}
