package com.zongkong.sentinel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故障注入：总控 `wake` 的各种答复 → [MainGuard.interpret] 的结论。
 *
 * 守的是一条底线（验收 T02 的延伸）：**证不了健康的都算不在**。特别是 `UNKNOWN`，
 * 以前被 deny-list 漏成了「在」，心跳被它一路续着，`Decide.STALE_MS` 的过期闸门永远等不到。
 * 纯 JVM，不碰 ContentResolver、不碰 Bundle、不需要 Robolectric。
 */
class MainGuardFaultInjectionTest {

    private fun v2(status: String) = mapOf<String, Any?>("healthStatus" to status, "alive" to true)

    // ---------- v2：只有 HEALTHY 算在 ----------

    @Test
    fun unknownIsNotGuardingAndMustNotRefreshHeartbeat() {
        // 这条是本次修的缺陷本体：UNKNOWN 曾经被当成「在」
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(v2("UNKNOWN")))
        // 顺带把「不能续心跳」这条说死：probe() 只在 guarding 时才 Store.refresh(...)
        assertFalse(MainGuard.interpret(v2("UNKNOWN")).guarding)
    }

    @Test
    fun suspectRecoveringPausedAreNotGuarding() {
        for (s in listOf("SUSPECT", "RECOVERING", "PAUSED")) {
            assertEquals("$s 不能算在", MainGuard.NOT_GUARDING, MainGuard.interpret(v2(s)))
            assertFalse("$s 不能续心跳", MainGuard.interpret(v2(s)).guarding)
        }
    }

    @Test
    fun disabledUnresponsiveUnregisteredAreNotGuarding() {
        for (s in listOf("DISABLED", "UNRESPONSIVE", "UNREGISTERED")) {
            assertEquals("$s 不能算在", MainGuard.NOT_GUARDING, MainGuard.interpret(v2(s)))
        }
    }

    @Test
    fun onlyHealthyIsGuarding() {
        assertEquals(MainGuard.GUARDING, MainGuard.interpret(v2("HEALTHY")))
        assertTrue(MainGuard.interpret(v2("HEALTHY")).guarding)
    }

    @Test
    fun unrecognisedStatusIsNotGuarding() {
        // 白名单：不认识的字符串一律不在（以前 deny-list 会把它算成在）
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(v2("WAT")))
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(v2("")))
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(mapOf<String, Any?>("healthStatus" to null)))
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(mapOf<String, Any?>("healthStatus" to 1)))
    }

    // ---------- 老版总控：没有 healthStatus，退回看 guard ----------

    @Test
    fun legacyGuardTrueIsGuarding() {
        assertEquals(MainGuard.GUARDING, MainGuard.interpret(mapOf<String, Any?>("guard" to true)))
    }

    @Test
    fun legacyGuardFalseOrMissingIsNotGuarding() {
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(mapOf<String, Any?>("guard" to false)))
        // 老版但没带 guard：证不了，就不算在
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(mapOf<String, Any?>("alive" to true)))
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(mapOf<String, Any?>("guard" to "true")))
    }

    @Test
    fun healthStatusWinsOverLegacyGuard() {
        // v2 说了 UNKNOWN，就别拿老字段给自己找台阶
        assertEquals(
            MainGuard.NOT_GUARDING,
            MainGuard.interpret(mapOf<String, Any?>("healthStatus" to "UNKNOWN", "guard" to true)),
        )
        assertEquals(
            MainGuard.GUARDING,
            MainGuard.interpret(mapOf<String, Any?>("healthStatus" to "HEALTHY", "guard" to false)),
        )
    }

    // ---------- 没有答复 / 答复里是错误 ----------

    @Test
    fun nullOrErrorReplyIsNotGuarding() {
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(null))
        assertEquals(
            MainGuard.NOT_GUARDING,
            MainGuard.interpret(mapOf<String, Any?>("error" to "只接受哨兵的连接")),
        )
        // error 优先于其它字段：带着错就一票否决
        assertEquals(
            MainGuard.NOT_GUARDING,
            MainGuard.interpret(mapOf<String, Any?>("error" to "不认识的请求", "healthStatus" to "HEALTHY")),
        )
    }

    @Test
    fun emptyReplyIsNotGuarding() {
        assertEquals(MainGuard.NOT_GUARDING, MainGuard.interpret(emptyMap<String, Any?>()))
    }
}
