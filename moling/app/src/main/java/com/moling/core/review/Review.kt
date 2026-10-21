package com.moling.core.review

import com.moling.core.mind.Tags
import com.moling.core.predict.DayStat
import com.moling.core.predict.Predict
import com.moling.core.predict.Prediction
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/*
 * 复盘和了解（v4 C5）。
 *
 * “她对你的了解”是一份她自己维护的文档（借鉴 Coopanion 的 PARTNER.md）：
 * 整份放进她的提示词；她每晚可以提一版修改，**你点“好”才算数**；你也可以直接改。每次改动留一个版本。
 * 这样关于你的结论永远是你确认过的，而不是她自己定的。
 */

@Serializable
data class AboutVersion(val at: Long, val text: String, val by: String)

object About {
    const val MAX_CHARS = 1500

    val SEED = """
        # 我对你的了解

        ## 你在乎的

        ## 你的规律
        （什么时候状态好、什么时候容易卡住、什么会把你拉走）

        ## 管用的打法
        （试过、有用的做法）

        ## 我还不确定的
    """.trimIndent()

    /** 夜里让她改这份文档：只根据今天确有的事实增删，猜测写进“我还不确定的”。 */
    fun updatePrompt(current: String, facts: String) = buildString {
        appendLine("【现在的文档】")
        appendLine(current.ifBlank { SEED })
        appendLine()
        appendLine("【今天发生的（都是记录下来的事实）】")
        appendLine(facts.take(2500))
        appendLine()
        appendLine("你在维护一份“我对他的了解”。根据今天的事实修改它：只写有记录支撑的；一次的现象写进“我还不确定的”，重复出现、或者他亲口说过的才移到上面；")
        appendLine("和事实对不上的旧内容删掉或改掉。不写评价和说教，不超过 $MAX_CHARS 字，保持原来的四个小节。")
        appendLine("没有值得改的就只输出【不改】。要改就只输出：")
        appendLine("【改了什么】一句话")
        append("【文档】（下面是完整的新文档）")
    }

    /** 解析出（改了什么，新文档）；【不改】或解析不出返回 null。 */
    fun parse(reply: String, current: String): Pair<String, String>? {
        if (reply.contains("【不改】")) return null
        val i = reply.indexOf("【文档】")
        if (i < 0) return null
        val doc = reply.substring(i + "【文档】".length).trim().removePrefix("（下面是完整的新文档）").trim()
        if (doc.length < 20 || doc == current.trim()) return null
        val what = Tags.parse(reply)["改了什么"].orEmpty().ifBlank { "更新了对你的了解" }
        return what.take(60) to doc.take(MAX_CHARS + 200)
    }

    /** 文档里的条目（“- ”“· ”开头的行），排水道和预言时当作“模式”用。 */
    fun bullets(doc: String, max: Int = 6): List<String> =
        doc.lines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("· ") }
            .map { it.drop(2).trim() }.filter { it.isNotBlank() }.take(max)
}

/** 一周的成绩单。 */
@Serializable
data class WeekReport(
    /** 这一周的周一。 */
    val week: String,
    val days: Int,
    /** 主线：开始了算 0.5，做完了算 1，按天平均；-1 = 没数据。 */
    val mainScore: Double,
    val drifts: Int,
    val nudges: Int,
    val workMinutes: Int,
    val playMinutes: Int,
    val acceptRate: Double,
    val vetoes: Int,
    val brier: Double,
    /** 撤支架：每做事一小时，她打扰了几次（越来越少才算成功）。 */
    val nudgesPerHour: Double,
    val text: String = "",
)

object Reviews {
    fun monday(day: String): String = LocalDate.parse(day).with(DayOfWeek.MONDAY).toString()

    /**
     * 算一周的数。[days] 是每天的统计，[accepts] 是这周他对提议的表态（true = 同意），
     * [vetoes] 是这周在总控倒计时里取消的次数。
     */
    fun week(monday: String, days: List<DayStat>, accepts: List<Boolean>, vetoes: Int, predictions: List<Prediction>): WeekReport {
        val start = LocalDate.parse(monday)
        val inWeek = days.filter { val d = LocalDate.parse(it.day); !d.isBefore(start) && d.isBefore(start.plusDays(7)) }
        val scored = inWeek.filter { it.mainScore >= 0 }
        val work = inWeek.sumOf { it.work }
        val nudges = inWeek.sumOf { it.nudges }
        return WeekReport(
            week = monday,
            days = inWeek.size,
            mainScore = if (scored.isEmpty()) -1.0 else scored.sumOf { it.mainScore } / scored.size,
            drifts = inWeek.sumOf { it.drifts },
            nudges = nudges,
            workMinutes = work,
            playMinutes = inWeek.sumOf { it.play },
            acceptRate = if (accepts.isEmpty()) -1.0 else accepts.count { it }.toDouble() / accepts.size,
            vetoes = vetoes,
            brier = Predict.brier(predictions),
            nudgesPerHour = if (work <= 0) -1.0 else nudges / (work / 60.0),
        )
    }

    /** 本地写的一段话（模型不可用时）：只说变化最大的两项。 */
    fun localText(r: WeekReport, last: WeekReport?): String {
        val parts = mutableListOf<Pair<Double, String>>()
        fun pct(x: Double) = if (x < 0) "—" else "${(x * 100).toInt()}%"
        if (last != null) {
            if (r.mainScore >= 0 && last.mainScore >= 0) parts += Math.abs(r.mainScore - last.mainScore) to "主线完成从 ${pct(last.mainScore)} 到 ${pct(r.mainScore)}"
            parts += Math.abs(r.drifts - last.drifts).toDouble() / 5 to "偏离从 ${last.drifts} 次到 ${r.drifts} 次"
            parts += Math.abs(r.workMinutes - last.workMinutes).toDouble() / 300 to "做事从 ${last.workMinutes / 60} 小时到 ${r.workMinutes / 60} 小时"
            if (r.nudgesPerHour >= 0 && last.nudgesPerHour >= 0) parts += Math.abs(r.nudgesPerHour - last.nudgesPerHour) to "每做事一小时我打扰你 %.1f 次（上周 %.1f）".format(r.nudgesPerHour, last.nudgesPerHour)
        }
        val top = parts.sortedByDescending { it.first }.take(2).joinToString("；") { it.second }
        return "这周：做事 ${r.workMinutes / 60} 小时，娱乐 ${r.playMinutes / 60} 小时，主线完成 ${pct(r.mainScore)}，偏离 ${r.drifts} 次，提议接受 ${pct(r.acceptRate)}。" +
            if (top.isNotBlank()) "变化最大的：$top。" else ""
    }

    fun prompt(r: WeekReport, last: WeekReport?, about: String) = buildString {
        appendLine("【这周】${localText(r, last)}")
        appendLine("【预言 Brier】${if (r.brier < 0) "没有" else "%.2f".format(r.brier)}；在总控倒计时里被取消 ${r.vetoes} 次")
        if (about.isNotBlank()) appendLine("【我对他的了解】\n${about.take(800)}")
        appendLine()
        appendLine("写这周的周报给他，四五句，口语：先说变化最大的两件事（用数字），再说一个你注意到的规律（要有数据撑着，没有就说没看出来），")
        append("最后提一个下周只试一周的改动（具体到什么时候做什么）。不夸、不说教。只输出周报正文。")
    }
}

/** 四个参谋：大判断前各说一句，再合成一句给他。 */
data class Council(val coach: String, val strategist: String, val critic: String, val friend: String, val together: String)

object Councils {
    fun prompt(question: String, situation: String, persona: String) = buildString {
        appendLine("【你是】$persona")
        appendLine("【局面】")
        appendLine(situation.take(1500))
        appendLine("【要判断的事】$question")
        appendLine()
        appendLine("先在心里分别站四个角色各说一句（每句不超过 40 字），再合成一句给他（不超过 60 字，要有明确的建议）。只输出：")
        appendLine("【教练】怎么让他现在动起来")
        appendLine("【军师】从目标倒推，这一步对不对、有没有更省力的路")
        appendLine("【对手】这个判断哪里站不住，他可能根本不是在逃避")
        appendLine("【朋友】他是不是累了，今天该不该放过他")
        append("【合起来】……")
    }

    fun parse(reply: String): Council? {
        val t = Tags.parse(reply)
        return Council(
            coach = t["教练"] ?: return null,
            strategist = t["军师"] ?: return null,
            critic = t["对手"] ?: return null,
            friend = t["朋友"] ?: return null,
            together = t["合起来"] ?: return null,
        )
    }
}
