package com.yishou.pc.win

import java.util.Base64

/**
 * 朗读：用 Windows 自带的语音（System.Speech），不联网。中文系统一般自带“慧慧”的声音。
 * 新的一句会打断上一句。
 */
object Voice {
    @Volatile private var current: Process? = null

    fun say(text: String) {
        if (!Win32.isWindows || text.isBlank()) return
        current?.destroy()
        // 文字用 UTF-16 的 base64 传进去，避免中文和引号被命令行弄乱
        val b64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_16LE))
        val script = "Add-Type -AssemblyName System.Speech; " +
            "\$t=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('$b64')); " +
            "\$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Speak(\$t)"
        current = try {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            null
        }
    }
}
