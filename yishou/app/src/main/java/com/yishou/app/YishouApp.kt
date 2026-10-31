package com.yishou.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.yishou.app.data.AppDatabase
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Task
import com.yishou.app.widget.BoardWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.yishou.app.llm.ChatClient
import com.yishou.app.llm.LlmCoach
import com.yishou.app.llm.LlmCardGen
import com.yishou.app.card.CardEngine
import com.yishou.app.card.CardSession
import com.yishou.app.card.Dealt
import com.yishou.app.llm.Vision
import com.yishou.app.round.RoundEngine
import com.yishou.app.settings.SettingsStore
import com.yishou.app.stats.StatsStore
import com.yishou.app.summary.SummaryEngine
import com.yishou.app.summary.SummaryScheduler
import com.yishou.app.system.Notifications
import com.yishou.app.system.Reminders
import com.yishou.app.window.WindowScheduler
import com.yishou.app.log.RunLog
import com.yishou.app.settings.AppZone
import com.yishou.app.speech.SpeechApi
import com.yishou.app.speech.VoiceInput

/*
 * 「一手」透明原则（同样显示在应用内“关于”页）：
 * - 仅供用户本人在自己的设备上使用，所有权限由用户在系统设置中手动授予。
 * - 应用图标、名称正常显示；运行时保持一条常驻通知，写明“一手正在运行”。
 * - 用户随时可以在系统设置里关闭无障碍服务或卸载应用，本应用不做任何阻止。
 * - 无障碍服务只读取当前前台应用的包名，不读取屏幕内容、输入内容或其他应用的数据。
 * - “让陪练看屏”需要你每次在系统弹窗里同意；只在你点“看一眼”（或你设置的自动间隔）时截一张屏，
 *   发给你自己配置的识图模型，图片不保存。随时可以在通知里停止。
 * - “分享 → 收进一手”只接收你主动分享的那一条；文字存进本机素材库，图片只拿去转写或认商品，不保存。
 * - 数据只保存在本机。唯一的网络请求是把任务文本、回答，以及你主动发出的照片或截屏，发送到你自己配置的大模型接口；
 *   填了语音接口时，你说的那一段录音和要朗读的那句话发给你自己配置的语音接口，录音不保存。
 * - 麦克风只在你点麦克风、或语音陪练读完题等你回答时打开，界面上有明显的“在听”标记。
 * - 每日使用总量的限制交给用户另装的「不做手机控」，本应用不重复实现。
 */

/** 应用入口。全局只有这几个对象，手动创建，不用依赖注入框架。 */
class YishouApp : Application() {

    /** 后台备几张核对过的题卡，入口思考页一打开就有题（没填接口时什么都不做） */
    fun prefetchCards() {
        if (!settings.llm.value.isComplete) return
        appScope.launch { cardEngine.prefetch() }
    }

    /** 新开一组题卡（主页、窗口、思考页各自一个） */
    fun newSession(): CardSession = CardSession(cardEngine, database.dao(), database.coach(), predictFirst = settings.app.value.predictFirst)

    /** 主线的教练（填了大模型接口才有；没有就用本机的六面问题接着走） */
    val lineCoach: com.yishou.app.llm.LineCoach by lazy {
        com.yishou.app.llm.LlmLineCoach(ChatClient(config = { settings.llm.value }, http = ChatClient.defaultHttpClient(40), usage = stats))
    }

    /**
     * 新开一个主线会话（主线页、语音对练、入口思考页各自一个）。
     * 岔路：教练改成捉虫 / 变条件，规则不放水；变条件时拿回响队列里的旧结论来撞，回到主干的结论进回响队列。
     */
    fun newLine(): com.yishou.app.line.LineSession {
        val coach = if (settings.llm.value.isComplete) lineCoach else null
        if (!com.yishou.app.system.Variant.chalu) return com.yishou.app.line.LineSession(database.lines(), coach)
        return com.yishou.app.line.LineSession(
            database.lines(), coach,
            style = com.yishou.app.line.CoachStyle.TRAP,
            lenient = false,
            recall = {
                val all = database.echoes().recent().filter { it.kind != com.yishou.app.data.EchoKind.DELTA }
                com.yishou.app.line.Echoes.pick(all, "", System.currentTimeMillis())?.text
            },
            onConclusion = { echo(it, com.yishou.app.data.EchoKind.CONCLUSION, "line") },
        )
    }

    /** 往回响队列里放一条（规则、差值、结论）。太短的不收。 */
    suspend fun echo(text: String, kind: String, scene: String = "") {
        val t = text.trim()
        if (!com.yishou.app.line.Echoes.usable(t)) return
        database.echoes().insert(com.yishou.app.data.Echo(text = t.take(120), kind = kind, scene = scene, createdAt = System.currentTimeMillis()))
    }

    /** 不在协程里时用：后台放一条 */
    fun echoLater(text: String, kind: String, scene: String = "") {
        appScope.launch { echo(text, kind, scene) }
    }

    /** 岔路第一次打开：强度直接拉到强、入口用主线下一手、题卡先押再看 */
    private fun chaluDefaults() {
        if (!com.yishou.app.system.Variant.chalu) return
        val sp = getSharedPreferences("chalu", android.content.Context.MODE_PRIVATE)
        if (sp.getBoolean("init", false)) return
        settings.updateApp { it.copy(talkLevel = "HARD", gateMode = "card", predictFirst = true) }
        sp.edit().putBoolean("init", true).apply()
    }

    val partner: com.yishou.app.partner.PartnerSessions by lazy { com.yishou.app.partner.PartnerSessions(this) }

    /** 素材池：唯一入口，所有练法从这里拿 */
    val library by lazy { com.yishou.app.library.LibraryStore(database) }

    val pool: com.yishou.app.pool.Pool by lazy { com.yishou.app.pool.Pool(this) }

    /** 一天的账：各处练的每一手（总结、时之轮都看它） */
    val dayLedger: com.yishou.app.summary.DayLedger by lazy { com.yishou.app.summary.DayLedger(database) }

    /** 刷着练：看屏出题用识图模型，判回答用文字模型 */
    val scrollLook: ChatClient by lazy { ChatClient(config = { settings.vision.value }, http = ChatClient.defaultHttpClient(60), usage = stats) }
    val scrollJudge: ChatClient by lazy { ChatClient(config = { settings.llm.value }, http = ChatClient.defaultHttpClient(30), usage = stats) }

    /** 问一次文字模型，失败返回 null。判卷、线程出题、提议待定项都走它（纯逻辑那边只认这个形状） */
    fun asker(kind: String): suspend (String, String) -> String? = { system, user ->
        (scrollJudge.complete(system, user, kind = kind, json = false) as? com.yishou.app.llm.LlmResult.Ok)?.value
    }

    /** 判卷：只对照锁死的要点，判卷引的原话本机再核对一遍（judge/） */
    val judge: com.yishou.app.judge.Judge by lazy { com.yishou.app.judge.LlmJudge(asker("判卷")) }

    /** 一手自己的语音伙伴（听写 → 文字模型 → 朗读） */
    val partnerVoice: com.yishou.app.partner.PartnerVoice by lazy { com.yishou.app.partner.PartnerVoice(this) }

    /** 不在协程里时用：后台做一件小事（写库之类） */
    fun appScopeLaunch(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    val level: com.yishou.app.line.Level get() = com.yishou.app.line.Level.of(settings.app.value.talkLevel)

    /** 运行日志装好，再接管未捕获的异常：先记下来，再交给系统照常处理（应用照常退出）。 */
    private fun installRunLog() {
        RunLog.install(RunLog(java.io.File(filesDir, "runlog.jsonl")))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            RunLog.e("崩溃", "线程 ${thread.name} 未处理的异常", e)
            previous?.uncaughtException(thread, e)
        }
    }

    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var engine: RoundEngine
        private set
    lateinit var summaryEngine: SummaryEngine
        private set
    lateinit var vision: Vision
        private set
    lateinit var stats: StatsStore
        private set
    lateinit var speechApi: SpeechApi
        private set
    /** 题卡：出题、核对、发牌、记分 */
    lateinit var cardEngine: CardEngine
        private set

    /** 写素材、锁答案、判卷 */
    lateinit var drillGen: com.yishou.app.llm.DrillGen
        private set
    /** 素材进来的几条路 */
    lateinit var materials: com.yishou.app.system.MaterialImport
        private set

    /** 新开一次练习（练思维页一个） */
    fun newDrill(): com.yishou.app.think.DrillSession =
        com.yishou.app.think.DrillSession(drillGen, database.think(), database.practice())

    /** 题包导入：在本机拆题，选择题进题卡库，申论进素材库 */
    val bankImport: com.yishou.app.bank.BankImport by lazy { com.yishou.app.bank.BankImport(cardEngine, database.think(), classify = { kind, id, text, category -> library.assign(kind, id, text, category) }) }

    /** 本机拆不出题时，让模型把文字整理成题包格式 */
    val bankTidy: com.yishou.app.llm.BankTidy by lazy {
        com.yishou.app.llm.BankTidy(ChatClient(config = { settings.llm.value }, http = ChatClient.defaultHttpClient(90), usage = stats))
    }
    /** 导入题库文件（Word、文本、PDF、图片） */
    val fileImport: com.yishou.app.system.FileImport by lazy { com.yishou.app.system.FileImport(this) }

    private val makingMaterial = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 后台备两段教练写的素材，练思维时不用等（没填接口时什么都不做）。
     * 素材库里还有你自己导入、分享、粘贴进来没练过的，就不现编——AI 先去加工你的东西。
     */
    fun prefetchMaterials() {
        if (!settings.llm.value.isComplete) return
        if (!makingMaterial.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val own = database.think().recentMaterials(300).count { it.uses == 0 && it.source != com.yishou.app.data.MaterialSource.MADE && it.source != com.yishou.app.data.MaterialSource.BUILTIN }
                if (own > 0) return@launch
                repeat(2) {
                    val fresh = database.think().recentMaterials(30).count { it.source == com.yishou.app.data.MaterialSource.MADE && it.uses == 0 }
                    if (fresh >= 2) return@launch
                    if (materials.make(null) is com.yishou.app.llm.LlmResult.Err) return@launch
                }
            } finally {
                makingMaterial.set(false)
            }
        }
    }

    /** 把原文加工成残局（只加工，不现编） */
    val endgameGen: com.yishou.app.llm.EndgameGen by lazy {
        com.yishou.app.llm.LlmEndgameGen(ChatClient(config = { settings.llm.value }, http = ChatClient.defaultHttpClient(60), usage = stats))
    }

    /** 海龟汤的主持人：只回“是 / 不是 / 部分是 / 无关” */
    val soupHost: com.yishou.app.soup.SoupJudge by lazy {
        com.yishou.app.llm.LlmSoupHost(ChatClient(config = { settings.llm.value }, http = ChatClient.defaultHttpClient(20), usage = stats))
    }

    private val makingEndgames = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 后台摆残局：
     * 1. 题库里带依据句的题，本机直接变成残局（不经过模型）；
     * 2. 你导入、分享、粘贴、拍进来的素材，交给模型截出残局，本机核对是原文才收（每次最多加工 3 段）。
     * 教练自己写的素材不拿来做残局。
     */
    fun prefetchEndgames() {
        if (!makingEndgames.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val dao = database.endgames()
                val keys = dao.keys().toHashSet()
                val now = System.currentTimeMillis()
                var added = 0
                for (row in database.cards().recent(3000)) {
                    if (row.source != com.yishou.app.data.CardSource.BANK || "card:${row.id}" in keys) continue
                    val card = runCatching { com.yishou.app.card.Card.parse(org.json.JSONObject(row.json)) }.getOrNull() ?: continue
                    val e = com.yishou.app.endgame.Endgames.fromCard(row.id, card, now + added) ?: continue
                    if (dao.insert(e) > 0) added++
                    if (added >= 200) break
                }
                if (!settings.llm.value.isComplete || dao.countDue(now) >= 8) return@launch
                val sp = getSharedPreferences("endgame", android.content.Context.MODE_PRIVATE)
                val doneIds = sp.getStringSet("materials", emptySet()).orEmpty().toMutableSet()
                val todo = database.think().recentMaterials(300).filter {
                    it.source != com.yishou.app.data.MaterialSource.MADE && it.source != com.yishou.app.data.MaterialSource.BUILTIN &&
                        it.id.toString() !in doneIds && com.yishou.app.think.Words.norm(it.text).length >= 60
                }.take(3)
                for (m in todo) {
                    val r = endgameGen.make(m.text)
                    if (r is com.yishou.app.llm.LlmResult.Err) break
                    (r as com.yishou.app.llm.LlmResult.Ok).value.forEachIndexed { i, d ->
                        dao.insert(com.yishou.app.endgame.Endgames.fromDraft(d, "material:${m.id}#$i", System.currentTimeMillis()))
                    }
                    doneIds += m.id.toString()
                    sp.edit().putStringSet("materials", doneIds.toSet()).apply()
                }
            } catch (e: Exception) {
                RunLog.e("残局", "摆残局失败", e)
            } finally {
                makingEndgames.set(false)
            }
        }
    }

    /** 0.16：入口换回刷题（一张题卡）。升级上来的换一次；之后在设置里改了就听你的 */
    private fun v16Defaults() {
        val sp = getSharedPreferences("upgrade", android.content.Context.MODE_PRIVATE)
        if (sp.getBoolean("v16_gate", false)) return
        settings.updateApp { it.copy(gateMode = "card", gateCard = true) }
        sp.edit().putBoolean("v16_gate", true).apply()
    }

    /** 0.15 装过的“急刹”快捷方式，0.16 起去掉 */
    private fun removeOldShortcuts() {
        runCatching { androidx.core.content.pm.ShortcutManagerCompat.removeDynamicShortcuts(this, listOf("brake")) }
    }

    /** 从分享页过来时要打开的页面（主界面取走后清空） */
    val pendingRoute = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** 分享过来的商品截图认出的商品，交给实操页的“比一比” */
    val pendingGoods = kotlinx.coroutines.flow.MutableStateFlow<List<com.yishou.app.deed.Goods>?>(null)

    /** “看一眼”看到的题，交给陪练窗口当下一张 */
    val incomingCard = kotlinx.coroutines.flow.MutableStateFlow<Dealt?>(null)
    /** 听你说：录音 + 听写（语音接口或系统识别），全应用共用一个，同一时间只听一处 */
    lateinit var voice: VoiceInput
        private set

    /**
     * 陪练窗口的 ViewModel 挂在应用上而不是页面上：页面被关掉、从最近任务划掉，
     * 计时、朗读和提醒照常进行（配合 WindowService 让进程保持前台）。
     */
    val windowOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    /** 应用级的协程范围：只做小组件刷新这类轻量的后台事 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        installRunLog()
        database = AppDatabase.create(this)
        settings = SettingsStore(this)
        chaluDefaults()
        v16Defaults()
        AppZone.id = settings.app.value.timeZone
        stats = StatsStore(this)
        val logError: (String, String) -> Unit = { kind, message -> RunLog.e(kind, message) }
        val coach = LlmCoach(ChatClient(config = { settings.llm.value }, usage = stats, onError = logError))
        engine = RoundEngine(database.dao(), coach)
        cardEngine = CardEngine(
            database.cards(),
            LlmCardGen(ChatClient(config = { settings.llm.value }, http = ChatClient.defaultHttpClient(60), usage = stats, onError = logError)),
        )
        drillGen = com.yishou.app.llm.LlmDrillGen(
            ChatClient(config = { settings.llm.value }, http = ChatClient.defaultHttpClient(60), usage = stats, onError = logError),
        )
        materials = com.yishou.app.system.MaterialImport(this)
        summaryEngine = SummaryEngine(database.dao(), coach, prefs = { settings.app.value }, ledger = { from, to -> dayLedger.between(from, to) })
        vision = Vision(
            ChatClient(config = { settings.vision.value }, http = ChatClient.defaultHttpClient(60), usage = stats, onError = logError),
        )
        speechApi = SpeechApi(config = { settings.speech.value })
        voice = VoiceInput(this)

        appScope.launch { library.indexMissing() }
        prefetchCards()
        prefetchMaterials()
        prefetchEndgames()
        removeOldShortcuts()

        // 时区设置一变就生效
        appScope.launch { settings.app.collect { AppZone.id = it.timeZone } }

        // 断点一变，桌面小组件跟着更新
        appScope.launch {
            val dao = database.dao()
            dao.observeCurrentTask()
                .flatMapLatest { task ->
                    if (task == null) flowOf<Pair<Task?, Breakpoint?>>(null to null)
                    else dao.observeBreakpoint(task.id).map { task to it }
                }
                .collect { (task, bp) ->
                    BoardWidget.render(
                        this@YishouApp,
                        task?.title,
                        bp?.nextQuestion?.takeIf { it.isNotBlank() } ?: bp?.pendingCoachMove,
                    )
                }
        }

        Notifications.ensureChannels(this)
        partner // 恢复未收线记录，不自动启动会话或服务
        WindowScheduler.reschedule(this)
        Reminders.scheduleNudge(this)
        Reminders.scheduleIdle(this)
        SummaryScheduler.schedule(this, replace = false)
    }
}
