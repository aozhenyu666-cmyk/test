package com.moling.core.mind

import java.io.File

/**
 * 导演记忆的文件存储：先写临时文件再改名（写到一半断电也不会把原文件写坏），
 * 每次覆盖前把上一份留成 .bak；主文件读不出来时退回 .bak。
 */
class FileMindStore(private val file: File) : MindStore {
    private val bak = File(file.path + ".bak")

    @Synchronized
    override fun load(): String? {
        val main = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
        if (MindCodec.tryDecode(main) != null) return main
        val backup = runCatching { bak.takeIf { it.exists() }?.readText() }.getOrNull()
        return if (MindCodec.tryDecode(backup) != null) backup else main
    }

    @Synchronized
    override fun save(json: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json)
        if (file.exists()) file.copyTo(bak, overwrite = true)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
