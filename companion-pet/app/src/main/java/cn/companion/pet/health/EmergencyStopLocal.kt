package cn.companion.pet.health

import android.util.Log

/*
 * 紧急暂停的「停止」动作与「能不能锁屏」判定（规范 §6、报告 §3.6、§8）。
 *
 * 这个文件只放**纯逻辑**：不碰 Android、不联网、不碰无障碍线程，可以在 JVM 单测里直接钉住。
 * 生产落点由 [PetPolicyStore] + [EmergencyPauseStore] 实现（见 PetGuardRuntime 里的 asStopSink）。
 *
 * 为什么需要三条来源（一手在这里踩过坑，captain 裁决 2026-10-25 要求对齐）：
 *  1. **内存事实**：本次进程内刚刚按下；
 *  2. **规则文件**：`policies.json` 里 `emergencyState == "STOPPED"`（规范 §4 的字段）；
 *  3. **独立标记文件**：`policy/emergency.json`。
 *
 * 只有第 1 条：进程重启就丢。
 * 只有第 2 条：窗口翻页会生成**新的 policyId** 顶掉旧规则，旧 STOPPED 规则不再存在 —— 暂停就漏了。
 * 所以第 3 条不可省。反过来，只要任意一条成立就必须算暂停（宁可少锁）。
 */

/**
 * 停止动作的落点。生产由 [PetPolicyStore] + [EmergencyPauseStore] 组合实现；
 * 单测用内存假件 / 故意失败的假件。
 */
interface StopSink {
    /** 当前规则（只读快照）。 */
    fun load(): List<PolicyDto>

    /** 把所有本地规则标 STOPPED 并原子落盘；返回是否写成功。 */
    fun stopAll(nowWall: Long): Boolean

    /** 用户恢复：把所有规则标回 NONE。 */
    fun resumeAll(): Boolean

    /** 独立暂停标记（跨窗口翻页 / 跨进程重启都不丢）；返回是否写成功。 */
    fun markEmergencyStopped(nowWall: Long, reason: String): Boolean

    /** 清掉独立暂停标记。 */
    fun clearEmergencyStopped(): Boolean

    /**
     * 独立标记此刻是否处于暂停（第三来源）。
     * 注意：语义是「用户没点恢复就一直算暂停」，不随窗口结束自动失效。
     */
    fun emergencyStopped(): Boolean
}

/** 三条来源的原始事实，交给 [EmergencyGate] 判定。 */
data class PauseSources(
    /** 来源 1：本次进程内的内存事实（`EmergencyPauseStore` 缓存 / 刚按下）。 */
    val memoryPaused: Boolean = false,
    /** 来源 2：`policies.json` 里存在 `emergencyState == "STOPPED"` 的规则。 */
    val policyStopped: Boolean = false,
    /** 来源 3：独立标记文件 `policy/emergency.json`。 */
    val markerStopped: Boolean = false,
)

/**
 * 「现在是否处于紧急暂停」的唯一判定。
 *
 * **tick() / 界面 / 单测全部共用这一处**，避免几处各写一遍然后漂移 ——
 * 漂移的后果就是「暂停了但兜底还在锁」，那比不暂停更严重。
 */
object EmergencyGate {

    fun isPaused(sources: PauseSources): Boolean =
        sources.memoryPaused || sources.policyStopped || sources.markerStopped

    /** 给界面/诊断的「为什么算暂停」说明。 */
    fun describe(sources: PauseSources): String {
        val hits = buildList {
            if (sources.memoryPaused) add("进程内已停")
            if (sources.policyStopped) add("规则已标 STOPPED")
            if (sources.markerStopped) add("独立暂停标记")
        }
        return if (hits.isEmpty()) "未暂停" else "已暂停（" + hits.joinToString("、") + "）"
    }

    /** 规则清单里是否有一条被标为 STOPPED（来源 2）。 */
    fun anyPolicyStopped(policies: List<PolicyDto>): Boolean =
        policies.any { it.emergencyState == "STOPPED" }
}

/**
 * 一次「停止」的真实结论。给哨兵的 emergencyStop 回包就用它填，界面文案也用它。
 *
 * @param ok 本地是否真的停了（**写完再读一遍核对**，不是「调用了就算」）
 * @param policyIds 已处于 STOPPED 的规则（摘要，给哨兵显示「已确认停止」）
 * @param alreadyStopped 之前就已经是暂停状态（幂等：重复调用仍 ok=true）
 */
data class StopOutcome(
    val ok: Boolean,
    val policyIds: List<String> = emptyList(),
    val atWall: Long = 0,
    val revision: Int = 0,
    val alreadyStopped: Boolean = false,
    val note: String = "",
)

/** 停止/恢复的真实执行。纯逻辑 + 一次落盘，可以在 JVM 单测里跑。 */
object EmergencyStopLocal {

    private const val TAG = "PetEmergencyStop"

    /**
     * 立即停止本地自动锁屏，幂等。
     *
     * 关键点（与一手 `EmergencyStop.stopLocal` 对齐）：
     *  - 只碰本地文件，**不联网、不请求模型、不等用户写字、不等冷静期**；
     *  - 写完**重新读一遍**核对：`ok` 只表示「真的落盘、规则确实都是 STOPPED、独立标记确实写上了」；
     *  - 已经全部 STOPPED 时**不再写规则**，但仍然返回 ok=true（幂等）；
     *  - 独立标记**每次都要确认写上**，因为它是跨窗口翻页/跨重启的唯一凭据；
     *  - 没有规则时也算停住（无可锁的规则 = 停），同样 ok=true。
     */
    fun stopLocal(sink: StopSink, nowWall: Long, reason: String = ""): StopOutcome {
        val before = runCatching { sink.load() }.getOrDefault(emptyList())
        val already = before.isNotEmpty() && before.all { it.emergencyState == "STOPPED" }

        val saved = if (already) true else runCatching { sink.stopAll(nowWall) }.getOrElse {
            Log.w(TAG, "停止本地规则时写盘失败", it)
            false
        }
        // 独立暂停标记：窗口翻页换 policyId、进程重启丢内存事实，只有它能兜住。
        // 没有规则时也必须写 —— 「没有规则」不等于「不会再有规则」。
        val marked = runCatching { sink.markEmergencyStopped(nowWall, reason) }.getOrElse {
            Log.w(TAG, "写紧急暂停标记失败", it)
            false
        }

        // 写完读回核对：这一步不做，就等于把「调用了」当「成功」。
        val after = runCatching { sink.load() }.getOrDefault(emptyList())
        val stoppedIds = after.filter { it.emergencyState == "STOPPED" }.map { it.policyId }
        val allStopped = after.isEmpty() || after.all { it.emergencyState == "STOPPED" }
        val flagOn = runCatching { sink.emergencyStopped() }.getOrDefault(false)
        val ok = saved && allStopped && marked && flagOn

        val note = when {
            !saved -> "本地规则写入失败，未能确认停止"
            !marked || !flagOn -> "暂停标记写入失败，未能确认停止"
            after.isEmpty() -> "当前没有本地规则（无可锁项），已写暂停标记"
            allStopped -> "本地规则已全部停止（${stoppedIds.size} 条）"
            else -> "仍有规则未标为停止：" + after.filter { it.emergencyState != "STOPPED" }.map { it.policyId }
        }
        return StopOutcome(
            ok = ok,
            policyIds = stoppedIds,
            atWall = nowWall,
            revision = after.maxOfOrNull { it.revision } ?: 0,
            alreadyStopped = already,
            note = note,
        )
    }

    /** 用户显式恢复：清标记 + 规则标回 NONE。返回是否真的写成功。 */
    fun resumeLocal(sink: StopSink): Boolean {
        val resumed = runCatching { sink.resumeAll() }.getOrDefault(false)
        val cleared = runCatching { sink.clearEmergencyStopped() }.getOrDefault(false)
        // 读回核对：标记必须真的没了，否则下次 tick 还会被当成暂停（保守但看得见）。
        val flagOn = runCatching { sink.emergencyStopped() }.getOrDefault(true)
        return resumed && cleared && !flagOn
    }
}
