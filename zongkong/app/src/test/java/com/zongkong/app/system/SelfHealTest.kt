package com.zongkong.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHealTest {

    private val me = "com.zongkong.app/com.zongkong.app.guard.GuardService"
    private val yishou = "com.yishou.app/com.yishou.app.gate.GateService"
    private val third = "com.example.third/com.example.third.Service"

    @Test
    fun `keeps other services and puts me last`() {
        assertEquals("$yishou:$me", SelfHeal.enabledList(yishou, me))
    }

    @Test
    fun `does not duplicate me`() {
        assertEquals("$yishou:$me", SelfHeal.enabledList("$yishou:$me", me))
    }

    @Test
    fun `merge keeps a service added during the gap`() {
        assertEquals("$yishou:$third:$me", SelfHeal.merge("$yishou:$third", listOf(yishou), me))
    }

    @Test
    fun `short and full component names count as one service`() {
        val short = "com.yishou.app/.gate.GateService"
        assertEquals("$short:$me", SelfHeal.merge(short, listOf(yishou), me))
    }

    @Test
    fun `othersOf removes only me`() {
        assertEquals(listOf(yishou), SelfHeal.othersOf("$yishou:$me", me))
    }

    @Test
    fun `only toggles when no other accessibility service is present`() {
        assertTrue(SelfHeal.shouldToggle(emptyList()))
        org.junit.Assert.assertFalse(SelfHeal.shouldToggle(listOf(yishou)))
    }

    @Test
    fun `merge never drops an existing third service`() {
        val merged = SelfHeal.merge("$third:$yishou", emptyList(), me)
        assertTrue(merged.contains(third))
        assertTrue(merged.contains(yishou))
        assertTrue(merged.endsWith(me))
    }
}
