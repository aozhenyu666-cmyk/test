package com.yishou.pc.win

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.yishou.pc.core.Foreground
import java.awt.Window

/** jna-platform 里没有 keybd_event，自己声明一下 */
@Suppress("FunctionName")
private interface Keys : StdCallLibrary {
    fun keybd_event(bVk: Byte, bScan: Byte, dwFlags: Int, dwExtraInfo: Pointer?)

    companion object {
        val INSTANCE: Keys by lazy { Native.load("user32", Keys::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    }
}

/** 当前前台窗口：句柄（用来最小化、恢复）和认窗口要用的信息。 */
data class Fg(val hwnd: WinDef.HWND, val pid: Int, val info: Foreground)

/** 一个打开着的窗口（规则页“从打开的窗口里选”用）。 */
data class OpenWindow(val exe: String, val title: String)

/**
 * 用到的几个 Windows 接口：读前台窗口的程序名和标题、上级进程、最小化、把自己的窗口顶到最前、开机自启。
 * 只读程序名和窗口标题，不读窗口里的内容。不是 Windows 时什么都不做（方便在别的系统上调界面）。
 */
object Win32 {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val selfPid = ProcessHandle.current().pid().toInt()

    /** 进程快照：pid → (父 pid, 程序名)。前台窗口换了才重新拍。 */
    private var snapshot: Map<Int, Pair<Int, String>> = emptyMap()
    private var snapshotFor = -1

    fun foreground(): Fg? {
        if (!isWindows) return null
        val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return null
        val pidRef = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
        val pid = pidRef.value
        if (pid == 0 || pid == selfPid) return null
        if (pid != snapshotFor) {
            snapshot = processes()
            snapshotFor = pid
        }
        val exe = snapshot[pid]?.second ?: return null
        return Fg(hwnd, pid, Foreground(exe.lowercase(), title(hwnd), ancestors(pid)))
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
        if (snap == null || WinBase_INVALID(snap)) return emptyMap()
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

    @Suppress("FunctionName")
    private fun WinBase_INVALID(h: WinNT.HANDLE) = h == WinNT.INVALID_HANDLE_VALUE

    /** 上级进程的程序名，由近到远，最多 8 层 */
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

    fun minimize(hwnd: WinDef.HWND) {
        if (isWindows) User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_MINIMIZE)
    }

    fun restore(hwnd: WinDef.HWND) {
        if (!isWindows) return
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE)
        User32.INSTANCE.SetForegroundWindow(hwnd)
    }

    /**
     * 把自己的窗口顶到最前并拿到键盘焦点。Windows 不让后台程序随便抢前台，
     * 常用的办法是先“按一下 Alt”，再设前台。
     */
    fun bringToFront(window: Window) {
        window.toFront()
        window.requestFocus()
        if (!isWindows) return
        try {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val alt = 0x12.toByte()
            Keys.INSTANCE.keybd_event(alt, 0, 0, null)
            User32.INSTANCE.SetForegroundWindow(hwnd)
            Keys.INSTANCE.keybd_event(alt, 0, 2 /* KEYEVENTF_KEYUP */, null)
        } catch (e: Throwable) {
            // 顶不上来也没关系，守门页本来就在最上层
        }
    }

    /** 当前打开着、有标题、看得见的窗口 */
    fun openWindows(): List<OpenWindow> {
        if (!isWindows) return emptyList()
        val procs = processes()
        val out = mutableListOf<OpenWindow>()
        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                val t = title(hwnd)
                if (t.isNotBlank()) {
                    val pidRef = IntByReference()
                    User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
                    val exe = procs[pidRef.value]?.second
                    if (exe != null && pidRef.value != selfPid) out += OpenWindow(exe.lowercase(), t)
                }
            }
            true
        }, null)
        return out.distinct()
    }

    // ---------- 开机自启：写在当前用户的“启动”注册表项里，设置里随时能关 ----------

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_NAME = "Yishou"

    /** 安装后的程序路径；直接从源码运行时没有，就不设自启 */
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
