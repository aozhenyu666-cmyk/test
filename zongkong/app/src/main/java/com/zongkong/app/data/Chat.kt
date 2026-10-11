package com.zongkong.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.zongkong.app.link.YishouClient
import com.zongkong.core.Config
import com.zongkong.core.Link
import com.zongkong.core.Status
import com.zongkong.core.ZkJson
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.AgentEnv
import com.zongkong.core.agent.ChatClient
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.work.Work
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZoneId
import kotlin.random.Random

/** 参谋的对话和它记住的事，存在 zk/chat.json。 */
@Serializable
data class ChatLog(
    val messages: List<ChatMsg> = emptyList(),
    val memory: List<String> = emptyList(),
)

/**
 * 参谋：对话在应用级的协程里跑，离开对话页也不中断；每一步（调用了什么工具、结果）实时写进对话。
 * 参谋要打开的页面放进 [route]，对话页看到就跳过去。
 */
class ChatRepo(private val context: Context, private val store: Store, private val actions: Actions) {
    private val file = File(File(context.filesDir, "zk"), "chat.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Any()

    private val _log = MutableStateFlow(load())
    val log: StateFlow<ChatLog> = _log.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 参谋说要打开的页面（导航完由界面清掉）。 */
    val route = MutableStateFlow<String?>(null)

    private var job: Job? = null

    val client = ChatClient({ store.config.value.ai })

    private val env = object : AgentEnv {
        override val zone: ZoneId get() = store.zone
        override val rnd: Random = Random.Default
        override fun now() = System.currentTimeMillis()
        override fun config(): Config = store.config.value
        override fun updateConfig(f: (Config) -> Config): Config = store.updateConfig(f)
        override fun work(): Work = store.work.value
        override fun updateWork(f: (Work) -> Work): Work = store.updateWork(f)
        override fun status(): Status = store.status()
        override fun kick() = actions.work.kick()
        override suspend fun yishou(): String = when (val r = YishouClient.status(context)) {
            is YishouClient.Result.Ok -> r.value.let { s ->
                buildString {
                    append("一手（${s.appVersion}）：").append(s.summary()).append('\n')
                    if (s.taskTitle.isNotBlank()) append("当前任务：${s.taskTitle}；目标：${s.taskGoal}\n断点：已知 ${s.known}｜卡在 ${s.stuck}｜下一问 ${s.next}\n")
                    if (s.ref.isNotBlank()) append("这个任务是总控交过去的：${s.ref}\n")
                    append("入口思考页：${if (s.gateOn) "开着" else "没开"}\n")
                    if (s.rule.isNotBlank()) append("${s.summaryDate} 总结的规则：${s.rule}；明天第一问：${s.tomorrowQuestion}\n")
                }
            }
            is YishouClient.Result.Err -> "读不到一手：${r.message}"
        }
        override suspend fun sendToYishou(actionKey: String): String {
            val order = Link.taskOrder(store.work.value, actionKey) ?: return "失败：没有这步行动"
            return when (val r = YishouClient.setTask(context, order)) {
                is YishouClient.Result.Ok -> r.value.ifBlank { "已交给一手" }
                is YishouClient.Result.Err -> "失败：${r.message}"
            }
        }
        override suspend fun sync(): String = actions.sync()
        override fun memory(): List<String> = _log.value.memory
        override fun remember(fact: String) = update { it.copy(memory = (it.memory.filterNot { m -> m == fact } + fact).takeLast(30)) }
        override fun forget(fact: String): Boolean {
            val before = _log.value.memory
            update { it.copy(memory = it.memory.filterNot { m -> m == fact || m.contains(fact) }) }
            return _log.value.memory.size < before.size
        }
        override fun resolveApp(name: String): String? = AppNames.resolve(context, name)
        override fun appLabel(pkg: String): String = AppNames.label(context, pkg)
    }

    val agent = Agent(client, env)

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _busy.value) return
        _busy.value = true
        job = scope.launch {
            try {
                val history = _log.value.messages
                agent.reply(history, t) { m ->
                    update { it.copy(messages = (it.messages + m).takeLast(MAX_KEEP)) }
                    if (m.role == ChatMsg.TOOL && m.route.isNotBlank()) route.value = m.route
                }
            } catch (e: Exception) {
                Log.e(TAG, "参谋出错", e)
                update { it.copy(messages = it.messages + ChatMsg(ChatMsg.ASSISTANT, "出错了：${e.message ?: e.javaClass.simpleName}", at = System.currentTimeMillis(), error = true)) }
            } finally {
                _busy.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        _busy.value = false
    }

    /** 清空对话（记住的事保留）。 */
    fun clear() = update { it.copy(messages = emptyList()) }

    fun forgetAll() = update { it.copy(memory = emptyList()) }

    private fun update(f: (ChatLog) -> ChatLog): Unit = synchronized(lock) {
        val next = f(_log.value)
        _log.value = next
        val text = ZkJson.encodeToString(ChatLog.serializer(), next)
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) file.writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "写 chat.json 失败", e)
        }
        Unit
    }

    private fun load(): ChatLog = try {
        if (file.exists()) ZkJson.decodeFromString(ChatLog.serializer(), file.readText()) else ChatLog()
    } catch (e: Exception) {
        Log.e(TAG, "chat.json 读不了", e)
        ChatLog()
    }

    companion object {
        private const val TAG = "ChatRepo"
        const val MAX_KEEP = 300
    }
}

/** 应用名 ↔ 包名：参谋说“把抖音加进拦截名单”时用。 */
object AppNames {
    private val aliases = mapOf(
        "抖音" to "com.ss.android.ugc.aweme", "快手" to "com.smile.gifmaker", "b站" to "tv.danmaku.bili", "B站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili", "小红书" to "com.xingin.xhs", "微博" to "com.sina.weibo", "知乎" to "com.zhihu.android",
        "贴吧" to "com.baidu.tieba", "王者荣耀" to "com.tencent.tmgp.sgame", "和平精英" to "com.tencent.tmgp.pubgmhd",
    )

    fun resolve(context: Context, name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val pm = context.packageManager
        if ('.' in n && installed(pm, n)) return n
        aliases[n]?.let { if (installed(pm, it)) return it }
        val launchers = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        } catch (e: Exception) {
            emptyList()
        }
        return launchers.map { it.activityInfo.applicationInfo }.firstOrNull { pm.getApplicationLabel(it).toString().equals(n, ignoreCase = true) }?.packageName
            ?: launchers.map { it.activityInfo.applicationInfo }.firstOrNull { pm.getApplicationLabel(it).toString().contains(n, ignoreCase = true) }?.packageName
            ?: aliases[n]
            ?: n.takeIf { '.' in it }
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun installed(pm: PackageManager, pkg: String) = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }
}
