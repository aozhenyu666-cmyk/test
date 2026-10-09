package com.yishou.pc

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.yishou.pc.core.Event
import com.yishou.pc.core.EventType
import com.yishou.pc.core.GateInfo
import com.yishou.pc.core.Pass
import com.yishou.pc.core.Prefs
import com.yishou.pc.ui.GateScreen
import com.yishou.pc.ui.MainScreen
import com.yishou.pc.ui.YishouTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 把几个页面画成图片，看排版（不需要屏幕）。图片在 app/build/snapshots。 */
class SnapshotTest {
    private val out = File("build/snapshots").apply { mkdirs() }

    private fun shot(name: String, w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(w, h, Density(1f)) { YishouTheme { content() } }
        repeat(3) { scene.render(it * 16_000_000L) }
        val img = scene.render(100_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun snapshots() {
        val dir = Files.createTempDirectory("pcsnap").toFile()
        val c = Controller(dir)
        val prefs = Prefs()
        val now = System.currentTimeMillis()
        val web = prefs.category(Prefs.WEB)!!
        val pass = Pass(Prefs.WEB, now - 20 * 60_000, now - 5 * 60_000, "看一集番剧", "做两道资料分析")
        val base = GateInfo(web, false, 15, 40, 15, 20, 2, 3, null, null)
        shot("gate-followup", 1280, 800) { GateScreen(GateRequest(base.copy(timeUp = pass, followUp = pass), "【4K】某某番剧 第 3 集 - 哔哩哔哩 - 夸克", null, now), null, now, c) }
        shot("gate-form", 1280, 900) { GateScreen(GateRequest(base.copy(timeUp = pass), "【4K】某某番剧 第 3 集 - 哔哩哔哩 - 夸克", null, now), null, now, c) }
        shot("gate-focus", 1280, 800) {
            GateScreen(GateRequest(base.copy(focus = true, maxGrant = 0), "WeGame", null, now), now + 23 * 60_000 + 15_000, now, c)
        }
        c.updatePrefs { it.copy(welcomed = false) }
        shot("main-today", 980, 900) { MainScreen(c) }
    }
}
