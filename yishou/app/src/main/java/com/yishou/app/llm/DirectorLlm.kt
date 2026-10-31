package com.yishou.app.llm

import com.yishou.app.partner.Director
import com.yishou.app.partner.DirectorCard
import com.yishou.app.partner.DirectorInput
import com.yishou.app.partner.Directors
import com.yishou.app.partner.Material

object DirectorPrompts {
    val SYSTEM = """
        你是「一手」语音伙伴的导演。你不直接和用户说话；你写一张很短的导演卡，交给普通文字对话模型生成伙伴的下一句话，再由独立朗读接口播出。
        用户的问题：看东西停在第一反应，推理到一半断掉，不会自己提问，容易被别的东西勾走。伙伴的工作是让他自己动脑，不替他想完。
        根据【计划】【已经过了】【最近对话】【没接次数】【上次收线】【记忆】，只输出下面五行：
        【下一步】伙伴接下来要推的一件事，一句话
        【问法】从六面里挑一个：精简 / 预判 / 联想 / 提问 / 体系 / 回响
        【强度】陪 / 诱 / 严（没接次数达到 2 时，改用二选一或打赌；用户自己说要逼紧时用严）
        【钩子】一句可以直接说出口的话，口语，不超过 25 字，优先二选一、打赌、“如果……会怎样”
        【收不收】到点或用户说要结束时写“收”，否则写“不收”
        不要替用户总结，不要给答案，不要写这五行以外的内容。
    """.trimIndent()

    fun user(input: DirectorInput): String = buildString {
        val material = when (input.plan.material) {
            Material.VIDEO -> "网课"
            Material.ARTICLE -> "文章"
            Material.DRILL -> "做题"
            Material.CHESS -> "象棋"
            Material.OTHER -> "其他"
        }
        appendLine("【计划】材料：$material；目标：${input.plan.goal}；时长：${input.plan.minutes} 分钟；强度：${input.plan.gear.label}")
        appendLine("【已经过了】${input.elapsedMin} 分钟")
        appendLine("【最近对话】")
        input.transcript.takeLast(12).forEach { appendLine("${it.who}：${it.text}") }
        appendLine("【没接次数】${input.missed}")
        appendLine("【上次收线】${input.lastClose?.takeIf { it.isNotBlank() } ?: "无"}")
        appendLine("【记忆】")
        input.memory.take(5).forEach { appendLine(it) }
    }.trimEnd()
}

/** 注入的 ChatClient 默认沿用文字配置；不为导演另存一份密钥。 */
class LlmDirector(private val chat: ChatClient) : Director {
    override suspend fun card(input: DirectorInput): LlmResult<DirectorCard> {
        val response = chat.complete(DirectorPrompts.SYSTEM, DirectorPrompts.user(input), "导演", json = false)
        val card = when (response) {
            is LlmResult.Ok -> Directors.parse(response.value, input)
            is LlmResult.Err -> null
        }
        // 取消由 ChatClient 继续抛出，不把停止会话伪装成一次成功的兜底。
        return LlmResult.Ok(card ?: Directors.local(input))
    }
}
