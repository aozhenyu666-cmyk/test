package com.yishou.app.dsh

import org.junit.Assert.*
import org.junit.Test

class DshHandoffTest {
    @Test fun verifiedDshGrantCanBeSent() {
        assertTrue(DshHandoff.shouldSend(true, true, false, "DSH Go"))
    }

    @Test fun ordinaryGateDoesNotSend() {
        assertFalse(DshHandoff.shouldSend(false, true, false, "娱乐"))
    }

    @Test fun unverifiedGrantDoesNotSend() {
        assertFalse(DshHandoff.shouldSend(true, false, false, "DSH Go"))
    }

    @Test fun testAndMorningGrantsDoNotSend() {
        assertFalse(DshHandoff.shouldSend(true, true, true, "DSH Go"))
        assertFalse(DshHandoff.shouldSend(true, true, false, "早上"))
    }

    @Test fun activeWindowExplainsWhyHandoffIsBlocked() {
        assertEquals("陪练窗口进行中，这次不能在一手答题放行", DshHandoff.blockedReason(true, false, false))
    }

    @Test fun unclosedPartnerExplainsWhyHandoffIsBlocked() {
        assertEquals("上次伴读还没收线，这次不能在一手答题放行", DshHandoff.blockedReason(false, true, false))
    }

    @Test fun recallOnlyExplainsWhyHandoffIsBlocked() {
        assertEquals("一手现在没有可出的题（题库空、也没配模型）", DshHandoff.blockedReason(false, false, true))
    }

    @Test fun availableQuestionDoesNotBlockHandoff() {
        assertNull(DshHandoff.blockedReason(false, false, false))
    }

    private fun extras(): MutableMap<String, Any> = mutableMapOf(
        DshHandoff.EXTRA_VERSION to 1,
        DshHandoff.EXTRA_EVENT_ID to "event-17",
        DshHandoff.EXTRA_NONCE to "0123456789abcdef0123456789abcdef",
        DshHandoff.EXTRA_TARGET_PACKAGE to "tv.danmaku.bili",
        DshHandoff.EXTRA_TARGET_LABEL to "哔哩哔哩",
        DshHandoff.EXTRA_REPLY_PACKAGE to "io.github.ccch1mneyyy.dsh_app",
    )

    @Test fun completeRequestKeepsAllFiveFields() {
        val req = DshHandoff.parseRequest(extras()::get)!!
        assertEquals("event-17", req.eventId)
        assertEquals("0123456789abcdef0123456789abcdef", req.nonce)
        assertEquals("tv.danmaku.bili", req.targetPackage)
        assertEquals("哔哩哔哩", req.targetLabel)
        assertEquals("io.github.ccch1mneyyy.dsh_app", req.replyPackage)
    }

    @Test fun wrongVersionIsRejected() {
        assertNull(DshHandoff.parseRequest(extras().apply { put(DshHandoff.EXTRA_VERSION, 2) }::get))
    }

    @Test fun shortNonceIsRejected() {
        assertNull(DshHandoff.parseRequest(extras().apply { put(DshHandoff.EXTRA_NONCE, "12345678") }::get))
    }

    @Test fun unrelatedReplyPackageIsRejected() {
        assertNull(DshHandoff.parseRequest(extras().apply { put(DshHandoff.EXTRA_REPLY_PACKAGE, "com.evil.app") }::get))
    }

    @Test fun suffixedDshPackageIsAccepted() {
        val req = DshHandoff.parseRequest(extras().apply {
            put(DshHandoff.EXTRA_REPLY_PACKAGE, "io.github.ccch1mneyyy.dsh_app.r4")
        }::get)!!
        assertEquals("io.github.ccch1mneyyy.dsh_app.r4", req.replyPackage)
    }

    @Test fun missingLabelFallsBackToPackage() {
        val req = DshHandoff.parseRequest(extras().apply { remove(DshHandoff.EXTRA_TARGET_LABEL) }::get)!!
        assertEquals(req.targetPackage, req.targetLabel)
    }

    @Test fun passKeepsCorrelationAndClampsMinutes() {
        val req = DshHandoff.parseRequest(extras()::get)!!
        for ((minutes, expected) in listOf(500 to 120, 0 to 1, 15 to 15)) {
            val pass = DshHandoff.passExtras(req, minutes, "pass:17")
            assertEquals(1, pass[DshHandoff.EXTRA_VERSION])
            assertEquals(req.eventId, pass[DshHandoff.EXTRA_EVENT_ID])
            assertEquals(req.nonce, pass[DshHandoff.EXTRA_NONCE])
            assertEquals(expected, pass[DshHandoff.EXTRA_PASS_MINUTES])
            assertEquals("pass:17", pass[DshHandoff.EXTRA_REF])
            assertEquals(5, pass.size)
        }
    }

    @Test fun missingOrWronglyTypedRequiredFieldsAreRejected() {
        for (key in listOf(DshHandoff.EXTRA_VERSION, DshHandoff.EXTRA_EVENT_ID, DshHandoff.EXTRA_NONCE,
            DshHandoff.EXTRA_TARGET_PACKAGE, DshHandoff.EXTRA_REPLY_PACKAGE)) {
            assertNull(DshHandoff.parseRequest(extras().apply { remove(key) }::get))
            assertNull(DshHandoff.parseRequest(extras().apply { put(key, false) }::get))
        }
    }

    @Test fun fieldLimitsAndReplyPrefixAreChecked() {
        for ((key, value) in listOf(
            DshHandoff.EXTRA_EVENT_ID to " ", DshHandoff.EXTRA_EVENT_ID to "e".repeat(65),
            DshHandoff.EXTRA_NONCE to "n".repeat(65), DshHandoff.EXTRA_TARGET_PACKAGE to " ",
            DshHandoff.EXTRA_TARGET_PACKAGE to "p".repeat(256),
            DshHandoff.EXTRA_REPLY_PACKAGE to "io.github.ccch1mneyyy.dsh_appfake",
        )) assertNull(DshHandoff.parseRequest(extras().apply { put(key, value) }::get))
        assertEquals(64, DshHandoff.parseRequest(extras().apply {
            put(DshHandoff.EXTRA_TARGET_LABEL, "标".repeat(65))
        }::get)!!.targetLabel.length)
        assertEquals(120, DshHandoff.passExtras(DshHandoff.parseRequest(extras()::get)!!,
            15, "记".repeat(121))[DshHandoff.EXTRA_REF].toString().length)
    }
}
