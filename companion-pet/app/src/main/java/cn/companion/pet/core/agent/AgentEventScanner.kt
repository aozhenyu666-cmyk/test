package cn.companion.pet.core.agent

import cn.companion.pet.core.companion.CompanionEvent
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventVisibility

/**
 * 按事件 ID（不是“最后一条”或 occurredAt）找未处理的用户 NOTE/MATERIAL。
 * 同毫秒事件、NOTE 后紧跟 EXCHANGE、重启后已处理 ID 都能正确处理；
 * 被 FORGET/来源失效的事件不会再次触发。
 */
class AgentEventScanner(
    private val maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    fun next(
        events: List<CompanionEvent>,
        processedIds: Set<String>,
        now: Long,
        visibility: EventVisibility,
    ): CompanionEvent? = events.firstOrNull { event ->
        (event.kind == EventKind.NOTE || event.kind == EventKind.MATERIAL) &&
            event.id !in processedIds &&
            event.occurredAt >= now - maxAgeMillis &&
            event.source != "agent" &&
            event.source != "tool" &&
            visibility.allows(event)
    }
}
