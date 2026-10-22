package com.zongkong.core.work

import com.zongkong.core.LlmClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

/*
 * 交付（0.13）：做完一步，交证据，总控看一眼给结论。
 *
 * 以前的“记录结果”只有文字和链接，交照片要绕去对话里发；而且结论全靠自己选“达到 / 部分达到”。
 * 现在：
 *   - 证据：照片（现拍或相册）、链接、一句话——任选，有一样就行
 *   - 总控看：AI 对照“完成依据”看证据（照片真的看），给“达到 / 部分达到 / 没达到”和一句理由
 *   - 最多两轮：第一轮可以说一句“补什么就够了”，你补一次，第二轮必须给结论，不再追问
 *   - 不认：可以按自己的判断记，但会标上“本人改判”，档案里看得到
 *   - 没配 AI：照旧自评（标“自评”）
 * 照片防糊弄：同一张照片不能交两件事；没有拍摄时间（截图 / 转存）、拍在开始之前的，告诉 AI 也告诉你。
 */

@Serializable
data class ProofItem(
    /** photo / link / text */
    val kind: String,
    /** 照片：本机路径；链接：网址；文字：内容。 */
    val value: String,
    /** 照片的拍摄时间（EXIF），没有 = 0。 */
    val takenAt: Long = 0,
    /** 照片内容的指纹（同一张不能交两次）。 */
    val hash: String = "",
) {
    companion object {
        const val PHOTO = "photo"
        const val LINK = "link"
        const val TEXT = "text"
    }
}

/** 一轮审看的结论。by = ai / self。 */
@Serializable
data class ProofRound(
    val at: Long,
    val met: String,
    val reason: String,
    /** 第一轮可以说一句“补什么就够了”。 */
    val ask: String = "",
    val by: String = "ai",
)

/** 一次交付的完整记录（本机保存，照片不上传 Notion）。 */
@Serializable
data class Delivery(
    val actionKey: String,
    val title: String,
    val items: List<ProofItem>,
    val rounds: List<ProofRound>,
    /** 最后记的结论（WorkOps.Met 的 name）。 */
    val final: String,
    /** ai = 按总控的结论；self = 自评（没配 AI 或不让看）；override = 本人改判。 */
    val source: String,
    val at: Long,
)

object Proof {
    const val MAX_ROUNDS = 2

    // ---------- 照片的疑点 ----------

    /**
     * 照片的疑点（给 AI 看，也给你看）。不直接拒收——拍得早不一定是假的，但要让看的人知道。
     * [startedAt]：这一步开始做的时刻（没开始过 = null）。[used]：照片指纹 → 交过的那一步的标题。
     */
    fun flags(p: ProofItem, startedAt: Long?, now: Long, used: Map<String, String>, zone: ZoneId): List<String> {
        if (p.kind != ProofItem.PHOTO) return emptyList()
        return buildList {
            if (p.hash.isNotBlank()) used[p.hash]?.let { add("这张照片交过「$it」了") }
            when {
                p.takenAt <= 0 -> add("没有拍摄时间（截图或转存的图）")
                p.takenAt > now + 5 * 60_000 -> add("拍摄时间在未来（手机时间不对？）")
                startedAt != null && p.takenAt < startedAt - 10 * 60_000 -> add("拍于 ${hm(p.takenAt, zone)}，在这一步开始（${hm(startedAt, zone)}）之前")
                startedAt == null && now - p.takenAt > 24 * 3_600_000L -> add("拍于 ${(now - p.takenAt) / 86_400_000L} 天前")
            }
        }
    }

    /** 重复交的照片：直接不收。 */
    fun duplicate(p: ProofItem, used: Map<String, String>): Boolean = p.kind == ProofItem.PHOTO && p.hash.isNotBlank() && p.hash in used

    // ---------- 让 AI 看 ----------

    val SYSTEM = """
        你是「总控」的验收官，用户做完一步行动，交来证据（照片、链接、文字），你对照这一步的“完成依据”下结论。
        规矩：
        1. 只看证据和完成依据。证据能合理说明做了、大体符合完成依据，就判“达到”。不吹毛求疵，不要求格式，不要求额外材料；一张能看出做完的照片就够。
        2. 证据只说明做了一部分，判“部分达到”；证据和这一步对不上、看不出做了，判“没达到”。
        3. 照片带着“疑点”（重复、没有拍摄时间、拍在开始之前）时要考虑，但疑点本身不等于没做：截图交的是电子产出很正常。
        4. 理由一句话，具体说看到了什么。用中文，称呼“你”。
        5. 只有第一轮、而且不是“达到”时，可以在 ask 里说一句“补什么就够了”（最多一样东西）；第二轮必须给结论，ask 留空。
        只输出一个 JSON 对象，不要输出别的：
        {"met": "达到" 或 "部分达到" 或 "没达到", "reason": "一句话", "ask": "第一轮可选：补什么就够了"}
    """.trimIndent()

    /**
     * 给 AI 的话。照片另外附（拼成一张图），这里只写清楚有几张、各自的拍摄时间和疑点。
     * [previous]：第二轮时，上一轮的结论（你按它补了东西）。
     */
    fun prompt(
        title: String,
        criteria: String,
        items: List<ProofItem>,
        flags: Map<Int, List<String>>,
        round: Int,
        previous: ProofRound?,
        zone: ZoneId,
    ): String = buildString {
        append("这一步：$title\n")
        append("完成依据：${criteria.ifBlank { "（没写。按“这一步的标题说的事做了没有”判断）" }}\n")
        append("第 $round 轮（最多 $MAX_ROUNDS 轮）。\n")
        previous?.let { append("上一轮你的结论：${it.met}——${it.reason}${if (it.ask.isNotBlank()) "；你让补：${it.ask}" else ""}。用户已经补交，这一轮必须给结论。\n") }
        append("\n证据：\n")
        var photoNo = 0
        items.forEachIndexed { i, p ->
            when (p.kind) {
                ProofItem.PHOTO -> {
                    photoNo++
                    append("- 照片 $photoNo（见附图，按顺序从左到右、从上到下）")
                    if (p.takenAt > 0) append("，拍于 ${hm(p.takenAt, zone)}")
                    flags[i]?.takeIf { it.isNotEmpty() }?.let { append("；疑点：${it.joinToString("；")}") }
                    append('\n')
                }
                ProofItem.LINK -> append("- 链接：${p.value}\n")
                else -> append("- 说明：${p.value.trim()}\n")
            }
        }
        if (items.isEmpty()) append("（什么都没交）\n")
    }

    fun parse(reply: String, round: Int, now: Long): ProofRound? {
        val o = LlmClient.extractJson(reply) ?: return null
        val raw = o["met"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val met = WorkOps.Met.entries.firstOrNull { it.label == raw || it.name.equals(raw, true) }
            ?: when {
                raw.contains("部分") -> WorkOps.Met.PARTIAL
                raw.contains("没") || raw.contains("未") -> WorkOps.Met.NO
                raw.contains("达到") || raw.contains("通过") -> WorkOps.Met.YES
                else -> return null
            }
        val reason = o["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { met.label }
        val ask = if (round < MAX_ROUNDS && met != WorkOps.Met.YES) o["ask"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty() else ""
        return ProofRound(now, met.name, reason, ask)
    }

    /** 还能再看一轮吗：第一轮没达到 / 部分达到、而且总控说了要补什么。 */
    fun canRetry(rounds: List<ProofRound>): Boolean =
        rounds.size < MAX_ROUNDS && rounds.lastOrNull()?.let { it.by == "ai" && it.met != WorkOps.Met.YES.name && it.ask.isNotBlank() } == true

    // ---------- 记下 ----------

    /**
     * 写进“结果”的那段文字：做了什么 + 证据清单 + 谁判的。照片只在手机里，这里只写几张、几点拍的。
     */
    fun resultText(note: String, items: List<ProofItem>, rounds: List<ProofRound>, final: WorkOps.Met, source: String, zone: ZoneId): String = buildString {
        val text = items.filter { it.kind == ProofItem.TEXT }.joinToString("；") { it.value.trim() }
        append(listOf(note.trim(), text).filter { it.isNotBlank() }.distinct().joinToString("；"))
        val photos = items.filter { it.kind == ProofItem.PHOTO }
        val links = items.filter { it.kind == ProofItem.LINK }
        val ev = buildList {
            if (photos.isNotEmpty()) add("照片 ${photos.size} 张" + photos.mapNotNull { p -> p.takenAt.takeIf { it > 0 }?.let { hm(it, zone) } }.takeIf { it.isNotEmpty() }?.let { "（${it.joinToString("、")} 拍）" }.orEmpty())
            links.forEach { add(it.value) }
        }
        if (ev.isNotEmpty()) append("\n证据：").append(ev.joinToString("；"))
        val last = rounds.lastOrNull { it.by == "ai" }
        append('\n')
        append(
            when (source) {
                "ai" -> "总控看过：${final.label}——${last?.reason.orEmpty()}"
                "override" -> "总控判${last?.let { WorkOps.Met.valueOf(it.met).label } ?: "？"}（${last?.reason.orEmpty()}），本人改判为${final.label}"
                else -> "自评：${final.label}"
            },
        )
    }.trim()

    fun hm(t: Long, zone: ZoneId): String = Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
}

/** 让 AI 看一轮。照片拼成一张图（[collage]，本机路径；没有照片就空）随消息发过去。 */
class ProofReviewer(private val chat: com.zongkong.core.agent.ChatApi) {
    suspend fun review(
        title: String,
        criteria: String,
        items: List<ProofItem>,
        flags: Map<Int, List<String>>,
        previous: ProofRound?,
        collage: String,
        now: Long,
        zone: ZoneId,
    ): com.zongkong.core.ApiResult<ProofRound> {
        val round = if (previous == null) 1 else 2
        val msg = com.zongkong.core.agent.ChatMsg(
            com.zongkong.core.agent.ChatMsg.USER,
            Proof.prompt(title, criteria, items, flags, round, previous, zone),
            at = now, image = collage,
        )
        return when (val r = chat.chat(Proof.SYSTEM, listOf(msg), emptyList())) {
            is com.zongkong.core.ApiResult.Err -> r
            is com.zongkong.core.ApiResult.Ok -> Proof.parse(r.value.content, round, now)
                ?.let { com.zongkong.core.ApiResult.Ok(it) }
                ?: com.zongkong.core.ApiResult.Err("总控的回答不是约定格式：${r.value.content.take(60)}")
        }
    }
}
