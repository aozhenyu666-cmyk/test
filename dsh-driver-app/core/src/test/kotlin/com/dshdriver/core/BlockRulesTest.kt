package com.dshdriver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockRulesTest {
    private val bili = "tv.danmaku.bili"
    private val rule = BlockRules(
        blocked = setOf(bili),
        always = setOf("com.android.systemui", "com.dshdriver.app"),
    )

    @Test fun outsideListAlwaysAllowed() {
        assertEquals(Verdict.ALLOW, rule.decide("com.google.android.apps.bard", 1_000))
        assertEquals(Verdict.ALLOW, rule.decide("com.openai.chatgpt", 1_000))
    }

    @Test fun listedWithoutPassIsBlocked() {
        assertEquals(Verdict.BLOCK, rule.decide(bili, 1_000))
    }

    @Test fun allowWindowIsHonoured() {
        val r = rule.allow(bili, 1_000 + 10 * 60_000)
        assertEquals(Verdict.ALLOW, r.decide(bili, 1_000 + 60_000))
    }

    @Test fun expiresExactlyAtDeadline() {
        val r = rule.allow(bili, 5_000)
        assertEquals(Verdict.ALLOW, r.decide(bili, 4_999))
        // 到点即过期：不该多放一秒
        assertEquals(Verdict.BLOCK, r.decide(bili, 5_000))
        assertEquals(Verdict.BLOCK, r.decide(bili, 5_001))
    }

    @Test fun alwaysAllowedBeatsBlockList() {
        val r = rule.withBlocked(setOf(bili, "com.android.systemui", "com.dshdriver.app"))
        assertEquals(Verdict.ALLOW, r.decide("com.android.systemui", 1_000))
        assertEquals(Verdict.ALLOW, r.decide("com.dshdriver.app", 1_000))
        assertEquals(Verdict.BLOCK, r.decide(bili, 1_000))
    }

    @Test fun blankPackageIsAllowed() {
        assertEquals(Verdict.ALLOW, rule.decide(null, 1_000))
        assertEquals(Verdict.ALLOW, rule.decide("", 1_000))
        assertEquals(Verdict.ALLOW, rule.decide("   ", 1_000))
    }

    @Test fun allowReturnsNewInstance() {
        val passed = rule.allow(bili, 9_000)
        assertEquals(Verdict.BLOCK, rule.decide(bili, 1_000))
        assertEquals(Verdict.ALLOW, passed.decide(bili, 1_000))
        assertNull(rule.expiry(bili))
        assertEquals(9_000L, passed.expiry(bili))
    }

    @Test fun pruneDropsExpiredOnly() {
        val r = rule.allow(bili, 5_000).allow("com.other", 50_000)
        val pruned = r.prune(10_000)
        assertNull(pruned.expiry(bili))
        assertEquals(50_000L, pruned.expiry("com.other"))
    }

    @Test fun neverBlockCoversTheDangerousOnes() {
        val r = BlockRules(blocked = BlockRules.NEVER_BLOCK, always = BlockRules.NEVER_BLOCK)
        BlockRules.NEVER_BLOCK.forEach { assertEquals(Verdict.ALLOW, r.decide(it, 1_000)) }
    }
}
