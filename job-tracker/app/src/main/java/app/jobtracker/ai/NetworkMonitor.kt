package app.jobtracker.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun interface NetworkMonitor {
    fun isOnline(): Boolean
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val network = connectivity?.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
