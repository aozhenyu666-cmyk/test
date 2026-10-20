package com.moling.app

import android.app.Application

/**
 * 墨灵的 Application。大脑宿主（[BrainHost]）在这里初始化：
 * 它读一次本地记忆，之后所有界面都从这里取状态。
 */
class MolingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BrainHost.init(this)
    }
}
