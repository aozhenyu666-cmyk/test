package com.zongkong.pc

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.agent.ToolCall
import com.zongkong.core.device.PhoneSide
import com.zongkong.pc.ui.CheckInCard
import com.zongkong.pc.ui.GateScreen
import com.zongkong.pc.ui.LockScreen
import com.zongkong.pc.ui.MainScreen
import com.zongkong.pc.ui.Tab
import com.zongkong.pc.ui.ZkTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 把几个页面画成图片看排版（不需要屏幕）。图片在 app/build/snapshots。 */
class SnapshotTest {
    private val out = File("build/snapshots").apply { mkdirs() }

    private fun shot(name: String, w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(w, h, Density(1f)) { ZkTheme { content() } }
        repeat(3) { scene.render(it * 16_000_000L) }
        val img = scene.render(1_000_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun snapshots() {
        val dir = Files.createTempDirectory("zkpc").toFile()
        val c = Controller(dir)
        val now = System.currentTimeMillis()
        val zone = c.zone
        // 一天：上午写代码，中午 B站，下午文档，晚上游戏
        var day = UsageDay(Usage.day(now, zone).toString())
        val base = Usage.day(now, zone).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        fun use(t: Target, title: String, fromMin: Int, minutes: Int) {
            var at = base + fromMin * 60_000L
            repeat(minutes) { at += 60_000; day = Usage.add(day, t, title, at, 60_000) }
        }
        use(Target("app:code.exe", "VS Code", Cat.WORK), "Main.kt", 0, 110)
        use(Target("site:B站", "B站", Cat.FUN, Defaults.SITES[0]), "【4K】某某番剧 第 3 集 - 哔哩哔哩", 150, 52)
        use(Target("app:winword.exe", "Word", Cat.WORK), "论文第二章.docx", 220, 95)
        use(Target("site:知乎", "知乎", Cat.SOCIAL), "如何评价……", 330, 24)
        use(Target("app:wegame.exe", "WeGame", Cat.GAME), "WeGame", 400, 12)
        c.seed(
            today = day,
            phone = PhoneSide(at = now, main = "高数第三章习题 20 道", goals = listOf("期末复习", "实习投递"), credibility = 66, stance = "正常"),
            chat = listOf(
                ChatMsg(ChatMsg.USER, "我想上 B站 看一个高数第三章的讲解视频", at = now - 120_000),
                ChatMsg(ChatMsg.ASSISTANT, "", at = now - 110_000, calls = listOf(ToolCall("c1", "usage", "{}"))),
                ChatMsg(ChatMsg.TOOL, "…", at = now - 100_000, callId = "c1", summary = "看了电脑使用记录（今天）"),
                ChatMsg(ChatMsg.ASSISTANT, "今天 B站 已经 52 分钟，额度还剩 8 分钟。讲解视频给你 15 分钟，到点回来做第三章前 5 题。\n【选项】好｜我只要 10 分钟｜不看了", at = now - 90_000),
            ),
        )
        c.env.updateState { it.copy(session = Session("写论文第二章", now - 40 * 60_000L, now + 50 * 60_000L), blocks = listOf(BlockHit(now - 3600_000, "app:wegame.exe", "WeGame", "游戏今天不给自己开"))) }
        shot("main-today", 1080, 980) { MainScreen(c, Tab.TODAY) {} }
        shot("main-chat", 1080, 760) { MainScreen(c, Tab.CHAT) {} }
        shot("main-rules", 1080, 1500) { MainScreen(c, Tab.RULES) {} }
        shot("main-link", 1080, 1100) { MainScreen(c, Tab.LINK) {} }
        shot("lock-session", 1440, 900) { LockScreen(Verdict.Machine(LockKind.NO_SESSION, "先说这次开电脑做什么、用多久。"), c) }
        shot("lock-curfew", 1440, 900) { LockScreen(Verdict.Machine(LockKind.CURFEW, "23:30–07:00 电脑锁着。真有事向总控申请。", now + 6 * 3600_000L), c) }
        shot("lock-hq", 1440, 900) { LockScreen(Verdict.Machine(LockKind.HQ, "手机上正在进行总控时间，电脑跟着锁。", now + 25 * 60_000L), c) }
        val bili = Target("site:B站", "B站", Cat.FUN, Defaults.SITES[0])
        shot("gate", 1440, 900) { GateScreen(GateView(bili, "【4K】某某番剧 第 3 集 - 哔哩哔哩 - Google Chrome", "今天的娱乐额度 60 分钟用完了（已用 61）。", 61, 60, now), c) }
        shot("checkin", 420, 230) { CheckInCard(CheckInView(45, "Word 31 分、B站 14 分", now), c) }
        c.seed(confirm = Confirm("x", "Stop-Process -Name steam", "你说十点后不玩，Steam 还开着", now))
        shot("main-confirm", 1080, 760) { MainScreen(c, Tab.CHAT) {} }
    }
}
