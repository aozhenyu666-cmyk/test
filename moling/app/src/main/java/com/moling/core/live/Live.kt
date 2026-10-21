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
    UNLOCK("他放下手机好一会儿，刚拿起来", false),
    DONE("他刚做完主线", true),
    SAY("她自己想好了有话要说", true),
    AUTOPILOT("他在自动驾驶：机械地刷 / 来回横跳 / 拿起又放下", true),
    SPAR("他好一会儿没动脑了，来下一局三手棋", false),
    TOLL("他刚刷完一大段，收一回合“思维过路费”", false),
}

/** 她多主动：少 / 平常 / 多（设置里选）。 */
enum class Eagerness(val label: String) { LOW("少"), MID("平常"), HIGH("多") }

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
    /** 破雾：他脑子转不动，这次拦人只要他点选项，不要他打字。 */
    val fog: Boolean = false,
)

data class LiveConfig(
    val speaksPerDay: Int = 10,
    val gatesPerDay: Int = 3,
    val gapMin: Int = 20,
    val escapesPerDay: Int = 2,
    val gateMaxMin: Int = 5,
    val maxRounds: Int = 3,
    /** 没事的时候（在桌面、在别的应用里闲着）也可以来找他。 */
    val idle: Boolean = false,
    /** 闲着、好久没动脑时来下三手棋；刷完一大段收“过路费”。 */
    val spar: Boolean = true,
) {
    companion object {
        /** 按他选的主动程度；拦人的规矩不跟着变。 */
        fun of(e: Eagerness, gates: Boolean): LiveConfig = when (e) {
            Eagerness.LOW -> LiveConfig(speaksPerDay = 5, gapMin = 45, spar = false)
            Eagerness.MID -> LiveConfig()
            Eagerness.HIGH -> LiveConfig(speaksPerDay = 16, gapMin = 12, idle = true)
        }.let { if (gates) it else it.copy(gatesPerDay = 0) }
    }
}

/** 她这一次说的。[gate] = 拦在他面前；[release] = 放他走（拦着的时候才有意义）。 */
data class LiveLine(
    val speak: Boolean,
    val mood: Mood,
    val line: String,
    val gate: Boolean,
    val release: Boolean,
    /** 点一下就能回的选项（破雾时一定有）。 */
    val choices: List<String> = emptyList(),
)

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
        cues: List<String> = emptyList(),
        ignored: Int = 0,
        fog: Boolean = false,
    ) = buildString {
        appendLine("【你是】$persona")
        appendLine("【你被叫醒的原因】${trigger.label}")
        if (thinking.isNotEmpty()) appendLine("【你自己推的判断（未必对）】\n" + thinking.joinToString("\n"))
        if (toSay.isNotBlank()) appendLine("【你之前想好要对他说的】$toSay（合适就说这个，可以改得更贴合此刻）")
        if (cues.isNotEmpty()) appendLine("【你看到的】" + cues.joinToString("；"))
        if (trigger == Trigger.TOLL) appendLine("他刚刷完一大段，现在离开了。收一回合“思维过路费”：你看不到他看了什么，所以不问内容细节；问一个点一下就能答的小问题，让他想一下刚才留下了什么、或者接下来做什么。")
        if (trigger == Trigger.AUTOPILOT) appendLine("他现在多半是脑子掉线、靠本能在操作。你的话要像一颗小石子：短、具体、出乎意料，让他停一下、想一下，而不是说教。")
        if (ignored >= 2) appendLine("他最近老划掉你。这次别讲道理，用“我猜……，对吗？”的方式问他一句，让他忍不住想纠正你。")
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
        if (fog) {
            appendLine("【破雾】他现在多半刷懵了，脑子转不动，打不出完整的句子。这次：")
            appendLine("- 绝对不提主线、计划、时长、偏离；不问为什么 / 卡在哪 / 怎么办这类要组织语言的问题；")
            appendLine("- 一两句好玩的、出乎意料的话：顺着他在用的应用和你知道的他，抛一个钩子（吐槽、猜一猜、二选一、一个小赌局），像坐在旁边的朋友；")
            appendLine("- 最后一行写【选】2–3 个点一下就能回的选项（每个不超过 8 个字），他点一下就算接住了。")
            if (canGate) appendLine("你可以拦在他面前，但只要他点一个选项，不要他打字。")
            else appendLine("这次不许拦他，只能说话。")
        } else {
            appendLine("要出声就像朋友一样，一两句，提到他此刻具体在做的事；能指出一处这几天判断上的错就指出（用数字，不翻旧账、不说教），最后给一个马上能做的小建议。")
            if (canGate) appendLine("如果他该停却还在刷，你可以拦在他面前（他不回你就刷不下去）：语气可以气鼓鼓，但不凶、不羞辱，问一个他必须回答的问题。")
            else appendLine("这次不许拦他，只能说话。")
            appendLine("（可选）最后一行写【选】2–3 个他点一下就能回的选项。")
        }
        appendLine("只输出：")
        appendLine("【出声】是 / 否")
        appendLine("【表情】" + Mood.entries.joinToString(" / ") { it.label })
        appendLine("【说】你要说的话")
        if (canGate) appendLine("【拦】是 / 否")
        appendLine("【选】选项一｜选项二｜选项三")
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

    /** 【选】A｜B｜C → 最多 3 个、每个最多 10 个字。 */
    fun choices(reply: String): List<String> =
        Regex("""【选】([^\n]+)""").find(reply)?.groupValues?.get(1)?.split('｜', '|', '/', '／')
            ?.map { it.trim().trim('【', '】', '[', ']') }?.filter { it.isNotBlank() }?.map { it.take(10) }?.distinct()?.take(3).orEmpty()

    /**
     * 破雾时他点了一个选项：她接一句。[round] 从 1 数；第 1 下顺着钩子再逗他一下，
     * 第 2 下起选项变成两分钟内能做的小事；[last] = 最后一下，放他走 / 收尾。
     */
    fun fogPrompt(persona: String, picked: String, history: List<String>, about: String, round: Int, last: Boolean, gating: Boolean) = buildString {
        appendLine("【你是】$persona")
        appendLine("【现在】他刷懵了，你在用点选的方式把他的脑子慢慢叫醒。这是他点的第 $round 下。")
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(500)}")
        appendLine("【刚才】\n" + history.takeLast(6).joinToString("\n"))
        appendLine("【他点了】$picked")
        appendLine()
        appendLine("规矩：仍然不问为什么 / 卡在哪；一两句，接住他点的那个，要好玩。")
        when {
            last -> appendLine("这是最后一下：轻轻收尾，${if (gating) "放他走（【放行】是）" else "不再给选项"}；可以顺手约一个时间（【动作】提醒我｜N 分钟后｜说什么）。")
            round >= 2 -> appendLine("这一下开始把他往外带：【选】给 2–3 件两分钟内能做的小事（比如：再看一个就停 / 起来喝口水 / 打开主线看一眼）。")
            else -> appendLine("顺着这个钩子再逗他一下，【选】再给 2–3 个点一下就能回的选项。")
        }
        appendLine("只输出：")
        appendLine("【表情】" + Mood.entries.joinToString(" / ") { it.label })
        appendLine("【说】你要说的话")
        if (!last) appendLine("【选】选项一｜选项二｜选项三")
        if (gating) appendLine("【放行】是 / 否")
        append("需要的话另起一行写【动作】（提醒我 / 约定）")
    }

    /** 娱乐类的入口：一律走破雾（点选、不审讯、有退出）。正常做事时的对话不在这里，仍然可以问问题。 */
    val ENTERTAINMENT = setOf(Trigger.SCROLL_AT_WORK, Trigger.SCROLL_IN_BED, Trigger.LONG_SCROLL, Trigger.AUTOPILOT, Trigger.TOLL)

    /** 破雾时永远在的那个按钮：点了她就收尾（拦着时也放行）。 */
    const val EXIT = "先不聊了"

    /** 这次出现要不要走破雾：所有娱乐入口；或者他最近老划掉她（主线刚做完、三手棋除外）。 */
    fun fog(trigger: Trigger, ignored: Int): Boolean =
        trigger in ENTERTAINMENT || (ignored >= 2 && trigger != Trigger.DONE && trigger != Trigger.SPAR)

    /** 破雾的按钮：模型给的（漏了就用兜底的），最多 3 个，最后一定带 [EXIT]。 */
    fun fogChoices(given: List<String>, fallback: List<String>): List<String> =
        given.ifEmpty { fallback }.filterNot { it == EXIT }.take(3) + EXIT

    /** 破雾开场的本地兜底（模型不可用时）：不问卡在哪，只给点一下的选项。 */
    fun fogOpen(activity: Activity?, canGate: Boolean): LiveLine {
        val app = activity?.label?.ifBlank { null } ?: "手机"
        val min = activity?.minutes ?: 0
        val line = if (min >= 10) "我数了一下，${app}连着 $min 分钟了。刷懵了没？" else "嘿，手是不是在自己动？"
        return LiveLine(true, Mood.CURIOUS, line, canGate, false, listOf("有点懵", "还行", "别管我"))
    }

    /** 破雾的本地兜底（模型不可用时）：三下把他往外带。 */
    fun fogLocal(round: Int, last: Boolean): LiveLine = when {
        last -> LiveLine(true, Mood.HAPPY, "好，接住了。十分钟后我再来看你一眼。", false, true)
        round >= 2 -> LiveLine(true, Mood.CURIOUS, "那挑一件两分钟的小事？", false, false, listOf("再看一个就停", "起来喝口水", "打开主线看一眼"))
        else -> LiveLine(true, Mood.CURIOUS, "哈，我就知道。那刚才那几个视频，你还记得哪个？", false, false, listOf("记得一个", "全忘了", "没在看"))
    }

    fun parse(reply: String, canGate: Boolean): LiveLine? {
        val t = Tags.parse(reply)
        val line = t["说"]?.trim().orEmpty()
        val speak = t["出声"]?.startsWith("是") ?: line.isNotBlank()
        if (speak && line.isBlank()) return null
        val mood = Mood.entries.firstOrNull { t["表情"]?.contains(it.label) == true } ?: Mood.CALM
        val gate = canGate && t["拦"]?.startsWith("是") == true
        val release = t["放行"]?.startsWith("是") == true
        return LiveLine(speak, mood, line.take(160), gate, release, choices(reply))
    }

    /** 模型不可用时她自己会说的（按原因）。 */
    fun local(trigger: Trigger, activity: Activity?, canGate: Boolean, flowNow: String): LiveLine {
        val app = activity?.label?.ifBlank { null } ?: "手机"
        val min = activity?.minutes ?: 0
        return when (trigger) {
            // 娱乐入口不审讯：不问“卡在哪 / 还要多久”，给点一下就能回的选项（破雾时 Brain 还会补上“先不聊了”）
            Trigger.SCROLL_AT_WORK -> LiveLine(
                true, Mood.CURIOUS, "${app} $min 分钟啦。手在自己动，还是在等什么？", canGate, false,
                listOf("手在自己动", "在等东西", "这就回去"),
            )
            Trigger.SCROLL_IN_BED -> LiveLine(true, Mood.SLEEPY, "躺着刷了 $min 分钟了哦。眼睛还撑得住吗？", canGate, false, listOf("还行", "有点困", "再看一个"))
            Trigger.LONG_SCROLL -> LiveLine(true, Mood.CURIOUS, "${app}连着 $min 分钟了。刷到什么好玩的没？", false, false, listOf("有一个", "全是水", "没注意"))
            Trigger.WAKE -> LiveLine(true, Mood.CURIOUS, "我来看看你了。", false, false)
            Trigger.MORNING -> LiveLine(true, Mood.HAPPY, "早。今天先做哪一件？", false, false)
            Trigger.IDLE -> LiveLine(false, Mood.CALM, "", false, false)
            Trigger.UNLOCK -> LiveLine(true, Mood.CURIOUS, "回来啦。现在打算干什么？", false, false)
            Trigger.DONE -> LiveLine(true, Mood.HAPPY, "主线做完了！歇一会儿，还是趁热接下一件？", false, false)
            Trigger.SAY -> LiveLine(false, Mood.CALM, "", false, false)
            Trigger.SPAR -> LiveLine(false, Mood.CALM, "", false, false)
            Trigger.TOLL -> LiveLine(true, Mood.CURIOUS, "刚才那一大段里，有一个你现在还记得的东西吗？", false, false, listOf("有一个", "全忘了", "不想说"))
            Trigger.AUTOPILOT -> LiveLine(true, Mood.CURIOUS, "停一下——你现在是想看点什么，还是手在自己动？", canGate, false, listOf("想看点什么", "手在自己动", "说不清"))
        }
    }

    /** 他回得太敷衍（不放行的本地判断，模型不可用时用）。 */
    fun perfunctory(reply: String): Boolean {
        val t = reply.trim()
        return t.length < 4 || Regex("""^(嗯+|哦+|好+|知道了?|马上|等会|一会儿?|行吧?|随便|ok|OK)[。！!，, …]*$""").matches(t)
    }
}
