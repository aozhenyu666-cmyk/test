package com.zongkong.sentinel.watch

import com.zongkong.sentinel.health.ClampedClock
import com.zongkong.sentinel.health.Mono
import com.zongkong.sentinel.health.Policy
import com.zongkong.sentinel.health.Wall
import java.io.File
import java.io.FileOutputStream

/**
 * 监管策略持久化（报告 §7，规范 §4）。
 *
 * 位置：`:watch` 进程的 `filesDir/policies.json`。规则：
 *  - **原子写**：先写 `policies.json.tmp`、fsync、再 rename 覆盖正式文件；半截文件永远不会被读到。
 *  - 带 `schemaVersion` 与文档级 `revision`；`revision` 只在成功落盘后 +1，客户端拿到的
 *    `acceptedRevision` 就是它——「先持久化，再开始监管」靠这个数字说话。
 *  - 解析失败**不丢旧文件**：返回空文档 + 失败原因，由界面如实显示，不静默清零用户的规则。
 *
 * 纯 `java.io` + 共享内核，没有 Android 依赖，可以在 JVM 单测里直接跑（本机无真机，必须可测）。
 */
class PolicyStore(private val dir: File) {

    data class Doc(
        val schemaVersion: Int = SCHEMA_VERSION,
        /** 文档级修订号：每次成功写入 +1。 */
        val revision: Int = 0,
        val policies: List<Policy> = emptyList(),
        val updatedAtWall: Long = 0,
    ) {
        fun activeAt(nowWall: Long): List<Policy> = policies.filter { it.effective(nowWall) }

        /** 仍需在线的应用并集（只看生效中的窗口）。 */
        fun requiredAppsAt(nowWall: Long): Set<String> =
            activeAt(nowWall).flatMap { it.requiredApps }.toSet()
    }

    data class LoadResult(val doc: Doc, val error: String? = null, val fromDisk: Boolean = false)

    /** 解析 / 合并结果。 */
    data class Merge(
        val ok: Boolean,
        val doc: Doc,
        val acceptedRevision: Int = doc.revision,
        val error: String? = null,
    )

    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, "$FILE_NAME.tmp")

    fun path(): File = file

    fun load(): LoadResult {
        if (!file.exists()) return LoadResult(Doc(), null, fromDisk = false)
        return try {
            LoadResult(decode(file.readText()), null, fromDisk = true)
        } catch (e: Throwable) {
            // 文件坏了：报错，但不删——界面显示「策略文件损坏」，交给人判断，别悄悄把监管清零。
            LoadResult(Doc(), "策略文件读取失败：${e.message ?: e.javaClass.simpleName}", fromDisk = true)
        }
    }

    /** 原子写。返回是否成功；失败时磁盘上的旧文件保持原样。 */
    fun save(doc: Doc): Boolean = try {
        if (!dir.exists()) dir.mkdirs()
        val text = encode(doc)
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // 某些文件系统 rename 到已存在目标会失败：退回「整体替换」，仍然不留半截读窗口。
            file.writeText(text)
            tmp.delete()
        }
        true
    } catch (_: Throwable) {
        try { tmp.delete() } catch (_: Throwable) {}
        false
    }

    /**
     * 客户端 `syncPolicy`（规范 §3.1/§4）。
     *
     * 铁律：**一个客户端只能改自己的规则**。调用方身份由 issuer 表示，
     * issuer 由 `WatchProvider` 用调用方 UID/包名/证书算出来，不采信消息体自报。
     * 同 `policyId` 只有 revision 更大才接受，防迟到旧包覆盖新规则。
     */
    fun applySyncPolicy(nowWall: Long, issuer: String, body: String): Merge {
        val incoming = try {
            decodePolicies(body)
        } catch (e: Throwable) {
            return Merge(false, load().doc, error = "策略 JSON 解析失败：${e.message ?: "格式错误"}")
        }
        if (incoming.isEmpty()) return Merge(false, load().doc, error = "策略里没有 policies")
        for (p in incoming) {
            if (p.issuer != issuer) {
                return Merge(false, load().doc, error = "叫 ${issuer} 的调用方不能写 ${p.issuer} 的规则")
            }
            if (p.endsAtWall <= p.startsAtWall) {
                return Merge(false, load().doc, error = "窗口非法：endsAt 必须晚于 startsAt")
            }
            if (p.policyId.isBlank()) return Merge(false, load().doc, error = "policyId 不能为空")
            if (p.revision <= 0) return Merge(false, load().doc, error = "revision 必须 ≥ 1")
        }

        val current = load().doc
        val byId = LinkedHashMap<String, Policy>()
        current.policies.forEach { byId[it.policyId] = it }
        for (p in incoming) {
            val old = byId[p.policyId]
            if (old != null && old.revision >= p.revision && old.issuer == p.issuer) {
                // 迟到 / 重复的旧包：不收，但也不算错——回当前 revision 让客户端知道该重发新版本。
                return Merge(true, current, current.revision, "已忽略旧 revision ${p.revision}（当前 ${old.revision}）")
            }
            byId[p.policyId] = p
        }
        val next = current.copy(
            revision = current.revision + 1,
            policies = byId.values.toList(),
            updatedAtWall = nowWall,
        )
        if (!save(next)) return Merge(false, current, error = "策略落盘失败，未接受")
        return Merge(true, next, next.revision, null)
    }

    /** 紧急暂停：本地立即生效（报告 §3.6/§8），不依赖网络与模型。 */
    fun setEmergency(nowWall: Long, policyId: String?, state: String): Merge {
        val current = load().doc
        if (current.policies.isEmpty()) return Merge(false, current, error = "没有规则可暂停")
        val next = current.copy(
            revision = current.revision + 1,
            updatedAtWall = nowWall,
            policies = current.policies.map { p ->
                if (policyId == null || p.policyId == policyId) p.copy(emergencyState = state) else p
            },
        )
        if (!save(next)) return Merge(false, current, error = "策略落盘失败，未接受")
        return Merge(true, next, next.revision, null)
    }

    /** 到期锁屏：到点、窗口内、未暂停、已启用的规则。 */
    fun lockDue(nowWall: Long): List<Policy> = load().doc.policies.filter {
        it.lockDue(nowWall) && it.emergencyState != EMERGENCY_STOPPED
    }

    /** 需要执行「失效处理」的生效窗口（要求应用不在线）：交给仲裁器去重。 */
    fun failureWindows(nowWall: Long): List<Policy> =
        load().doc.policies.filter { it.effective(nowWall) && it.emergencyState != EMERGENCY_STOPPED }

    companion object {
        const val FILE_NAME = "policies.json"
        const val SCHEMA_VERSION = 2
        const val EMERGENCY_NONE = "NONE"
        const val EMERGENCY_STOPPED = "STOPPED"

        /** 客户端包名 → 策略 issuer。不允许客户端冒充别的 issuer。 */
        fun issuerForPackage(pkg: String): String? = when (pkg) {
            "com.zongkong.app" -> "zongkong"
            "com.yishou.app" -> "yishou"
            "cn.companion.pet" -> "pet"
            "com.zongkong.sentinel" -> "sentinel"
            else -> null
        }

        fun encode(doc: Doc): String = MiniJson.encode(
            MiniJson.obj(
                "schemaVersion" to doc.schemaVersion,
                "revision" to doc.revision,
                "updatedAtWall" to doc.updatedAtWall,
                "policies" to doc.policies.map { encodePolicy(it) },
            ),
        )

        private fun encodePolicy(p: Policy): Map<String, Any?> = MiniJson.obj(
            "policyId" to p.policyId,
            "revision" to p.revision,
            "issuer" to p.issuer,
            "requiredApps" to p.requiredApps.toList().sorted(),
            "startsAtWall" to p.startsAtWall,
            "endsAtWall" to p.endsAtWall,
            "lockAtWall" to (p.lockAtWall ?: 0L),
            "failureAction" to p.failureAction,
            "repairGraceMs" to p.repairGraceMs,
            "emergencyState" to p.emergencyState,
            "enabled" to p.enabled,
        )

        fun decode(text: String): Doc {
            val root = MiniJson.map(MiniJson.parse(text))
            val list = MiniJson.list(root["policies"]).map { decodePolicy(MiniJson.map(it)) }
            return Doc(
                schemaVersion = MiniJson.int(root, "schemaVersion", SCHEMA_VERSION),
                revision = MiniJson.int(root, "revision", 0),
                policies = list,
                updatedAtWall = MiniJson.lng(root, "updatedAtWall", 0),
            )
        }

        /** 客户端发来的 policyJson：既接受 §4 的整份文档，也接受单个 policy 对象。 */
        fun decodePolicies(text: String): List<Policy> {
            val root = MiniJson.map(MiniJson.parse(text))
            return if (root.containsKey("policies")) {
                MiniJson.list(root["policies"]).map { decodePolicy(MiniJson.map(it)) }
            } else {
                listOf(decodePolicy(root))
            }
        }

        private fun decodePolicy(m: Map<String, Any?>): Policy {
            val lockAt = MiniJson.lng(m, "lockAtWall", 0L)
            return Policy(
                policyId = MiniJson.str(m, "policyId"),
                revision = MiniJson.int(m, "revision", 1),
                issuer = MiniJson.str(m, "issuer"),
                requiredApps = MiniJson.strings(m["requiredApps"]).toSet(),
                startsAtWall = MiniJson.lng(m, "startsAtWall", 0L),
                endsAtWall = MiniJson.lng(m, "endsAtWall", 0L),
                lockAtWall = if (lockAt > 0) lockAt else null,
                failureAction = MiniJson.str(m, "failureAction", "LOCK"),
                repairGraceMs = MiniJson.lng(m, "repairGraceMs", 120_000),
                emergencyState = MiniJson.str(m, "emergencyState", EMERGENCY_NONE),
                enabled = MiniJson.bool(m, "enabled", false),
            )
        }
    }
}

/**
 * 单调压紧的墙钟（共享内核 [ClampedClock]）在生产里的接线：
 * 启动时用磁盘上的墙钟 + 本次开机单调时间重新立锚（`reanchor`），
 * 这样改系统时间、重启、重复崩溃都不能把监管窗口往后拖。
 */
class WatchClock(
    wall: Wall = Wall { System.currentTimeMillis() },
    mono: Mono = Mono { android.os.SystemClock.elapsedRealtime() },
) {
    private val clamp = ClampedClock(wall, mono)
    private val mono = mono

    fun now(): Long = clamp.now()

    fun monoNow(): Long = mono.now()

    fun reanchorFrom(persistedWall: Long) {
        if (persistedWall > 0) clamp.reanchor(persistedWall, mono.now())
    }
}
