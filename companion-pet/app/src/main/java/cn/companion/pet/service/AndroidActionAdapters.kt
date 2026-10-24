package cn.companion.pet.service

import cn.companion.pet.PetApp
import cn.companion.pet.bridge.Assistants
import cn.companion.pet.bridge.GeminiLauncher
import cn.companion.pet.core.action.ActionAdapter
import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionRequest
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionRunner
import cn.companion.pet.core.action.ActionStatus
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.memory.ProfileRules
import cn.companion.pet.core.session.AssistantApp
import cn.companion.pet.core.think.ThoughtNotes
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 第一批 Android 工具适配器。每个都只调用应用已有能力，不新增权限、不读私有目录、
 * 不执行无障碍点击或 Shell。真实结果由 ActionResult 的四态返回，界面不能只显示“已执行”。
 */
object AndroidActionAdapters {
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    fun build(app: PetApp): ActionRunner {
        val adapters = mapOf<ActionKind, ActionAdapter>(
            ActionKind.READ_MEMORY to object : ActionAdapter {
                override fun execute(request: ActionRequest): ActionResult {
                    val query = request.args["query"].orEmpty()
                    val limit = request.args["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 10
                    val lines = synchronized(app.memoryLock) {
                        val facts = app.facts.live().map { it.text }
                        val profile = ProfileRules.applyTombstones(app.memory.profileLines(), app.memory.tombstones())
                        (profile + facts).distinct()
                    }.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                        .take(limit)
                    return ActionResult(
                        request.id, request.kind, ActionStatus.SUCCESS,
                        "读取到 " + lines.size + " 条可见记忆",
                        mapOf("text" to lines.joinToString("\n")),
                    )
                }
            },
            ActionKind.READ_MATERIALS to object : ActionAdapter {
                override fun execute(request: ActionRequest): ActionResult {
                    val limit = request.args["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    val items = synchronized(app.memoryLock) { app.materialInbox.all().take(limit) }
                    return ActionResult(
                        request.id, request.kind, ActionStatus.SUCCESS,
                        "读取到 " + items.size + " 条素材",
                        mapOf("text" to items.joinToString("\n") { it.title.ifBlank { it.url ?: it.id } }),
                    )
                }
            },
            ActionKind.SAVE_NOTE to object : ActionAdapter {
                override fun execute(request: ActionRequest): ActionResult {
                    val text = request.args["text"].orEmpty().trim()
                    if (text.isEmpty()) return ActionResult(request.id, request.kind, ActionStatus.FAILED, "念头为空")
                    val event = synchronized(app.memoryLock) {
                        app.thoughts.add(
                            LocalDate.now(),
                            ThoughtNotes.Thought(LocalTime.now().format(HHMM), "工具", "", text),
                        )
                        app.events.append(EventKind.NOTE, text, source = "tool")
                    }
                    return ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "念头已保存", mapOf("eventId" to event.id))
                }
            },
            ActionKind.OPEN_ASSISTANT to object : ActionAdapter {
                override fun execute(request: ActionRequest): ActionResult {
                    val target = runCatching { AssistantApp.fromName(request.args["target"]) }.getOrElse { app.settings.assistant.let(AssistantApp::fromName) }
                    val assistants = Assistants(app, app.settings)
                    val pkg = assistants.packageOf(target)
                        ?: return ActionResult(request.id, request.kind, ActionStatus.UNAVAILABLE, target.label + " 没有配置/安装")
                    val outcome = GeminiLauncher(app).launch(pkg, adjacent = false)
                    return when (outcome) {
                        GeminiLauncher.Outcome.LAUNCHED -> ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "已打开 " + target.label)
                        GeminiLauncher.Outcome.NOT_INSTALLED -> ActionResult(request.id, request.kind, ActionStatus.UNAVAILABLE, target.label + " 未安装")
                        GeminiLauncher.Outcome.FAILED -> ActionResult(request.id, request.kind, ActionStatus.FAILED, "系统拒绝打开 " + target.label)
                    }
                }
            },
            ActionKind.SPEAK to object : ActionAdapter {
                override fun execute(request: ActionRequest): ActionResult {
                    if (!app.settings.ttsEnabled) return ActionResult(
                        request.id, request.kind, ActionStatus.UNAVAILABLE, "TTS 开关未打开",
                    )
                    val text = request.args["text"].orEmpty().trim()
                    if (text.isEmpty()) return ActionResult(request.id, request.kind, ActionStatus.FAILED, "播报内容为空")
                    Speech(app).say(text)
                    return ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "已交给系统 TTS", mapOf("engine" to "android_tts"))
                }
            },
        )
        return ActionRunner(adapters)
    }
}
