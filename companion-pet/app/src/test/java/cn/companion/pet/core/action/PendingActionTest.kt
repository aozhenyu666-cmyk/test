package cn.companion.pet.core.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingActionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun approveIsOneTimeAndPersistsRejection() {
        val store = PendingActionStore(tmp.root)
        val item = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "虚构念头"), "保存念头",
            packageName = "com.test", now = 1_000)!!
        assertNotNull(store.approve(item.id, 1_100, "com.test"))
        assertNull(store.approve(item.id, 1_200, "com.test"))
        assertEquals(PendingStatus.APPROVED, PendingActionStore(tmp.root).list(1_300).single().status)
        val second = store.create(ActionKind.SPEAK, mapOf("text" to "读一句"), "朗读", now = 2_000)!!
        assertTrue(store.rejectOrCancel(second.id))
        assertEquals(PendingStatus.REJECTED, store.list(2_100).first { it.id == second.id }.status)
    }

    @Test
    fun foregroundSwitchAndExpiryReject() {
        val store = PendingActionStore(tmp.root)
        val item = store.create(ActionKind.TAP_NODE, mapOf("node_id" to "n1"), "点击", packageName = "com.test", now = 1_000)!!
        assertNull(store.approve(item.id, 1_100, "com.other"))
        val expired = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "x"), "保存", now = 2_000, ttlMillis = 100)!!
        assertNull(store.approve(expired.id, 2_200, null))
        assertEquals(PendingStatus.EXPIRED, store.list(2_300).first { it.id == expired.id }.status)
    }

    @Test
    fun finishRecordsResultOnlyForApproved() {
        val store = PendingActionStore(tmp.root)
        val item = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "x"), "保存", now = 1_000)!!
        val result = ActionResult("pending-" + item.id, item.kind, ActionStatus.SUCCESS, "已保存")
        assertFalse(store.finish(item.id, result))
        store.approve(item.id, 1_100)
        assertTrue(store.finish(item.id, result))
        assertEquals(PendingStatus.EXECUTED, store.list(1_200).single().status)
        assertEquals("已保存", store.list(1_200).single().result?.reason)
    }

    @Test
    fun approvedCanOnlyBeReopenedExplicitly() {
        val store = PendingActionStore(tmp.root)
        val item = store.create(ActionKind.SAVE_NOTE, mapOf("text" to "x"), "保存", now = 1_000)!!
        assertNotNull(store.approve(item.id, 1_100))
        val reopened = store.reopen(item.id, 1_200)
        assertNotNull(reopened)
        assertEquals(PendingStatus.PENDING, reopened!!.status)
        assertNotNull(store.approve(item.id, 1_300))
    }
}