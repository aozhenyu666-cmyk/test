package cn.companion.pet.core.partner

import cn.companion.pet.core.companion.CompanionEvent

/** Chat history may feed a diary; it must not bypass the explicit long-term-memory question. */
object PartnerConsent {
    fun nightlyProfile(proposed: List<String>, existing: List<String>, events: List<CompanionEvent>): List<String> =
        if (events.any { it.source == "partner" || it.source.startsWith("partner:") }) existing else proposed
}
