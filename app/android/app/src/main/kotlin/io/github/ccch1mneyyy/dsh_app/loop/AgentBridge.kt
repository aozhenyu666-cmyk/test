package io.github.ccch1mneyyy.dsh_app.loop

/**
 * AgentBridge v0 (T7): the seam where a real DSH agent turn will plug in, plus the local fallback
 * that is the only implementation today.
 *
 * Deliberately decoupled from [LoopEngine]: the engine decides *whether* to show a page and records
 * the outcome; the bridge only produces the sentence that page shows. Nothing here reads the
 * screen — an event carries [LoopEvent.packageName], the source and the timestamp, and that is all
 * a remote implementation would ever be given (see docs/loop/ARCHITECTURE.md §4).
 *
 * The contract is fail-soft by construction: every failure, timeout or malformed answer is a
 * [Delivery.Fallback]. The caller then closes its receipt with `reason=agent_fallback`, so a dead
 * agent never turns into a lost event or a page with no text.
 */
interface AgentBridge {
    /**
     * [context] is the small, screen-free payload: event id, source, package name, timestamp.
     * Returning a blank [Delivery.Reply.text] is treated as a fallback by [DeliveryGuard].
     */
    fun deliver(event: LoopEvent, context: Map<String, Any?>, timeoutMs: Long): Delivery

    sealed interface Delivery {
        /** Text the interaction page shows instead of its generic sentence. */
        data class Reply(val text: String) : Delivery
        /** Use the local template; [reason] goes into the receipt. */
        data class Fallback(val reason: String) : Delivery
    }
}

/**
 * The payload an [AgentBridge] may see: no screen content, no node text, no app list.
 * Kept as a plain function so both the local bridge and a future remote one share one definition.
 */
object AgentContext {
    fun of(event: LoopEvent): Map<String, Any?> = linkedMapOf(
        "eventId" to event.eventId,
        "source" to event.source.name,
        "packageName" to event.packageName,
        "timestamp" to event.timestamp,
        "visitId" to event.visitId,
    )
}

/**
 * v0 bridge: a fixed sentence per package, no model, no network, no waiting.
 *
 * It exists so the page always has something to say and so the interface is exercised by tests
 * before T8 brings a real agent turn. [labels] maps a package to the app name the person sees;
 * [template] builds the sentence from that name.
 */
class LocalTemplateBridge(
    private val labels: (String?) -> String? = { null },
    private val template: (String) -> String = { app -> DEFAULT_TEMPLATE.replace("{app}", app) },
    /** Simulated remote failure, used by tests to prove the fallback path. */
    private val failWith: String? = null,
) : AgentBridge {
    override fun deliver(event: LoopEvent, context: Map<String, Any?>, timeoutMs: Long): AgentBridge.Delivery {
        failWith?.let { return AgentBridge.Delivery.Fallback(it) }
        val pkg = event.packageName
            ?: return AgentBridge.Delivery.Fallback("no_package")
        val name = labels(pkg)?.takeIf { it.isNotBlank() } ?: pkg
        val text = runCatching { template(name) }.getOrNull()
        return if (text.isNullOrBlank()) AgentBridge.Delivery.Fallback("empty_template") else AgentBridge.Delivery.Reply(text)
    }

    companion object {
        const val DEFAULT_TEMPLATE = "{app}"
    }
}

/**
 * Runs a bridge and guarantees the two things callers rely on: it never throws, and it never takes
 * longer than the deadline. Both become [AgentBridge.Delivery.Fallback], which the page renders
 * with its own local text and which the receipt records as `agent_fallback`.
 */
object DeliveryGuard {
    /**
     * [runner] is what actually calls the bridge; it is a lambda so the timeout can be applied on a
     * worker without this pure module owning a thread pool.
     */
    fun run(
        bridge: AgentBridge?,
        event: LoopEvent,
        timeoutMs: Long,
        runner: ((AgentBridge, LoopEvent) -> AgentBridge.Delivery)? = null,
    ): AgentBridge.Delivery {
        if (bridge == null) return AgentBridge.Delivery.Fallback("no_bridge")
        if (timeoutMs <= 0) return AgentBridge.Delivery.Fallback("no_time")
        return try {
            val delivery = if (runner != null) runner(bridge, event)
            else bridge.deliver(event, AgentContext.of(event), timeoutMs)
            when (delivery) {
                is AgentBridge.Delivery.Reply -> if (delivery.text.isBlank()) AgentBridge.Delivery.Fallback("blank_reply") else delivery
                is AgentBridge.Delivery.Fallback -> delivery
            }
        } catch (t: Throwable) {
            AgentBridge.Delivery.Fallback("agent_error: ${t.javaClass.simpleName}")
        }
    }
}
