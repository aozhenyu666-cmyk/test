package cn.companion.pet.core.partner

import cn.companion.pet.core.voice.SayingLedger
import cn.companion.pet.core.voice.VoiceIntentQueue

/** Commit a successful output receipt once, using its final wording rather than its draft intent. */
class PartnerDelivery(
    private val governor: ProactiveGovernor,
    private val intents: VoiceIntentQueue,
    private val ledger: SayingLedger,
) {
    private val committed = LinkedHashSet<String>()
    fun confirm(receiptId: String, candidate: TopicCandidate, actualText: String, at: Long, delivered: Boolean): Boolean {
        if (!delivered || actualText.isBlank() || !committed.add(receiptId)) return false
        while (committed.size > 128) committed.remove(committed.first())
        governor.onShown(at)
        ledger.record(actualText, at)
        if (candidate.id.startsWith("vi-")) intents.markSpoken(candidate.id, at)
        return true
    }
}
