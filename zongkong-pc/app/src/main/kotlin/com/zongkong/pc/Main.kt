package com.zongkong.pc

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.zongkong.pc.ui.CheckInCard
import com.zongkong.pc.ui.GateScreen
import com.zongkong.pc.ui.LockScreen
import com.zongkong.pc.ui.LockShade
import com.zongkong.pc.ui.MainScreen
import com.zongkong.pc.ui.SealIcon
import com.zongkong.pc.ui.Tab
import com.zongkong.pc.ui.ZkTheme
import com.zongkong.pc.win.Win32
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/*
 * 「总控」电脑版。透明原则（也写在“连接与设置”里）：
 * - 只给你自己在自己的电脑上用。图标、托盘、开始菜单里都看得见，不隐藏，不防卸载。
 * - 只读前台窗口的程序名和标题；开了“看屏幕”才截屏，截图只存在这台电脑上。
 * - 联网只为两件事：你自己填的 AI 接口、和手机互通用的你自己的 Notion。
 * - 随时可以退出——要写一句原因，手机会知道；被强行结束，下次打开会记成“失联”。
 */

private fun dataDir(): File {
    val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
    return File(base, if (Win32.isWindows) "ZongKong" else ".zongkong-pc").apply { mkdirs() }
}

fun main(args: Array<String>) {
    val dir = dataDir()
    // 只开一个：再次打开时，请已经在运行的那个把主窗口叫出来，自己退出
    val lock = RandomAccessFile(File(dir, "running.lock"), "rw").channel.tryLock()
    if (lock == null) {
        if ("--tray" !in args) File(dir, "show.request").writeText("show")
        exitProcess(0)
    }
    val c = Controller(dir)
    val startHidden = "--tray" in args

    application(exitProcessOnExit = false) {
        val config by c.config.collectAsState()
        val lockV by c.lock.collectAsState()
        val gate by c.gate.collectAsState()
        val checkIn by c.checkIn.collectAsState()
        var mainVisible by remember { mutableStateOf(!startHidden || !config.welcomed) }
        var tab by remember { mutableStateOf(if (config.welcomed) Tab.TODAY else Tab.LINK) }
        val tray = rememberTrayState()

        LaunchedEffect(Unit) { c.start(this) }
        LaunchedEffect(Unit) { c.toasts.collect { (t, m) -> tray.sendNotification(Notification(t, m.ifBlank { " " })) } }
        LaunchedEffect(Unit) {
            c.showMain.collect { t ->
                mainVisible = true
                Tab.entries.firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { tab = it }
            }
        }
        LaunchedEffect(Unit) {
            val req = File(dir, "show.request")
            while (true) {
                if (req.exists()) { req.delete(); mainVisible = true }
                delay(700)
            }
        }

        Tray(
            icon = SealIcon,
            state = tray,
            tooltip = "总控：看着这台电脑",
            onAction = { mainVisible = true },
            menu = {
                Item("打开总控", onClick = { mainVisible = true; tab = Tab.TODAY })
                Item("跟总控说", onClick = { mainVisible = true; tab = Tab.CHAT })
                Item("结束这次会话", onClick = { c.endSession() })
                Separator()
                Item("退出…", onClick = { mainVisible = true; tab = Tab.LINK })
            },
        )

        if (mainVisible) {
            Window(
                onCloseRequest = { mainVisible = false; c.welcomed() },
                title = "总控",
                icon = SealIcon,
                state = rememberWindowState(size = DpSize(1080.dp, 760.dp)),
            ) {
                ZkTheme { MainScreen(c, tab) { tab = it } }
            }
        }

        // 锁屏：每块显示器一张，主显示器上是完整的那张
        lockV?.let { v ->
            val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().let { ge -> if (ge.isHeadlessInstance) emptyList() else ge.screenDevices.map { it.defaultConfiguration.bounds } }
            val primary = if (GraphicsEnvironment.isHeadless()) null else GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
            screens.forEachIndexed { i, b ->
                key(v.kind, i) {
                    Window(
                        onCloseRequest = {},
                        title = "总控：${v.kind.title}",
                        icon = SealIcon,
                        undecorated = true,
                        alwaysOnTop = true,
                        resizable = false,
                        focusable = b == primary,
                        state = rememberWindowState(position = WindowPosition(b.x.dp, b.y.dp), size = DpSize(b.width.dp, b.height.dp)),
                    ) {
                        LaunchedEffect(Unit) {
                            // 只在别的程序跑到前面时抢回来；你在锁屏里打字时不碰焦点
                            while (true) {
                                if (b == primary) Win32.bringToFront(window)
                                delay(3000)
                            }
                        }
                        ZkTheme { if (b == primary) LockScreen(v, c) else LockShade(v) }
                    }
                }
            }
        }

        // 拦截页（整台没锁时才有）
        if (lockV == null) gate?.let { g ->
            // 按主显示器的大小摆一个普通的置顶窗口；不用“独占全屏”——那个模式下 Windows 的输入法和键盘输入会坏（能选中文字却打不了字）
            val b = if (GraphicsEnvironment.isHeadless()) java.awt.Rectangle(0, 0, 1280, 800) else GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
            key(g.shownAt) {
                Window(
                    onCloseRequest = { c.dismissGate() },
                    title = "总控：${g.target.label} 现在关着",
                    icon = SealIcon,
                    undecorated = true,
                    alwaysOnTop = true,
                    resizable = false,
                    state = rememberWindowState(position = WindowPosition(b.x.dp, b.y.dp), size = DpSize(b.width.dp, b.height.dp)),
                ) {
                    LaunchedEffect(Unit) { Win32.bringToFront(window) }
                    ZkTheme { GateScreen(g, c) }
                }
            }
        }

        // 右下角的问候
        if (lockV == null) checkIn?.let { v ->
            key(v.at) {
                Window(
                    onCloseRequest = { c.dismissCheckIn() },
                    title = "总控问一句",
                    icon = SealIcon,
                    undecorated = true,
                    alwaysOnTop = true,
                    resizable = false,
                    state = rememberWindowState(position = WindowPosition(Alignment.BottomEnd), size = DpSize(420.dp, 300.dp)),
                ) {
                    // 问候小窗不抢焦点：你正在别处打字也不会被打断，点它再回答
                    ZkTheme { CheckInCard(v, c) }
                }
            }
        }
    }
    lock.release()
}
