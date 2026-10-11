package com.yishou.pc.core

import org.json.JSONObject
import java.io.File

/**
 * 心跳：运行时每半分钟写一次时间，正常退出（托盘退出、关机）时标记一下。
 * 下次启动时，上次没标记就说明是被结束的（任务管理器、断电），记进逃跑记录。
 */
class Heartbeat(private val file: File) {

    fun beat(now: Long) = write(now, clean = false)

    fun markClean(now: Long) = write(now, clean = true)

    /** 上次没有正常退出时，返回最后一次心跳的时间。 */
    fun lastUnclean(): Long? = try {
        if (!file.exists()) null
        else {
            val o = JSONObject(file.readText())
            if (o.optBoolean("clean", true)) null else o.optLong("t").takeIf { it > 0 }
        }
    } catch (e: Exception) {
        null
    }

    private fun write(now: Long, clean: Boolean) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("t", now).put("clean", clean).toString())
        } catch (e: Exception) {
            // 写不进去不影响守门
        }
    }
}
