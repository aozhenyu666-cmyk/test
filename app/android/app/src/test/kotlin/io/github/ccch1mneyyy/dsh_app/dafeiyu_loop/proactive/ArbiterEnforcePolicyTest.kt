package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ArbiterDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArbiterEnforcePolicyTest {
    private val strictDecision = ArbiterDecision.fromWire(
        mapOf(
            "mode" to "allowlist",
            "allow" to listOf("md.obsidian", "com.baidu.netdisk"),
            "needPass" to listOf("com.android.chrome"),
            "why" to "P2.5 接管严格保护中",
            "unlockHow" to "完成当前局或打开百度网盘回位 3 分钟",
            "priority" to "P2.5",
        ),
    )!!

    @Test
    fun offSwitchReturnsNullToPreserveLegacyBehavior() {
        val outcome = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = false,
            decision = strictDecision,
            pkg = "tv.danmaku.bili",
            neverLock = false,
            disarmed = false,
            shadow = false,
        )
        assertNull("arbiterEnforce=false must return null so legacy enforcement runs untouched", outcome)
    }

    @Test
    fun enforceTrueAppliesArbiterDecisionWithWhyAndUnlockHow() {
        val blocked = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = strictDecision,
            pkg = "tv.danmaku.bili",
            neverLock = false,
            disarmed = false,
            shadow = false,
        )!!
        assertEquals("non-allowlisted app must be locked", true, blocked.shouldLock)
        assertEquals("why must match arbiter decision", "P2.5 接管严格保护中", blocked.why)
        assertEquals("unlockHow must match arbiter decision", "完成当前局或打开百度网盘回位 3 分钟", blocked.unlockHow)
        assertEquals("reasonTag must be arbiter_lock", "arbiter_lock", blocked.reasonTag)

        val allowed = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = strictDecision,
            pkg = "md.obsidian",
            neverLock = false,
            disarmed = false,
            shadow = false,
        )!!
        assertEquals("allowlisted app must not be locked", false, allowed.shouldLock)
        assertEquals("reasonTag must be arbiter_open", "arbiter_open", allowed.reasonTag)
    }

    @Test
    fun neverLockShadowAndDisarmAlwaysOverrideArbiterEnforce() {
        val neverLocked = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = strictDecision,
            pkg = "com.bbk.launcher2",
            neverLock = true,
            disarmed = false,
            shadow = false,
        )!!
        assertEquals("I1 neverLock must never lock even under arbiterEnforce=true", false, neverLocked.shouldLock)
        assertEquals("never_lock tag expected", "never_lock", neverLocked.reasonTag)

        val disarmed = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = strictDecision,
            pkg = "tv.danmaku.bili",
            neverLock = false,
            disarmed = true,
            shadow = false,
        )!!
        assertEquals("I4 emergency disarm must never lock even under arbiterEnforce=true", false, disarmed.shouldLock)
        assertEquals("disarmed tag expected", "disarmed", disarmed.reasonTag)

        val shadowed = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = strictDecision,
            pkg = "tv.danmaku.bili",
            neverLock = false,
            disarmed = false,
            shadow = true,
        )!!
        assertEquals("test-mode shadow must defer real lock even under arbiterEnforce=true", false, shadowed.shouldLock)
        assertEquals("shadow tag expected", "shadow", shadowed.reasonTag)
    }
}
