package com.zongkong.pc.win

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.zongkong.pc.Fg
import java.awt.Window

/** jna-platform 里没有 keybd_event，自己声明。 */
@Suppress("FunctionName")
private interface Keys : StdCallLibrary {
    fun keybd_event(bVk: Byte, bScan: Byte, dwFlags: Int, dwExtraInfo: Pointer?)

    companion object {
        val INSTANCE: Keys by lazy { Native.load("user32", Keys::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    }
}

/** 前台窗口：句柄、进程、认窗口要用的信息。 */
data class WinFg(val hwnd: WinDef.HWND, val pid: Int, val fg: Fg)

/**
 * 用到的 Windows 接口：前台窗口的程序名和标题、多久没碰键盘鼠标、收起 / 关掉窗口、锁屏、开机自启。
 * 不是 Windows 时什么都不做（方便在别的系统上调界面、跑测试）。
 */
object Win32 {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val selfPid = ProcessHandle.current().pid().toInt()

    private var snapshot: Map<Int, Pair<Int, String>> = emptyMap()
    private var snapshotAt = 0L

    fun foreground(): WinFg? {
        if (!isWindows) return null
        return try {
            val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return null
            val pidRef = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
            val pid = pidRef.value
            if (pid == 0 || pid == selfPid) return null
            if (pid !in snapshot || System.currentTimeMillis() - snapshotAt > 30_000) {
                snapshot = processes()
                snapshotAt = System.currentTimeMillis()
            }
            val exe = snapshot[pid]?.second ?: return null
            WinFg(hwnd, pid, Fg(exe.lowercase(), title(hwnd), ancestors(pid)))
        } catch (e: Throwable) {
            null
        }
    }

    private fun title(hwnd: WinDef.HWND): String {
        val len = User32.INSTANCE.GetWindowTextLength(hwnd)
        if (len <= 0) return ""
        val buf = CharArray(len + 1)
        User32.INSTANCE.GetWindowText(hwnd, buf, buf.size)
        return Native.toString(buf)
    }

    private fun processes(): Map<Int, Pair<Int, String>> {
        val snap = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
        if (snap == null || snap == WinNT.INVALID_HANDLE_VALUE) return emptyMap()
        val out = HashMap<Int, Pair<Int, String>>()
        try {
            val entry = Tlhelp32.PROCESSENTRY32.ByReference()
            if (Kernel32.INSTANCE.Process32First(snap, entry)) {
                do {
                    out[entry.th32ProcessID.toInt()] = entry.th32ParentProcessID.toInt() to Native.toString(entry.szExeFile)
                } while (Kernel32.INSTANCE.Process32Next(snap, entry))
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(snap)
        }
        return out
    }

    private fun ancestors(pid: Int): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf(pid)
        var cur = snapshot[pid]?.first ?: return out
        repeat(8) {
            if (cur == 0 || !seen.add(cur)) return out
            val p = snapshot[cur] ?: return out
            out += p.second.lowercase()
            cur = p.first
        }
        return out
    }

    /** 多久没碰键盘鼠标（毫秒）。 */
    fun idleMs(): Long {
        if (!isWindows) return 0
        return try {
            val info = WinUser.LASTINPUTINFO()
            if (!User32.INSTANCE.GetLastInputInfo(info)) return 0
            (Kernel32.INSTANCE.GetTickCount().toLong() - info.dwTime.toLong()) and 0xFFFFFFFFL
        } catch (e: Throwable) {
            0
        }
    }

    fun minimize(hwnd: WinDef.HWND) {
        if (isWindows) runCatching { User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_MINIMIZE) }
    }

    /** 关掉某个程序的所有可见窗口（发“关闭”消息，和点右上角的叉一样），返回关了几个。 */
    fun closeApp(exe: String): Int {
        if (!isWindows) return 0
        val procs = processes()
        var n = 0
        runCatching {
            User32.INSTANCE.EnumWindows({ hwnd, _ ->
                if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                    val pidRef = IntByReference()
                    User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
                    if (pidRef.value != selfPid && procs[pidRef.value]?.second?.lowercase() == exe.lowercase()) {
                        User32.INSTANCE.PostMessage(hwnd, WinUser.WM_CLOSE, null, null)
                        n++
                    }
                }
                true
            }, null)
        }
        return n
    }

    /** Windows 自己的锁屏（要输密码 / PIN 才能回来）。 */
    fun lockWorkStation() {
        if (isWindows) runCatching { User32.INSTANCE.LockWorkStation() }
    }

    /** 把自己的窗口顶到最前：Windows 不让后台程序随便抢前台，先“按一下 Alt”再设前台。 */
    fun bringToFront(window: Window) {
        window.toFront()
        window.requestFocus()
        if (!isWindows) return
        try {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val alt = 0x12.toByte()
            Keys.INSTANCE.keybd_event(alt, 0, 0, null)
            User32.INSTANCE.SetForegroundWindow(hwnd)
            Keys.INSTANCE.keybd_event(alt, 0, 2, null)
        } catch (e: Throwable) {
            // 顶不上来也没关系，锁屏本来就在最上层
        }
    }

    // ---------- 开机自启：当前用户的“启动”注册表项 ----------

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_NAME = "ZongKong"

    /** 安装后的程序路径；直接从源码运行时没有。 */
    private val exePath: String? get() = System.getProperty("jpackage.app-path")

    fun setAutostart(on: Boolean): Boolean {
        if (!isWindows) return false
        return try {
            if (on) {
                val path = exePath ?: return false
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_NAME, "\"$path\" --tray")
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_NAME)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun autostartOn(): Boolean = isWindows && try {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_NAME)
    } catch (e: Exception) {
        false
    }
}

/**
 * 锁屏时把“逃出锁屏”的快捷键吃掉：Win 键、Alt+Tab、Alt+Esc、Ctrl+Esc、Alt+F4。
 * Ctrl+Alt+Del 系统不让拦（也不该拦）——从那里打开任务管理器结束总控，会记成“失联”报给手机。
 * 钩子只在锁屏时生效；出任何错都放行按键，不会把键盘弄坏。
 */
object KeyGuard {
    @Volatile var active = false
    private var started = false

    fun start() {
        if (!Win32.isWindows || started) return
        started = true
        Thread({
            try {
                val hMod = Kernel32.INSTANCE.GetModuleHandle(null)
                val proc = WinUser.LowLevelKeyboardProc { nCode, wParam, info ->
                    try {
                        if (active && nCode >= 0) {
                            val vk = info.vkCode
                            val alt = (info.flags and 0x20) != 0
                            val ctrl = (User32.INSTANCE.GetAsyncKeyState(0x11).toInt() and 0x8000) != 0
                            val swallow = vk == 0x5B || vk == 0x5C || (alt && (vk == 0x09 || vk == 0x1B || vk == 0x73)) || (ctrl && vk == 0x1B)
                            if (swallow) return@LowLevelKeyboardProc WinDef.LRESULT(1)
                        }
                    } catch (e: Throwable) {
                        // 放行
                    }
                    User32.INSTANCE.CallNextHookEx(null, nCode, wParam, WinDef.LPARAM(Pointer.nativeValue(info.pointer)))
                }
                User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, proc, hMod, 0) ?: return@Thread
                val msg = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) != 0) {
                    User32.INSTANCE.TranslateMessage(msg)
                    User32.INSTANCE.DispatchMessage(msg)
                }
            } catch (e: Throwable) {
                // 钩不上就只靠全屏置顶
            }
        }, "keyguard").apply { isDaemon = true; start() }
    }
}
