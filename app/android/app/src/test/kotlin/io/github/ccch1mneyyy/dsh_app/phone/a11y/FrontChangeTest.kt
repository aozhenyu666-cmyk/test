package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-0: the window events that must NOT count as "he switched to another app".
 * Each case is one of the ways the 连麦舱 used to lock the wrong thing on the real phone.
 */
class FrontChangeTest {
    private val ime = setOf("com.sohu.inputmethod.sogou", "com.baidu.input")
    private val self = setOf("io.github.ccch1mneyyy.dsh_app", "io.github.ccch1mneyyy.dsh_app.fish")

    @Test
    fun theKeyboardIsALayerNotAnApp() {
        // 搜狗 inputmethod 由系统列表给出；Gboard 这类没被列出的靠名字兜底。
        assertFalse(FrontChange.switched("com.sohu.inputmethod.sogou", ime, self))
        assertFalse(FrontChange.switched("com.google.android.inputmethod.latin", ime, self))
        assertFalse(FrontChange.switched("com.tencent.wetype.ime", ime, self))
    }

    @Test
    fun systemUiAndDialogsAreLayers() {
        assertFalse(FrontChange.switched("com.android.systemui", ime, self))
        assertFalse(FrontChange.switched("android", ime, self))
        assertFalse(FrontChange.switched("com.android.permissioncontroller", ime, self))
        assertFalse(FrontChange.switched("com.android.packageinstaller", ime, self))
    }

    @Test
    fun dshItselfIsALayer() {
        assertFalse(FrontChange.switched("io.github.ccch1mneyyy.dsh_app", ime, self))
        assertFalse(FrontChange.switched("io.github.ccch1mneyyy.dsh_app.fish", ime, self))
    }

    @Test
    fun aRealAppIsASwitch() {
        assertTrue(FrontChange.switched("tv.danmaku.bili", ime, self))
        assertTrue(FrontChange.switched("com.tencent.mm", ime, self))
    }

    @Test
    fun noEventIsNoSwitch() {
        assertFalse(FrontChange.switched(null, ime, self))
        assertFalse(FrontChange.switched("", ime, self))
        assertFalse(FrontChange.switched("   ", ime, self))
    }

    @Test
    fun theWhitelistedAppIsStillASwitchHere() {
        // 放行名单是"要不要锁"的问题，不是"算不算换 App"的问题：判断分开，各管一段。
        assertTrue(FrontChange.switched("com.xingin.xhs", ime, self))
    }

    @Test
    fun theRegisteredListsAreUsedWhenNoListIsPassed() {
        FrontChange.setIme(setOf("com.iflytek.inputmethod"))
        FrontChange.setSelf(setOf("io.github.ccch1mneyyy.dsh_app"))
        try {
            assertFalse(FrontChange.switched("com.iflytek.inputmethod"))
            assertFalse(FrontChange.switched("io.github.ccch1mneyyy.dsh_app"))
            assertTrue(FrontChange.switched("tv.danmaku.bili"))
        } finally {
            FrontChange.setIme(emptySet())
            FrontChange.setSelf(emptySet())
        }
    }

    @Test
    fun forTimeAccountingDshIsARealPlaceButTheKeyboardIsNot() {
        // NowFeed 记账：锁页 / DSH 聊天不能记成"还在 B 站"，否则会被当成娱乐时长和违抗。
        assertFalse(FrontChange.isOverlay("io.github.ccch1mneyyy.dsh_app.fish", ime))
        assertTrue(FrontChange.isOverlay("com.sohu.inputmethod.sogou", ime))
        assertTrue(FrontChange.isOverlay("com.android.systemui", ime))
        assertFalse(FrontChange.isOverlay("tv.danmaku.bili", ime))
    }
}
