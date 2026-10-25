package cn.companion.pet.core.skill

import cn.companion.pet.core.device.DeviceObservation
import cn.companion.pet.core.device.NodeRef
import kotlinx.serialization.Serializable

/**
 * 技能：用户自己搭的一串固定步骤（参考 JARVIS Skills v3 / MacroDroid）。
 * 不录触摸：每一步都是明确的动作 + 明确挑选的目标元素，回放时按元素找，找不到就如实失败。
 */
@Serializable
enum class SkillStepType { OPEN_APP, TAP, INPUT, SCROLL, BACK, WAIT, WAIT_FOR }

/** 目标元素：按资源 ID / 文字找。不存坐标，布局变了宁可失败也不点错地方。 */
@Serializable
data class ElementTarget(
    val text: String = "",
    val resourceId: String = "",
    val role: String = "",
    /** 应用守卫：目标所在的应用；前台不是它就不执行。 */
    val packageName: String = "",
) {
    fun describe(): String = when {
        text.isNotBlank() -> "“" + text.take(24) + "”"
        resourceId.isNotBlank() -> resourceId.substringAfterLast('/').take(32)
        else -> "（未命名元素）"
    }

    companion object {
        fun of(node: NodeRef) = ElementTarget(node.text.take(80), node.resourceId, node.role.substringAfterLast('.'), node.packageName)
    }
}

@Serializable
data class SkillStep(
    val id: String,
    val type: SkillStepType,
    /** OPEN_APP 的包名。 */
    val appPackage: String = "",
    /** 给人看的应用名（OPEN_APP）。 */
    val appLabel: String = "",
    val target: ElementTarget? = null,
    /** INPUT 要写的文字（用户自己填的，不会是密码框：密码框不进观察）。 */
    val text: String = "",
    val forward: Boolean = true,
    val waitMs: Long = 0,
) {
    fun describe(): String = when (type) {
        SkillStepType.OPEN_APP -> "打开 " + appLabel.ifBlank { appPackage }
        SkillStepType.TAP -> "点 " + (target?.describe() ?: "?")
        SkillStepType.INPUT -> "在 " + (target?.describe() ?: "?") + " 里写“" + text.take(20) + "”"
        SkillStepType.SCROLL -> if (forward) "往下滚" else "往上滚"
        SkillStepType.BACK -> "返回"
        SkillStepType.WAIT -> "等 " + (waitMs / 1000.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } + " 秒"
        SkillStepType.WAIT_FOR -> "等到出现 " + (target?.describe() ?: "?")
    }
}

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val steps: List<SkillStep> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long = 0,
    val lastRunOk: Boolean? = null,
    /** 上次运行每一步一行：“✓ 1 打开 哔哩哔哩”/“✗ 3 点“发送” — 等了 8 秒没出现”。 */
    val lastRunLog: List<String> = emptyList(),
)

/**
 * 按目标在当前观察里找元素。顺序：资源 ID + 文字都对 → 资源 ID 唯一 → 文字完全相同 → 文字包含。
 * 多个候选时优先可点/可编辑的；找不到返回 null，不猜。
 */
object ElementResolver {
    fun resolve(target: ElementTarget, observation: DeviceObservation, forInput: Boolean = false): NodeRef? {
        if (target.packageName.isNotBlank() && observation.packageName != target.packageName) return null
        val nodes = observation.nodes.filter { (if (forInput) it.editable else true) && it.right > it.left && it.bottom > it.top }
        fun pick(c: List<NodeRef>): NodeRef? = c.firstOrNull { if (forInput) it.editable else it.clickable } ?: c.firstOrNull()
        val text = target.text.trim()
        val rid = target.resourceId.trim()
        if (rid.isNotEmpty() && text.isNotEmpty()) pick(nodes.filter { it.resourceId == rid && it.text.trim() == text })?.let { return it }
        if (rid.isNotEmpty()) {
            val byId = nodes.filter { it.resourceId == rid }
            // 同一个 ID 有很多（列表项），只有文字也对得上才算；ID 唯一时直接用。
            if (byId.size == 1) return byId.single()
            if (byId.size > 1 && text.isEmpty()) return null
        }
        if (text.isNotEmpty()) {
            pick(nodes.filter { it.text.trim() == text })?.let { return it }
            if (text.length >= 2) pick(nodes.filter { it.text.contains(text, ignoreCase = true) })?.let { return it }
        }
        return null
    }

    /** 给挑选界面用：这一屏值得挑的元素（可点或可编辑、有文字或 ID），按从上到下、从左到右排。 */
    fun pickable(observation: DeviceObservation, limit: Int = 12): List<NodeRef> =
        observation.nodes
            .filter { (it.clickable || it.editable) && (it.text.isNotBlank() || it.resourceId.isNotBlank()) }
            .filter { it.right > it.left && it.bottom > it.top }
            .distinctBy { it.text.trim() + "|" + it.resourceId }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .take(limit)
}
