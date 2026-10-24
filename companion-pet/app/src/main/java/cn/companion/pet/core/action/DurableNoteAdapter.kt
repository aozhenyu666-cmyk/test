package cn.companion.pet.core.action

import cn.companion.pet.core.companion.*
import cn.companion.pet.core.think.ThoughtNotes
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A stable action owns one indexed note and one deterministic event, separate from legacy MD. */
class DurableNoteAdapter(
    private val notes: ThoughtNotes,
    private val events: EventLog,
    private val memoryLock: Any = Any(),
    private val available: () -> Boolean = { true },
    private val clock: Clock = Clock.systemDefaultZone(),
    private val afterNoteStored: () -> Unit = {},
) : ActionAdapter {
    override fun execute(request: ActionRequest) = execute(request, ActionCancellation())
    override fun execute(request: ActionRequest, cancellation: ActionCancellation): ActionResult = synchronized(memoryLock) {
        cancellation.throwIfCancelled()
        if (!available()) return@synchronized ActionResult(request.id, request.kind, ActionStatus.CANCELLED,
            "屏幕已锁定或执行环境不可用，未保存念头")
        val now = clock.millis()
        val local = Instant.ofEpochMilli(now).atZone(clock.zone)
        val note = notes.saveAction(request.id, requestFingerprint(request), local.toLocalDate(), now,
            ThoughtNotes.Thought(local.format(DateTimeFormatter.ofPattern("HH:mm")), "工具", "", request.args.getValue("text").trim()))
        afterNoteStored()
        receipt(request, note)
    }

    override fun recover(receipt: WriteReceipt): ActionResult? = synchronized(memoryLock) {
        val note = notes.actionNote(receipt.id) ?: return@synchronized null
        check(note.fingerprint == receipt.fingerprint)
        receipt(ActionRequest(receipt.id, receipt.kind), note)
    }

    private fun receipt(request: ActionRequest, note: ThoughtNotes.ActionNote): ActionResult {
        val eventId = eventId(note.id)
        note.thought?.let { thought ->
            events.appendEventOnce(CompanionEvent(eventId, note.occurredAt, note.day, EventKind.NOTE, thought.text, source = "tool"))
        }
        return ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "念头保存结果已核对",
            mapOf("noteId" to note.id, "eventId" to eventId, "day" to note.day, "deleted" to (note.thought == null).toString()))
    }

    fun delete(date: LocalDate, thought: ThoughtNotes.Thought) = synchronized(memoryLock) {
        val id = thought.id
        if (id != null) {
            val record = notes.actionNote(id) ?: return@synchronized
            check(record.day == date.toString())
            // Source invalidation precedes body deletion, so interrupted deletion cannot expose a derived memory.
            val forgetId = "forget-" + eventId(id)
            val now = clock.millis()
            val forget = events.all().firstOrNull { it.id == forgetId } ?: CompanionEvent(forgetId, now,
                events.dayOf(now).toString(), EventKind.FORGET, "删除工具念头", source = "tool", sourceIds = listOf(eventId(id)))
            events.appendEventOnce(forget)
        }
        notes.delete(date, thought)
    }

    private fun eventId(id: String): String = "tool-note-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
