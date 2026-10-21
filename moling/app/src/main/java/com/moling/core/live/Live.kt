package com.moling.core.live

import com.moling.core.mind.Tags
import com.moling.core.power.Charter
import kotlinx.serialization.Serializable

/*
 * 活的墨灵（v5）。
 *
 * 借鉴 Coopanion / Cortico 和 dsh-bili-whale：她是被事件叫醒的、有自己判断的伙伴，而不是总控的智能版。
 *  - 她自己决定说不说（不说也是一种回答）；有每天的“精力”（出声次数、拦人次数），被冷落会退后；
 *  - 她有表情（[Mood]），说话短、像朋友；
 *  - 他在娱乐里该停的时候，她可以**拦在他面前**：不回她一句话，这个娱乐就继续不下去（[GateRules]）。
 *
 * 拦人的硬规矩写在代码里，模型说了不算：
 *  - 只拦娱乐 / 购物类的应用；电话、短信、支付、地图、设置、总控、墨灵自己永远不拦（[Charter.neverTouch]）；
 *  - 每天最多拦 [LiveConfig.gatesPerDay] 次；每次最多 [LiveConfig.gateMaxMin] 分钟，到点自己放行；
 *  - 永远有“急事”按钮（每天 [LiveConfig.escapesPerDay] 次，用了就放行，记一笔）；
 *  - 最多聊 [LiveConfig.maxRounds] 轮，之后一定放行（可以带一个约定）。
 * 冻结、修总控、放宽规则仍然不是她的事。
 */

/** 她的表情（形象按它画）。 */
@Serializable
enum class Mood(val label: String) {
    CALM("平静"), HAPPY("开心"), CURIOUS("好奇"), WORRIED("担心"), POUT("气鼓鼓"), SLEEPY("困"), SMUG("得意"), SAD("难过"),
}

/** 什么把她叫醒了。[salient]：重要的一定看；不重要的她有时懒得出声（像那条鱼“有权利不看”）。 */
@Serializable
enum class Trigger(val label: String, val salient: Boolean) {
    SCROLL_AT_WORK("该做事的时候在刷", true),
    SCROLL_IN_BED("夜里躺着刷", true),
    LONG_SCROLL("刷了很久", false),
    WAKE("她自己定的唤醒", true),
    MORNING("早上第一次解锁", false),
    IDLE("没事的时候", false),
}

/** 他此刻在干什么（App 看到的）。[glance]：总控截图识图看到的内容，没有就空。 */
data class Activity(val pkg: String, val label: String, val category: String, val minutes: Int, val glance: String = "")

/** 她这一天的精力。 */
@Serializable
data class Spirit(
    val day: String = "",
    val spoke: Int = 0,
    val gates: Int = 0,
    val escapes: Int = 0,
    val lastAt: Long = 0,
    /** 连续被冷落（划掉、急事、拦到超时没理）的次数；他认真回一次就清零。 */
    val ignored: Int = 0,
)

/** 正在拦着他的那一次。 */
@Serializable
data class GateSession(
    val startedAt: Long,
    val trigger: Trigger,
    val pkg: String,
    val label: String,
    val rounds: Int = 0,
)

data class LiveConfig(
    val speaksPerDay: Int = 10,
    val gatesPerDay: Int = 3,
    val gapMin: Int = 20,
    val escapesPerDay: Int = 2,
    val gateMaxMin: Int = 5,
    val maxRounds: Int = 3,
)

/** 她这一次说的。[gate] = 拦在他面前；[release] = 放他走（拦着的时候才有意义）。 */
data class LiveLine(val speak: Boolean, val mood: Mood, val line: String, val gate: Boolean, val release: Boolean)

object Spirits {
    fun today(s: Spirit, day: String): Spirit = if (s.day == day) s else Spirit(day = day, ignored = s.ignored)

    /**
     * 现在可不可以出声：精力（被冷落越多，今天能说的越少）、间隔、不重要的事有一半时候懒得说。
     * [roll] 是 0..1 的随机数（测试里固定）。
     */
    fun mayAct(s: Spirit, t: Trigger, now: Long, cfg: LiveConfig, roll: Double): Boolean {
        val cap = (cfg.speaksPerDay - s.ignored * 2).coerceAtLeast(if (t.salient) 2 else 0)
        if (s.spoke >= cap) return false
        if (s.lastAt > 0 && now - s.lastAt < cfg.gapMin * 60_000L && t != Trigger.WAKE) return false
        if (!t.salient && roll < 0.5) return false
        return true
    }

    /** 今天还能不能拦人：被冷落 3 次以上的那天不再拦（她会退后，只说话）。 */
    fun mayGate(s: Spirit, cfg: LiveConfig): Boolean = s.gates < cfg.gatesPerDay && s.ignored < 3
}

object GateRules {
    /** 只拦娱乐 / 购物；永不碰的包一律不拦。 */
    fun eligible(a: Activity?): Boolean =
        a != null && a.pkg.isNotBlank() && a.pkg !in Charter.neverTouch && (a.category == "ENTERTAINMENT" || a.category == "SHOPPING")

    /** 拦着超过 [LiveConfig.gateMaxMin] 分钟，到点自己放行（安全阀）。 */
    fun expired(g: GateSession, now: Long, cfg: LiveConfig): Boolean = now - g.startedAt >= cfg.gateMaxMin * 60_000L
}

object Live {
    /**
     * 她被叫醒时的提示词。[misjudged] 是这几天判断上的错（他的和她的），[about] 是她对他的了解。
     * [canGate] = false 时不许拦（规矩不允许或今天拦够了）。
     */
    fun prompt(
        persona: String,
        trigger: Trigger,
        activity: Activity?,
        now: String,
        flow: String,
        about: String,
        misjudged: List<String>,
        history: List<String>,
        canGate: Boolean,
        thinking: List<String> = emptyList(),
        toSay: String = "",
    ) = buildString {
        appendLine("【你是】$persona")
        appendLine("【你被叫醒的原因】${trigger.label}")
        if (thinking.isNotEmpty()) appendLine("【你自己推的判断（未必对）】\n" + thinking.joinToString("\n"))
        if (toSay.isNotBlank()) appendLine("【你之前想好要对他说的】$toSay（合适就说这个，可以改得更贴合此刻）")
        activity?.let { a ->
            append("【他此刻】在用 ${a.label}（${a.minutes} 分钟）")
            if (a.glance.isNotBlank()) append("，屏幕上看到的：${a.glance}")
            appendLine()
        }
        appendLine("【此刻】$now")
        if (flow.isNotBlank()) appendLine("【今天的水道】\n$flow")
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(900)}")
        if (misjudged.isNotEmpty()) appendLine("【这几天判断上的错】\n" + misjudged.joinToString("\n") { "- $it" })
        if (history.isNotEmpty()) appendLine("【刚才的对话】\n" + history.takeLast(6).joinToString("\n"))
        appendLine()
        appendLine("你是一个活的伙伴，不是闹钟。先判断现在要不要出声：不重要、他刚被打扰过、他在做正事，就不出声。")
        appendLine("要出声就像朋友一样，一两句，提到他此刻具体在做的事；能指出一处这几天判断上的错就指出（用数字，不翻旧账、不说教），最后给一个马上能做的小建议。")
        if (canGate) appendLine("如果他该停却还在刷，你可以拦在他面前（他不回你就刷不下去）：语气可以气鼓鼓，但不凶、不羞辱，问一个他必须回答的问题。")
        else appendLine("这次不许拦他，只能说话。")
        appendLine("只输出：")
        appendLine("【出声】是 / 否")
        appendLine("【表情】" + Mood.entries.joinToString(" / ") { it.label })
        appendLine("【说】你要说的话")
        if (canGate) appendLine("【拦】是 / 否")
        append("需要改变现实时，另起一行写【动作】（格式同对话：专注｜分钟｜做什么、约定｜做什么｜HH:MM｜怎么检查、提醒我｜N 分钟后｜说什么）")
    }

    /** 拦着他时，他回了一句：她决定放不放。 */
    fun replyPrompt(persona: String, g: GateSession, reply: String, history: List<String>, about: String, rounds: Int, maxRounds: Int) = buildString {
        appendLine("【你是】$persona")
        appendLine("【现在】你拦在他面前（他在用 ${g.label}），这是第 ${rounds + 1} 轮，最多 $maxRounds 轮。")
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(600)}")
        appendLine("【刚才的对话】\n" + history.takeLast(8).joinToString("\n"))
        appendLine("【他刚说】$reply")
        appendLine()
        appendLine("他认真回答了（说清楚了卡在哪、或者约好了几点停、或者有正当理由），就放他走，可以顺手约一个时间（【动作】约定 或 提醒我）。")
        appendLine("他在敷衍（“嗯”“马上”“知道了”），就再问一个更具体的问题，不放。最后一轮一定放，但要约好时间。语气可以气鼓鼓，不凶、不羞辱。只输出：")
        appendLine("【表情】" + Mood.entries.joinToString(" / ") { it.label })
        appendLine("【说】你要说的话")
        appendLine("【放行】是 / 否")
        append("需要的话另起一行写【动作】（约定 / 提醒我 / 专注）")
    }

    fun parse(reply: String, canGate: Boolean): LiveLine? {
        val t = Tags.parse(reply)
        val line = t["说"]?.trim().orEmpty()
        val speak = t["出声"]?.startsWith("是") ?: line.isNotBlank()
        if (speak && line.isBlank()) return null
        val mood = Mood.entries.firstOrNull { t["表情"]?.contains(it.label) == true } ?: Mood.CALM
        val gate = canGate && t["拦"]?.startsWith("是") == true
        val release = t["放行"]?.startsWith("是") == true
        return LiveLine(speak, mood, line.take(160), gate, release)
    }

    /** 模型不可用时她自己会说的（按原因）。 */
    fun local(trigger: Trigger, activity: Activity?, canGate: Boolean, flowNow: String): LiveLine {
        val app = activity?.label?.ifBlank { null } ?: "手机"
        val min = activity?.minutes ?: 0
        return when (trigger) {
            Trigger.SCROLL_AT_WORK -> LiveLine(
                true, Mood.POUT,
                "${app}已经 $min 分钟了，现在本该是「${flowNow.ifBlank { "今天的主线" }}」。先说一句：卡在哪了？", canGate, false,
            )
            Trigger.SCROLL_IN_BED -> LiveLine(true, Mood.POUT, "躺着刷了 $min 分钟了哦。说一句：几点放下手机睡觉？", canGate, false)
            Trigger.LONG_SCROLL -> LiveLine(true, Mood.WORRIED, "${app}连着 $min 分钟了。还要多久？", false, false)
            Trigger.WAKE -> LiveLine(true, Mood.CURIOUS, "我来看看你了。", false, false)
            Trigger.MORNING -> LiveLine(true, Mood.HAPPY, "早。今天先做哪一件？", false, false)
            Trigger.IDLE -> LiveLine(false, Mood.CALM, "", false, false)
        }
    }

    /** 他回得太敷衍（不放行的本地判断，模型不可用时用）。 */
    fun perfunctory(reply: String): Boolean {
        val t = reply.trim()
        return t.length < 4 || Regex("""^(嗯+|哦+|好+|知道了?|马上|等会|一会儿?|行吧?|随便|ok|OK)[。！!，, …]*$""").matches(t)
    }
}
