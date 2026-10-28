package com.yishou.app.gate

import org.junit.Assert.*
import org.junit.Test

class PageDeliveryTest {
    @Test fun silentLaunchDenialNeedsVisibleFallbackOnlyOnce() {
        val delivery = PageDelivery()
        delivery.request(100)
        assertEquals(PageDelivery.Result.WAITING, delivery.observe(1099, false, true))
        assertEquals(PageDelivery.Result.FALLBACK, delivery.observe(1100, false, true))
        assertEquals(PageDelivery.Result.WAITING, delivery.observe(2100, false, true))
        assertEquals(PageDelivery.Result.VISIBLE, delivery.observe(2200, true, true))
        assertEquals(PageDelivery.Result.CANCELLED, delivery.observe(2300, true, true))
    }

    @Test fun leavingOrDisablingRuleCancelsPendingPage() {
        val delivery = PageDelivery()
        delivery.request(100)
        assertEquals(PageDelivery.Result.CANCELLED, delivery.observe(1200, false, false))
        assertEquals(PageDelivery.Result.CANCELLED, delivery.observe(1300, false, true))
    }

    @Test fun newTargetGetsItsOwnDeadline() {
        val delivery = PageDelivery()
        delivery.request(100)
        delivery.request(1000)
        assertEquals(PageDelivery.Result.WAITING, delivery.observe(1100, false, true))
        assertEquals(PageDelivery.Result.FALLBACK, delivery.observe(2000, false, true))
    }
}

