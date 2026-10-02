package com.behaviordept.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.behaviordept.app.ui.theme.BehaviorTheme

/** 从通知或桌面快捷方式进来时要去的页面。seq 每次加一，同一个页面也能再次触发。 */
data class RouteRequest(val route: String, val seq: Int)

class MainActivity : ComponentActivity() {
    private val request = mutableStateOf<RouteRequest?>(null)
    private var seq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        Shortcuts.install(this)
        setContent {
            BehaviorTheme {
                AppRoot(request = request.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val route = when {
            intent.getBooleanExtra(EXTRA_OPEN_TODAY, false) -> ROUTE_TODAY
            else -> intent.getStringExtra(EXTRA_ROUTE)
        } ?: return
        request.value = RouteRequest(route, ++seq)
    }

    companion object {
        const val EXTRA_OPEN_TODAY = "open_today"
        const val EXTRA_ROUTE = "route"
        const val ROUTE_TODAY = "today"
        const val ROUTE_URGE = "urge"
        const val ROUTE_MATCH = "match"
        const val ROUTE_WEEKLY = "weekly"
    }
}
