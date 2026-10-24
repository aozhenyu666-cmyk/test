package cn.companion.pet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.companion.pet.core.action.*
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.think.ThoughtNotes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic files under a fresh cache directory only. Never uses real memory, credentials or chat. */
@RunWith(AndroidJUnit4::class)
class DurableActionInstrumentedTest {
    @Test fun androidFilesSupportReconstructionAndDeletedNoteNeverReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "action-fixture-" + UUID.randomUUID())
        try {
            fun notes() = ThoughtNotes(File(root, "notes"))
            fun adapter() = DurableNoteAdapter(notes(), EventLog(root))
            fun runner() = ActionRunner(mapOf(ActionKind.SAVE_NOTE to adapter()), journal = FileActionJournal(File(root, "journal")))
            val request = ActionRequest("android-file-fixture", ActionKind.SAVE_NOTE, mapOf("text" to "设备合成念头"))
            val first = runner().execute(request)
            assertEquals(ActionStatus.SUCCESS, first.status)
            assertEquals(first, runner().execute(request))
            val stored = notes()
            val date = stored.dates().single()
            assertEquals(1, stored.list(date).size)
            adapter().delete(date, stored.list(date).single())
            assertEquals(first, runner().execute(request))
            assertTrue(notes().list(date).isEmpty())
            assertEquals(1, EventLog(root).all().count { it.kind == EventKind.NOTE })
            assertEquals(1, EventLog(root).all().count { it.kind == EventKind.FORGET })
        } finally { root.deleteRecursively() }
    }
}
