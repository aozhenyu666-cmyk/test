package com.zongkong.pc.win

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.net.httpserver.HttpServer
import com.zongkong.pc.Pac
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@Suppress("FunctionName")
private interface WinInet : StdCallLibrary {
    fun InternetSetOptionW(hInternet: Pointer?, option: Int, buffer: Pointer?, length: Int): Boolean

    companion object {
        val INSTANCE: WinInet by lazy { Native.load("wininet", WinInet::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    }
}

/**
 * 网络层拦网站。
 *  1. 本机起两个小服务：一个发 PAC 脚本，一个是“拦截口”（https 直接拒绝，http 回一页“关着”）。
 *  2. 把当前用户的“自动代理脚本”指向它（注册表 Internet Settings，不用管理员），通知系统刷新。
 * Chrome、Edge、Firefox（默认“用系统代理”）、夸克等都跟着系统代理走。
 * 原来开着代理软件（比如 Clash 设的系统代理）的，不拦的网站照旧走原来的代理。
 * 总控退出时把设置还原；被强行结束时 PAC 地址打不开，浏览器自动直连——不会断网。
 */
class NetGuard(private val dataDir: File) {
    private var pacServer: HttpServer? = null
    private var blockSocket: ServerSocket? = null
    @Volatile private var pac: String = Pac.script(emptySet(), 0)
    @Volatile private var blocked: Set<String> = emptySet()
    var pacPort = 0
        private set
    var blockPort = 0
        private set
    private var version = 0
    @Volatile var lastError: String = ""
        private set

    private val saved = File(dataDir, "proxy-before.txt")

    fun start() {
        if (pacServer != null) return
        try {
            val lo = InetAddress.getLoopbackAddress()
            blockSocket = (18181..18199).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p, 50, lo) }.getOrNull() }
            blockPort = blockSocket?.localPort ?: 0
            blockSocket?.let { ss -> thread(isDaemon = true, name = "netguard-block") { serveBlock(ss) } }
            pacServer = (18180 downTo 18160).firstNotNullOfOrNull { p -> runCatching { HttpServer.create(InetSocketAddress(lo, p), 10) }.getOrNull() }
            pacServer?.let { s ->
                s.createContext("/") { ex ->
                    val body = pac.toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/x-ns-proxy-autoconfig")
                    ex.responseHeaders.add("Cache-Control", "no-store")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                s.start()
                pacPort = s.address.port
            }
        } catch (e: Exception) {
            lastError = "拦截服务起不来：${e.message}"
        }
    }

    /** 拦截口：读第一行，CONNECT（https）直接拒绝；普通 http 回“关着”的页面。 */
    private fun serveBlock(ss: ServerSocket) {
        while (!ss.isClosed) {
            val sock = runCatching { ss.accept() }.getOrNull() ?: continue
            thread(isDaemon = true) {
                runCatching {
                    sock.soTimeout = 5000
                    sock.use { s ->
                        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val parts = line.split(' ')
                        val out = s.getOutputStream()
                        if (parts.firstOrNull() == "CONNECT") {
                            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            val host = parts.getOrNull(1)?.substringAfter("://")?.substringBefore('/').orEmpty()
                            val body = Pac.page(host).toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    /** 更新要拦的域名。变了才改系统设置。 */
    fun update(domains: Set<String>) {
        if (pacPort == 0 || blockPort == 0) return
        val fallback = existingProxy()?.let { "PROXY $it; DIRECT" } ?: "DIRECT"
        val next = Pac.script(domains, blockPort, fallback)
        val changed = next != pac
        pac = next
        blocked = domains
        if (changed || !applied()) apply()
    }

    private val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    private fun url() = "http://127.0.0.1:$pacPort/pac?v=$version"

    /** 系统设置是不是还指着我们（被人手动改掉会再改回来）。 */
    fun applied(): Boolean {
        if (!Win32.isWindows) return true
        return runCatching {
            Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "AutoConfigURL") &&
                Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "AutoConfigURL").startsWith("http://127.0.0.1:$pacPort/pac")
        }.getOrDefault(false)
    }

    /** 原来开着的手动代理（例如 127.0.0.1:7890）。 */
    private fun existingProxy(): String? {
        if (!Win32.isWindows) return null
        return runCatching {
            val on = Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "ProxyEnable") &&
                Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, key, "ProxyEnable") == 1
            if (!on) return null
            val v = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "ProxyServer")
            // “http=127.0.0.1:7890;https=…”这种取 https 那段，否则整段
            v.split(';').firstOrNull { it.startsWith("https=") }?.substringAfter('=') ?: v.takeIf { '=' !in it }
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains("127.0.0.1:$blockPort") }
    }

    private fun apply() {
        if (!Win32.isWindows) return
        try {
            val cur = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "AutoConfigURL") }.getOrNull()
            if (cur != null && !cur.startsWith("http://127.0.0.1:") && !saved.exists()) saved.writeText(cur)
            version++
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "AutoConfigURL", url())
            refresh()
            lastError = ""
        } catch (e: Exception) {
            lastError = "改不了系统代理设置：${e.message}"
        }
    }

    private fun refresh() {
        runCatching {
            WinInet.INSTANCE.InternetSetOptionW(null, 39, null, 0) // INTERNET_OPTION_SETTINGS_CHANGED
            WinInet.INSTANCE.InternetSetOptionW(null, 37, null, 0) // INTERNET_OPTION_REFRESH
        }
    }

    /** 退出时还原。 */
    fun stop() {
        if (Win32.isWindows) runCatching {
            val before = saved.takeIf { it.exists() }?.readText()?.trim()
            if (!before.isNullOrBlank()) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "AutoConfigURL", before)
            else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "AutoConfigURL")) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, "AutoConfigURL")
            saved.delete()
            refresh()
        }
        runCatching { pacServer?.stop(0) }
        runCatching { blockSocket?.close() }
        pacServer = null
    }

    fun blockedNow(): Set<String> = blocked
}
