package com.yishou.app.healthwire

import android.content.Context
import android.util.Log
import com.yishou.app.health.Policy
import java.io.File

/**
 * 本地规则的持久化（规范 §4）。
 *
 * 存 `filesDir/local-policy.json`，**原子写**（写 .tmp 再 rename），带 schemaVersion。
 * 一手只有一个进程，所以不需要跨进程同步机制；但**先持久化再开始监管**这条必须守住：
 * [save] 返回成功之后，调用方才能把状态说成「已就绪」。
 *
 * 读不出来（文件损坏）时不崩、不静默放行：返回空规则 + 记录原因，界面据此显示「本地规则读不出来」。
 */
class PolicyStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE)

    @Volatile private var cached: LocalPolicy.Doc? = null

    @Volatile var lastError: String = ""
        private set

    /** 读当前规则（带内存缓存；文件被外部改动的场景不存在，一手只有一个进程）。 */
    @Synchronized
    fun load(): LocalPolicy.Doc {
        cached?.let { return it }
        val doc = try {
            if (file.exists()) LocalPolicy.parse(file.readText()) else LocalPolicy.Doc()
        } catch (e: Exception) {
            lastError = "本地规则读取失败：${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, "读取本地规则失败", e)
            LocalPolicy.Doc()
        }
        cached = doc
        return doc
    }

    /** 原子写：tmp → rename。返回是否落盘成功。 */
    @Synchronized
    fun save(doc: LocalPolicy.Doc): Boolean {
        val text = LocalPolicy.toJson(doc)
        val tmp = File(file.parentFile, "$FILE.tmp")
        return try {
            file.parentFile?.mkdirs()
            tmp.writeText(text)
            // File.renameTo 在同目录内是原子的（POSIX rename 语义）；失败再退回逐字节替换。
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
            cached = doc
            lastError = ""
            Facts.onRuleRevision(doc.revision)
            true
        } catch (e: Exception) {
            lastError = "本地规则保存失败：${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "保存本地规则失败", e)
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
            false
        }
    }

    /**
     * 从哨兵同步下来的一条规则入库。只接受 issuer=yishou、revision 更新的（规范 §4：只能改自己的规则）。
     * @return 新的 revision；没接受时返回当前 revision，error 说明原因（null = 只是旧版本，不算错）。
     */
    @Synchronized
    fun merge(incoming: Policy): Triple<Boolean, Int, String?> {
        val (next, error) = LocalPolicy.merge(load(), incoming)
        if (error != null) return Triple(false, load().revision, error)
        if (next == load()) return Triple(false, next.revision, null)
        val ok = save(next)
        return Triple(ok, next.revision, if (ok) null else lastError)
    }

    /** 紧急暂停：所有规则标 STOPPED 并立即落盘（本地可达，不等网络、不问模型）。 */
    @Synchronized
    fun stopAll(nowWall: Long): Boolean = save(LocalPolicy.stopAll(load()).also { _ -> lastStopAt = nowWall })

    @Synchronized
    fun resumeAll(): Boolean = save(LocalPolicy.resumeAll(load()))

    /**
     * 单独记一份「用户按过紧急暂停」的本地标记（规范 §6）。
     *
     * 为什么不能只看规则上的 `emergencyState`：
     *  - 窗口翻页后 `RuleBuilder` 会按新窗口生成**新 policyId** 的规则，旧的 STOPPED 规则不再存在；
     *  - 进程重启后如果只从规则推断，暂停状态会丢。
     * 一旦丢了，`LocalGuard` 就会重新自动锁屏——「暂停了但兜底还在锁」比不暂停更糟。
     *
     * 所以暂停是**独立持久化**的一件事：只要用户没点恢复，就一直算暂停。
     * 这是纯本地文件，不进同步给哨兵的 policyJson（规范 §4 的字段不变）。
     */
    @Synchronized
    fun markEmergencyStopped(nowWall: Long, reason: String, bySentinel: Boolean = false): Boolean = try {
        val o = org.json.JSONObject()
            .put("stopped", true)
            .put("atWall", nowWall)
            .put("reason", reason.take(200))
            .put("bySentinel", bySentinel)
        val tmp = File(file.parentFile, "$EMERGENCY_FILE.tmp")
        file.parentFile?.mkdirs()
        tmp.writeText(o.toString())
        if (!tmp.renameTo(emergencyFile)) emergencyFile.writeText(o.toString()) else Unit
        lastStopAt = nowWall
        _emergencyStopped = true
        true
    } catch (e: Exception) {
        lastError = "紧急暂停标记写入失败：${e.message ?: e.javaClass.simpleName}"
        Log.e(TAG, "写紧急暂停标记失败", e)
        false
    }

    @Synchronized
    fun clearEmergencyStopped(): Boolean = try {
        emergencyFile.delete()
        _emergencyStopped = false
        true
    } catch (e: Exception) {
        Log.w(TAG, "清除紧急暂停标记失败", e)
        false
    }

    /** 用户按过紧急暂停且还没恢复。**LocalGuard 的硬前置条件**。 */
    fun emergencyStopped(): Boolean {
        if (_emergencyStopped) return true
        val v = try {
            emergencyFile.exists() && org.json.JSONObject(emergencyFile.readText()).optBoolean("stopped", false)
        } catch (e: Exception) {
            // 读不出来时按「已暂停」处理：宁可不锁，也不能在文件损坏时偷偷恢复锁屏。
            Log.w(TAG, "读紧急暂停标记失败，按已暂停处理", e)
            true
        }
        _emergencyStopped = v
        return v
    }

    /**
     * 暂停的「什么时候、为什么」（规范 §6：记录原因与使用时间）。
     *
     * 从标记文件里读，所以**进程重启后界面仍能说出时间与理由**——
     * 只放内存的话，重启后界面就只剩一句「暂停中」，说不清是哪一次、为什么。
     * 没有标记时返回 null。
     */
    fun emergencyRecord(): EmergencyStop.PauseMark? {
        if (!emergencyFile.exists()) return null
        return try {
            val o = org.json.JSONObject(emergencyFile.readText())
            if (!o.optBoolean("stopped", false)) {
                null
            } else {
                EmergencyStop.PauseMark(
                    atWall = o.optLong("atWall", 0L),
                    reason = o.optString("reason").orEmpty(),
                    bySentinel = o.optBoolean("bySentinel", false),
                )
            }
        } catch (e: Exception) {
            // 读不出来但文件在：仍按「暂停中」显示，只是时间/理由未知。
            EmergencyStop.PauseMark(atWall = 0L, reason = "", bySentinel = false)
        }
    }

    private val emergencyFile: File get() = File(file.parentFile, EMERGENCY_FILE)

    @Volatile private var _emergencyStopped: Boolean = false

    /** 紧急暂停的使用时刻（规范 §6：记录原因与使用时间）。 */
    @Volatile var lastStopAt: Long = 0
        private set

    @Synchronized
    fun reset() {
        cached = null
        lastError = ""
        _emergencyStopped = false
    }

    companion object {
        private const val TAG = "PolicyStore"
        private const val FILE = "local-policy.json"

        /** 紧急暂停标记：单独一个文件，既不进 policyJson，也不会被窗口翻页冲掉。 */
        private const val EMERGENCY_FILE = "emergency-stop.json"

        /** 协议里一手的包名（协调者：哨兵按这个键认人）。 */
        const val PKG = "com.yishou.app"
    }
}
