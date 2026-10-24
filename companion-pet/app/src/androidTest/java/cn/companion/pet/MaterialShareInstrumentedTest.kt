package cn.companion.pet

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.MaterialDraft
import cn.companion.pet.core.companion.MaterialInbox
import cn.companion.pet.core.companion.MaterialSaveResult
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 设备层：分享入口确实注册；素材在真实文件系统上保存、重启读取、删除。 */
@RunWith(AndroidJUnit4::class)
class MaterialShareInstrumentedTest {

    @Test
    fun textShareResolvesToMaterialActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.name == MaterialShareActivity::class.java.name }
        assertTrue("Manifest must expose ACTION_SEND text/plain", resolved)
    }

    @Test
    fun materialInboxSurvivesFilesAndDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "material-test-" + UUID.randomUUID())
        try {
            val events = EventLog(root)
            val inbox = MaterialInbox(root, events)
            val saved = inbox.save(MaterialDraft(body = "设备合成素材")) as MaterialSaveResult.Created
            val reopened = MaterialInbox(root, EventLog(root))
            assertTrue(reopened.all().any { it.id == saved.material.id })
            assertTrue(reopened.delete(saved.material.id))
            assertTrue(reopened.all().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
