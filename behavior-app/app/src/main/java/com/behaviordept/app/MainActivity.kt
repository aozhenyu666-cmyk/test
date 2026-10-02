package com.behaviordept.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.behaviordept.app.ui.theme.BehaviorTheme

class MainActivity : ComponentActivity() {
    /** 每次从通知进入都加一，界面据此回到“今日”。 */
    private val openToday = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(EXTRA_OPEN_TODAY, false) == true) openToday.intValue++
        setContent {
            BehaviorTheme {
                AppRoot(openTodaySignal = openToday.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_TODAY, false)) openToday.intValue++
    }

    companion object {
        const val EXTRA_OPEN_TODAY = "open_today"
    }
}
