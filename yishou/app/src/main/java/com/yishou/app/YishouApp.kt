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

    /** 新开一个主线会话（主线页、语音对练、入口思考页各自一个） */
    fun newLine(): com.yishou.app.line.LineSession =
        com.yishou.app.line.LineSession(database.lines(), if (settings.llm.value.isComplete) lineCoach else null)

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
    val bankImport: com.yishou.app.bank.BankImport by lazy { com.yishou.app.bank.BankImport(cardEngine, database.think()) }

    /** 本机拆不出题时，让模型把文字整理成题包格式 */
    val bankTidy: com.yishou.app.llm.BankTidy by lazy {
        com.yishou.app.llm.BankTidy(ChatClient(config = { settings.llm.value }, http = ChatClient.defaultHttpClient(90), usage = stats))
    }
    /** 导入题库文件（Word、文本、PDF、图片） */
    val fileImport: com.yishou.app.system.FileImport by lazy { com.yishou.app.system.FileImport(this) }

    private val makingMaterial = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 后台备两段教练写的素材，练思维时不用等（没填接口时什么都不做） */
    fun prefetchMaterials() {
        if (!settings.llm.value.isComplete) return
        if (!makingMaterial.compareAndSet(false, true)) return
        appScope.launch {
            try {
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
        summaryEngine = SummaryEngine(database.dao(), coach, prefs = { settings.app.value })
        vision = Vision(
            ChatClient(config = { settings.vision.value }, http = ChatClient.defaultHttpClient(60), usage = stats, onError = logError),
        )
        speechApi = SpeechApi(config = { settings.speech.value })
        voice = VoiceInput(this)

        prefetchCards()
        prefetchMaterials()

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
        WindowScheduler.reschedule(this)
        Reminders.scheduleNudge(this)
        Reminders.scheduleIdle(this)
        SummaryScheduler.schedule(this, replace = false)
    }
}
