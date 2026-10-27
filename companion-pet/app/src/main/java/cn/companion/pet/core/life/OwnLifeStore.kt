package cn.companion.pet.core.life

import kotlinx.serialization.json.Json
import java.io.File

/**
 * 「它自己的生活」的落盘。
 *
 * 只做 I/O：读文件、写文件、原子替换、损坏文件另存备份，**不做任何业务判断**
 * （不推进进度、不改情绪、不挑素材）。那些都是 [OwnLifePlanner] / [OwnLifeCycle] 的事。
 *
 * 写失败时旧文件原样保留（不先删旧文件再写），所以「读到一半断电」不会把生活读没了；
 * 文件损坏时另存一份再返回空状态，下一次写入不会悄悄覆盖掉可恢复的证据。
 */
class OwnLifeStore(
    root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 同目录原子替换；测试里可以换成失败的实现，确认旧状态不丢。 */
    private val replace: (File, File) -> Boolean = ::atomicReplace,
) {
    private val file = File(root, "own_life.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    /**
     * 最近一次读/写失败（或上层编排放手）的原因，成功后清空。
     * 诊断页直接显示这一句；它不影响任何行为。
     */
    @Volatile
    var lastError: String? = null

    /** 读回状态；文件不存在或损坏时返回空状态。任何情况都不抛异常。 */
    fun read(): OwnLifeState = synchronized(lock) {
        if (!file.exists()) {
            lastError = null
            return@synchronized OwnLifeState()
        }
        runCatching { json.decodeFromString(OwnLifeState.serializer(), file.readText()) }
            .getOrElse {
                // 损坏的文件另存一份，不让下一次写入把它覆盖掉。
                val backup = File(file.parentFile, "own_life.corrupt-" + clock() + ".json")
                lastError = if (file.renameTo(backup)) "生活状态文件损坏，已另存为 " + backup.name else "生活状态文件损坏"
                OwnLifeState()
            }
    }

    /** 先写临时文件，再原子替换；替换失败时旧 own_life.json 不动，返回 false。 */
    fun write(state: OwnLifeState): Boolean = synchronized(lock) {
        return@synchronized try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(OwnLifeState.serializer(), state))
            if (replace(tmp, file)) {
                lastError = null
                true
            } else {
                tmp.delete()
                lastError = "生活状态写入失败，保留旧状态"
                false
            }
        } catch (e: Exception) {
            lastError = "生活状态写入失败，保留旧状态"
            false
        }
    }

    private companion object {
        fun atomicReplace(from: File, to: File): Boolean = runCatching {
            java.nio.file.Files.move(
                from.toPath(),
                to.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrElse {
            runCatching {
                java.nio.file.Files.move(
                    from.toPath(),
                    to.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)
        }
    }
}
