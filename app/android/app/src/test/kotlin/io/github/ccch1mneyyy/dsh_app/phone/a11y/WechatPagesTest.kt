package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D5 微信误入识别：只认微信自己的页面类名，认不出来就不管（这一条不许升级成违抗）。 */
class WechatPagesTest {
    @Test
    fun theThreePagesTheContractNamesAreRecognised() {
        assertEquals("finder", WechatPages.page("com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI"))
        assertEquals("moment", WechatPages.page("com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"))
        assertEquals("novel", WechatPages.page("com.tencent.mm.plugin.readerapp.ui.ReaderAppUI"))
    }

    @Test
    fun theChatPageIsNotAHitItIsTheEndOfOne() {
        val chat = "com.tencent.mm.ui.LauncherUI"
        assertNull(WechatPages.page(chat))
        assertTrue(WechatPages.isChat(chat))
        assertTrue(WechatPages.isChat("com.tencent.mm.ui.chatting.ChattingUI"))
        // 别的页面既不是误入、也不算"回到聊天页"，所以什么都不收尾。
        assertFalse(WechatPages.isChat("com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"))
        assertNull(WechatPages.page(null))
        assertFalse(WechatPages.isChat(null))
    }

    @Test
    fun threeSecondsIsTheLineBetweenAPeekAndAHit() {
        assertEquals(3_000L, WechatPages.DWELL_MS)
        assertEquals("com.tencent.mm", WechatPages.PKG)
    }
}
