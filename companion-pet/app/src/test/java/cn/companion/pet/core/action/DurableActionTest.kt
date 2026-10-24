package cn.companion.pet.core.action

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.*
import cn.companion.pet.core.think.ThoughtNotes
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.EventKind

class DurableActionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun committedWriteSurvivesRunnerReconstructionAndRejectsChangedPayload() {
        val dir = folder.newFolder()
        var calls = 0
        val adapter = object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult {
                calls++
                return ActionResult(request.id, request.kind, ActionStatus.SUCCESS, "念头已保存", mapOf("noteId" to "note-one"))
            }
        }
        fun runner() = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter), journal = FileActionJournal(dir))
        val request = ActionRequest("stable-id", ActionKind.SAVE_NOTE, mapOf("text" to "合成念头"))
        val first = runner().execute(request)
        assertEquals(ActionStatus.SUCCESS, first.status)
        assertEquals(first, runner().execute(request))
        assertEquals(first, runner().result("stable-id"))
        assertEquals(1, calls)
        val conflict = runner().execute(request.copy(args = mapOf("text" to "另一份念头")))
        assertEquals(ActionStatus.FAILED, conflict.status)
        assertTrue(conflict.reason.contains("不能更换"))
        assertEquals(1, calls)
        assertFalse(dir.walkTopDown().filter(File::isFile).any { it.readText().contains("合成念头") })
    }

    @Test fun notePersistedBeforeEventIsRecoveredOnceAndLegacyNotesStillWork() {
        val root = folder.newFolder()
        val date = LocalDate.of(2026, 10, 24)
        val notes = ThoughtNotes(File(root, "notes"))
        val legacy = ThoughtNotes.Thought("09:00", "浏览器", "旧摘录", "旧格式念头")
        notes.add(date, legacy)
        val request = ActionRequest("note-crash", ActionKind.SAVE_NOTE, mapOf("text" to "新格式念头"))
        val interrupted = DurableNoteAdapter(notes, EventLog(root), clock = Clock.fixed(
            Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC), afterNoteStored = { error("synthetic interruption") })
        val first = ActionRunner(mapOf(ActionKind.SAVE_NOTE to interrupted), journal = FileActionJournal(File(root, "journal"))).execute(request)
        assertEquals("true", first.data["needsReview"])
        assertEquals(listOf("旧格式念头", "新格式念头"), notes.list(date).map { it.text })
        assertTrue(EventLog(root).all().isEmpty())
        val reopened = ThoughtNotes(File(root, "notes"))
        val recovered = DurableNoteAdapter(reopened, EventLog(root), clock = Clock.fixed(
            Instant.parse("2026-10-25T11:00:00Z"), ZoneOffset.UTC))
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to recovered), journal = FileActionJournal(File(root, "journal")))
        val receipt = runner.execute(request)
        assertEquals(ActionStatus.SUCCESS, receipt.status)
        assertEquals(date.toString(), receipt.data["day"])
        assertEquals(listOf("旧格式念头", "新格式念头"), reopened.list(date).map { it.text })
        assertTrue(reopened.list(date.plusDays(1)).isEmpty())
        assertEquals(1, EventLog(root).all().count { it.kind == EventKind.NOTE })
        assertEquals(receipt, runner.execute(request))
        reopened.removeLast(date) // existing overlay undo still targets legacy Markdown
        assertEquals(listOf("新格式念头"), reopened.list(date).map { it.text })
        val indexed = reopened.list(date).single()
        recovered.delete(date, indexed)
        assertTrue(reopened.list(date).isEmpty())
        assertTrue(reopened.dates().isEmpty())
        assertEquals(1, EventLog(root).all().count { it.kind == EventKind.FORGET })
        // Clearing the body does not clear the identity or allow the deleted note to resurrect.
        assertEquals(receipt, runner.execute(request))
        assertTrue(reopened.list(date).isEmpty())
    }

    @Test fun checkRecoversStoredNoteWithoutRequiringItsBodyOrRepeatingTheEffect() {
        val root = folder.newFolder()
        val notes = ThoughtNotes(File(root, "notes"))
        val actual = DurableNoteAdapter(notes, EventLog(root))
        val interrupted = object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult { actual.execute(request); error("lost receipt") }
        }
        val request = ActionRequest("check-after-restart", ActionKind.SAVE_NOTE, mapOf("text" to "仅保存在笔记中"))
        val journal = File(root, "journal")
        assertEquals("true", ActionRunner(mapOf(ActionKind.SAVE_NOTE to interrupted), journal = FileActionJournal(journal))
            .execute(request).data["needsReview"])
        val restored = ActionRunner(mapOf(ActionKind.SAVE_NOTE to DurableNoteAdapter(ThoughtNotes(File(root, "notes")), EventLog(root))),
            journal = FileActionJournal(journal))
        assertEquals(ActionStatus.SUCCESS, restored.result(request.id)!!.status)
        assertEquals(1, notes.dates().sumOf { notes.list(it).size })
        assertEquals(1, EventLog(root).all().count { it.kind == EventKind.NOTE })
    }

    @Test fun interruptionImmediatelyAfterPrepareDoesNotExecuteOnRestart() {
        val dir = folder.newFolder()
        var calls = 0
        val adapter = object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult { calls++; return ActionResult(request.id, request.kind, ActionStatus.SUCCESS) }
        }
        val request = ActionRequest("prepared", ActionKind.OPEN_ASSISTANT)
        val failed = FileActionJournal(dir, checkpoint = { if (it == JournalCheckpoint.AFTER_PREPARE) error("crash") })
        ActionRunner(mapOf(ActionKind.OPEN_ASSISTANT to adapter), journal = failed).execute(request)
        assertEquals(0, calls)
        val restored = ActionRunner(mapOf(ActionKind.OPEN_ASSISTANT to adapter), journal = FileActionJournal(dir))
        assertEquals("true", restored.execute(request).data["needsReview"])
        assertEquals("true", restored.result(request.id)!!.data["needsReview"])
        assertEquals(0, calls)
    }

    @Test fun lostReceiptForExternalEffectsNeverReopensOrRespeaks() {
        for (kind in listOf(ActionKind.OPEN_ASSISTANT, ActionKind.SPEAK)) {
            val dir = folder.newFolder()
            var calls = 0
            val adapter = object : ActionAdapter {
                override fun execute(request: ActionRequest): ActionResult { calls++; return ActionResult(request.id, request.kind, ActionStatus.SUCCESS) }
            }
            val request = ActionRequest("external", kind, if (kind == ActionKind.SPEAK) mapOf("text" to "合成朗读") else emptyMap())
            val faulty = FileActionJournal(dir, checkpoint = { if (it == JournalCheckpoint.BEFORE_RECEIPT) error("lost receipt") })
            assertEquals("true", ActionRunner(mapOf(kind to adapter), journal = faulty).execute(request).data["needsReview"])
            val restored = ActionRunner(mapOf(kind to adapter), journal = FileActionJournal(dir))
            assertEquals("true", restored.execute(request).data["needsReview"])
            assertEquals(1, calls)
        }
    }

    @Test fun fullJournalRetainsOldIdentitiesAndStorageFailureDoesNotExecute() {
        val dir = folder.newFolder()
        var calls = 0
        val adapter = object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult { calls++; return ActionResult(request.id, request.kind, ActionStatus.SUCCESS) }
        }
        fun runner() = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter), journal = FileActionJournal(dir, capacity = 1))
        val old = ActionRequest("first", ActionKind.SAVE_NOTE, mapOf("text" to "一"))
        val receipt = runner().execute(old)
        assertEquals(ActionStatus.SUCCESS, receipt.status)
        assertEquals(ActionStatus.FAILED, runner().execute(old.copy(id = "second")).status)
        assertEquals(receipt, runner().execute(old))
        assertEquals(1, calls)
        assertEquals(ActionStatus.FAILED, runner().execute(ActionRequest("first", ActionKind.READ_MEMORY)).status)
        val blocked = folder.newFile()
        assertEquals(ActionStatus.FAILED, ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter),
            journal = FileActionJournal(blocked)).execute(old.copy(id = "blocked")).status)
        assertEquals(1, calls)
    }

    @Test fun twoRunnersReconcileSameNoteWhileFirstWaitsForReceipt() {
        val root = folder.newFolder()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val notes = ThoughtNotes(File(root, "notes"))
        val request = ActionRequest("parallel", ActionKind.SAVE_NOTE, mapOf("text" to "并发念头"))
        val first = DurableNoteAdapter(notes, EventLog(root), afterNoteStored = {
            entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        })
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to first), journal = FileActionJournal(File(root, "journal")))
        try {
            val waiting = pool.submit<ActionResult> { runner.execute(request) }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val other = ActionRunner(mapOf(ActionKind.SAVE_NOTE to DurableNoteAdapter(ThoughtNotes(File(root, "notes")), EventLog(root))),
                journal = FileActionJournal(File(root, "journal")))
            val second = other.execute(request)
            assertEquals(ActionStatus.SUCCESS, second.status)
            assertEquals(ActionStatus.FAILED, other.execute(request.copy(args = mapOf("text" to "修改过"))).status)
            release.countDown()
            assertEquals(second, waiting.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, notes.dates().sumOf { notes.list(it).size })
            assertEquals(1, EventLog(root).all().count { it.kind == EventKind.NOTE })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun readAfterForgetAlwaysUsesCurrentMaterialsAndNeverPersistsContent() {
        val root = folder.newFolder()
        val inbox = cn.companion.pet.core.companion.MaterialInbox(root, EventLog(root))
        val saved = inbox.save(cn.companion.pet.core.companion.MaterialDraft(body = "合成的待遗忘材料"))
            as cn.companion.pet.core.companion.MaterialSaveResult.Created
        val adapter = object : ActionAdapter {
            override fun execute(request: ActionRequest) = ActionResult(request.id, request.kind, ActionStatus.SUCCESS,
                data = mapOf("text" to inbox.all().joinToString { it.body }))
        }
        val dir = File(root, "journal")
        fun runner() = ActionRunner(mapOf(ActionKind.READ_MATERIALS to adapter), journal = FileActionJournal(dir))
        val reader = runner()
        val request = ActionRequest("fresh-read", ActionKind.READ_MATERIALS)
        assertEquals("合成的待遗忘材料", reader.execute(request).data["text"])
        inbox.delete(saved.material.id)
        assertEquals("", reader.execute(request).data["text"])
        assertEquals("", runner().execute(request).data["text"])
        assertFalse(dir.walkTopDown().filter(File::isFile).any { it.readText().contains("合成的待遗忘材料") })
        assertNull(reader.result(request.id))
    }

    @Test fun coldStartCanListUnknownWritesWithoutSavingTheirArguments() {
        val dir = folder.newFolder()
        val adapter = object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult = error("interrupted")
        }
        val request = ActionRequest("cold-start", ActionKind.SPEAK, mapOf("text" to "不进入执行账本的正文"))
        ActionRunner(mapOf(ActionKind.SPEAK to adapter), journal = FileActionJournal(dir)).execute(request)
        val restored = ActionRunner(emptyMap(), journal = FileActionJournal(dir))
        val row = restored.history().single()
        assertEquals("cold-start", row.id)
        assertEquals(ActionKind.SPEAK, row.kind)
        assertNull(row.status)
        assertFalse(dir.walkTopDown().filter(File::isFile).any { it.readText().contains("不进入执行账本的正文") })
    }

    @Test fun cancellingAfterNoteStoragePreservesItsCommittedTruth() {
        val root = folder.newFolder()
        val stored = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val notes = ThoughtNotes(File(root, "notes"))
        val adapter = DurableNoteAdapter(notes, EventLog(root), afterNoteStored = {
            stored.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        })
        val request = ActionRequest("cancel-after-store", ActionKind.SAVE_NOTE, mapOf("text" to "已落盘念头"))
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter), journal = FileActionJournal(File(root, "journal")))
        try {
            val future = pool.submit<ActionResult> { runner.execute(request) }
            assertTrue(stored.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runner.cancel(request.id))
            release.countDown()
            assertEquals(ActionStatus.SUCCESS, future.get(3, java.util.concurrent.TimeUnit.SECONDS).status)
            assertEquals(ActionStatus.SUCCESS, runner.result(request.id)!!.status)
            assertEquals(1, notes.dates().sumOf { notes.list(it).size })
            val restored = ActionRunner(mapOf(ActionKind.SAVE_NOTE to DurableNoteAdapter(ThoughtNotes(File(root, "notes")), EventLog(root))),
                journal = FileActionJournal(File(root, "journal")))
            assertEquals(ActionStatus.SUCCESS, restored.execute(request).status)
            assertEquals(1, EventLog(root).all().count { it.kind == EventKind.NOTE })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun corruptJournalFailsClosedWithoutDeletingTheEvidence() {
        val dir = folder.newFolder()
        var calls = 0
        val adapter = object : ActionAdapter {
            override fun execute(request: ActionRequest): ActionResult { calls++; return ActionResult(request.id, request.kind, ActionStatus.SUCCESS) }
        }
        val request = ActionRequest("before-corruption", ActionKind.SAVE_NOTE, mapOf("text" to "合成内容"))
        assertEquals(ActionStatus.SUCCESS, ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter), journal = FileActionJournal(dir)).execute(request).status)
        val file = File(dir, "writes.json")
        file.writeText("[{truncated")
        val restored = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter), journal = FileActionJournal(dir))
        assertEquals(ActionStatus.FAILED, restored.execute(request).status)
        assertEquals(ActionStatus.FAILED, restored.execute(request.copy(id = "new-id")).status)
        assertEquals(1, calls)
        assertEquals("[{truncated", file.readText())
    }

    @Test fun deletingOneIndexedNoteKeepsLegacyAndOtherNotesAndDigestCompatible() {
        val root = folder.newFolder()
        val notes = ThoughtNotes(File(root, "notes"))
        val date = LocalDate.of(2026, 10, 24)
        val legacy = ThoughtNotes.Thought("08:00", "旧版", "旧引文", "旧格式保留")
        notes.add(date, legacy)
        val adapter = DurableNoteAdapter(notes, EventLog(root), clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC))
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter), journal = FileActionJournal(File(root, "journal")))
        runner.execute(ActionRequest("note-a", ActionKind.SAVE_NOTE, mapOf("text" to "删掉这一条")))
        runner.execute(ActionRequest("note-b", ActionKind.SAVE_NOTE, mapOf("text" to "另一条保留")))
        adapter.delete(date, notes.list(date).single { it.id == "note-a" })
        assertEquals(listOf("旧格式保留", "另一条保留"), notes.list(date).map { it.text })
        val digest = ThoughtNotes.digestPrompt("合成人设", notes.list(date)).last().content
        assertTrue(digest.contains("旧格式保留"))
        assertTrue(digest.contains("另一条保留"))
        assertFalse(digest.contains("删掉这一条"))
        assertFalse(digest.contains("note-b"))
        adapter.delete(date, legacy)
        assertEquals(listOf("另一条保留"), notes.list(date).map { it.text })
        assertEquals(listOf(date), notes.dates())
        val indexedFiles = File(root, "notes/.action-notes").walkTopDown().filter(File::isFile)
        assertFalse(indexedFiles.any { it.readText().contains("删掉这一条") })
    }

    @Test fun closingQueuedLookupPreventsStartingAReceiptRepair() {
        val root = folder.newFolder()
        val notes = ThoughtNotes(File(root, "notes"))
        val request = ActionRequest("queued-check", ActionKind.SAVE_NOTE, mapOf("text" to "合成念头"))
        val interrupted = DurableNoteAdapter(notes, EventLog(root), afterNoteStored = { error("before event") })
        val journal = File(root, "journal")
        ActionRunner(mapOf(ActionKind.SAVE_NOTE to interrupted), journal = FileActionJournal(journal)).execute(request)
        val runner = ActionRunner(mapOf(ActionKind.SAVE_NOTE to DurableNoteAdapter(notes, EventLog(root))), journal = FileActionJournal(journal))
        val queue = mutableListOf<Runnable>()
        val session = ActionSession(runner, java.util.concurrent.Executor { queue += it })
        session.check(ActionRequest(request.id, request.kind))
        session.close()
        queue.single().run()
        assertTrue(EventLog(root).all().isEmpty())
    }
}
