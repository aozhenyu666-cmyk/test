package com.yishou.app

import android.app.Application
import com.yishou.app.data.AppDatabase
import com.yishou.app.llm.ChatClient
import com.yishou.app.llm.LlmCoach
import com.yishou.app.llm.Vision
import com.yishou.app.round.RoundEngine
import com.yishou.app.settings.SettingsStore
import com.yishou.app.stats.StatsStore
import com.yishou.app.summary.SummaryEngine
import com.yishou.app.summary.SummaryScheduler
import com.yishou.app.system.Notifications
import com.yishou.app.system.Reminders
import com.yishou.app.window.WindowScheduler

/*
 * 「一手」透明原则（同样显示在应用内“关于”页）：
 * - 仅供用户本人在自己的设备上使用，所有权限由用户在系统设置中手动授予。
 * - 应用图标、名称正常显示；运行时保持一条常驻通知，写明“一手正在运行”。
 * - 用户随时可以在系统设置里关闭无障碍服务或卸载应用，本应用不做任何阻止。
 * - 无障碍服务只读取当前前台应用的包名，不读取屏幕内容、输入内容或其他应用的数据。
 * - “让陪练看屏”需要你每次在系统弹窗里同意；只在你点“看一眼”（或你设置的自动间隔）时截一张屏，
 *   发给你自己配置的识图模型，图片不保存。随时可以在通知里停止。
 * - 数据只保存在本机。唯一的网络请求是把任务文本、回答，以及你主动发出的照片或截屏，发送到你自己配置的大模型接口。
 * - 每日使用总量的限制交给用户另装的「不做手机控」，本应用不重复实现。
 */

/** 应用入口。全局只有这几个对象，手动创建，不用依赖注入框架。 */
class YishouApp : Application() {

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

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
        settings = SettingsStore(this)
        stats = StatsStore(this)
        val coach = LlmCoach(ChatClient(config = { settings.llm.value }, usage = stats))
        engine = RoundEngine(database.dao(), coach)
        summaryEngine = SummaryEngine(database.dao(), coach, prefs = { settings.app.value })
        vision = Vision(ChatClient(config = { settings.vision.value }, http = ChatClient.defaultHttpClient(60), usage = stats))

        Notifications.ensureChannels(this)
        WindowScheduler.reschedule(this)
        Reminders.scheduleNudge(this)
        SummaryScheduler.schedule(this, replace = false)
    }
}
