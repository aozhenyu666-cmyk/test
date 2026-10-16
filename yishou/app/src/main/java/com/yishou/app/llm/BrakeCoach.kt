package com.yishou.app.llm

import com.yishou.app.brake.Brake

/** 急刹的那把手术刀：一秒把原话切成事实和脑补，说出最大那条脑补要什么证据。不讲道理。 */
interface BrakeCoach {
    suspend fun split(raw: String): LlmResult<Cut>

    data class Cut(val split: Brake.Split, val proof: String)
}

object BrakePrompts {
    val SYSTEM = """
        用户正处在冲动上头的时刻，下面是他刚倒出来的原话。不要安慰，不要讲道理，不要给建议。只做切分：
        【事实】摄像机能拍到、录音能录到的部分，每条一行，用他的原话，不加推测
        【脑补】他对别人想法的猜测、对未来的断言、“应该”“一定”“就是故意”这类，每条一行，用他的原话
        【证据】要证明最大那条脑补是真的，必须有的一个硬证据（谁、何时、做了什么、说了什么原话），一句话，不超过 30 字
        原话里没有的东西不要写。
    """.trimIndent()

    val NAMES = mapOf("事实" to "facts", "脑补" to "fantasies", "证据" to "proof")

    fun parse(text: String): BrakeCoach.Cut? {
        val o = Tagged.toJson(text, NAMES, lists = setOf("facts", "fantasies"))
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            .filter { it.isNotEmpty() && it != "无" }
        val fan = list("fantasies")
        val fact = list("facts")
        if (fan.isEmpty() && fact.isEmpty()) return null
        return BrakeCoach.Cut(Brake.Split(fact, fan), o.optString("proof").trim().takeIf { it != "无" }.orEmpty())
    }
}

class LlmBrakeCoach(private val chat: ChatClient) : BrakeCoach {
    override suspend fun split(raw: String): LlmResult<BrakeCoach.Cut> =
        when (val r = chat.complete(BrakePrompts.SYSTEM, raw.take(600), "急刹", json = false)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> BrakePrompts.parse(r.value)?.let { LlmResult.Ok(it) } ?: LlmResult.Err(LlmError.Local("没拆出来，用本机的拆法"))
        }
}
