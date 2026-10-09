package com.zongkong.app

import android.app.Application
import com.zongkong.app.data.Actions
import com.zongkong.app.data.Store
import com.zongkong.app.system.Notifications

class ZkApp : Application() {
    lateinit var store: Store
        private set
    lateinit var actions: Actions
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        actions = Actions(this, store)
        Notifications.ensureChannels(this)
    }
}

val android.content.Context.zk: ZkApp get() = applicationContext as ZkApp
