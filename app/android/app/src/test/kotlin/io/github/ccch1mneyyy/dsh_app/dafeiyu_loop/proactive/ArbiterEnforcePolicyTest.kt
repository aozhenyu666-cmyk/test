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

        val released = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = strictDecision,
            pkg = "tv.danmaku.bili",
            neverLock = false,
            disarmed = false,
            shadow = false,
            released = true,
        )!!
        assertEquals("unified release (quiz/spend/bout-buffer) must open even under arbiterEnforce=true", false, released.shouldLock)
        assertEquals("released tag expected", "released", released.reasonTag)

        val systemPkg = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = strictDecision,
            pkg = "com.android.permissioncontroller",
            neverLock = false,
            disarmed = false,
            shadow = false,
            isSystem = true,
        )!!
        assertEquals("I1 isSystem must never lock even under arbiterEnforce=true", false, systemPkg.shouldLock)
        assertEquals("never_lock tag expected for system", "never_lock", systemPkg.reasonTag)
    }

    @Test
    fun defaultEnforceSwitchIsTrueAndExplicitFalseDowngradesSafely() {
        assertEquals("stage 1 #18: arbiterEnforce default must be true", true, ArbiterEnforcePolicy.DEFAULT_ENFORCE_ENABLED)
        assertEquals("unset pref must resolve to true", true, ArbiterEnforcePolicy.resolveEnforceSwitch(null))
        assertEquals("explicit true must resolve to true", true, ArbiterEnforcePolicy.resolveEnforceSwitch(true))
        assertEquals("explicit false must still allow downgrade", false, ArbiterEnforcePolicy.resolveEnforceSwitch(false))
    }

    @Test
    fun staleOrNullDecisionFallsBackToNullInsteadOfMisLocking() {
        val t0 = 1_800_000_000_000L
        assertNull(
            "null decision must return null for safe fallback",
            ArbiterEnforcePolicy.evaluateAt(
                arbiterEnforce = true,
                decision = null,
                receivedAt = t0,
                now = t0 + 1_000L,
                pkg = "tv.danmaku.bili",
                neverLock = false,
                disarmed = false,
                shadow = false,
            ),
        )
        assertNull(
            "decision older than 10m must return null for safe fallback",
            ArbiterEnforcePolicy.evaluateAt(
                arbiterEnforce = true,
                decision = strictDecision,
                receivedAt = t0,
                now = t0 + ArbiterDecision.FRESH_MS + 1L,
                pkg = "tv.danmaku.bili",
                neverLock = false,
                disarmed = false,
                shadow = false,
            ),
        )
        assertNull(
            "receivedAt=0 must return null",
            ArbiterEnforcePolicy.evaluateAt(
                arbiterEnforce = true,
                decision = strictDecision,
                receivedAt = 0L,
                now = t0,
                pkg = "tv.danmaku.bili",
                neverLock = false,
                disarmed = false,
                shadow = false,
            ),
        )
        val freshOutcome = ArbiterEnforcePolicy.evaluateAt(
            arbiterEnforce = true,
            decision = strictDecision,
            receivedAt = t0,
            now = t0 + ArbiterDecision.FRESH_MS,
            pkg = "tv.danmaku.bili",
            neverLock = false,
            disarmed = false,
            shadow = false,
        )!!
        assertEquals("fresh decision within 10m must still enforce", true, freshOutcome.shouldLock)
    }

    @Test
    fun freeModeAllowsAppsNotInDenyListSuchAsWechatWithinQuota() {
        val freeDecision = ArbiterDecision.fromWire(
            mapOf(
                "mode" to "free",
                "deny" to listOf(mapOf("app" to "tv.danmaku.bili", "why" to "今日娱乐额度已用完")),
                "why" to "自由态：微信 35m 额度内可用",
                "unlockHow" to "额度内自由使用",
                "priority" to "P6",
            ),
        )!!
        val wechat = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = freeDecision,
            pkg = "com.tencent.mm",
            neverLock = false,
            disarmed = false,
            shadow = false,
        )!!
        assertEquals("WeChat within quota must not lock", false, wechat.shouldLock)
        assertEquals("arbiter_open", wechat.reasonTag)

        val bili = ArbiterEnforcePolicy.evaluate(
            arbiterEnforce = true,
            decision = freeDecision,
            pkg = "tv.danmaku.bili",
            neverLock = false,
            disarmed = false,
            shadow = false,
        )!!
        assertEquals("denied app must lock with arbiter reason", true, bili.shouldLock)
        assertEquals("今日娱乐额度已用完", bili.why)
    }

    @Test
    fun focusAllowResolverMergesFreshArbiterAllowlistAheadOfLegacySessionAllow() {
        val arbiterP4 = ArbiterDecision.fromWire(
            mapOf(
                "mode" to "allowlist",
                "allow" to listOf("md.obsidian", "com.google.android.apps.bard", "moe.nb4a"),
                "why" to "P4 专注严格局",
                "unlockHow" to "完成局",
                "priority" to "P4",
            ),
        )!!
        val merged = FocusAllowResolver.mergePackages(
            arbiter = arbiterP4,
            sessionAllow = listOf("com.legacy.study"),
            sessionPkg = "com.legacy.task",
            focusTaskPkg = "com.focus.doc",
            focusAllowed = listOf("md.obsidian", "com.focus.extra"),
        ).toList()

        assertEquals(
            "ArbiterShadow work packages must appear first and merge without duplicates",
            listOf(
                "md.obsidian",
                "com.google.android.apps.bard",
                "moe.nb4a",
                "com.legacy.study",
                "com.legacy.task",
                "com.focus.doc",
                "com.focus.extra",
            ),
            merged,
        )

        val openDecision = ArbiterDecision.fromWire(
            mapOf(
                "mode" to "open",
                "allow" to listOf("*"),
                "priority" to "P0",
            ),
        )!!
        val mergedOpen = FocusAllowResolver.mergePackages(
            arbiter = openDecision,
            sessionAllow = emptyList(),
            sessionPkg = null,
            focusTaskPkg = null,
            focusAllowed = listOf("md.obsidian"),
        ).toList()
        assertEquals("Wildcard '*' in open mode must not leak into focus package list", listOf("md.obsidian"), mergedOpen)
    }
}
