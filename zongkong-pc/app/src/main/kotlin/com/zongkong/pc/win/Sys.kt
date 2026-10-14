package com.zongkong.pc.win

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** 朗读：Windows 自带的语音（System.Speech），不联网。新的一句会打断上一句。 */
object Voice {
    @Volatile private var current: Process? = null

    fun say(text: String) {
        if (!Win32.isWindows || text.isBlank()) return
        current?.destroy()
        val b64 = Base64.getEncoder().encodeToString(text.replace(Regex("【选项】.*$", RegexOption.MULTILINE), "").toByteArray(Charsets.UTF_16LE))
        val script = "Add-Type -AssemblyName System.Speech; " +
            "\$t=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('$b64')); " +
            "\$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Speak(\$t)"
        current = runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull()
    }
}

/** 截屏：所有显示器拼成一张，缩到 1600 宽，存成 JPEG（只存在这台电脑上，看的时候发给你自己的 AI）。 */
object Screen {
    fun capture(dir: File): String? = try {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        if (ge.isHeadlessInstance) null else {
            val all = ge.screenDevices.map { it.defaultConfiguration.bounds }.fold(Rectangle()) { a, b -> if (a.isEmpty) Rectangle(b) else a.union(b) }
            val img = java.awt.Robot().createScreenCapture(all)
            val scale = minOf(1.0, 1600.0 / img.width)
            val out = BufferedImage((img.width * scale).toInt(), (img.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, out.width, out.height, null)
                dispose()
            }
            dir.mkdirs()
            // 只留最近 20 张
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(19)?.forEach { it.delete() }
            val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
            ImageIO.write(out, "jpg", f)
            f.absolutePath
        }
    } catch (e: Throwable) {
        null
    }
}

/** 执行 PowerShell 命令（总控用）：20 秒超时，输出最多 8000 字。 */
object Shell {
    fun run(command: String, timeoutSec: Long = 20): String {
        if (!Win32.isWindows) return "（不是 Windows，没有执行）"
        return try {
            val p = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
                "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $command",
            ).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { if (out.length < 8000) out.appendLine(it) } } }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return out.toString() + "\n（超过 $timeoutSec 秒，停了）"
            }
            reader.join(1000)
            "退出码 ${p.exitValue()}\n" + out.toString().trim().ifBlank { "（没有输出）" }
        } catch (e: Exception) {
            "执行失败：${e.message}"
        }
    }
}
