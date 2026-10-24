package cn.companion.pet.core.companion

import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.probe.ProbePrompt
import cn.companion.pet.core.scene.Scene
import cn.companion.pet.core.text.UnicodeText
import cn.companion.pet.net.ChatMessage
import cn.companion.pet.net.ChatRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ContextLimits(
    val recallMaxItems: Int = 6,
    val recallMaxChars: Int = 800,
    val recentMaxItems: Int = 12,
    val recentMaxChars: Int = 2400,
    val dynamicMaxChars: Int = 5000,
    val materialMaxChars: Int = 1200,
)

/**
 * 统一的上下文组装器：稳定 persona 作为指令；记忆、最近交流、素材都作为带来源的数据。
 *
 * 这里不读整份 profile。连续性开关打开后，长期了解通过结构化事实和有界召回进入；
 * 关掉开关时调用方仍走旧的 ProbePrompt 路径。
 */
class CompanionContextAssembler(
    private val events: EventLog,
    private val facts: FactStore,
    private val zone: ZoneId = events.zone,
    private val recall: MemoryRecall = MemoryRecall(events, facts, zone),
    private val materials: MaterialInbox? = null,
) {
    fun assemble(
        persona: String,
        moment: ProbeMoment,
        material: String?,
        now: Long,
        tombstones: Set<String> = emptySet(),
        limits: ContextLimits = ContextLimits(),
        userQuery: String? = null,
    ): List<ChatMessage> {
        materials?.purgeExpired(now)
        val recentEvents = recentEvents(limits, tombstones, now)
        val recent = recentEvents.map { it.text to it.text }
        val query = userQuery?.takeIf { it.isNotBlank() } ?: buildQuery(moment, now, tombstones)
        // 已经在“最近交流”里的事件及其来源，召回不再重复。
        val recentIds = recentEvents.flatMap { it.sourceIds + it.id }.toSet()
        val recalled = recall.recall(
            query = query,
            now = now,
            tombstones = tombstones,
            excludeTexts = recent.map { it.second }.toSet(),
            limits = RecallLimits(limits.recallMaxItems, limits.recallMaxChars),
            excludeIds = recentIds,
        )

        val system = ChatMessage(ChatRole.SYSTEM, persona.trim() + "\n\n" + ProbePrompt.FORMAT_RULES)
        val body = StringBuilder()
        body.append("# 我记得的相关的事（按相关性，最多 ").append(limits.recallMaxItems).append(" 条）\n")
        if (recalled.isEmpty()) {
            body.append("（没有足够相关的事，不硬凑）\n")
        } else {
            recalled.forEach { item ->
                body.append("- ").append(item.day).append(" · ").append(item.sourceLabel).append(" · ").append(item.text).append('\n')
            }
        }
        body.append("\n# 最近的交流（最多 ").append(limits.recentMaxItems).append(" 条）\n")
        if (recent.isEmpty()) body.append("（还没有交流记录）\n") else recent.forEach { body.append(it.first).append('\n') }

        moment.topic?.trim()?.takeIf { it.isNotEmpty() }?.let {
            body.append("\n# 想接着聊的事（有真实来源）\n- ").append(it).append('\n')
        }
        val sharedBlock = materials?.contextBlock(now, maxItems = 2, maxChars = 900).orEmpty()
        if (sharedBlock.isNotEmpty()) body.append('\n').append(sharedBlock).append('\n')
        body.append("\n# 此刻\n")
        body.append("- 时间：").append(moment.timeText).append('\n')
        body.append("- 场景：").append(moment.scene.label).append('\n')
        body.append("- 前台：").append(moment.foregroundLabel).append('\n')
        body.append("- 行为轨迹：").append(moment.trajectory).append('\n')
        if (moment.petState.isNotBlank()) body.append("- 我自己此刻：").append(moment.petState).append('\n')
        if (moment.stuck) body.append("- 他在同一页停了一分半钟没动，可能卡住了。针对屏幕上的具体段落说一句。\n")
        if (moment.escalating) body.append("- 他还没回应上一句。\n")
        if (moment.rephrase) body.append("- 上次说的话他完全没理，这次换一种完全不同的说法和话题类型。\n")
        if (moment.weighty) body.append("- 这是今天少有的“有分量的开口”：从我攒下的话题或日记里挑一件想认真跟他说的事。\n")
        if (moment.afterHeartbeat) body.append("- 他刚亮屏，锁屏那段时间我轻轻敲过他。问一句刚才脑子里在想什么。\n")

        val screen = if (moment.scene == Scene.LEARNING) material?.trim().orEmpty() else ""
        val screenText = UnicodeText.truncate(sanitizeMarkers(screen), limits.materialMaxChars)
        fun screenBlock(text: String) = "\n" + ProbePrompt.SCREEN_OPEN + "\n" + text + "\n" + ProbePrompt.SCREEN_CLOSE + "\n"
        val materialBlock = if (screen.isNotEmpty()) screenBlock(screenText) else ""
        body.append(materialBlock)

        var text = body.toString()
        // 产品上限是“整个动态上下文”5000 码点。超了就按确定顺序先删最早的最近交流，再截素材。
        for ((line, _) in recent) {
            if (UnicodeText.count(text) <= limits.dynamicMaxChars) break
            text = text.replaceFirst(line + "\n", "")
        }
        if (UnicodeText.count(text) > limits.dynamicMaxChars && materialBlock.isNotEmpty()) {
            val withoutMaterial = text.removeSuffix(materialBlock)
            val available = limits.dynamicMaxChars - UnicodeText.count(withoutMaterial) - UnicodeText.count(screenBlock(""))
            text = if (available > 0) withoutMaterial + screenBlock(UnicodeText.truncate(screenText, available)) else withoutMaterial
        }
        if (UnicodeText.count(text) > limits.dynamicMaxChars && sharedBlock.isNotEmpty()) {
            // 分享材料是可选的证据块；超预算时整块丢弃，优先保留对话与当前素材标记。
            text = text.replace(sharedBlock, "").replace("\n\n\n", "\n\n")
        }
        if (UnicodeText.count(text) > limits.dynamicMaxChars) {
            text = UnicodeText.truncate(text, limits.dynamicMaxChars)
        }
        return listOf(system, ChatMessage(ChatRole.USER, text.trimEnd()))
    }

    /** 最近交流用已经格式化的一行一条，最多 12 条/2400 码点（从旧到新裁剪）。 */
    private fun recentEvents(limits: ContextLimits, tombstones: Set<String>, now: Long): List<CompanionEvent> {
        val snapshot = events.all()
        val visibility = EventVisibility(snapshot, tombstones, now)
        val all = snapshot
            .filter { it.kind == EventKind.EXCHANGE || it.kind == EventKind.LEGACY_LINE }
            .filter { visibility.allows(it) }
            .takeLast(limits.recentMaxItems)
            .toMutableList()
        var used = all.sumOf { UnicodeText.count(it.text) + 1 }
        while (all.isNotEmpty() && used > limits.recentMaxChars) {
            used -= UnicodeText.count(all.first().text) + 1
            all.removeAt(0)
        }
        return all
    }

    private fun buildQuery(moment: ProbeMoment, now: Long, tombstones: Set<String>): String {
        val snapshot = events.all()
        val visibility = EventVisibility(snapshot, tombstones, now)
        val userLines = snapshot
            .filter { it.kind == EventKind.USER_INPUT || it.kind == EventKind.NOTE || it.kind == EventKind.CORRECTION }
            .filter { it.occurredAt <= now && visibility.allows(it) }
            .takeLast(6)
            .joinToString(" ") { it.text }
        return listOf(
            moment.screenText.orEmpty(),
            moment.trajectory,
            moment.foregroundLabel,
            moment.scene.label,
            userLines,
        ).joinToString("\n")
    }

    private fun sanitizeMarkers(text: String): String =
        text.replace(ProbePrompt.SCREEN_CLOSE, "</screen_material_>")
            .replace(ProbePrompt.SCREEN_OPEN, "<screen_material_>")

    companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        fun dayOf(epochMillis: Long, zone: ZoneId): String =
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()
    }
}
