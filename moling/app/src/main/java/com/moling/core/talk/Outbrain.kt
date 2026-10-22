package com.moling.core.talk

import com.moling.core.mind.Actor
import com.moling.core.workspace.ArtifactKind
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.Artifacts
import com.moling.core.workspace.Focus
import com.moling.core.workspace.Material
import com.moling.core.workspace.PortError
import com.moling.core.workspace.WorkspaceArtifact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/*
 * 外脑会话（v13 第十二批 A1–A3）：同一个焦点从墨灵进外脑，再带着有来源的结果回来。
 *
 *  - [EntryPack]：进场包。问题、目标、体系图主干、最大缺口、材料摘要、待审草稿块、回合制契约、返回格式、来源索引。
 *    只放摘要，不把整库复制给外部服务；敏感材料默认不放，先预览再发。
 *  - [OutbrainSession]：一次外脑会话。绑定 sessionId / focusId / focusRevision / payloadHash / 起止时间 / artifactId；
 *    状态 PREPARED → OPENED → WAITING_RESULT → RESULT_RECEIVED → REVIEWED，另有 CANCELLED / EXPIRED。
 *  - [ActorResult]：外脑带回来的原文。按 sessionId + contentHash 去重；partial（上屏一半）不落成最终结论。
 *  - [Outbrain]：纯函数状态机（见 OutbrainMachine.kt）。不联网、不读 Android 权限；传输在宿主实现。
 *
 * 外脑原文是材料，不是命令：进来只落 ACTOR_RESULT 稿子（READY_FOR_REVIEW），要不要采用、要不要收进体系图由他点。
 */

@Serializable
enum class OutbrainStatus { PREPARED, OPENED, WAITING_RESULT, RESULT_RECEIVED, REVIEWED, CANCELLED, EXPIRED }

@Serializable
enum class OutbrainTransport { SHARE, API, RELAY }

/** 选路要的能力。不给模型名硬编码猜能力。 */
object OutbrainCaps {
    /** 能把进场包送出去（分享 / 复制 / API 请求）。 */
    const val SEND_PACK = "SEND_PACK"

    /** 能把文字结论带回来（分享回来 / 粘贴 / 读收件箱）。 */
    const val RETURN_TEXT = "RETURN_TEXT"

    /** 传输里能带同一个 sessionId（回来时对得上）。 */
    const val SESSION_ID = "SESSION_ID"

    /** 外脑可以异步读黑板、交回收件箱。 */
    const val ASYNC_RESULT = "ASYNC_RESULT"
}

fun OutbrainTransport.caps(): Set<String> = when (this) {
    OutbrainTransport.SHARE -> setOf(OutbrainCaps.SEND_PACK, OutbrainCaps.RETURN_TEXT, OutbrainCaps.SESSION_ID)
    OutbrainTransport.API -> setOf(OutbrainCaps.SEND_PACK, OutbrainCaps.RETURN_TEXT, OutbrainCaps.SESSION_ID)
    OutbrainTransport.RELAY -> setOf(OutbrainCaps.SEND_PACK, OutbrainCaps.RETURN_TEXT, OutbrainCaps.SESSION_ID, OutbrainCaps.ASYNC_RESULT)
}

fun OutbrainTransport.label(): String = when (this) {
    OutbrainTransport.SHARE -> "分享 / 复制"
    OutbrainTransport.API -> "模型 API"
    OutbrainTransport.RELAY -> "联络站（MCP）"
}

/** 一个可用端点。configured = 它现在真的能用（填了地址/密钥/装了 App），不是“理论上支持”。 */
@Serializable
data class OutbrainEndpoint(
    val id: String,
    val label: String,
    val transport: OutbrainTransport,
    val actor: Actor = Actor.NONE,
    val configured: Boolean = true,
    /** 用户偏好：数字小的先用。 */
    val preference: Int = 10,
    val note: String = "",
)

sealed interface OutbrainRoute {
    data class Pick(val endpoint: OutbrainEndpoint) : OutbrainRoute
    data class Failed(val error: PortError) : OutbrainRoute
}

/**
 * 选路：按“需要的能力 + 可用端点 + 用户偏好”选，不按模型名猜。
 * 偏好：用户在端点列表里排在前面的（[preferred] 里的 id）先用；其次 [preferTransport]；再其次 preference 数字；最后 id。
 */
object OutbrainRouter {
    fun pick(
        endpoints: List<OutbrainEndpoint>,
        need: Set<String> = setOf(OutbrainCaps.SEND_PACK, OutbrainCaps.RETURN_TEXT),
        preferred: Set<String> = emptySet(),
        preferTransport: OutbrainTransport? = null,
    ): OutbrainRoute {
        val configured = endpoints.filter { it.configured }
        val capable = configured.filter { e -> e.transport.caps().containsAll(need) }
        if (capable.isEmpty()) {
            val anyCapable = endpoints.filter { e -> e.transport.caps().containsAll(need) }
            return OutbrainRoute.Failed(
                if (anyCapable.isEmpty()) {
                    PortError("NO_CAPABLE", "没有能同时送出进场包、带回文字的端点")
                } else {
                    PortError("NOT_CONFIGURED", "外脑端点还没配置：" + anyCapable.joinToString("、") { it.label })
                },
            )
        }
        val pick = capable.sortedWith(
            compareBy(
                { if (it.id in preferred) 0 else 1 },
                { if (preferTransport != null && it.transport == preferTransport) 0 else 1 },
                { it.preference },
                { it.id },
            ),
        ).first()
        return OutbrainRoute.Pick(pick)
    }
}

// ------------------------------------------------------------------ 进场包

@Serializable
data class EntryMaterial(
    val id: String,
    val title: String,
    val kind: String,
    /** 摘要，不是全文。 */
    val summary: String,
    val sensitive: Boolean = false,
    /** 因为超长被截短。 */
    val trimmed: Boolean = false,
)

@Serializable
data class EntryBlock(val artifactId: String, val blockId: String, val markdown: String, val sourceIds: List<String> = emptyList())

/**
 * 进场包。控制长度：材料最多 6 条、每条摘要 400 字；待审草稿块最多 4 块、每块 500 字；整包 3500 字封顶。
 * [omitted] = 没放进来几条（他删掉的 + 敏感的 + 超上限的）；[truncated] = 有内容被截短。
 */
@Serializable
data class EntryPack(
    val sessionId: String,
    val focusId: String,
    val focusRevision: Long,
    val question: String,
    val goal: String,
    val spine: List<String> = emptyList(),
    val gap: String = "",
    val materials: List<EntryMaterial> = emptyList(),
    val drafts: List<EntryBlock> = emptyList(),
    val sourceIndex: List<String> = emptyList(),
    val omitted: Int = 0,
    val truncated: Boolean = false,
    val at: Long = 0,
) {
    /** 发出去 / 复制出去的那段话（超过上限截断，并明确标出来）。 */
    fun text(): String {
        val body = render().trim()
        return if (body.length > EntryPackLimits.MAX_TEXT_CHARS) {
            body.take(EntryPackLimits.MAX_TEXT_CHARS) + "\n…（进场包太长，已经截断）"
        } else {
            body
        }
    }

    private fun render(): String = buildString {
        appendLine("【外脑会话】$sessionId")
        appendLine("【回来时】把结论整段发回来；开头保留这一行：[[外脑:$sessionId]]")
        appendLine()
        appendLine("【这件事】$question")
        if (goal.isNotBlank()) appendLine("【目标】$goal")
        if (spine.isNotEmpty()) {
            appendLine("【体系图主干（他确认过的）】")
            spine.take(EntryPackLimits.MAX_SPINE).forEach { appendLine("- $it") }
        }
        if (gap.isNotBlank()) appendLine("【最大的缺口】$gap")
        if (materials.isNotEmpty()) {
            appendLine("【材料摘要】")
            materials.forEach { m ->
                appendLine("- [" + m.id + "] " + m.title.take(40) + "：" + m.summary + if (m.trimmed) "…" else "")
            }
        }
        if (drafts.isNotEmpty()) {
            appendLine("【待审草稿块（还没采用，别当结论）】")
            drafts.forEach { b -> appendLine("- [" + b.artifactId + "/" + b.blockId + "] " + b.markdown) }
        }
        appendLine("【这一轮怎么走】")
        appendLine("- 你是外脑，不是墨灵：只做这一件，不替他改稿，不替他交总控。")
        appendLine("- 你看到的是摘要，不是全部历史、通知和其它库。")
        appendLine("- 一次回合：先给结论，再给依据；不确定就标「待核」，看不到就直说「看不到」，不要编。")
        appendLine("【返回格式】")
        appendLine("- 结论 / 依据 / 不确定三块；每条依据后面标来源编号（m-… / n-…），没来源写「推测」。")
        appendLine("- 最多 600 字。")
        if (omitted > 0) appendLine("【没放进来】还有 $omitted 条材料没放进这个包（敏感或太长）；需要哪条就点名。")
        if (sourceIndex.isNotEmpty()) appendLine("【来源索引】" + sourceIndex.joinToString("、"))
    }
}

object EntryPackLimits {
    const val MAX_MATERIALS = 6
    const val MAX_MATERIAL_CHARS = 400
    const val MAX_DRAFTS = 4
    const val MAX_DRAFT_CHARS = 500
    const val MAX_SPINE = 5
    const val MAX_TEXT_CHARS = 3500
}

// ------------------------------------------------------------------ 会话

@Serializable
enum class OutbrainEventKind { PREPARED, OPENED, WAITING, RESULT_PARTIAL, RESULT_RECEIVED, REVIEWED, CANCELLED, EXPIRED, CLOSED, RECOVERED, DEGRADED, ATTRIBUTION, REOPENED, SNOOZED }

@Serializable
data class OutbrainEvent(val at: Long, val kind: OutbrainEventKind, val note: String = "")

@Serializable
data class OutbrainSession(
    val id: String,
    val focusId: String,
    val focusRevision: Long,
    val actor: Actor,
    val transport: OutbrainTransport,
    val endpointId: String,
    val status: OutbrainStatus,
    val payloadHash: String,
    val pack: EntryPack,
    val createdAt: Long,
    val openedAt: Long = 0,
    val closedAt: Long = 0,
    val expiresAt: Long = 0,
    val reviewedAt: Long = 0,
    val timeline: List<OutbrainEvent> = emptyList(),
    val resultIds: List<String> = emptyList(),
    val artifactId: String? = null,
    val lastError: String = "",
    /** 他点了「稍后」：这个时间前不再提醒/挂常驻通知。 */
    val snoozeUntil: Long = 0,
) {
    /** 还在等他/外脑回来（没关、没取消、没复查）。 */
    fun live(): Boolean = status == OutbrainStatus.PREPARED || status == OutbrainStatus.OPENED ||
        status == OutbrainStatus.WAITING_RESULT || status == OutbrainStatus.RESULT_RECEIVED

    /** 已经退场、还没收到最终结论：显示轻量收线卡，不算完成。 */
    fun needCollect(): Boolean = closedAt > 0 && status != OutbrainStatus.CANCELLED &&
        status != OutbrainStatus.REVIEWED && status != OutbrainStatus.RESULT_RECEIVED

    fun withEvent(at: Long, kind: OutbrainEventKind, note: String = ""): OutbrainSession =
        copy(timeline = (timeline + OutbrainEvent(at, kind, note)).takeLast(Outbrain.TIMELINE_KEEP))
}

/** 外脑带回来的一段。partial = 流式上屏的一半，不是最终回答；turn 用来合并同一轮流式。 */
@Serializable
data class ActorResult(
    val id: String,
    val sessionId: String,
    val actor: Actor,
    val text: String,
    val contentHash: String,
    val partial: Boolean = false,
    val turn: Int = 0,
    /** share / api / relay / paste / voice。 */
    val source: String = "",
    val receivedAt: Long,
)

@Serializable
data class OutbrainState(
    val sessions: List<OutbrainSession> = emptyList(),
    val results: List<ActorResult> = emptyList(),
    val seq: Long = 0,
    val log: List<String> = emptyList(),
)

object OutbrainCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(s: OutbrainState): String = json.encodeToString(OutbrainState.serializer(), trim(s))

    fun decode(raw: String?): OutbrainState =
        if (raw.isNullOrBlank()) OutbrainState()
        else runCatching { json.decodeFromString(OutbrainState.serializer(), raw) }.getOrElse {
            // 读坏了：不把 seq 归零（否则新会话会和旧 ob-1/ob-2 撞号、把旧结果认错）。
            // 用当前毫秒当初值，编号照样可用，只是不再从小数字重来。
            OutbrainState(seq = System.currentTimeMillis())
        }

    /** 控制体积；会话本身留最近 40 个，结果留 200 条。 */
    fun trim(s: OutbrainState): OutbrainState = s.copy(
        sessions = s.sessions.takeLast(40),
        results = s.results.takeLast(200),
        log = s.log.takeLast(100),
    )
}

/** 收到结果后的处理结果（给宿主决定要不要落稿子）。 */
data class ReceiveOutcome(
    val session: OutbrainSession?,
    val result: ActorResult,
    /** 真的挂到了会话上（true）还是只进历史（false）。 */
    val attached: Boolean,
    /** 会话已取消/过期/复查过：结果只进历史，不覆盖当前焦点。 */
    val history: Boolean = false,
    /** 和已有结果重复（去重命中）。 */
    val duplicate: Boolean = false,
    /** 没有最终结论：partial 还只在上屏。 */
    val partialOnly: Boolean = false,
)

object Packs {
    /** 敏感材料：默认不放进进场包。 */
    private val sensitiveRe = Regex(
        "(密码|口令|验证码|身份证|银行卡|信用卡|私钥|密钥|api[_-]?key|token|secret|password)",
        RegexOption.IGNORE_CASE,
    )

    fun isSensitive(m: Material): Boolean = sensitiveRe.containsMatchIn(m.title + "\n" + m.text)

    /** 从当前焦点的材料 + 稿子拼进场包（纯函数，不联网）。 */
    fun build(
        sessionId: String,
        focus: Focus,
        materials: List<Material>,
        artifacts: List<WorkspaceArtifact>,
        spine: List<String> = emptyList(),
        gap: String = "",
        now: Long = 0,
        exclude: Set<String> = emptySet(),
        includeSensitive: Boolean = false,
        maxMaterials: Int = EntryPackLimits.MAX_MATERIALS,
    ): EntryPack {
        val mine = focus.materialIds.mapNotNull { id -> materials.firstOrNull { it.id == id } }
        val usable = mine.filter { it.id !in exclude && (includeSensitive || !isSensitive(it)) }
        var truncated = false
        val picked = usable.take(maxMaterials).map { m ->
            val body = m.text.trim().replace(Regex("\\s+"), " ")
            val cut = body.length > EntryPackLimits.MAX_MATERIAL_CHARS
            if (cut) truncated = true
            EntryMaterial(m.id, m.title.ifBlank { body.take(20) }, m.kind, body.take(EntryPackLimits.MAX_MATERIAL_CHARS), isSensitive(m), cut)
        }
        // 待审草稿块：最新稿子里还没采用的正文段，只给这些。
        val drafts = mutableListOf<EntryBlock>()
        val ordered = artifacts
            .filter { it.focusId == focus.id && it.status != ArtifactStatus.ARCHIVED && it.kind != ArtifactKind.ACTOR_RESULT }
            .sortedByDescending { it.createdAt }
        outer@ for (a in ordered) {
            for (b in a.blocks) {
                if (drafts.size >= EntryPackLimits.MAX_DRAFTS) break@outer
                if (!Artifacts.isContent(b) || b.exerciseOnly || b.accepted) continue
                val md = b.markdown.trim().replace(Regex("\\s+"), " ")
                if (md.isBlank()) continue
                if (md.length > EntryPackLimits.MAX_DRAFT_CHARS) truncated = true
                drafts += EntryBlock(a.id, b.id, md.take(EntryPackLimits.MAX_DRAFT_CHARS), b.sourceIds)
            }
        }
        val sources = (picked.map { it.id } + drafts.flatMap { it.sourceIds }).distinct()
        val omitted = mine.size - picked.size
        return EntryPack(
            sessionId = sessionId,
            focusId = focus.id,
            focusRevision = focus.revision,
            question = focus.question,
            goal = focus.goal,
            spine = spine.take(EntryPackLimits.MAX_SPINE),
            gap = gap,
            materials = picked,
            drafts = drafts,
            sourceIndex = sources,
            omitted = omitted,
            truncated = truncated,
            at = now,
        )
    }

    fun hash(vararg parts: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001").toByteArray())
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    fun payloadHash(p: EntryPack): String = hash(
        p.focusId,
        p.focusRevision.toString(),
        p.question,
        p.goal,
        p.spine.joinToString("|"),
        p.gap,
        p.materials.joinToString("|") { it.id + ":" + it.summary },
        p.drafts.joinToString("|") { it.artifactId + "/" + it.blockId },
    )
}
