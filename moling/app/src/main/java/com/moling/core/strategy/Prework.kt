package com.moling.core.strategy

import com.moling.core.workspace.ArtifactBlock
import com.moling.core.workspace.ArtifactOption
import com.moling.core.workspace.Artifacts
import com.moling.core.workspace.BlockKind
import com.moling.core.workspace.Material
import com.moling.core.workspace.WorkspaceArtifact

/*
 * 第一块代工（v13 第九批）：提示词 + 解析 + 没有模型时的本地兜底。
 *
 * 规矩：
 *  - 没联网：依据只能是他给的材料（m-…）和体系图（n-…）。模型写了不存在的编号就当没写，那一块降成“待核的假设”——不假造引用；
 *  - 模型漏了字段 / 格式不对：解析返回 null，由 [Workbench] 用本地骨架兜底（纲要、初稿、挑刺都有兜底；改写和四参谋没有，直接说“离线”）；
 *  - 【靶子】是练习用的诱饵：exerciseOnly，不导出、不进体系图。
 */

/** 解析出来、还没编号的一份稿子。 */
data class Draft(
    val title: String,
    val blocks: List<ArtifactBlock>,
    val questions: List<String>,
)

/** 挑刺的结果：一句刺 + A/B 两条改法。 */
data class Critique(val point: String, val options: List<Pair<String, String>>)

object Prework {
    /** 一个作业最多等多久（毫秒）：过了还没回来就算失败。 */
    const val DEADLINE_MS = 3 * 60_000L

    private fun materialLines(ms: List<Material>) = ms.joinToString("\n") { "[${it.id}] ${it.title.takeIf { t -> t.isNotBlank() }?.let { t -> "$t：" }.orEmpty()}${it.text.take(1500)}" }

    private fun rules(sb: StringBuilder) {
        sb.appendLine("规矩：你现在没联网。依据只能写上面材料 / 体系图的编号（m-… / n-…）；材料里没有、是你推的就写“推测”。")
        sb.appendLine("不许编数据、文献、人名、出处、引语。不确定的写进【待核】。")
    }

    fun outlinePrompt(persona: String, question: String, materials: List<Material>, atlas: List<String>, about: String) = buildString {
        appendLine("【你是】$persona")
        appendLine("【任务】他要攻这件事：$question。他给了材料，你替他先做一块：一份三点纲要，让他在上面挑、改。")
        appendLine("【材料】\n${materialLines(materials)}")
        if (atlas.isNotEmpty()) appendLine("【他体系图里相关的】\n" + atlas.joinToString("\n"))
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(500)}")
        appendLine()
        appendLine("要求：三个要点，每点一句话（不超过 60 字），能直接当一段的主题句；三点之间要有推进，不要换个说法重复。")
        rules(this)
        appendLine("可以写一个【靶子】：一句听着像他会说、但故意藏了一个漏洞的话（练习找茬用）。")
        appendLine("只输出：")
        appendLine("【标题】不超过 20 字")
        appendLine("【要点】一句话｜依据编号（多个用逗号）或 推测")
        appendLine("【要点】……")
        appendLine("【要点】……")
        appendLine("【待核】需要他核实的一点（0–3 行，每行一个【待核】）")
        appendLine("【追问】一个他答了、稿子就会更好的问题（可选）")
        append("【靶子】一句话｜漏洞在哪（可选）")
    }

    fun draftPrompt(persona: String, question: String, outline: List<ArtifactBlock>, materials: List<Material>, about: String) = buildString {
        appendLine("【你是】$persona")
        appendLine("【任务】把他认可的纲要写成初稿：$question")
        appendLine("【纲要】\n" + outline.joinToString("\n") { "- ${it.markdown.lines().joinToString(" ")}（依据：${it.sourceIds.joinToString(",").ifBlank { "推测" }}）" })
        appendLine("【材料】\n${materialLines(materials)}")
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(400)}")
        appendLine()
        appendLine("要求：每个要点写成一段（80–200 字），第一句就是论点；用他的材料举例，不空喊。")
        rules(this)
        appendLine("只输出：")
        appendLine("【标题】不超过 20 字")
        appendLine("【段】这一段的正文（一行写完）｜依据编号 或 推测")
        append("【待核】……（0–3 行）")
    }

    fun criticPrompt(persona: String, title: String, all: List<ArtifactBlock>, target: ArtifactBlock, materials: List<Material>) = buildString {
        appendLine("【你是】$persona。这次你扮演“对手”：专挑刺，但挑完要给出路。")
        appendLine("【稿子】$title")
        appendLine(all.filter { Artifacts.isContent(it) && !it.exerciseOnly }.joinToString("\n") { "- ${it.markdown.lines().joinToString(" ").take(200)}" })
        appendLine("【要挑的这一段】\n${target.markdown}")
        if (materials.isNotEmpty()) appendLine("【他的材料】\n${materialLines(materials)}")
        appendLine()
        appendLine("找这一段最致命的一个问题（逻辑跳步、偷换概念、没依据、说了等于没说……），一句话说清。")
        appendLine("再给两条改法，A 和 B 方向要不同（比如 A 收窄说法、B 补一个例子），每条写出改好后的整段。")
        appendLine("没联网：不许编数据和出处，需要数据的地方写“（待核：…）”。")
        appendLine("只输出：")
        appendLine("【刺】一句话")
        appendLine("【A】不超过 8 字的名字｜改好后的整段（一行写完）")
        append("【B】不超过 8 字的名字｜改好后的整段（一行写完）")
    }

    fun rewritePrompt(persona: String, title: String, all: List<ArtifactBlock>, target: ArtifactBlock, ask: String) = buildString {
        appendLine("【你是】$persona")
        appendLine("【稿子】$title")
        appendLine(all.filter { Artifacts.isContent(it) && !it.exerciseOnly }.joinToString("\n") { "- ${it.markdown.lines().joinToString(" ").take(200)}" })
        appendLine("【要改的这一段】\n${target.markdown}")
        appendLine("【他要的】${ask.ifBlank { DEFAULT_REWRITE }}")
        appendLine()
        appendLine("只改这一段，意思不丢、依据不丢；没联网，不许编数据和出处。")
        append("只输出：\n【改】改好后的整段")
    }

    const val DEFAULT_REWRITE = "开门见山、更短更有力，第一句就是论点"

    fun councilPrompt(persona: String, title: String, all: List<ArtifactBlock>) = buildString {
        appendLine("【你是】$persona。现在你同时是四个参谋，各说一句，对着这份稿子：")
        appendLine("【稿子】$title")
        appendLine(all.filter { Artifacts.isContent(it) && !it.exerciseOnly }.joinToString("\n") { "- ${it.markdown.lines().joinToString(" ").take(200)}" })
        appendLine()
        appendLine("对手：最大的漏洞；教练：下一步最该补的一处；军师：怎么排结构更能打；朋友：哪里写得好、该保留。每个一句，具体到哪一段。")
        appendLine("只输出：")
        appendLine("【对手】……")
        appendLine("【教练】……")
        appendLine("【军师】……")
        append("【朋友】……")
    }

    // ------------------------------------------------------------------ 解析

    private fun lines(reply: String, tag: String) = Regex("""【$tag】([^\n]+)""").findAll(reply).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()

    /** 依据字段 → 真实存在的编号；只有一份材料时写“材料”也认。编不出来的编号丢掉。 */
    fun refs(field: String, valid: Set<String>): List<String> {
        if (field.contains("推测")) return emptyList()
        val ids = Regex("""m-\d+|n-\d+(?:-\d+)?""").findAll(field).map { it.value }.filter { it in valid }.distinct().toList()
        if (ids.isNotEmpty()) return ids
        val ms = valid.filter { it.startsWith("m-") }
        return if (field.contains("材料") && ms.size == 1) ms else emptyList()
    }

    private fun point(text: String, field: String, valid: Set<String>, newId: () -> String): ArtifactBlock {
        val src = refs(field, valid)
        return ArtifactBlock(newId(), if (src.isEmpty()) BlockKind.ASSUMPTION else BlockKind.MARKDOWN, text, src)
    }

    private fun bait(reply: String, newId: () -> String): ArtifactBlock? = lines(reply, "靶子").firstOrNull()?.let { l ->
        val f = l.split('｜', '|').map { it.trim() }
        val text = f[0].take(100).takeIf { it.length >= 4 } ?: return@let null
        val id = newId()
        ArtifactBlock(
            id, BlockKind.CHOICE, "练习 · 找茬：「$text」",
            options = listOf(
                ArtifactOption("$id-flaw", "有漏洞", explanation = f.getOrNull(1).orEmpty().take(80)),
                ArtifactOption("$id-ok", "没问题", explanation = f.getOrNull(1).orEmpty().take(80)),
            ),
            exerciseOnly = true,
        )
    }

    /** 纲要：至少两个要点才算数，否则 null（走本地骨架）。 */
    fun parseOutline(reply: String, valid: Set<String>, newId: () -> String): Draft? {
        val pts = lines(reply, "要点").mapNotNull { l ->
            val f = l.split('｜', '|').map { it.trim() }
            f[0].trimStart('1', '2', '3', '.', '、', ' ').take(120).takeIf { it.length >= 4 }?.let { it to f.getOrNull(1).orEmpty() }
        }.take(3)
        if (pts.size < 2) return null
        val blocks = pts.mapIndexed { i, (t, f) -> point("${i + 1}. $t", f, valid, newId) } + listOfNotNull(bait(reply, newId))
        val title = lines(reply, "标题").firstOrNull()?.take(30).orEmpty()
        return Draft(title, blocks, (lines(reply, "待核").take(3) + lines(reply, "追问").take(1)).map { it.take(100) })
    }

    fun parseDraft(reply: String, valid: Set<String>, newId: () -> String): Draft? {
        val ps = lines(reply, "段").mapNotNull { l ->
            val i = maxOf(l.lastIndexOf('｜'), l.lastIndexOf('|'))
            val (t, f) = if (i > 0) l.substring(0, i).trim() to l.substring(i + 1) else l to ""
            t.take(1200).takeIf { it.length >= 10 }?.let { it to f }
        }.take(8)
        if (ps.isEmpty()) return null
        return Draft(lines(reply, "标题").firstOrNull()?.take(30).orEmpty(), ps.map { (t, f) -> point(t, f, valid, newId) }, lines(reply, "待核").take(3).map { it.take(100) })
    }

    fun parseCritic(reply: String): Critique? {
        val point = lines(reply, "刺").firstOrNull()?.take(160) ?: return null
        val opts = listOf("A", "B").mapNotNull { k ->
            lines(reply, k).firstOrNull()?.let { l ->
                val i = l.indexOfFirst { it == '｜' || it == '|' }
                if (i <= 0) null else (l.substring(0, i).trim().take(10) to l.substring(i + 1).trim().take(1200)).takeIf { it.second.length >= 4 }
            }
        }
        if (opts.isEmpty()) return null
        return Critique(point, opts)
    }

    /** 改写：有【改】用它；模型漏了标签但回了一小段正文也认（去掉别的标签行）。 */
    fun parseRewrite(reply: String): String? {
        lines(reply, "改").firstOrNull()?.let { return it.take(1200) }
        val t = reply.lines().filterNot { it.trim().startsWith("【") }.joinToString("\n").trim()
        return t.takeIf { it.length in 4..1200 }
    }

    fun parseCouncil(reply: String, newId: () -> String): List<ArtifactBlock>? {
        val notes = listOf("对手", "教练", "军师", "朋友").mapNotNull { v ->
            lines(reply, v).firstOrNull()?.take(200)?.let { ArtifactBlock(newId(), BlockKind.REVIEW_NOTE, it, voice = v) }
        }
        return notes.takeIf { it.size >= 2 }
    }

    // ------------------------------------------------------------------ 本地兜底（没模型 / 模型乱写）

    private fun sentences(text: String) = text.split('。', '！', '？', '!', '?', '\n', '；', ';')
        .map { it.trim().trimStart('-', '*', '•', ' ') }.filter { it.length >= 4 }

    /** 纲要骨架：从他的材料里拆三句当要点（带材料编号）；不够三句的位置标“待补”。 */
    fun localOutline(question: String, materials: List<Material>, newId: () -> String): Draft {
        val picked = materials.flatMap { m -> sentences(m.text).map { it to m.id } }.distinctBy { it.first }.take(3)
        val blocks = picked.mapIndexed { i, (t, mid) -> ArtifactBlock(newId(), BlockKind.MARKDOWN, "${i + 1}. ${t.take(120)}", listOf(mid)) } +
            (picked.size until 3).map { i -> ArtifactBlock(newId(), BlockKind.ASSUMPTION, "${i + 1}. （待补：${if (i == 0) "你最想说的一句" else "另一面 / 一个例子"}）") }
        return Draft(question.take(20), blocks, listOf("模型这次没接上：这是从你的材料里拆出来的骨架，还没加工"))
    }

    /** 初稿骨架：每个要点一节，正文待展开。 */
    fun localDraft(question: String, outline: List<ArtifactBlock>, newId: () -> String): Draft = Draft(
        question.take(20),
        outline.map { b -> ArtifactBlock(newId(), BlockKind.ASSUMPTION, "${b.markdown.lines().first().trimStart('1', '2', '3', '.', ' ')}——（待展开）", b.sourceIds) },
        listOf("模型这次没接上：每段先留了论点，正文要你自己展开或稍后再让她写"),
    )

    /** 离线挑刺：三问 + 两条不用模型也能做的改法。 */
    fun localCritic(target: ArtifactBlock): Critique {
        val t = target.markdown.trim()
        return Critique(
            "（离线）先自查三问：这句有依据吗？换个人能反驳吗？对谁有用？",
            listOf(
                "补依据" to "$t（依据：待补）",
                "收窄说法" to "至少在（待补：什么情况）下，${t.trimStart('1', '2', '3', '.', ' ')}",
            ),
        )
    }
}
