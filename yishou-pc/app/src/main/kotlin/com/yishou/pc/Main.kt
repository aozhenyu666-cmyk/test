package com.yishou.pc

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.yishou.pc.ui.GateScreen
import com.yishou.pc.ui.MainScreen
import com.yishou.pc.ui.StoneIcon
import com.yishou.pc.ui.YishouTheme
import com.yishou.pc.win.Win32
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/*
 * 「一手」电脑版透明原则（同样显示在首次打开的说明里）：
 * - 只给你自己在自己的电脑上用。图标、托盘、开始菜单里都看得见，不隐藏，不防卸载。
 * - 只读前台窗口的程序名和标题，不读窗口内容，不截屏，不记键盘。
 * - 不联网。设置和记录都是这台电脑上的普通文件。
 * - 随时可以关自启、退出、卸载。退出要写一句原因；被强行结束会在下次启动时记一笔，仅此而已。
 */

private fun dataDir(): File {
    val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
    return File(base, if (Win32.isWindows) "Yishou" else ".yishou-pc").apply { mkdirs() }
}

fun main(args: Array<String>) {
    val dir = dataDir()
    // 只开一个：第二次打开时，请已经在运行的那个把主窗口叫出来，自己退出
    val lock = RandomAccessFile(File(dir, "running.lock"), "rw").channel.tryLock()
    if (lock == null) {
        if ("--tray" !in args) File(dir, "show.request").writeText("show")
        exitProcess(0)
    }
    val controller = Controller(dir)
    val startHidden = "--tray" in args

    application(exitProcessOnExit = false) {
        val prefs by controller.prefs.collectAsState()
        val gate by controller.gate.collectAsState()
        val focus by controller.focus.collectAsState()
        val now by controller.now.collectAsState()
        val quitAsked by controller.quitAsked.collectAsState()
        var mainVisible by remember { mutableStateOf(!startHidden || !prefs.welcomed) }
        val tray = rememberTrayState()

        LaunchedEffect(Unit) { controller.start(this) }
        LaunchedEffect(Unit) { controller.toasts.collect { (t, m) -> tray.sendNotification(Notification(t, m)) } }
        LaunchedEffect(quitAsked) { if (quitAsked) mainVisible = true }
        LaunchedEffect(Unit) { controller.showMain.collect { mainVisible = true } }

        Tray(
            icon = StoneIcon,
            state = tray,
            tooltip = if (focus != null) "一手：专注中" else "一手：守门中",
            onAction = { mainVisible = true },
            menu = {
                Item("打开一手", onClick = { mainVisible = true })
                Item("开始专注 ${prefs.focusMinutes} 分钟", enabled = focus == null, onClick = { controller.startFocus(prefs.focusMinutes) })
                Separator()
                Item("退出…", onClick = { controller.askQuit(true) })
            },
        )

        if (mainVisible) {
            Window(
                onCloseRequest = {
                    mainVisible = false
                    controller.askQuit(false)
                },
                title = "一手",
                icon = StoneIcon,
                state = rememberWindowState(size = DpSize(980.dp, 720.dp)),
            ) {
                YishouTheme { MainScreen(controller) }
            }
        }

        gate?.let { req ->
            key(req.shownAt) {
                Window(
                    onCloseRequest = {},
                    title = "一手：先停一下",
                    icon = StoneIcon,
                    undecorated = true,
                    alwaysOnTop = true,
                    resizable = false,
                    state = rememberWindowState(placement = WindowPlacement.Fullscreen),
                ) {
                    LaunchedEffect(Unit) { Win32.bringToFront(window) }
                    YishouTheme { GateScreen(req, focus?.end, now, controller) }
                }
            }
        }
    }
    lock.release()
}
