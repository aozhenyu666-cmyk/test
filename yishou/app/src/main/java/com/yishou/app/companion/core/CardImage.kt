package com.yishou.app.companion.core

import java.io.File

/**
 * 训练卡图片的命名与归属规则。
 *
 * 只有用户开着「保留训练卡图片」时才存，而且只存被选入卡片的那一屏：
 * 不为了存卡去持续录屏。文件名用「场次-轮次」，同一条记录重存也只覆盖自己那张。
 *
 * 删除时必须确认路径真的在自家目录下：别让一条坏记录带着路径把别处的文件删了。
 */
object CardImage {

    /** 卡片图放在应用私有目录下的这一层 */
    const val DIR = "companion/cards"

    fun fileName(sessionId: Long, turnId: Long): String = "$sessionId-$turnId.jpg"

    fun dir(filesDir: File): File = File(filesDir, DIR)

    fun file(filesDir: File, sessionId: Long, turnId: Long): File = File(dir(filesDir), fileName(sessionId, turnId))

    /**
     * 这个路径是不是我们自己写的卡片图。
     * 用规范化后的绝对路径比前缀，防止 `..` 或绝对路径混进来。
     */
    fun owned(filesDir: File, path: String): Boolean {
        if (path.isBlank()) return false
        return try {
            val root = dir(filesDir).canonicalFile
            val f = File(path).canonicalFile
            f.parentFile == root && f.name.endsWith(".jpg")
        } catch (e: Exception) {
            false
        }
    }
}
