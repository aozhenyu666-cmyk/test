package com.zongkong.app

import android.app.Application
import com.zongkong.app.data.Actions
import com.zongkong.app.data.Store
import com.zongkong.app.system.Notifications

/** 从别的 App 分享进来的内容：文字 / 链接 / 截图（已复制到本机的路径）。 */
data class Shared(val text: String, val image: String = "", val at: Long = System.currentTimeMillis())

class ZkApp : Application() {
    /** 分享进来、还没处理的内容。 */
    val shared = kotlinx.coroutines.flow.MutableStateFlow<Shared?>(null)

    /** 要带去 ChatGPT 的话（在“去 GPT”页里显示、复制）。 */
    var prompt: String = ""
    var promptTitle: String = ""

    lateinit var store: Store
        private set
    lateinit var actions: Actions
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        actions = Actions(this, store)
        Notifications.ensureChannels(this)
        com.zongkong.app.system.Reminders.reschedule(this)
    }
}

val android.content.Context.zk: ZkApp get() = applicationContext as ZkApp
