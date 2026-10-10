package app.jobtracker

import android.app.Application

class JobTrackerApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
