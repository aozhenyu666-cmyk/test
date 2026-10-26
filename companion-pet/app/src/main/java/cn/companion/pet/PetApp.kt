package cn.companion.pet

import android.app.Application
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.FactStore
import cn.companion.pet.core.companion.TopicQueue
import cn.companion.pet.core.companion.LegacyEventImport
import cn.companion.pet.core.companion.MaterialInbox
import cn.companion.pet.core.action.ActionRunner
import cn.companion.pet.core.agent.AgentBudgetPort
import cn.companion.pet.core.agent.AgentEventLedger
import cn.companion.pet.core.agent.AgentLoop
import cn.companion.pet.core.agent.AgentModelConfig
import cn.companion.pet.core.agent.AgentTraceStore
import cn.companion.pet.core.agent.ModelAgentDecisionSource
import cn.companion.pet.core.vision.ScreenObservationStore
import cn.companion.pet.core.goal.GoalStore
import cn.companion.pet.core.goal.GoalSupervisor
import cn.companion.pet.core.goal.GoalAgentCoordinator
import cn.companion.pet.core.action.PendingActionStore
import cn.companion.pet.core.vision.VisionAnalyzer
import cn.companion.pet.core.vision.VisionModelConfig
import cn.companion.pet.core.memory.MemoryStore
import cn.companion.pet.core.memory.NightlyStore
import cn.companion.pet.core.probe.AtomicBudget
import cn.companion.pet.core.think.ThoughtNotes
import cn.companion.pet.data.BudgetPrefs
import cn.companion.pet.data.CallStatusPrefs
import cn.companion.pet.data.SecretReadResult
import cn.companion.pet.data.SecretStore
import cn.companion.pet.data.Settings
import cn.companion.pet.data.Stats
import cn.companion.pet.net.ChatCompletionClient
import cn.companion.pet.net.DeepSeekClient
import cn.companion.pet.net.RecordingChatClient
import cn.companion.pet.a11y.AndroidDeviceController
import cn.companion.pet.a11y.AndroidShizukuProbe
import cn.companion.pet.service.AndroidActionAdapters
import cn.companion.pet.work.NightlyWorker
import java.io.File
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/** 全局单例的唯一出处。 */
class PetApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var stats: Stats
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var memory: MemoryStore
        private set
    lateinit var events: EventLog
        private set
    lateinit var nightly: NightlyStore
        private set
    lateinit var facts: FactStore
        private set
    lateinit var memoryEdits: cn.companion.pet.core.memory.MemoryEdits
        private set
    lateinit var topics: TopicQueue
        private set
    lateinit var materialInbox: MaterialInbox
        private set
    lateinit var budget: AtomicBudget
        private set
    lateinit var thoughts: ThoughtNotes
        private set
    lateinit var actions: ActionRunner
        private set
    lateinit var deviceController: cn.companion.pet.core.device.DeviceController
        private set
    lateinit var agentTrace: AgentTraceStore
        private set
    lateinit var agentEventLedger: AgentEventLedger
        private set
    val screenObservations = ScreenObservationStore()
    lateinit var goalStore: GoalStore
        private set
    lateinit var goalSupervisor: GoalSupervisor
        private set
    lateinit var goalCoordinator: GoalAgentCoordinator
        private set
    lateinit var pendingActions: PendingActionStore
        private set
    /** 批准并执行待确认动作的唯一入口（设置页和悬浮气泡共用）。 */
    lateinit var pendingExecutor: cn.companion.pet.core.action.PendingExecutor
        private set
    val visionAnalyzer by lazy { VisionAnalyzer(client) { visionConfig() } }
    lateinit var agentLoop: AgentLoop
        private set
    lateinit var noteActions: cn.companion.pet.core.action.DurableNoteAdapter
        private set
    /** 最近一次调用模型的结果，“开始”页据此告诉用户为什么它不开口。 */
    val callStatus by lazy { CallStatusPrefs(this) }
    val client: ChatCompletionClient by lazy { RecordingChatClient(DeepSeekClient(), callStatus) }
    private val speechHolder = lazy { cn.companion.pet.service.Speech(this) }
    val speech: cn.companion.pet.service.Speech get() = speechHolder.value
    fun stopSpeech() {
        actions.cancelKind(cn.companion.pet.core.action.ActionKind.SPEAK)
        if (speechHolder.isInitialized()) speech.stopAll()
    }
    val actionPlanner by lazy {
        cn.companion.pet.core.action.ActionPlanner(sourceAvailable = { id ->
            synchronized(memoryLock) {
                materialInbox.purgeExpired()
                val snapshot = events.all()
                val visibility = cn.companion.pet.core.companion.EventVisibility(snapshot, memory.tombstones())
                snapshot.any { it.id == id && visibility.allows(it) }
            }
        })
    }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                actions.cancelAll()
                if (speechHolder.isInitialized()) speech.stopAll()
            }
        }
    }

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var keyLoaded = false

    /** API Key 读一次后缓存；设置页保存或清除后调用 [invalidateKey]。 */
    fun apiKey(): String? {
        if (!keyLoaded) {
            cachedKey = (secrets.read() as? SecretReadResult.Available)?.value
            keyLoaded = true
        }
        return cachedKey
    }

    fun invalidateKey() { keyLoaded = false }

    private fun agentModelConfig(): AgentModelConfig? {
        val key = apiKey() ?: return null
        return AgentModelConfig(settings.endpoint, settings.model, key)
    }

    /** 视觉服务单独的 Key（可空，空时用搭话的 Key）。读写碰 Keystore，放 IO 线程。 */
    val visionSecrets by lazy { SecretStore(this, "vision_secret_store", "companion-pet-vision-token") }

    /**
     * 视觉配置：模型名必须由用户填（DeepSeek 搭话模型多半不能看图）。
     * 地址留空沿用搭话的；搭话 Key 只在看图和搭话是同一家时才沿用，换了服务商必须填视觉 Key。
     */
    fun visionSetup(): cn.companion.pet.core.vision.VisionSetup = cn.companion.pet.core.vision.VisionSetupResolver.resolve(
        visionModel = settings.visionModel,
        visionEndpoint = settings.visionEndpoint,
        chatEndpoint = settings.endpoint,
        visionKey = (visionSecrets.read() as? SecretReadResult.Available)?.value,
        chatKey = apiKey(),
    )

    fun visionConfig(): VisionModelConfig? = visionSetup().config

    /** 桌宠在运行时由它注册：设置页点“运行”就交给桌宠跑。没在运行时为 null。 */
    @Volatile var skillLauncher: ((String) -> Unit)? = null

    val modelLister by lazy { cn.companion.pet.net.ModelLister() }

    /** C3 本机主动性：克制状态（持久化）+ 协调器（内存统计）。设置页诊断读它，桌宠大脑用它。 */
    val presencePolicy by lazy {
        cn.companion.pet.core.presence.PresencePolicy(
            cn.companion.pet.core.presence.PresenceConfig(dailyCap = settings.presenceDailyCap),
            cn.companion.pet.core.presence.PresencePolicyStore(java.io.File(filesDir, "memory")),
        )
    }
    val presenceEngine by lazy { cn.companion.pet.core.presence.LocalPresenceEngine(presencePolicy) }
    /** 桌宠运行时注册：设置页诊断读守护状态。没在运行时为 null。 */
    @Volatile var focusStatus: (() -> cn.companion.pet.core.focus.FocusStatus)? = null
    /** 桌宠运行时注册：无障碍执行路径健康的一句话结论。 */
    @Volatile var executionHealth: (() -> String)? = null
    /** 桌宠运行时注册：练习换休息本轮进度。 */
    @Volatile var practiceStatus: (() -> String)? = null
    /** 桌宠运行时注册：五层能力状态与“能否声称监管可靠”。 */
    @Volatile var capabilityStatus: (() -> List<String>)? = null

    /** 用户自己搭的技能（固定步骤，按元素回放）。 */
    val skillStore by lazy { cn.companion.pet.core.skill.SkillStore(java.io.File(filesDir, "memory")) }

    /** 最近一次看图的证据（不含图片/Key/回复原文）。 */
    val visionEvidence by lazy { cn.companion.pet.data.VisionEvidencePrefs(this) }

    /** 记忆文件的读写都经过这把锁：服务、每晚整理、设置页编辑互斥。 */
    val memoryLock: Any get() = memory.lock

    override fun onCreate() {
        super.onCreate()
        // 最先装上：之后任何未捕获异常都会留下记录，开始页可以一键复制给开发者。
        cn.companion.pet.data.Diagnostics.install(this)
        instance = this
        settings = Settings(this)
        stats = Stats(this)
        secrets = SecretStore(this)
        memory = MemoryStore(File(filesDir, "memory"))
        events = EventLog(memory.root)
        facts = FactStore(memory.root)
        topics = TopicQueue(memory.root, events = events)
        materialInbox = MaterialInbox(memory.root, events, topics = topics, lock = memory.lock)
        materialInbox.purgeExpired()
        memoryEdits = cn.companion.pet.core.memory.MemoryEdits(memory, facts, events)
        nightly = NightlyStore(memory, events, facts = facts)
        budget = AtomicBudget(BudgetPrefs(this))
        thoughts = ThoughtNotes(File(memory.root, "notes"))
        noteActions = cn.companion.pet.core.action.DurableNoteAdapter(thoughts, events, memoryLock,
            available = {
                android.os.Looper.myLooper() != android.os.Looper.getMainLooper() &&
                    getSystemService(android.os.PowerManager::class.java)?.isInteractive == true &&
                    getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == false
            })
        deviceController = AndroidDeviceController(this, settings, AndroidShizukuProbe(this))
        actions = AndroidActionAdapters.build(this)
        agentTrace = AgentTraceStore(memory.root)
        agentEventLedger = AgentEventLedger(memory.root)
        goalStore = GoalStore(memory.root)
        goalSupervisor = GoalSupervisor(goalStore) { id ->
            pendingActions.find(id)?.let {
                (it.status == cn.companion.pet.core.action.PendingStatus.PENDING && System.currentTimeMillis() <= it.expiresAt) ||
                    it.status == cn.companion.pet.core.action.PendingStatus.APPROVED
            } == true
        }
        pendingActions = PendingActionStore(memory.root)
        // agentLoop 在下方 actions 之后初始化；这里用 lazy 不行，放到 agentLoop 初始化后。
        
        agentLoop = AgentLoop(
            source = ModelAgentDecisionSource(client) { agentModelConfig() },
            runner = actions,
            budget = object : AgentBudgetPort {
                override fun reserve(day: String) = budget.reserve(settings.dailyLimit, day)
                override fun remaining(day: String) = budget.remaining(settings.dailyLimit, day)
            },
            trace = agentTrace,
            planner = actionPlanner,
            pending = pendingActions,
        )
        goalCoordinator = GoalAgentCoordinator(goalStore, goalSupervisor, agentLoop)
        pendingExecutor = cn.companion.pet.core.action.PendingExecutor(
            pendingActions, actions,
            onFinished = { action, result ->
                if (action.taskId.isNotBlank()) goalStore.applyPendingResult(action.taskId, result, System.currentTimeMillis())
            },
            onRejected = { action ->
                if (action.taskId.isNotBlank()) goalStore.markTaskRejected(action.taskId, System.currentTimeMillis())
            },
        )
        // 上次“批准后、写回执前”被杀的确认：查账本补结果，查不到标为中断，绝不自动重做。
        Thread {
            runCatching { pendingActions.recoverInterrupted(System.currentTimeMillis()) { id -> actions.result(id) } }
        }.start()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        // 旧 today.md 只在第一次启动迁移一次；迁移有备份且幂等。
        LegacyEventImport.ensure(memory, events)
        // 上次提交如果中途崩溃，先把 journal 做完。
        nightly.recover()
        NightlyWorker.schedule(this)
    }

    override fun onTerminate() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        actions.cancelAll()
        if (speechHolder.isInitialized()) speech.shutdown()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: PetApp
            private set
    }
}
