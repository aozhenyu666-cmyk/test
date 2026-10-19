package com.zongkong.pc

import com.sun.jna.platform.win32.Kernel32
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.device.PhoneSide
import com.zongkong.pc.win.Win32
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * 电脑上的文件（%APPDATA%\ZongKong）：设置、状态、每天的使用记录、对话、手机那边最后一次的状态、心跳。
 * 都是普通 JSON，记事本能看。
 */
class Store(val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init { dir.mkdirs() }

    private fun <T> load(name: String, ser: KSerializer<T>, def: T): T = try {
        val f = File(dir, name)
        if (f.exists()) json.decodeFromString(ser, f.readText()) else def
    } catch (e: Exception) {
        def
    }

    @Synchronized
    private fun <T> save(name: String, ser: KSerializer<T>, value: T) {
        try {
            val f = File(dir, name)
            f.parentFile.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json.encodeToString(ser, value))
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // 写不进去不影响运行
        }
    }

    fun loadConfig() = load("config.json", PcConfig.serializer(), PcConfig())
    fun saveConfig(c: PcConfig) = save("config.json", PcConfig.serializer(), c)
    fun loadState() = load("state.json", PcState.serializer(), PcState())
    fun saveState(s: PcState) = save("state.json", PcState.serializer(), s)
    fun loadUsage(d: LocalDate) = load("usage/$d.json", UsageDay.serializer(), UsageDay(d.toString()))
    fun saveUsage(u: UsageDay) = save("usage/${u.date}.json", UsageDay.serializer(), u)
    fun loadChat(): List<ChatMsg> = load("chat.json", ListSerializer(ChatMsg.serializer()), emptyList())
    fun saveChat(c: List<ChatMsg>) = save("chat.json", ListSerializer(ChatMsg.serializer()), c.takeLast(300))
    fun loadPhone(): PhoneSide? = load("phone.json", PhoneSide.serializer(), PhoneSide()).takeIf { it.at > 0 }
    fun savePhone(p: PhoneSide) = save("phone.json", PhoneSide.serializer(), p)

    @Serializable
    private data class Beat(val t: Long, val clean: Boolean, val why: String = "")

    /** 心跳：运行时每半分钟写一次；正常关机时标记“clean”。 */
    fun beat(now: Long, clean: Boolean, why: String = "") = save("heartbeat.json", Beat.serializer(), Beat(now, clean, why))

    /** 上次没有正常退出：最后一次心跳的时刻和原因（手动退出会写原因）。 */
    fun lastUnclean(): Pair<Long, String>? = load("heartbeat.json", Beat.serializer(), Beat(0, true)).takeIf { !it.clean && it.t > 0 }?.let { it.t to it.why }

    companion object {
        /** 开机多久了（判断“上次没正常退出”是关机了还是总控被结束了）。 */
        fun uptimeMs(): Long = if (Win32.isWindows) runCatching { Kernel32.INSTANCE.GetTickCount64() }.getOrDefault(Long.MAX_VALUE / 4) else Long.MAX_VALUE / 4
    }
}
