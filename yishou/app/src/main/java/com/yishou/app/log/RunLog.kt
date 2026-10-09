package com.yishou.app.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行日志的一条：什么时候、哪一块、出了什么事。 */
data class LogEntry(val time: Long, val area: String, val message: String) {

    fun toJson(): String = JSONObject().put("t", time).put("a", area).put("m", message).toString()

    companion object {
        fun fromJson(line: String): LogEntry? = try {
            val o = JSONObject(line)
            LogEntry(o.getLong("t"), o.optString("a"), o.optString("m"))
        } catch (e: JSONException) {
            null
        }
    }
}

/**
 * 运行日志：只记失败和异常（请求失败、朗读或识别不可用、服务出错、崩溃），
 * 每行一条 JSON 存在应用私有目录，只保留最近 [max] 条。内容只在本机，复制出去由你决定。
 */
class RunLog(
    private val file: File,
    private val max: Int = 300,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _version = MutableStateFlow(0)
    /** 每次有新记录或清空就加一，页面据此刷新 */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized
    fun add(area: String, message: String, error: Throwable? = null) {
        val text = buildString {
            append(message.trim())
            if (error != null) {
                append("（").append(error.javaClass.simpleName)
                error.message?.let { append("：").append(it.take(300)) }
                append("）")
                // 崩溃时带上调用栈的前几行，方便定位
                error.stackTrace.take(STACK_LINES).forEach { append("\n  at ").append(it) }
            }
        }
        val lines = readLines() + LogEntry(clock(), area, text.take(MAX_CHARS)).toJson()
        write(lines.takeLast(max))
        _version.value++
    }

    /** 最新的在前 */
    @Synchronized
    fun entries(): List<LogEntry> = readLines().mapNotNull(LogEntry::fromJson).asReversed()

    @Synchronized
    fun clear() {
        file.delete()
        _version.value++
    }

    private fun readLines(): List<String> = try {
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(lines: List<String>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(lines.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            // 写不进去就算了，日志本身不能让应用出错
        }
    }

    companion object {
        private const val STACK_LINES = 6
        private const val MAX_CHARS = 2000

        @Volatile
        private var installed: RunLog? = null

        fun install(log: RunLog) {
            installed = log
        }

        val current: RunLog? get() = installed

        /** 随手记一条；还没装好时（比如单元测试里）什么都不做。 */
        fun e(area: String, message: String, error: Throwable? = null) {
            installed?.add(area, message, error)
        }

        /** 复制用的纯文本，最新的在前 */
        fun format(entries: List<LogEntry>): String {
            val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            return entries.joinToString("\n") { "${f.format(Date(it.time))} [${it.area}] ${it.message}" }
        }
    }
}
