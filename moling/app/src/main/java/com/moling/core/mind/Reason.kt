package com.moling.core.mind

/**
 * 本地推理：一条事件 + 局面 → 值不值得提一件事。纯函数，不花钱。
 *
 * 输出里把“看到了什么”和“猜是怎么回事”分开（假设账本），并且总带着别的解释——
 * 没收到产出不等于没做事，在购物 App 里也可能只是买菜。猜测只换来一个问题，不换来惩罚。
 */
data class Assessment(
    val topic: String,
    val priority: Int,
    val card: Card,
    val hypothesis: Hypothesis? = null,
    val incident: Incident? = null,
    /** 绕过“能不能打扰”的限制（只给健康类）。 */
    val urgent: Boolean = false,
)

object Reason {
    private const val HOUR = 3_600_000L

    fun assess(sense: Sense, s: Situation, mind: MindState, cfg: DirectorConfig): Assessment? {
        val f = s.facts
        val now = sense.at
        return when (sense.kind) {
            SenseKind.HEALTH -> health(sense, now)
            SenseKind.PLAN_DUE, SenseKind.LONG_ENTERTAINMENT, SenseKind.TICK, SenseKind.FOREGROUND -> stall(s, now, cfg) ?: idle(sense, s, now, cfg)
            SenseKind.IDLE_START -> idle(sense, s, now, cfg)
            SenseKind.UNLOCK -> resume(mind, f, now)
            else -> null
        }
    }

    /** 主线过点没开始，而且在娱乐（或者过点很久）。 */
    private fun stall(s: Situation, now: Long, cfg: DirectorConfig): Assessment? {
        val f = s.facts
        if (f.mainTitle.isBlank() || s.mainLateMin < cfg.lateMin) return null
        if (!s.playing && s.mainLateMin < cfg.lateMin * 3) return null
        val topic = "stall:${f.mainKey.ifBlank { f.mainTitle }}"
        val fact = buildString {
            append("「${f.mainTitle}」计划时间过了 ${s.mainLateMin} 分钟，没有开始")
            if (s.playing) append("；${f.foregroundLabel.ifBlank { "娱乐应用" }}用了 ${f.foregroundMinutes} 分钟")
        }
        val h = Hypothesis(
            id = "h-$now",
            topic = topic,
            fact = fact,
            guess = "这件事启动阻力大，或者卡在某一步",
            alternatives = listOf("在休息，打算待会儿做", "在做别的正事", "在吃饭或处理生活的事", "计划时间本身不合适"),
            check = "问一个点选题：在做事 / 休息 / 卡住了 / 在忙别的",
            confidence = if (s.playing) "中" else "低",
            at = now,
        )
        return Assessment(topic, if (s.playing) 80 else 60, Cards.stall("c-$now", f.mainTitle, s.mainLateMin, now), h)
    }

    /** 空档里在娱乐：问一下这段怎么用。只在刚进入空档或娱乐时长上来时提。 */
    private fun idle(sense: Sense, s: Situation, now: Long, cfg: DirectorConfig): Assessment? {
        if (!s.idle) return null
        val longPlay = sense.kind == SenseKind.LONG_ENTERTAINMENT || (s.playing && s.facts.foregroundMinutes >= 15)
        if (sense.kind != SenseKind.IDLE_START && !longPlay) return null
        return Assessment("idle:${dayKey(now)}:${hourOf(now) / 3}", if (longPlay) 50 else 35, Cards.idle("c-$now", now, cfg.talkMin))
    }

    /** 解锁时，如果有没想完的念头、而且好一阵没说话了，就递上接力棒。 */
    private fun resume(mind: MindState, f: Facts, now: Long): Assessment? {
        val b = mind.baton ?: return null
        if (now - f.lastContactAt < 3 * HOUR) return null
        if (now - b.updatedAt > 3 * 24 * HOUR) return null
        return Assessment("resume:${b.id}", 40, Cards.resume("c-$now", b, now))
    }

    /** 无障碍四层：用户自己关掉的只记录不打扰；意外掉线才求助。 */
    private fun health(sense: Sense, now: Long): Assessment? {
        val p = sense.payload
        val permission = p["permission"]?.toBooleanStrictOrNull() ?: true
        val connected = p["connected"]?.toBooleanStrictOrNull() ?: true
        val lastEventAt = p["lastEventAt"]?.toLongOrNull() ?: now
        val userDisabled = p["userDisabled"]?.toBooleanStrictOrNull() ?: false
        val silentMin = (now - lastEventAt) / 60_000
        val what = when {
            !permission -> "无障碍权限关了"
            !connected -> "无障碍服务没连上"
            silentMin >= 30 && p["screenOn"] == "true" -> "亮屏 $silentMin 分钟没收到事件"
            else -> return null
        }
        val incident = Incident("i-$now", "无障碍", now, what + if (userDisabled) "（用户自己关的）" else "")
        if (userDisabled) {
            // 尊重用户的决定：只记一笔，用一张不朗读、低优先级的卡说明后果。
            return Assessment("health", 30, Cards.health("c-$now", what, now).copy(speak = ""), incident = incident)
        }
        return Assessment("health", 90, Cards.health("c-$now", what, now), incident = incident, urgent = true)
    }
}
