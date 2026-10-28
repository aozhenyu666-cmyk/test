package cn.companion.pet.core.focus

import cn.companion.pet.core.training.ActionReceipt
import cn.companion.pet.core.training.ActionReceiptState

/** A request receipt is never an observation. Evidence is session-local and expires. */
class FocusActionEvidence(private val freshnessMillis: Long = 30_000) {
    private var session = ""
    private var sequence = 0L
    private val receipts = linkedMapOf<String, ActionReceipt>()

    fun reset(sessionId: String) { session = sessionId; receipts.clear(); sequence++ }

    fun requested(capability: String, now: Long): String {
        val id = session + ":" + (++sequence)
        receipts[id] = ActionReceipt(id, capability, ActionReceiptState.REQUESTED, now, "动作已请求，等待执行")
        while (receipts.size > 16) receipts.remove(receipts.keys.first())
        return id
    }

    fun reported(id: String, accepted: Boolean, now: Long, detail: String) {
        val old = receipts[id] ?: return
        if (old.state !in setOf(ActionReceiptState.REQUESTED, ActionReceiptState.DISPATCHED)) return
        receipts[id] = old.copy(state = if (accepted) ActionReceiptState.API_REPORTED_SUCCESS else ActionReceiptState.FAILED,
            at = now, detail = detail, observed = false)
    }

    fun observed(id: String, confirmed: Boolean?, now: Long, detail: String) {
        val old = receipts[id] ?: return
        if (old.state == ActionReceiptState.FAILED) return
        receipts[id] = old.copy(state = when (confirmed) {
            true -> ActionReceiptState.OBSERVED_CONFIRMED
            false -> ActionReceiptState.FAILED
            null -> ActionReceiptState.UNKNOWN
        }, at = now, detail = detail, observed = confirmed == true)
    }

    fun latest(now: Long): ActionReceipt? = history(now).lastOrNull()

    fun history(now: Long): List<ActionReceipt> = receipts.values.map {
        if (now < it.at || now - it.at > freshnessMillis) it.copy(state = ActionReceiptState.UNKNOWN,
            observed = false, detail = "这次动作证据已过期：" + it.detail) else it
    }
}

/** Read after the UI draw, independently of WindowManager.addView's return value. */
data class BlockObservation(val attached: Boolean, val drawn: Boolean, val fillsScreen: Boolean)
