package com.yishou.app.partner

import com.yishou.app.llm.Faces
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.Tagged

data class Turn(val who: String, val text: String)

data class DirectorInput(
    val plan: PartnerPlan,
    val elapsedMin: Int,
    val transcript: List<Turn>,
    val missed: Int,
    val lastClose: String?,
    val memory: List<String>,
)

data class DirectorCard(
    val next: String,
    val face: Int,
    val gear: Gear,
    val hook: String,
    val close: Boolean,
) {
    init { require(face in 1..6) }
}

interface Director {
    suspend fun card(input: DirectorInput): LlmResult<DirectorCard>
}

object Directors {
    private val tags = setOf("下一步", "问法", "强度", "钩子", "收不收")
    private val hooks = listOf(
        "先说条件，还是先说结论？",
        "你押成立，还是不成立？",
        "它像旧例，还是正好相反？",
        "缺的是条件，还是依据？",
        "两者是因果，还是并列？",
        "上次那条，这里适用还是不适用？",
    )

    fun parse(text: String, input: DirectorInput): DirectorCard? {
        // 错行或多余字段不猜，交给本机卡，免得把说明当成下一步。
        if (text.lineSequence().count { it.isNotBlank() } != 5) return null
        val sections = Tagged.sections(text, tags)
        if (sections.keys != tags || sections.values.any { it.size != 1 || it.single().isBlank() }) return null
        fun value(tag: String) = sections.getValue(tag).single().trim()
        val face = Faces.ALL.firstOrNull { it.short == value("问法") }?.number ?: return null
        val gear = Gear.entries.firstOrNull { it.label == value("强度") } ?: return null
        val close = value("收不收")
        if (!close.contains("收")) return null
        val hook = value("钩子")
        return DirectorCard(
            next = value("下一步"),
            face = face,
            gear = gear,
            hook = hook.substring(0, hook.offsetByCodePoints(0, minOf(25, hook.codePointCount(0, hook.length)))),
            close = due(input) || (close.contains("收") && !close.contains("不收")),
        )
    }

    /** 按已过分钟轮换，重试同一份输入仍得到同一张卡，不在失败时悄悄加压。 */
    fun local(input: DirectorInput): DirectorCard {
        val face = input.elapsedMin.coerceAtLeast(0) % 6 + 1
        val close = due(input)
        return DirectorCard(
            next = if (close) "收线：问今天聊出了什么，再问下一步做什么" else Faces.of(face)!!.action,
            face = face,
            gear = input.plan.gear,
            hook = if (close) "一句话，今天聊出了什么？" else hooks[face - 1],
            close = close,
        )
    }

    /** 只交给文字模型的内部上下文；调用方不能拿这段去朗读。 */
    fun context(card: DirectorCard): String = """
        【内部导演卡】只用于决定下一句，不复述卡或标签。
        【下一步】${card.next}
        【问法】${Faces.of(card.face)!!.short}
        【强度】${card.gear.label}
        【钩子】${card.hook}
        【收不收】${if (card.close) "收" else "不收"}
    """.trimIndent()

    private fun due(input: DirectorInput) = input.elapsedMin >= input.plan.minutes
}
