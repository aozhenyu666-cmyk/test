package com.behaviordept.app

import android.app.Application
import android.content.Context
import com.behaviordept.app.ai.AiClient
import com.behaviordept.app.ai.AiCoach
import com.behaviordept.app.data.AppDatabase
import com.behaviordept.app.data.BackupService
import com.behaviordept.app.data.EventLog
import com.behaviordept.app.data.GuardRepository
import com.behaviordept.app.data.SecretStore
import com.behaviordept.app.data.SessionRepository
import com.behaviordept.app.data.SettingsStore
import com.behaviordept.app.data.StudyRepository
import com.behaviordept.app.data.TrainingRepository
import com.behaviordept.app.data.WeeklyService
import com.behaviordept.app.reminder.Notifications
import com.behaviordept.app.reminder.ReminderScheduler
import com.behaviordept.app.reminder.ReviewDueWorker
import com.behaviordept.app.reminder.UsageSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 手写的依赖容器：单人单机的 App 不需要 DI 框架。 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = AppDatabase.build(appContext)
    val events = EventLog(db.eventDao())
    val study = StudyRepository(db.studyDao(), events)
    val sessions = SessionRepository(db.sessionDao(), events)
    val settings = SettingsStore(appContext)
    val secrets = SecretStore(appContext)
    val coach = AiCoach(appContext, AiClient(), settings, secrets, db.aiCallDao(), events)
    val backup = BackupService(db)
    val training = TrainingRepository(db.skillDao(), db.eventDao(), events)
    val guard = GuardRepository(appContext, db.guardDao(), db.eventDao(), events)
    val weekly = WeeklyService(db, events)
}

class BehaviorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.appScope.launch(Dispatchers.IO) {
            container.secrets.load()
            ReminderScheduler.reschedule(this@BehaviorApp, container.settings.current())
            // v2：第一次打开时放进三角洲、考公两个技能和默认防线规则。
            if (!container.settings.flag(SettingsStore.FLAG_SEEDED_V2)) {
                container.training.seedIfEmpty()
                container.guard.seedIfEmpty()
                container.settings.setFlag(SettingsStore.FLAG_SEEDED_V2)
            }
            container.guard.applyDue()
            container.guard.sync()
        }
        ReviewDueWorker.enqueue(this)
        UsageSyncWorker.enqueue(this)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as BehaviorApp).container
