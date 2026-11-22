package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D5 微信误入识别：只认微信自己的页面类名，认不出来就不管（这一条不许升级成违抗）。 */
class WechatPagesTest {
    private val wx = WechatPages.PKG
    private val moments = "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"
    private val finder = "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI"
    private val chat = "com.tencent.mm.ui.LauncherUI"

    @Test
    fun theThreePagesTheContractNamesAreRecognised() {
        assertEquals("finder", WechatPages.page(finder))
        assertEquals("moment", WechatPages.page(moments))
        assertEquals("novel", WechatPages.page("com.tencent.mm.plugin.readerapp.ui.ReaderAppUI"))
        assertNull(WechatPages.page(chat))
        assertNull(WechatPages.page(null))
    }

    @Test
    fun threeSecondsIsTheLineBetweenAPeekAndAHit() {
        assertEquals(3_000L, WechatPages.DWELL_MS)
        assertEquals("com.tencent.mm", WechatPages.PKG)
    }

    @Test
    fun backToChatAfterFiveSecondsIsOneHit() {
        val s = WechatStay()
        assertNull(s.onWindow(wx, moments, 1_000))
        assertEquals(WechatHit("moment", 5_000), s.onWindow(wx, chat, 6_000))
        // 已经结算过，再切页面不重复报。
        assertNull(s.onWindow(wx, chat, 9_000))
    }

    @Test
    fun aPeekUnderThreeSecondsIsNothing() {
        val s = WechatStay()
        s.onWindow(wx, finder, 0)
        assertNull(s.onWindow(wx, chat, 2_999))
    }

    @Test
    fun leavingWechatStraightToAnotherAppStillCounts() {
        val s = WechatStay()
        s.onWindow(wx, moments, 0)
        assertEquals(WechatHit("moment", 4_000), s.onWindow("com.android.launcher3", null, 4_000))
    }

    @Test
    fun screenOffSettlesTheStay() {
        val s = WechatStay()
        s.onWindow(wx, finder, 0)
        assertEquals(WechatHit("finder", 10_000), s.onScreenOff(10_000))
        assertNull(s.onScreenOff(20_000))
    }

    @Test
    fun theNotificationShadeIsNotLeaving() {
        val s = WechatStay()
        s.onWindow(wx, moments, 0)
        assertNull(s.onWindow("com.android.systemui", null, 2_000))
        assertEquals(WechatHit("moment", 6_000), s.onWindow(wx, chat, 6_000))
    }

    @Test
    fun movingFromMomentsToFinderSettlesMomentsAndStartsFinder() {
        val s = WechatStay()
        s.onWindow(wx, moments, 0)
        assertEquals(WechatHit("moment", 3_000), s.onWindow(wx, finder, 3_000))
        assertEquals(WechatHit("finder", 4_000), s.onWindow(wx, chat, 7_000))
    }

    @Test
    fun aSubPageOfTheSamePlaceKeepsTheClockRunning() {
        val s = WechatStay()
        s.onWindow(wx, moments, 0)
        // 朋友圈里点开大图：仍是朋友圈，计时不重来。
        assertNull(s.onWindow(wx, "com.tencent.mm.plugin.sns.ui.SnsBrowseUI", 2_000))
        assertEquals(WechatHit("moment", 5_000), s.onWindow(wx, chat, 5_000))
    }
}
