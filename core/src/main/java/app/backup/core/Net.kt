package app.backup.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object Net {
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun flow(ctx: Context): Flow<Boolean> = callbackFlow {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline(app)) }
            override fun onLost(network: Network) { trySend(isOnline(app)) }
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { trySend(isOnline(app)) }
        }
        cm.registerDefaultNetworkCallback(cb)
        trySend(isOnline(app))
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()
}
