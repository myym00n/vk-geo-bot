package ru.geo.vkbot

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow

/** Следит за сетью: как только интернет появился — бот переподключается сразу, без ожидания. */
object NetWatch {
    val generation = MutableStateFlow(0)
    @Volatile var online = true
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
                generation.value = generation.value + 1
            }
            override fun onLost(network: Network) {
                online = cm.activeNetwork != null
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
    }

    fun stop(context: Context) {
        val cb = callback ?: return
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        callback = null
    }
}
