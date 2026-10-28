package cn.companion.pet.core.partner

/** A topic is allowed even if it contains “计划/任务”; reject guilt pressure, not the user's subject. */
object PartnerLanguage {
    fun violates(text: String): Boolean = listOf("你又不理我", "你不理我我就", "不回复我就", "都是你的错", "你让我好难过")
        .any { it in text }
}
