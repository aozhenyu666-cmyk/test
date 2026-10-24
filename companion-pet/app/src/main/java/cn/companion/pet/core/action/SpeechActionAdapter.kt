package cn.companion.pet.core.action

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** An accepted receipt means the system engine accepted playback, not that the user heard it. */
interface SpeechPort {
    fun submit(text: String, id: String, receipt: (Boolean, String) -> Unit)
    fun stop(id: String)
}

class SpeechActionAdapter(
    private val speaker: SpeechPort,
    private val enabled: () -> Boolean,
    private val readyTimeoutMillis: Long = 5000,
) : ActionAdapter {
    override fun execute(request: ActionRequest) = execute(request, ActionCancellation())

    override fun execute(request: ActionRequest, cancellation: ActionCancellation): ActionResult {
        fun result(status: ActionStatus, reason: String) = ActionResult(request.id, request.kind, status, reason)
        if (!enabled()) return result(ActionStatus.UNAVAILABLE, "TTS 开关未打开")
        val receipt = CompletableFuture<ActionResult>()
        val remove = cancellation.onCancel {
            speaker.stop(request.id)
            receipt.complete(result(ActionStatus.CANCELLED, "播报已取消"))
        }
        try {
            if (!cancellation.isCancelled) {
                speaker.submit(request.args["text"].orEmpty(), request.id) { accepted, reason ->
                    receipt.complete(result(if (accepted) ActionStatus.SUCCESS else ActionStatus.UNAVAILABLE, reason))
                }
            }
            return receipt.get(readyTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            speaker.stop(request.id)
            return result(ActionStatus.UNAVAILABLE, "系统 TTS 未及时就绪，本次播报已撤回")
        } catch (_: InterruptedException) {
            speaker.stop(request.id)
            Thread.currentThread().interrupt()
            return result(ActionStatus.CANCELLED, "播报等待已取消")
        } catch (_: Exception) {
            speaker.stop(request.id)
            return result(ActionStatus.UNAVAILABLE, "系统 TTS 暂不可用")
        } finally { remove() }
    }
}
