package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-18 锁机事故：插件把「当前前台」当锁目标，前台是桌面 / DSH 自己（锁页本身）时锁了桌面和自己。
 * 所有落锁入口都先过 [SystemSafeAllowlist.neverLock]；这里钉死哪些包永远不能被锁。
 */
class NeverLockGateTest {
    private val own = "io.github.ccch1mneyyy.dsh_app.fish"

    @Test
    fun `iQOO desktop, DSH itself, system ui, ime and NekoBox are never locked`() {
        val never = listOf(
            "com.bbk.launcher2",
            "com.vivo.upslide",
            "com.vivo.globalsearch",
            "com.iqoo.secure",
            "com.android.systemui",
            own,
            "io.github.ccch1mneyyy.dsh_app",
            "moe.nb4a",
            "com.sohu.inputmethod.sogou.vivo",
            "com.android.dialer",
            "com.eg.android.AlipayGphone",
            "some.vendor.launcher",
            "",
        )
        for (p in never) assertTrue(p, SystemSafeAllowlist.neverLock(p, own))
        assertTrue(SystemSafeAllowlist.neverLock(null, own))
    }

    @Test
    fun `runtime-resolved home and enabled ime are honoured`() {
        assertTrue(SystemSafeAllowlist.neverLock("com.example.mylauncherx", own, home = setOf("com.example.mylauncherx")))
        assertTrue(SystemSafeAllowlist.neverLock("com.example.typing", own, ime = setOf("com.example.typing")))
    }

    @Test
    fun `entertainment apps are still lockable`() {
        for (p in listOf("com.tencent.tmgp.sgame", "tv.danmaku.bili", "com.ss.android.ugc.aweme", "com.kuaishou.nebula")) {
            assertFalse(p, SystemSafeAllowlist.neverLock(p, own))
        }
    }
}
