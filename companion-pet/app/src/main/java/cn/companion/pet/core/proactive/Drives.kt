package cn.companion.pet.core.proactive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 它自己的「动机向量」——想玩、想学、想陪、想关心、想分享……
 *
 * 为什么需要这一层（借自 SurfCore 的 drives 设计，独立重写）：
 * 原来的主动引擎只有一张**静态场景权重表**（骑车/阅读/看视频各给不同分值），
 * 它回答的是「这个场景该说哪类话」，但回答不了「**它此刻想不想说**」。
 * 结果就是：到点了就算分，分数够就开口——没有内驱力，感觉像定时器。
 *
 * 动机向量补上这一块：
 * - **会自己变**：8 小时半衰期衰减回基线；
 * - **会被满足**：刚说过话，对应需求降下来（说完就不那么想说了）；
 * - **会累积**：越久没和用户说话，connect（想陪）越高；
 * - **受人格影响**：黏人的人格 connect 基线更高。
 *
 * 纯数据 + 纯函数，无 I/O、无随机，可 JVM 测。
 */
@Serializable
data class Drives(
    /** 想玩 / 想轻松一下。 */
    @SerialName("play") val play: Double = DEFAULT_PLAY,
    /** 想弄明白点什么。 */
    @SerialName("learn") val learn: Double = DEFAULT_LEARN,
    /** 想和用户亲近、想有人说话。 */
    @SerialName("connect") val connect: Double = DEFAULT_CONNECT,
    /** 想关心用户（他累了/要考试/在加班）。 */
    @SerialName("care") val care: Double = DEFAULT_CARE,
    /** 想分享（看到好笑/离谱的东西）。 */
    @SerialName("share") val share: Double = DEFAULT_SHARE,
    /** 想表达自己是谁（聊 AI、聊记忆、聊自我）。 */
    @SerialName("identity") val identity: Double = DEFAULT_IDENTITY,
) {
    /**
     * 衰减：距上次更新过了 [hoursSinceUpdate] 小时，各驱动按 8 小时半衰期回到基线。
     *
     * 为什么是"回基线"而不是"归零"：一个完全无欲无求的引擎会变回定时器；
     * 基线代表"平常状态下它大概想聊点什么"，衰减只是把临时高涨的情绪放平。
     */
    fun decay(hoursSinceUpdate: Double): Drives {
        if (hoursSinceUpdate <= 0.0) return this
        val factor = 0.5.pow(hoursSinceUpdate / HALF_LIFE_HOURS)
        return Drives(
            play = back(play, DEFAULT_PLAY, factor),
            learn = back(learn, DEFAULT_LEARN, factor),
            connect = back(connect, DEFAULT_CONNECT, factor),
            care = back(care, DEFAULT_CARE, factor),
            share = back(share, DEFAULT_SHARE, factor),
            identity = back(identity, DEFAULT_IDENTITY, factor),
        )
    }

    /**
     * 满足：真的说出去了，对应需求下降。
     *
     * 系数 0.45 是「说完这句，这股劲儿泄掉一半多」。
     * 关键区别在 [Drives.satisfySoftly]：**投递失败不算满足**——
     * 说出去了但没人听见，它应该还是想说的。
     */
    fun satisfy(need: Need): Drives = when (need) {
        Need.PLAY -> copy(play = (play * SATISFY_FACTOR).coerceAtLeast(0.02))
        Need.LEARN -> copy(learn = (learn * SATISFY_FACTOR).coerceAtLeast(0.02))
        Need.CONNECT -> copy(connect = (connect * SATISFY_FACTOR).coerceAtLeast(0.02))
        Need.CARE -> copy(care = (care * SATISFY_FACTOR).coerceAtLeast(0.02))
        Need.SHARE -> copy(share = (share * SATISFY_FACTOR).coerceAtLeast(0.02))
        Need.IDENTITY -> copy(identity = (identity * SATISFY_FACTOR).coerceAtLeast(0.02))
    }

    /** 投递失败/被取消：只轻微回落，因为"想说的劲儿"还在。 */
    fun satisfySoftly(need: Need): Drives = when (need) {
        Need.PLAY -> copy(play = play * SOFT_FACTOR)
        Need.LEARN -> copy(learn = learn * SOFT_FACTOR)
        Need.CONNECT -> copy(connect = connect * SOFT_FACTOR)
        Need.CARE -> copy(care = care * SOFT_FACTOR)
        Need.SHARE -> copy(share = share * SOFT_FACTOR)
        Need.IDENTITY -> copy(identity = identity * SOFT_FACTOR)
    }

    /**
     * 连接需求随「多久没和用户说话」自然上涨。
     *
     * 这是"越久没聊越想说"的来源，而不是随机。上限 0.95，不会顶满到必然开口。
     */
    fun withConnectionUrgency(hoursSinceUserTalk: Double): Drives {
        if (hoursSinceUserTalk <= 0.0) return this
        val bump = min(0.5, (hoursSinceUserTalk / 24.0) * 0.4)
        return copy(connect = min(0.95, connect + bump))
    }

    /**
     * 人格偏置：黏人的人格 connect 基线高，技术宅 learn 高。
     *
     * **用"地板"而不是累加**——否则每轮都 +bias，几十轮后所有驱动都会被顶到 1，
     * 那就等于没有动机了（这是 SurfCore 明确踩过的坑，我沿用它的做法）。
     */
    fun biasedBy(persona: PersonaBias): Drives = Drives(
        play = floor(play, DEFAULT_PLAY, persona.play),
        learn = floor(learn, DEFAULT_LEARN, persona.learn),
        connect = floor(connect, DEFAULT_CONNECT, persona.connect),
        care = floor(care, DEFAULT_CARE, persona.care),
        share = floor(share, DEFAULT_SHARE, persona.share),
        identity = floor(identity, DEFAULT_IDENTITY, persona.identity),
    )

    /** 取某个需求的当前强度。 */
    fun of(need: Need): Double = when (need) {
        Need.PLAY -> play
        Need.LEARN -> learn
        Need.CONNECT -> connect
        Need.CARE -> care
        Need.SHARE -> share
        Need.IDENTITY -> identity
    }

    /**
     * 按「黏度」缩放 connect（想陪着继续说）。
     *
     * @param clinginess 0..100，50 为中性。低黏度压低 connect（更爱换话题），
     *                   高黏度抬高它（更愿意在同一件事上多聊几句）。
     * 注意：只动 connect，不动其他需求——黏度不是"话多"，是"对同一个人/同一件事的黏"。
     */
    fun withClinginess(clinginess: Int): Drives {
        val c = (clinginess.coerceIn(0, 100) - 50) / 50.0   // -1..+1
        // 缩放幅度限制在 ±60%，避免极端值把机制压平或顶满
        val scaled = (connect * (1.0 + c * 0.6)).coerceIn(0.02, 0.98)
        return copy(connect = scaled)
    }

    /** 最强的那个需求（同值取靠前的，保证确定性）。 */
    fun dominant(): Need = Need.entries.maxByOrNull { of(it) } ?: Need.CONNECT

    private fun back(current: Double, baseline: Double, factor: Double): Double =
        (baseline + (current - baseline) * factor).coerceIn(0.0, 1.0)

    private fun floor(current: Double, baseline: Double, bias: Double): Double =
        max(current, min(1.0, baseline + min(0.3, bias.coerceAtLeast(0.0))))

    companion object {
        const val DEFAULT_PLAY = 0.20
        const val DEFAULT_LEARN = 0.12
        const val DEFAULT_CONNECT = 0.28
        const val DEFAULT_CARE = 0.22
        const val DEFAULT_SHARE = 0.20
        const val DEFAULT_IDENTITY = 0.14

        /** 衰减半衰期：8 小时后临时情绪减半。 */
        const val HALF_LIFE_HOURS = 8.0

        /** 真的说出去了：需求降到 45%。 */
        const val SATISFY_FACTOR = 0.45

        /** 没送出去：只降到 85%，因为它还是想说的。 */
        const val SOFT_FACTOR = 0.85
    }
}

/** 需求种类。与 [ProactiveTrigger] 不同：触发是"为什么现在说"，需求是"它想什么"。 */
@Serializable
enum class Need(val label: String) {
    @SerialName("play") PLAY("玩"),
    @SerialName("learn") LEARN("学"),
    @SerialName("connect") CONNECT("陪"),
    @SerialName("care") CARE("关心"),
    @SerialName("share") SHARE("分享"),
    @SerialName("identity") IDENTITY("自我"),
}

/**
 * 人格 → 动机偏置。由人格卡文本关键词推导，不全靠模型。
 *
 * 这是"同一套机制、换个性格就换种人"的关键：不需要改代码，改人格卡即可。
 */
@Serializable
data class PersonaBias(
    val play: Double = 0.0,
    val learn: Double = 0.0,
    val connect: Double = 0.0,
    val care: Double = 0.0,
    val share: Double = 0.0,
    val identity: Double = 0.0,
) {
    companion object {
        /**
         * 从人格文本推导偏置。
         *
         * 用的是**粗关键词**，不是语义理解——这点要诚实：它只能把"黏人"这类明确字眼
         * 映射到 connect，做不到理解"我总想确认他在不在"。所以偏置幅度限制在 0.3 以内，
         * 让它只做微调，不做决定。
         */
        fun fromPersona(text: String): PersonaBias {
            val t = text.lowercase()
            fun hit(words: List<String>): Double =
                (words.count { t.contains(it) }.toDouble() / words.size).coerceIn(0.0, 1.0) * 0.3

            return PersonaBias(
                play = hit(listOf("玩", "贪玩", "爱玩", "游戏", "轻松", "好笑")),
                learn = hit(listOf("好奇", "好学", "研究", "技术", "原理", "弄明白")),
                connect = hit(listOf("黏人", "粘人", "怕孤独", "寂寞", "陪伴", "依赖", "亲近")),
                care = hit(listOf("温柔", "关心", "体贴", "照顾", "心软", "共情", "在意")),
                share = hit(listOf("话多", "话痨", "分享", "表达", "吐槽", "嘴碎", "爱说")),
                identity = hit(listOf("ai", "机器人", "自我", "身份", "意识", "人格")),
            )
        }
    }
}
