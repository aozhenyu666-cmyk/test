package com.zongkong.app

import android.app.Application
import com.zongkong.app.data.Actions
import com.zongkong.app.data.Store
import com.zongkong.app.system.Notifications

/**
 * 从别的页面带进对话的一条「主题预填」请求。
 *
 * [topic] 是稳定的键（[com.zongkong.core.agent.OverallSnapshot.Topic] 的 key），
 * [prompt] 是已经转成可读的文字，界面只把它放进输入框，等用户自己决定发不发。
 */
data class ChatTopic(val mode: String, val topic: String, val label: String, val prompt: String)

/** 从别的 App 分享进来的内容：文字 / 链接 / 截图（已复制到本机的路径）。 */
data class Shared(val text: String, val image: String = "", val at: Long = System.currentTimeMillis())

class ZkApp : Application() {
    /** 分享进来、还没处理的内容。 */
    val shared = kotlinx.coroutines.flow.MutableStateFlow<Shared?>(null)

    /** 要带去 ChatGPT 的话（在“去 GPT”页里显示、复制）。 */
    var prompt: String = ""
    var promptTitle: String = ""

    /** 应用级协程：离开页面也要做完的小事（比如通知一手开局）。 */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** 别的页面要发给参谋的一句话（快捷按钮、“问参谋”），对话页打开时发出。 */
    var chatDraft: String = ""

    /**
     * 别的页面要「在整体对话里带着一个主题开聊」（全局页的每个区块）。
     *
     * 和 [chatDraft] 的区别：这个**只预填输入框**，不自动发送、不新建任何东西；
     * [chatDraft] 的语义是「直接说出去」，两者不能混用。
     */
    var chatTopic: ChatTopic? = null

    /** 别的页面带进“脉络 → 录入”的材料。 */
    var veinDraft: String = ""

    lateinit var store: Store
        private set
    lateinit var actions: Actions
        private set
    /** 参谋（AI 对话）。 */
    lateinit var chat: com.zongkong.app.data.ChatRepo
        private set

    override fun onCreate() {
        super.onCreate()
        // 一律北京时间：时间显示、换日、定时都按它
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        store = Store(this)
        actions = Actions(this, store)
        chat = com.zongkong.app.data.ChatRepo(this, store, actions)
        Notifications.ensureChannels(this)
        com.zongkong.app.system.Reminders.reschedule(this)
        com.zongkong.app.system.Reminders.watchdog(this)
    }
}

val android.content.Context.zk: ZkApp get() = applicationContext as ZkApp

/** 广播接收器里拿应用对象。 */
fun zkApp(context: android.content.Context): ZkApp = context.applicationContext as ZkApp
