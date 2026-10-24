package cn.companion.pet

import android.app.Application
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.FactStore
import cn.companion.pet.core.companion.TopicQueue
import cn.companion.pet.core.companion.LegacyEventImport
import cn.companion.pet.core.companion.MaterialInbox
import cn.companion.pet.core.action.ActionRunner
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

    /** 记忆文件的读写都经过这把锁：服务、每晚整理、设置页编辑互斥。 */
    val memoryLock: Any get() = memory.lock

    override fun onCreate() {
        super.onCreate()
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
        actions = AndroidActionAdapters.build(this)
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
