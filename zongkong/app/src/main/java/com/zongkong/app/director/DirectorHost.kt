package com.zongkong.app.director

import android.content.Context
import android.util.Log
import com.zongkong.app.window.MailuoWindow
import com.zongkong.app.zk
import com.zongkong.core.director.Ask
import com.zongkong.core.director.CHOICE_FIX
import com.zongkong.core.director.CHOICE_GO
import com.zongkong.core.director.Card
import com.zongkong.core.director.Director
import com.zongkong.core.director.DirectorEngine
import com.zongkong.core.director.Facts
import com.zongkong.core.director.FactsSource
import com.zongkong.core.director.FileMindStore
import com.zongkong.core.director.Opening
import com.zongkong.core.director.Reply
import com.zongkong.core.director.Sense
import com.zongkong.core.director.SessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 导演的宿主（施工队 A 的 A1）。
 *
 * 只做四件事：拿着一个 [Director]、把事件 / 回应 / 收线**串行**交给它、把返回的卡交给小窗、统计花费。
 * 装的是施工队 B 的真导演 [DirectorEngine]；构造参数就是约定里 A 要实现的接口：
 * [FactsSource]（[PhoneFacts]）、[Ask]（总控已有的文字模型，30 秒超时 + 字数统计）、
 * `MindStore`（B 写的 [FileMindStore]：原子写 + 备份）。
 *
 * 开关在 设置 → 运行 → 墨灵导演（试验），**默认关**；关着时总控行为和以前完全一样。
 */
object DirectorHost {
    private const val TAG = "DirectorHost"

    /** 导演会读写记忆文件、可能等模型：放后台线程，显示时再切回主线程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 导演是有状态的：事件、回应、开场白必须一条一条来，不能并发打进去。 */
    private val gate = Mutex()

    private var engine: Director? = null
    private var factsSource: FactsSource? = null

    /** 最近一张卡：小窗收起后再点开还能看到。 */
    @Volatile
    private var last: Card? = null

    /** 花费统计（P8）。 */
    val stats = AskStats()

    @Volatile
    var started = false
        private set

    /** 应用启动时调一次（ZkApp.onCreate）。只建对象，开关关着时什么也不做。 */
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        val store = app.zk.store
        val fs = FactsSource { now -> PhoneFacts.of(app, now) }
        factsSource = fs
        engine = DirectorEngine(
            facts = fs,
            ask = stats.wrap(app, com.zongkong.core.LlmAsk(app.zk.actions.llm)),
            store = FileMindStore(File(app.filesDir, "zk/mind.json")),
            config = { store.config.value.mailuo.engine },
        )
        MailuoWindow.attachHost(object : MailuoWindow.Host {
            override fun reply(reply: Reply) = DirectorHost.reply(app, reply)
            override fun onSession(log: SessionLog) = DirectorHost.onSession(app, log)
            override fun cardClosed() { last = null }
        })
        started = true
        Log.i(TAG, "导演宿主启动：DirectorEngine；开关=${store.config.value.mailuo.enabled}")
    }

    /** 开关。关着的时候所有入口都直接返回，不碰模型、不动总控原来的行为。 */
    fun enabled(context: Context): Boolean =
        runCatching { context.zk.store.config.value.mailuo.enabled }.getOrDefault(false)

    fun engineOrNull(): Director? = engine

    /** “此刻”的纯文本（Notion / 调试页用）。 */
    fun nowText(now: Long = System.currentTimeMillis()): String =
        runCatching { engine?.nowText(now) }.getOrNull() ?: "导演还没起来"

    /** 一条感知事件。 */
    fun sense(context: Context, sense: Sense) {
        if (!started || !enabled(context)) return
        val app = context.applicationContext
        scope.launch {
            val card = try {
                gate.withLock { engine?.onSense(sense) }
            } catch (e: Exception) {
                Log.w(TAG, "onSense 失败", e)
                null
            }
            // 事件返回 null 只表示“现在不打扰”，不代表要收起用户正在看的卡。
            if (card != null) present(app, card)
        }
    }

    /** 用户的回应。 */
    fun reply(context: Context, reply: Reply) {
        if (!started) return
        val app = context.applicationContext
        runCatching { app.zk.store.touch() }
        scope.launch {
            val card = try {
                gate.withLock { engine?.onReply(reply) }
            } catch (e: Exception) {
                Log.w(TAG, "onReply 失败", e)
                null
            }
            // “去聊 / 去打开”由小窗自己接着显示开场白或跳设置页，这里不能把它收起。
            val handledByWindow = reply is Reply.Pick && (reply.key == CHOICE_GO || reply.key == CHOICE_FIX)
            if (card != null || !handledByWindow) present(app, card)
        }
    }

    /** 去演员那里聊之前拿开场白。拿不到返回 null（小窗停在原地，不假装有）。 */
    suspend fun opening(
        context: Context,
        actor: com.zongkong.core.director.Actor,
        cardId: String?,
    ): Opening? {
        if (!started) return null
        return try {
            withContext(Dispatchers.Default) { gate.withLock { engine?.opening(actor, cardId, System.currentTimeMillis()) } }
        } catch (e: Exception) {
            Log.w(TAG, "opening 失败", e)
            null
        }
    }

    /** 收线。 */
    fun onSession(context: Context, log: SessionLog) {
        if (!started) return
        val app = context.applicationContext
        runCatching { app.zk.store.touch() }
        scope.launch {
            val card = try {
                gate.withLock { engine?.onSession(log) }
            } catch (e: Exception) {
                Log.w(TAG, "onSession 失败", e)
                null
            }
            present(app, card)
        }
    }

    /** 此刻议程最上面的卡（不调模型）。 */
    fun currentCard(): Card? =
        runCatching { engine?.currentCard(System.currentTimeMillis()) }.getOrNull() ?: last

    /** 此刻的事实（A4 的定时判断要用，不再重复问一次无障碍）。 */
    fun factsNow(now: Long = System.currentTimeMillis()): Facts =
        factsSource?.facts(now) ?: Facts(now = now, minute = 0)

    private suspend fun present(app: Context, card: Card?) = withContext(Dispatchers.Main) {
        if (card == null) {
            last = null
            MailuoWindow.hide()
            return@withContext
        }
        last = card
        MailuoWindow.show(card)
        if (card.speak.isNotBlank() && app.zk.store.config.value.mailuo.speak) MailuoWindow.speak(card.speak)
    }

    // ---------- 花费统计（P8） ----------

    /**
     * 每次请求的字数和次数，按天记。
     * 只记字数不记钱：模型价格会变，字数是能核对的原始值；写坏了最多丢一天的统计。
     */
    class AskStats {
        fun record(context: Context, charsIn: Int, charsOut: Int) {
            val today = com.zongkong.core.DayClock.dateKey(System.currentTimeMillis(), com.zongkong.core.DayClock.BEIJING)
            val p = context.getSharedPreferences("director", Context.MODE_PRIVATE)
            val day = p.getString("day", "") ?: ""
            val calls = if (day == today) p.getInt("calls", 0) + 1 else 1
            val chars = if (day == today) p.getInt("chars", 0) + charsIn + charsOut else charsIn + charsOut
            p.edit().putString("day", today).putInt("calls", calls).putInt("chars", chars).apply()
        }

        /** 文字，给调试页直接用。 */
        fun text(context: Context): String {
            val p = context.getSharedPreferences("director", Context.MODE_PRIVATE)
            val today = com.zongkong.core.DayClock.dateKey(System.currentTimeMillis(), com.zongkong.core.DayClock.BEIJING)
            if ((p.getString("day", "") ?: "") != today) return "今天还没叫过模型"
            return "今天叫了 ${p.getInt("calls", 0)} 次模型，共 ${p.getInt("chars", 0)} 字"
        }

        /** 给导演的 [Ask] 包一层：30 秒超时 + 字数统计；失败一律 null，导演会用本地模板。 */
        fun wrap(context: Context, inner: Ask): Ask = Ask { system, user ->
            val r = withTimeoutOrNull(30_000L) {
                try {
                    inner.ask(system, user)
                } catch (e: Exception) {
                    Log.w(TAG, "问模型失败", e)
                    null
                }
            }
            if (r != null) record(context, system.length + user.length, r.length)
            r
        }
    }
}
