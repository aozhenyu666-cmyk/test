package cn.companion.pet.service

import android.os.Handler
import cn.companion.pet.PetApp
import cn.companion.pet.core.device.DeviceStatus
import cn.companion.pet.core.skill.ElementResolver
import cn.companion.pet.core.skill.ElementTarget
import cn.companion.pet.core.skill.Skill
import cn.companion.pet.core.skill.SkillRunner
import cn.companion.pet.core.skill.SkillStep
import cn.companion.pet.core.skill.SkillStepType
import cn.companion.pet.overlay.BubbleAction
import cn.companion.pet.overlay.BubbleSpec
import cn.companion.pet.core.probe.Mood
import cn.companion.pet.overlay.PetOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 桌宠上的“技能”：运行已有技能、在要操作的 App 里一步步挑元素录一个新技能。
 * 挑元素靠无障碍节点（不是录触摸）：点桌宠 → 列出这一屏能点/能写的元素 → 选一个就记下来并帮你点下去。
 * 所有动作都走 DeviceController（允许名单/锁屏/前台/观察时效）。
 */
class SkillPetFlow(
    private val app: PetApp,
    private val overlay: PetOverlay,
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val appLabel: (String) -> String,
    /** 收起时让 PetBrain 恢复正常状态。 */
    private val onIdle: () -> Unit,
) {
    private sealed interface Waiting {
        data object Name : Waiting
        data class Input(val target: ElementTarget) : Waiting
    }

    private var recordingId: String? = null
    private var runJob: Job? = null
    @Volatile private var stopRequested = false
    private var waiting: Waiting? = null
    private var menuOpen = false
    private var noticeUntil = 0L

    private val store get() = app.skillStore
    private val runner by lazy { SkillRunner(app.deviceController) }

    /** 运行/录制/菜单期间桌宠不主动搭话。 */
    val busy: Boolean get() = runJob?.isActive == true || recordingId != null || waiting != null || menuOpen ||
        System.currentTimeMillis() < noticeUntil

    val awaitingText: Boolean get() = waiting != null

    /** 点桌宠：运行中=停止；录制中=挑下一步；否则不处理。 */
    fun onPetTapped(): Boolean {
        if (runJob?.isActive == true) { stopRequested = true; bubble("好，停下。", textOnly = true); return true }
        if (recordingId != null) { openPicker(); return true }
        if (menuOpen || waiting != null) { close(); return true }
        return false
    }

    fun openMenu() {
        menuOpen = true
        scope.launch {
            val skills = withContext(Dispatchers.IO) { store.list() }
            val actions = mutableListOf<BubbleAction>()
            skills.take(6).forEach { s -> actions += BubbleAction("▶ " + s.name.take(8)) { runSkill(s.id) } }
            actions += BubbleAction("录新技能", highlighted = skills.isEmpty()) { askName() }
            actions += BubbleAction("×") { close() }
            overlay.setMood(Mood.CURIOUS)
            val text = if (skills.isEmpty()) "还没有技能。录一个：在要用的 App 里，一步步点我选要点的地方。"
            else "运行哪个技能？点运行中的我可以随时停。"
            overlay.showBubble(BubbleSpec(text, instant = true, actions = actions))
        }
    }

    fun submitText(text: String) {
        val w = waiting ?: return
        waiting = null
        val value = text.trim()
        when (w) {
            Waiting.Name -> {
                if (value.isEmpty()) { close(); return }
                scope.launch {
                    val created = withContext(Dispatchers.IO) { store.create(value, System.currentTimeMillis()) }
                    if (created == null) { notice("没建成：" + (store.lastError ?: "存储失败")); return@launch }
                    recordingId = created.id
                    bubble("开始录「${created.name}」。到要操作的页面，点我选这一步；选好的会帮你点下去。", listOf(
                        BubbleAction("选这一步", highlighted = true) { openPicker() },
                        BubbleAction("完成") { finishRecording() },
                    ))
                }
            }
            is Waiting.Input -> {
                if (value.isEmpty()) { recordPrompt(); return }
                addAndTry(SkillStep(UUID.randomUUID().toString(), SkillStepType.INPUT, target = w.target, text = value.take(300)))
            }
        }
    }

    fun cancel() {
        stopRequested = true
        waiting = null
        menuOpen = false
    }

    private fun askName() {
        menuOpen = false
        waiting = Waiting.Name
        bubble("给新技能起个名字（比如“打开番茄钟”）：", listOf(BubbleAction("×") { close() }))
        overlay.openBubbleInput("技能名字")
    }

    fun runSkill(id: String) {
        menuOpen = false
        if (runJob?.isActive == true) return
        stopRequested = false
        runJob = scope.launch {
            val skill = withContext(Dispatchers.IO) { store.get(id) } ?: run { notice("技能不见了"); return@launch }
            overlay.setMood(Mood.THINKING)
            bubble("运行「${skill.name}」…（点我停止）", textOnly = true)
            val result = withContext(Dispatchers.IO) {
                runner.run(skill, cancelled = { stopRequested }) { i, total, step ->
                    handler.post { if (runJob?.isActive == true) overlay.showBubble(BubbleSpec("「${skill.name}」第 ${i + 1}/$total 步：" + step.describe(), textOnly = true, instant = true)) }
                }.also { store.recordRun(skill.id, it, System.currentTimeMillis()) }
            }
            notice(summary(skill, result))
        }
    }

    private fun summary(skill: Skill, r: SkillRunner.RunResult): String {
        if (r.ok) {
            val unverified = r.outcomes.count { it.status == SkillRunner.StepStatus.UNVERIFIED }
            return "「${skill.name}」做完了（${r.outcomes.size} 步" + (if (unverified > 0) "，$unverified 步没法核实，看一眼对不对" else "") + "）。"
        }
        val failed = r.outcomes.lastOrNull { it.status == SkillRunner.StepStatus.FAILED }
        return when {
            failed != null -> "「${skill.name}」第 ${failed.index + 1} 步没成功：${failed.reason}"
            r.stoppedReason.isNotBlank() -> "「${skill.name}」停了：${r.stoppedReason}"
            else -> "「${skill.name}」没做完。"
        }
    }

    private fun recordPrompt() {
        val id = recordingId ?: return
        scope.launch {
            val skill = withContext(Dispatchers.IO) { store.get(id) } ?: run { recordingId = null; close(); return@launch }
            bubble("录制「${skill.name}」：已 ${skill.steps.size} 步。到下一个页面后点我继续选。", listOf(
                BubbleAction("选这一步", highlighted = true) { openPicker() },
                BubbleAction("完成") { finishRecording() },
            ))
        }
    }

    private fun openPicker() {
        val id = recordingId ?: return
        overlay.setMood(Mood.THINKING)
        scope.launch {
            val (skill, observed) = withContext(Dispatchers.IO) {
                store.get(id) to runCatching { app.deviceController.observe() }.getOrNull()
            }
            if (skill == null) { recordingId = null; close(); return@launch }
            val obs = if (observed?.status == DeviceStatus.SUCCESS) app.deviceController.lastObservation() else null
            if (obs == null) {
                bubble("这一屏读不到（${observed?.reason ?: "无障碍没连上"}）。先切到要操作的 App 再点我。", listOf(
                    BubbleAction("完成") { finishRecording() },
                ))
                return@launch
            }
            val pkg = obs.packageName
            val actions = mutableListOf<BubbleAction>()
            val lastOpen = skill.steps.lastOrNull { it.type == SkillStepType.OPEN_APP }?.appPackage
            if (lastOpen != pkg) {
                actions += BubbleAction("打开${appLabel(pkg).take(6)}", highlighted = skill.steps.isEmpty()) {
                    addAndTry(SkillStep(UUID.randomUUID().toString(), SkillStepType.OPEN_APP, appPackage = pkg, appLabel = appLabel(pkg)), execute = false)
                }
            }
            ElementResolver.pickable(obs, 8).forEachIndexed { i, node ->
                val label = (CIRCLED.getOrNull(i) ?: "") + (if (node.editable) "✎" else "") +
                    node.text.ifBlank { node.resourceId.substringAfterLast('/') }.take(8)
                actions += BubbleAction(label) {
                    val target = ElementTarget.of(node)
                    if (node.editable) {
                        waiting = Waiting.Input(target)
                        bubble("要在 ${target.describe()} 里写什么？", listOf(BubbleAction("×") { recordPrompt() }))
                        overlay.openBubbleInput("要写的文字")
                    } else {
                        addAndTry(SkillStep(UUID.randomUUID().toString(), SkillStepType.TAP, target = target))
                    }
                }
            }
            actions += BubbleAction("往下滚") { addAndTry(SkillStep(UUID.randomUUID().toString(), SkillStepType.SCROLL, forward = true)) }
            actions += BubbleAction("返回") { addAndTry(SkillStep(UUID.randomUUID().toString(), SkillStepType.BACK)) }
            actions += BubbleAction("等2秒") { addAndTry(SkillStep(UUID.randomUUID().toString(), SkillStepType.WAIT, waitMs = 2_000), execute = false) }
            actions += BubbleAction("完成") { finishRecording() }
            val allowed = pkg in app.settings.deviceAllowedApps
            overlay.setMood(Mood.CURIOUS)
            overlay.showBubble(BubbleSpec(
                "选这一步（${appLabel(pkg)}）" + if (allowed) "：" else "。这个 App 不在“允许操作”名单，只记下不帮你点：",
                instant = true, actions = actions,
            ))
        }
    }

    /** 记下这一步；需要时立刻试做一次（录制时边录边走）。 */
    private fun addAndTry(step: SkillStep, execute: Boolean = true) {
        val id = recordingId ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.addStep(id, step, System.currentTimeMillis()) }
            if (!ok) { bubble("没记下：" + (store.lastError ?: "存储失败"), textOnly = true); handler.postDelayed({ recordPrompt() }, 2500); return@launch }
            if (!execute) { recordPrompt(); return@launch }
            overlay.setMood(Mood.THINKING)
            val outcome = withContext(Dispatchers.IO) { runner.runStep(step) }
            val text = when (outcome.status) {
                SkillRunner.StepStatus.OK -> "记下了，也做了：" + step.describe()
                SkillRunner.StepStatus.UNVERIFIED -> "记下了：" + step.describe() + "（" + outcome.reason + "）"
                else -> "记下了，但没帮你做成：" + outcome.reason + "。你自己操作一下再继续。"
            }
            bubble(text, textOnly = true)
            handler.postDelayed({ if (recordingId == id) recordPrompt() }, 2200)
        }
    }

    private fun finishRecording() {
        val id = recordingId ?: return close()
        recordingId = null
        scope.launch {
            val skill = withContext(Dispatchers.IO) { store.get(id) }
            notice(if (skill == null) "没找到这个技能。" else "已保存「${skill.name}」，共 ${skill.steps.size} 步。点我 → 技能 → ▶ 就能重放；“我的 → 技能”里可以调整。")
        }
    }

    private fun close() {
        menuOpen = false
        waiting = null
        overlay.hideBubble()
        overlay.setMood(Mood.IDLE)
        onIdle()
    }

    private fun bubble(text: String, actions: List<BubbleAction> = emptyList(), textOnly: Boolean = false) {
        overlay.showBubble(if (textOnly || actions.isEmpty()) BubbleSpec(text, textOnly = true, instant = true)
        else BubbleSpec(text, instant = true, actions = actions))
    }

    private fun notice(text: String) {
        noticeUntil = System.currentTimeMillis() + 7_000
        overlay.setMood(Mood.IDLE)
        bubble(text, textOnly = true)
        handler.postDelayed({ if (!busy) { overlay.hideBubble(); onIdle() } }, 7_200)
    }

    private companion object {
        val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧")
    }
}
