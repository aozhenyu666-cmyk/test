package com.moling.app

import android.content.Context
import android.util.Log
import com.moling.app.data.LlmClient
import com.moling.app.data.MolingAsk
import com.moling.app.data.MolingCodec
import com.moling.app.data.MolingState
import com.moling.app.data.PendingAction
import com.moling.app.data.SettingsStore
import com.moling.core.atlas.AtlasOps
import com.moling.core.atlas.Node
import com.moling.core.atlas.NodeKind
import com.moling.core.flow.Cause
import com.moling.core.flow.ChannelKind
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.link.describe
import com.moling.core.mind.DirectorConfig
import com.moling.core.mind.DirectorEngine
import com.moling.core.mind.FileMindStore
import com.moling.core.mind.MindCodec
import com.moling.core.mind.MindState
import com.moling.core.mind.dayKey
import com.moling.core.talk.Talk
import com.moling.core.talk.TalkAction
import com.moling.core.talk.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 一条回复：正文 + 动作卡（先给界面看，点“好”才执行）+ 是不是模型回的。 */
data class ChatReply(val text: String, val actions: List<TalkAction>, val byModel: Boolean)

/*
 * 墨灵的大脑宿主（M2）：App 访问核心逻辑的唯一入口。
 *
 * 手里只有四样东西：设置（M1）、问模型的 Ask（M1）、[PhoneFacts]（这一批能看到的局面）、
 * 导演 [DirectorEngine]（核心库）；外加一份 [MolingState] 存成 mind/moling.json。
 * 改状态的操作全过一个 Mutex：模型慢、文件要写，并发会互相覆盖。
 * 界面只读 [state]；写文件在 IO 线程，失败不抛给界面。
 */
object BrainHost {
    private const val TAG = "BrainHost"

    /** 权柄说明，先写死（设置里能改权柄是后面的事）。 */
    private const val TIER_TEXT = "规划者：可以提建议和排计划，动现实前先问你"

    /** 对话只留最近 40 条，记忆才不越长越慢。 */
    private const val TALK_KEEP = 40

    /** 改 MolingState 的操作一条一条来。 */
    private val gate = Mutex()

    @Volatile
    private var ready = false

    private lateinit var settings: SettingsStore
    private lateinit var ask: MolingAsk
    private lateinit var director: DirectorEngine
    private lateinit var stateStore: FileMindStore

    private val _state = MutableStateFlow(MolingState())

    /** 界面读这个：对话、水道、体系图、待办。 */
    val state: StateFlow<MolingState> = _state

    val settingsStore: SettingsStore get() = settings

    /**
     * App 启动时调一次（MolingApp.onCreate）。重复调直接返回。
     * 两个记忆文件都放 filesDir/mind/：杀进程重开还在。
     */
    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val app = context.applicationContext
            val dir = File(app.filesDir, "mind").apply { mkdirs() }

            settings = SettingsStore(app)
            ask = MolingAsk(settings, LlmClient { settings.load() })
            // 事实里只给今天的水道，昨天的不能当今天的主线。
            val phoneFacts = PhoneFacts { todayFlow() }
            val directorStore = FileMindStore(File(dir, "director.json"))
            director = DirectorEngine(
                facts = phoneFacts,
                ask = ask,
                store = directorStore,
                // 每次现读设置：设置页改完人格，下一句话就生效。
                config = { settings.load().let { DirectorConfig(persona = it.persona, callUser = it.callUser) } },
            )
            stateStore = FileMindStore(File(dir, "moling.json"))
            val loaded = MolingCodec.decode(stateStore.load())
            _state.value = loaded
            // 第一次启动就把两个记忆文件落下来：杀进程重开，验收时它们一定都在。
            if (!File(dir, "director.json").exists()) runCatching { directorStore.save(MindCodec.encode(director.snapshot())) }
            if (!File(dir, "moling.json").exists()) runCatching { stateStore.save(MolingCodec.encode(loaded)) }
            ready = true
            Log.i(TAG, "大脑宿主起来了")
        }
    }

    // ---------------------------------------------------------------- 读

    /** 今天的计划；不是今天的（凌晨 4 点换日）算没有。 */
    fun todayFlow(): FlowPlan? {
        val plan = _state.value.flow ?: return null
        return plan.takeIf { it.day == dayKey(System.currentTimeMillis()) }
    }

    /** 给提示词用的水道文字；没有就是空串。 */
    fun flowText(): String = todayFlow()?.let { Flow.render(it) }.orEmpty()

    /** 水道里此刻该在的那条标题；没有就是空串。 */
    fun currentChannelTitle(now: Long): String {
        val plan = todayFlow() ?: return ""
        return Flow.current(plan, Flow.minuteOf(now))?.title.orEmpty()
    }

    /** 此刻的纯文本（提示词、调试页用）。导演没起来时不抛异常，返回空串。 */
    fun nowText(now: Long): String = if (ready) runCatching { director.nowText(now) }.getOrDefault("") else ""

    /** 接力棒的下一问（没有就空串）。 */
    fun batonNextAsk(): String = directorSnapshot().baton?.nextAsk.orEmpty()

    /** 导演记忆的快照（调试页、接力棒用）。 */
    fun directorSnapshot(): MindState =
        if (ready) runCatching { director.snapshot() }.getOrDefault(MindState()) else MindState()

    // ---------------------------------------------------------------- 发消息

    /**
     * 发一句话，拿回复。顺序照 DEEPSEEK.md M3 第 1–4 步：
     * 先把自己这句记进对话 → 拼提示词问模型 → 拆正文和动作 → 正文进对话并落盘。
     * 模型失败 / 没配密钥 / 超时都用 Talk.local 的本地回复顶上，绝不抛给界面。
     */
    suspend fun send(text: String): ChatReply = gate.withLock {
        if (!ready) return@withLock ChatReply("我还没起来，等一下再说。", emptyList(), false)
        val now = System.currentTimeMillis()
        val s0 = _state.value
        val withUser = s0.copy(talk = (s0.talk + Turn("他", text, now)).takeLast(TALK_KEEP))
        // 先把自己这句摆上，模型慢的时候界面也有反应。
        _state.value = withUser

        val reply = try {
            val cfg = settings.load()
            val prompt = Talk.prompt(
                persona = cfg.persona,
                now = nowText(now),
                flow = flowText(),
                baton = batonNextAsk(),
                atlas = atlasOutline(),
                tierText = TIER_TEXT,
                // 刚加的那句在 user 参数里，history 里别重复。
                history = withUser.talk.dropLast(1),
                user = text,
            )
            ask.ask(cfg.persona, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "问模型失败，改用本地回复", e)
            null
        }

        val parsed = if (reply != null) Talk.parse(reply, Flow.dayStart(dayKey(now)))
        else Talk.local(text, batonNextAsk(), currentChannelTitle(now))

        val s1 = _state.value
        commit(s1.copy(talk = (s1.talk + Turn("墨灵", parsed.text, now)).takeLast(TALK_KEEP)))
        ChatReply(parsed.text, parsed.actions, byModel = reply != null)
    }

    // ---------------------------------------------------------------- 动作

    /**
     * 用户点了动作卡上的“好”。点之前什么都不做；到这才执行。
     * 总控指令 / 去聊这一批只记账（下一批接总控）；记下 → 进体系图；改道 → 改今天的水道。
     * 失败返回一句人话，不抛给界面。
     */
    suspend fun accept(action: TalkAction): String = gate.withLock {
        if (!ready) return@withLock "我还没起来，等一下再点。"
        try {
            val now = System.currentTimeMillis()
            val s = _state.value
            when (action) {
                is TalkAction.Zk -> {
                    val item = PendingAction("p-" + now + "-" + s.pending.size, "zk", action.command.describe(), now)
                    commit(s.copy(pending = s.pending + item))
                    "记下了，这一项下一批再接上"
                }
                is TalkAction.Summon -> {
                    val item = PendingAction("p-" + now + "-" + s.pending.size, "summon", "去聊 " + action.actor.label + "：" + action.topic, now)
                    commit(s.copy(pending = s.pending + item))
                    "记下了，这一项下一批再接上"
                }
                is TalkAction.AtlasNote -> {
                    val (atlas, topic) = AtlasOps.topic(s.atlas, action.topic, now)
                    val node = Node(
                        id = "n-" + now + "-" + atlas.nodes.size,
                        topicId = topic.id,
                        claim = action.claim,
                        kind = NodeKind.JUDGMENT,
                        source = "墨灵对话",
                        confidence = "中",
                        createdAt = now,
                    )
                    commit(s.copy(atlas = atlas.copy(nodes = atlas.nodes + node), topicId = topic.id))
                    "记进体系图「" + topic.name + "」：" + action.claim
                }
                is TalkAction.Reroute -> reroute(action.cause)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "动作没办成", e)
            "刚才没办成，等会儿再试"
        }
    }

    /** 改道：卡住 / 累了 / 晚了 / 情况变了 → 对今天的水道重排，并留一句为什么。 */
    private suspend fun reroute(causeText: String): String {
        val now = System.currentTimeMillis()
        val plan = todayFlow() ?: return "今天还没有水道"
        val minute = Flow.minuteOf(now)
        val channel = Flow.current(plan, minute)
            ?: plan.channels.firstOrNull { it.kind == ChannelKind.MAIN }
            ?: plan.channels.firstOrNull()
            ?: return "今天还没有水道"
        val cause = when {
            causeText.contains("卡") -> Cause.STUCK
            causeText.contains("累") -> Cause.TIRED
            causeText.contains("晚") -> Cause.LATE
            else -> Cause.CONFLICT
        }
        val (next, msg) = Flow.reroute(plan, channel.id, cause, minute)
        commit(_state.value.copy(flow = next))
        return msg
    }

    // ---------------------------------------------------------------- 存取

    /** 把新状态摆到界面上，再落盘。写文件失败只记日志：内存里的状态不能丢。 */
    private suspend fun commit(new: MolingState) {
        _state.value = new
        withContext(Dispatchers.IO) {
            runCatching { stateStore.save(MolingCodec.encode(new)) }
                .onFailure { Log.w(TAG, "moling.json 写失败", it) }
        }
    }

    /** 当前主题的大纲；没主题就空串。 */
    private fun atlasOutline(): String {
        val s = _state.value
        if (s.topicId.isBlank() || s.atlas.topics.none { it.id == s.topicId }) return ""
        return AtlasOps.outline(s.atlas, s.topicId)
    }
}
