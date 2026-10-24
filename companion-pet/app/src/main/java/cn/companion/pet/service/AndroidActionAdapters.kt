package cn.companion.pet.service

import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import cn.companion.pet.PetApp
import cn.companion.pet.bridge.Assistants
import cn.companion.pet.bridge.GeminiLauncher
import cn.companion.pet.core.action.*
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.session.AssistantApp
import cn.companion.pet.core.think.ThoughtNotes
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Synchronous tool API: call off the UI thread. Android effects are dispatched to the main looper. */
object AndroidActionAdapters {
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    private fun adapter(block: (ActionRequest, ActionCancellation) -> ActionResult) = object : ActionAdapter {
        override fun execute(request: ActionRequest) = execute(request, ActionCancellation())
        override fun execute(request: ActionRequest, cancellation: ActionCancellation): ActionResult {
            if (Looper.myLooper() == Looper.getMainLooper()) return ActionResult(
                request.id, request.kind, ActionStatus.FAILED, "请在后台任务中执行这个动作",
            )
            cancellation.throwIfCancelled()
            return block(request, cancellation)
        }
    }

    private fun userPresent(app: PetApp): Boolean =
        app.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

    private fun mainAction(request: ActionRequest, cancellation: ActionCancellation, block: () -> ActionResult): ActionResult {
        val handler = Handler(Looper.getMainLooper())
        val result = CompletableFuture<ActionResult>()
        val task = Runnable {
            if (result.isDone || cancellation.isCancelled) return@Runnable
            result.complete(runCatching { block() }.getOrElse {
                ActionResult(request.id, request.kind, ActionStatus.FAILED, "系统动作未能完成")
            })
        }
        val remove = cancellation.onCancel {
            handler.removeCallbacks(task)
            result.complete(ActionResult(request.id, request.kind, ActionStatus.CANCELLED, "已取消尚未执行的系统动作"))
        }
        handler.post(task)
        return try { result.get(5, TimeUnit.SECONDS) }
        catch (_: TimeoutException) {
            handler.removeCallbacks(task)
            ActionResult(request.id, request.kind, ActionStatus.FAILED, "系统未及时返回，请核对动作状态")
        } catch (_: InterruptedException) {
            handler.removeCallbacks(task)
            Thread.currentThread().interrupt()
            ActionResult(request.id, request.kind, ActionStatus.CANCELLED, "系统动作等待已取消")
        } finally { remove() }
    }

    fun build(app: PetApp): ActionRunner {
        val reader = VisibleMemoryReader(app.memory, app.events, app.facts)
        val speechAdapter by lazy { SpeechActionAdapter(app.speech, enabled = { app.settings.ttsEnabled }) }
        return ActionRunner(mapOf(
            ActionKind.READ_MEMORY to adapter { request, cancellation ->
                val lines = synchronized(app.memoryLock) {
                    app.materialInbox.purgeExpired()
                    cancellation.throwIfCancelled()
                    reader.read(request.args["query"].orEmpty(), request.args["limit"]?.toIntOrNull() ?: 10)
                }
                ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "读取到 ${lines.size} 条可见记忆", mapOf("text" to lines.joinToString("\n")))
            },
            ActionKind.READ_MATERIALS to adapter { request, cancellation ->
                val (text, display) = synchronized(app.memoryLock) {
                    cancellation.throwIfCancelled()
                    val now = System.currentTimeMillis()
                    val limit = request.args["limit"]?.toIntOrNull() ?: 5
                    val text = app.materialInbox.contextBlock(now, limit, 4000)
                    val display = app.materialInbox.all(now).take(limit).joinToString("\n\n") { item ->
                        listOf(item.title.ifBlank { "未命名素材" }, item.evidenceLabel(), item.url.orEmpty(),
                            cn.companion.pet.core.text.UnicodeText.truncate(item.body, 500), item.note)
                            .filter { it.isNotBlank() }.joinToString("\n")
                    }.ifBlank { "还没有可见素材" }
                    text to display
                }
                ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "已读取当前可见素材及证据等级",
                    mapOf("text" to text, "displayText" to display))
            },
            ActionKind.SAVE_NOTE to adapter { request, cancellation ->
                val event = synchronized(app.memoryLock) {
                    cancellation.throwIfCancelled()
                    if (!userPresent(app)) return@adapter ActionResult(request.id, request.kind, ActionStatus.CANCELLED, "屏幕已锁定，未保存念头")
                    val text = request.args.getValue("text").trim()
                    app.thoughts.add(LocalDate.now(), ThoughtNotes.Thought(LocalTime.now().format(HHMM), "工具", "", text))
                    app.events.append(EventKind.NOTE, text, source = "tool")
                }
                ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "念头已保存", mapOf("eventId" to event.id))
            },
            ActionKind.OPEN_ASSISTANT to adapter { request, cancellation ->
                mainAction(request, cancellation) {
                    cancellation.throwIfCancelled()
                    if (!userPresent(app)) return@mainAction ActionResult(request.id, request.kind, ActionStatus.CANCELLED, "屏幕已锁定，未打开应用")
                    val assistants = Assistants(app, app.settings)
                    val target = request.args["target"]?.let { name -> AssistantApp.entries.first { it.name == name } } ?: assistants.current
                    val pkg = assistants.packageOf(target)
                        ?: return@mainAction ActionResult(request.id, request.kind, ActionStatus.UNAVAILABLE, target.label + " 未配置或未安装")
                    when (GeminiLauncher(app).launch(pkg, adjacent = false)) {
                        GeminiLauncher.Outcome.LAUNCHED -> ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "系统已接受打开 " + target.label)
                        GeminiLauncher.Outcome.NOT_INSTALLED -> ActionResult(request.id, request.kind, ActionStatus.UNAVAILABLE, target.label + " 未安装")
                        GeminiLauncher.Outcome.FAILED -> ActionResult(request.id, request.kind, ActionStatus.FAILED, "系统拒绝打开 " + target.label)
                    }
                }
            },
            ActionKind.SPEAK to adapter { request, cancellation ->
                if (!userPresent(app)) ActionResult(request.id, request.kind, ActionStatus.CANCELLED, "屏幕已锁定，未提交播报")
                else speechAdapter.execute(request, cancellation)
            },
        ))
    }
}
