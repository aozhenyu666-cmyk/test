package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The PC fish bridge (SurfCore + memory + QQ) as an MCP server for the local engine.
 *
 * The phone never runs Windows software: it only connects to the authenticated
 * Streamable HTTP endpoint the person deployed on the PC (Tailscale/private
 * network + bearer token). The endpoint lives in
 * `/var/lib/dsh-app/fish/bridge.json`, written by the release deploy script,
 * never in the APK. Without a valid file this overlay is not passed at all, so
 * a missing PC bridge cannot delay or break DSH startup.
 */
object FishMcpGuest {
    const val GUEST_DIR = "/opt/dsh-app/fish"
    const val PATCH = "$GUEST_DIR/fish.patch.yml"
    const val CONFIG = "/var/lib/dsh-app/fish/bridge.json"

    private const val WEB_PATCH_ARG = "--patch /etc/dsh-app/dsh-web.patch.yml"
    private const val DSH_WEB = "/usr/local/bin/dsh web"

    data class Endpoint(val url: String, val token: String, val serverName: String)

    fun configFile(rootfs: File): File = File(rootfs, CONFIG.removePrefix("/"))
    fun file(rootfs: File): File = File(rootfs, PATCH.removePrefix("/"))

    /** The launch command with the overlay right after the app's own one; null when [arg] is not the `dsh web` launch. */
    fun launchArg(arg: String): String? {
        val web = arg.indexOf("$DSH_WEB ")
        if (web < 0 || arg.indexOf("$DSH_WEB ", web + 1) >= 0 || arg.contains("--profile")) return null
        if (arg.contains("--patch $PATCH")) return null
        val patch = arg.indexOf(WEB_PATCH_ARG, web)
        val at = if (patch >= 0 && arg.indexOf(WEB_PATCH_ARG, patch + 1) < 0) patch + WEB_PATCH_ARG.length else web + DSH_WEB.length
        return arg.substring(0, at) + " --patch $PATCH" + arg.substring(at)
    }

    /**
     * Reads the endpoint and writes the overlay. False (and no overlay) when the
     * file is absent, malformed, or not http(s): the engine then starts without
     * PC tools instead of failing on a bad patch.
     */
    fun writeFiles(rootfs: File): Boolean {
        val patch = file(rootfs)
        return try {
            val endpoint = readEndpoint(configFile(rootfs)) ?: run {
                patch.delete()
                return false
            }
            val dir = File(rootfs, GUEST_DIR.removePrefix("/"))
            if (Files.isSymbolicLink(dir.toPath()) || (dir.exists() && !dir.isDirectory)) dir.delete()
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
            writeAtomic(patch, patchYaml(endpoint).toByteArray())
            true
        } catch (_: Exception) {
            patch.delete()
            false
        }
    }

    fun readEndpoint(file: File): Endpoint? {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        if (file.length() > 16 * 1024) return null
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return null
        }
        val url = field(text, "url") ?: return null
        val token = field(text, "token") ?: return null
        val serverName = (field(text, "serverName") ?: "fish").let {
            if (Regex("^[A-Za-z0-9_-]{1,32}$").matches(it)) it else "fish"
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (token.length < 16 || token.length > 4096) return null
        if (url.length > 2048 || url.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        if (token.any { it == '\n' || it == '\r' || it == '\u0000' || it == '\'' }) return null
        return Endpoint(url, token, serverName)
    }

    /** Tiny JSON string reader: the file is app-owned and flat, so no JSON dependency is needed. */
    private fun field(text: String, name: String): String? {
        val match = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(text) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .trim()
            .ifEmpty { null }
    }

    fun patchYaml(endpoint: Endpoint): String = buildString {
        appendLine("# dsh-app: PC fish bridge over MCP. Written from /var/lib/dsh-app/fish/bridge.json;")
        appendLine("# passed with --patch on normal launches only (never in safe mode). Edits do not survive.")
        appendLine("- insert:")
        appendLine("    - id: dsh-app-fish")
        appendLine("      name: '@deepseek-ai/dsh-mcp-client'")
        appendLine("      config:")
        appendLine("        serverName: ${endpoint.serverName}")
        appendLine("        transport: streamable-http")
        appendLine("        url: '${endpoint.url.replace("'", "''")}'")
        appendLine("        headers:")
        appendLine("          Authorization: 'Bearer ${endpoint.token}'")
        appendLine("        toolCallTimeoutMs: 120000")
        appendLine("        failOnStartupError: false")
        appendLine("        reconnect:")
        appendLine("          enabled: true")
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && target.readBytes().contentEquals(bytes)) return
        val tmp = File(target.parentFile, ".${target.name}.dsh-app-${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(bytes)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(target)) throw IOException("could not replace ${target.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
