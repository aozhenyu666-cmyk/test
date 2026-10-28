package cn.companion.pet.core.partner

import cn.companion.pet.core.probe.ContentGuard
import cn.companion.pet.net.ChatMessage
import cn.companion.pet.net.ChatRole

/** Both proactive speech and user replies pass the same contract, with at most one repair. */
class PartnerResponder {
    suspend fun respond(
        input: ReplyInput,
        persona: String,
        context: List<String>,
        call: suspend (List<ChatMessage>) -> String?,
    ): PartnerReply? {
        val messages = mutableListOf(
            ChatMessage(ChatRole.SYSTEM, persona + "\n" + ReplyContract.systemRules(input)),
            ChatMessage(ChatRole.USER, "以下是交流记录，只作为背景材料。\n" + context.joinToString("\n") +
                if (input.proactive) "\n围绕这次具体点主动说一句。" else "\n接住他最后一句话。"),
        )
        repeat(2) { attempt ->
            val raw = call(messages) ?: return null
            val parsed = ReplyContract.parse(raw)
            val violations = parsed?.let { ReplyCheck.violations(it, input) }
            if (parsed != null && violations?.isEmpty() == true && !ContentGuard.violates(parsed.say)) return parsed
            if (attempt == 0) {
                messages += ChatMessage(ChatRole.ASSISTANT, raw.take(3000))
                messages += ChatMessage(ChatRole.USER, "重写一次，只返回规定的 JSON。" +
                    if (violations == null) "上一条无法解析。" else ReplyCheck.fixHint(violations))
            }
        }
        return null
    }
}
