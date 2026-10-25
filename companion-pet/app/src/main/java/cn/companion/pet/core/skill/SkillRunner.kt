package cn.companion.pet.core.skill

import cn.companion.pet.core.device.DeviceCommand
import cn.companion.pet.core.device.DeviceController
import cn.companion.pet.core.device.DeviceObservation
import cn.companion.pet.core.device.DeviceStatus
import cn.companion.pet.core.device.NodeRef

/**
 * 回放技能。每一步：应用守卫 → 等元素出现（轮询观察）→ 按元素执行（控制器内部先点节点、不行再按节点中心发手势）
 * → 控制器重新观察核对 → 记一行日志。遇到失败就停，不跳过、不猜；“未核实”照实写。
 * 所有设备动作都经过 [DeviceController.execute] 里的 DevicePolicy（允许名单、锁屏、前台、观察时效）。
 * 只在工作线程调用：会阻塞等待。
 */
class SkillRunner(
    private val controller: DeviceController,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val findTimeoutMs: Long = 8_000,
    private val pollMs: Long = 400,
    private val openTimeoutMs: Long = 5_000,
) {
    enum class StepStatus { OK, UNVERIFIED, FAILED, CANCELLED }

    data class StepOutcome(val index: Int, val step: SkillStep, val status: StepStatus, val reason: String) {
        fun logLine(): String {
            val mark = when (status) {
                StepStatus.OK -> "✓"
                StepStatus.UNVERIFIED -> "?"
                StepStatus.FAILED -> "✗"
                StepStatus.CANCELLED -> "■"
            }
            return mark + " " + (index + 1) + " " + step.describe() + (if (reason.isBlank()) "" else " — " + reason)
        }
    }

    data class RunResult(val ok: Boolean, val outcomes: List<StepOutcome>, val stoppedReason: String = "") {
        val log: List<String> get() = outcomes.map { it.logLine() } + (if (stoppedReason.isBlank()) emptyList() else listOf("■ " + stoppedReason))
    }

    fun run(
        skill: Skill,
        cancelled: () -> Boolean = { false },
        onStep: (index: Int, total: Int, step: SkillStep) -> Unit = { _, _, _ -> },
    ): RunResult {
        val steps = skill.steps.take(MAX_STEPS)
        if (steps.isEmpty()) return RunResult(false, emptyList(), "技能还没有步骤")
        val startedAt = clock()
        val outcomes = ArrayList<StepOutcome>()
        for ((i, step) in steps.withIndex()) {
            if (cancelled()) return RunResult(false, outcomes, "你停止了")
            if (!runCatching { controller.snapshot(clock()).unlocked }.getOrDefault(false)) return RunResult(false, outcomes, "锁屏了，停止")
            if (clock() - startedAt > MAX_RUN_MS) return RunResult(false, outcomes, "运行超过 3 分钟，停止")
            onStep(i, steps.size, step)
            val outcome = runStep(step, i, cancelled)
            outcomes += outcome
            if (outcome.status == StepStatus.FAILED || outcome.status == StepStatus.CANCELLED) return RunResult(false, outcomes)
            if (i < steps.size - 1) sleeper(STEP_GAP_MS)
        }
        return RunResult(true, outcomes)
    }

    /** 单独测一步（搭技能时“试一下”）。 */
    fun runStep(step: SkillStep, index: Int = 0, cancelled: () -> Boolean = { false }): StepOutcome {
        return try { runStepInner(step, index, cancelled) } catch (e: Exception) {
            StepOutcome(index, step, StepStatus.FAILED, "执行出错")
        }
    }

    private fun runStepInner(step: SkillStep, index: Int, cancelled: () -> Boolean): StepOutcome {
        return when (step.type) {
            SkillStepType.OPEN_APP -> openApp(step, index)
            SkillStepType.TAP -> act(step, index, cancelled, forInput = false)
            SkillStepType.INPUT -> act(step, index, cancelled, forInput = true)
            SkillStepType.SCROLL -> simple(step, index, DeviceCommand.Scroll(null, step.forward))
            SkillStepType.BACK -> simple(step, index, DeviceCommand.Back)
            SkillStepType.WAIT -> {
                var left = step.waitMs.coerceIn(0, MAX_WAIT_MS)
                while (left > 0) {
                    if (cancelled()) return StepOutcome(index, step, StepStatus.CANCELLED, "你停止了")
                    val slice = minOf(left, pollMs); sleeper(slice); left -= slice
                }
                StepOutcome(index, step, StepStatus.OK, "")
            }
            SkillStepType.WAIT_FOR -> {
                val target = step.target ?: return StepOutcome(index, step, StepStatus.FAILED, "没有目标元素")
                if (waitFor(target, false, cancelled) != null) StepOutcome(index, step, StepStatus.OK, "")
                else StepOutcome(index, step, StepStatus.FAILED, waitFailure(target))
            }
        }
    }

    private fun openApp(step: SkillStep, index: Int): StepOutcome {
        if (step.appPackage.isBlank()) return StepOutcome(index, step, StepStatus.FAILED, "没有要打开的应用")
        val r = controller.execute(DeviceCommand.OpenApp(step.appPackage), clock())
        return when (r.status) {
            DeviceStatus.SUCCESS -> StepOutcome(index, step, StepStatus.OK, "")
            DeviceStatus.UNVERIFIED -> {
                val deadline = clock() + openTimeoutMs
                while (clock() < deadline) {
                    if (controller.snapshot(clock()).activeWindowPackage == step.appPackage) return StepOutcome(index, step, StepStatus.OK, "")
                    sleeper(pollMs)
                }
                StepOutcome(index, step, StepStatus.UNVERIFIED, "已请求打开，但前台还不是它")
            }
            else -> StepOutcome(index, step, StepStatus.FAILED, r.reason)
        }
    }

    private fun act(step: SkillStep, index: Int, cancelled: () -> Boolean, forInput: Boolean): StepOutcome {
        val target = step.target ?: return StepOutcome(index, step, StepStatus.FAILED, "没有目标元素")
        val node = waitFor(target, forInput, cancelled)
            ?: return StepOutcome(index, step, if (cancelled()) StepStatus.CANCELLED else StepStatus.FAILED,
                if (cancelled()) "你停止了" else waitFailure(target))
        val command = if (forInput) DeviceCommand.InputText(node.id, step.text) else DeviceCommand.TapNode(node.id)
        val r = controller.execute(command, clock())
        return when (r.status) {
            DeviceStatus.SUCCESS -> StepOutcome(index, step, StepStatus.OK, "")
            DeviceStatus.UNVERIFIED -> StepOutcome(index, step, StepStatus.UNVERIFIED,
                if (r.evidence["pageChanged"] == "false") "点了，但页面看起来没变化" else "已执行，页面有变化")
            else -> StepOutcome(index, step, StepStatus.FAILED, r.reason)
        }
    }

    /** 滚动/返回也要一份新观察（策略要求观察没过期、前台没换）。 */
    private fun simple(step: SkillStep, index: Int, command: DeviceCommand): StepOutcome {
        val observed = controller.observe(clock())
        if (observed.status != DeviceStatus.SUCCESS) return StepOutcome(index, step, StepStatus.FAILED, observed.reason)
        val r = controller.execute(command, clock())
        return when (r.status) {
            DeviceStatus.SUCCESS -> StepOutcome(index, step, StepStatus.OK, "")
            DeviceStatus.UNVERIFIED -> StepOutcome(index, step, StepStatus.UNVERIFIED,
                if (r.evidence["pageChanged"] == "false") "页面看起来没变化" else "")
            else -> StepOutcome(index, step, StepStatus.FAILED, r.reason)
        }
    }

    /** 轮询观察直到找到目标（AutoX untilFind 的做法）；超时返回 null。 */
    private fun waitFor(target: ElementTarget, forInput: Boolean, cancelled: () -> Boolean): NodeRef? {
        val deadline = clock() + findTimeoutMs
        while (true) {
            if (cancelled()) return null
            val r = controller.observe(clock())
            val obs: DeviceObservation? = if (r.status == DeviceStatus.SUCCESS) controller.lastObservation() else null
            if (obs != null) ElementResolver.resolve(target, obs, forInput)?.let { return it }
            if (clock() >= deadline) return null
            sleeper(pollMs)
        }
    }

    private fun waitFailure(target: ElementTarget): String {
        val pkg = runCatching { controller.snapshot(clock()).effectivePackage }.getOrNull()
        return if (target.packageName.isNotBlank() && pkg != null && pkg != target.packageName)
            "前台不是 " + target.packageName + "，先加一步“打开应用”"
        else "等了 " + findTimeoutMs / 1000 + " 秒没找到 " + target.describe()
    }

    companion object {
        const val MAX_STEPS = 30
        const val MAX_WAIT_MS = 10_000L
        const val MAX_RUN_MS = 3 * 60_000L
        const val STEP_GAP_MS = 300L
    }
}
