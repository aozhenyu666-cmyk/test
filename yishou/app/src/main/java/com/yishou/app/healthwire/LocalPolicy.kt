package com.yishou.app.healthwire

import com.yishou.app.health.Policy
import org.json.JSONArray
import org.json.JSONObject

/*
 * 一手的本地监管规则（规范 §4）。
 *
 * 为什么要自己存一份：
 *  - 「断网 / 哨兵失联，已启用的本地规则必须照常生效」（规范 §0 铁律 3）。
 *  - 哨兵保存并回了 acceptedRevision 之前，界面不能显示「已就绪」（规范 §4「先持久化再开始监管」）。
 *  - 规则过期必须停止执行（[Policy.effective] 已经保证），不靠心跳续命。
 *
 * 这里是**纯 JSON ↔ 数据类**的转换，不碰文件、不碰 Android，可以在 JVM 单测里直接验。
 * 字段与规范 §4 逐字对应，一个不能少：schemaVersion / policyId / revision / issuer /
 * requiredApps / startsAtWall / endsAtWall / lockAtWall / failureAction / repairGraceMs /
 * emergencyState / enabled。
 */
object LocalPolicy {

    const val SCHEMA_VERSION = 2

    /** 一手自己的 issuer 名（规范 §4 的 issuer 取值里是 `yishou`）。 */
    const val ISSUER = "yishou"

    data class Doc(
        val schemaVersion: Int = SCHEMA_VERSION,
        val policies: List<Policy> = emptyList(),
    ) {
        /** 文档级修订号：取所有规则里最大的 revision（哨兵同步回来的是这个口径）。 */
        val revision: Int get() = policies.maxOfOrNull { it.revision } ?: 0

        val active: List<Policy> get() = policies.filter { it.enabled }

        fun effectiveAt(nowWall: Long): List<Policy> = policies.filter { it.effective(nowWall) }

        fun byId(policyId: String): Policy? = policies.firstOrNull { it.policyId == policyId }
    }

    fun parse(text: String?): Doc {
        if (text.isNullOrBlank()) return Doc()
        return try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("policies") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { policyOf(it) }
            }
            Doc(o.optInt("schemaVersion", SCHEMA_VERSION), list)
        } catch (e: Exception) {
            // 文件坏了不能崩：退回空规则（= 不监管），并且界面要能看出「本地规则读不出来」。
            Doc()
        }
    }

    private fun policyOf(o: JSONObject): Policy? {
        val id = o.optString("policyId").trim()
        if (id.isEmpty()) return null
        val required = o.optJSONArray("requiredApps")?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
        }.orEmpty()
        val lockAt = o.optLong("lockAtWall", 0L).let { if (it > 0L) it else null }
        return Policy(
            policyId = id,
            revision = o.optInt("revision", 1).coerceAtLeast(1),
            issuer = o.optString("issuer", ISSUER).ifBlank { ISSUER },
            requiredApps = required,
            startsAtWall = o.optLong("startsAtWall", 0L),
            endsAtWall = o.optLong("endsAtWall", 0L),
            lockAtWall = lockAt,
            failureAction = o.optString("failureAction", "LOCK").let { if (it == "NOTIFY_ONLY") it else "LOCK" },
            repairGraceMs = o.optLong("repairGraceMs", 2 * 60_000L),
            emergencyState = o.optString("emergencyState", "NONE").let { if (it == "STOPPED") it else "NONE" },
            enabled = o.optBoolean("enabled", false),
        )
    }

    fun toJson(doc: Doc): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put(
            "policies",
            JSONArray().apply {
                doc.policies.forEach { p ->
                    put(
                        JSONObject()
                            .put("policyId", p.policyId)
                            .put("revision", p.revision)
                            .put("issuer", p.issuer)
                            .put("requiredApps", JSONArray().apply { p.requiredApps.sorted().forEach { put(it) } })
                            .put("startsAtWall", p.startsAtWall)
                            .put("endsAtWall", p.endsAtWall)
                            .put("lockAtWall", p.lockAtWall ?: 0L)
                            .put("failureAction", p.failureAction)
                            .put("repairGraceMs", p.repairGraceMs)
                            .put("emergencyState", p.emergencyState)
                            .put("enabled", p.enabled),
                    )
                }
            },
        )
        .toString()

    /** 一条规则的形态是否讲得通（窗口不倒挂、时间不是 0）。 */
    fun validate(p: Policy): String? = when {
        p.policyId.isBlank() -> "policyId 不能为空"
        p.startsAtWall <= 0L -> "startsAtWall 必须是有效墙上时间"
        p.endsAtWall <= p.startsAtWall -> "endsAtWall 必须晚于 startsAtWall"
        p.requiredApps.isEmpty() -> "requiredApps 不能为空"
        p.lockAtWall != null && p.lockAtWall <= p.startsAtWall -> "lockAtWall 必须晚于 startsAtWall"
        else -> null
    }

    /**
     * 合并一条从哨兵同步过来的规则（规范 §4：**只能改自己的规则**）。
     *
     * @return 合并后的文档；哨兵的 revision 不比本地新时不覆盖（防止旧消息把新规则顶掉）。
     */
    fun merge(doc: Doc, incoming: Policy): Pair<Doc, String?> {
        val problem = validate(incoming)
        if (problem != null) return doc to problem
        if (incoming.issuer != ISSUER) {
            return doc to "只接受 issuer=yishou 的规则（收到 ${incoming.issuer}）"
        }
        val existing = doc.byId(incoming.policyId)
        if (existing != null && incoming.revision <= existing.revision) {
            return doc to null // 旧版本：忽略，不算失败
        }
        val next = doc.policies.filter { it.policyId != incoming.policyId } + incoming
        return Doc(SCHEMA_VERSION, next.sortedBy { it.policyId }) to null
    }

    /** 紧急暂停：把本轮所有规则标成 STOPPED（不删除规则、不放行任务）。 */
    fun stopAll(doc: Doc): Doc = Doc(
        SCHEMA_VERSION,
        doc.policies.map { it.copy(emergencyState = "STOPPED") },
    )

    /** 紧急暂停解除（用户回来重新开始）：清掉 STOPPED 标记。 */
    fun resumeAll(doc: Doc): Doc = Doc(
        SCHEMA_VERSION,
        doc.policies.map { it.copy(emergencyState = "NONE") },
    )

    /** 任何一条规则还处于 STOPPED，就不自动锁屏（规范 §6：紧急暂停立即生效）。 */
    fun anyStopped(doc: Doc): Boolean = doc.policies.any { it.emergencyState == "STOPPED" }
}
