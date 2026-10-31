package com.yishou.app.gate

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 拦截日志：每次切换应用，一手看到了什么、拦没拦、为什么。
 * 真手机上拦不住时，看这里就知道断在哪一步（服务没连上、没在关注名单、有放行、系统不让弹出来……）。
 * 只记包名和判断结果，不记屏幕内容。存在应用私有目录，最多留 400 条。
 */
object GateTrace {
    data class Entry(val at: Long, val pkg: String?, val text: String)

    private const val KEEP = 400
    private val lines = ArrayDeque<Entry>()
    private var file: File? = null
    private var lastKey: String? = null
    private var lastKeyAt = 0L
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "gate_trace.txt")
        file = f
        runCatching {
            if (f.exists()) f.readLines().takeLast(KEEP).mapNotNull(::parse).forEach { lines.addLast(it) }
        }
    }

    /**
     * 记一条。同一个应用、同一句话 30 秒内只记一次（每秒的巡检不会刷屏）。
     */
    @Synchronized
    fun log(text: String, pkg: String? = null, now: Long = System.currentTimeMillis()) {
        val key = "$pkg|$text"
        if (key == lastKey && now - lastKeyAt < 30_000) return
        lastKey = key
        lastKeyAt = now
        val e = Entry(now, pkg, text.replace('\n', ' '))
        lines.addLast(e)
        while (lines.size > KEEP) lines.removeFirst()
        runCatching {
            val f = file ?: return@runCatching
            f.appendText(format(e) + "\n")
            // 文件长到两倍再整理一次，平时只追加
            if (f.length() > 200_000) f.writeText(lines.joinToString("\n", postfix = "\n") { format(it) })
        }
    }

    @Synchronized
    fun recent(): List<Entry> = lines.toList().asReversed()

    @Synchronized
    fun clear() {
        lines.clear()
        lastKey = null
        runCatching { file?.writeText("") }
    }

    fun stamp(at: Long): String = time.format(Instant.ofEpochMilli(at))

    fun format(e: Entry): String = "${e.at}\t${e.pkg.orEmpty()}\t${e.text}"

    private fun parse(line: String): Entry? {
        val p = line.split('\t', limit = 3)
        if (p.size < 3) return null
        return Entry(p[0].toLongOrNull() ?: return null, p[1].ifEmpty { null }, p[2])
    }

    /** 给人看、复制出去的文字 */
    @Synchronized
    fun export(): String = lines.joinToString("\n") { "${stamp(it.at)}  ${it.pkg?.let { p -> "[$p] " }.orEmpty()}${it.text}" }
}
