package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What to do when something can be done for the downloads on its own: nothing, ask first, or just do it. */
enum class AutoMode(val intValue: Int) {
    OFF(0),
    ASK(1),
    AUTO(2),
    ;

    companion object {
        fun fromInt(value: Int): AutoMode = entries.find { it.intValue == value } ?: OFF
    }
}

/** Whether the app may use mobile data (or any metered network) for the server. */
enum class MobileDataPolicy(val intValue: Int) {
    ASK(0),
    ALWAYS(1),
    NEVER(2),
    ;

    companion object {
        fun fromInt(value: Int): MobileDataPolicy = entries.find { it.intValue == value } ?: ASK
    }
}

object NetworkState {
    /** Mobile data asked once ("yes, this time") lasts until the app is closed. */
    @Volatile
    var allowedThisSession: Boolean = false

    /** On mobile data or another metered network (also when Android's data saver applies). */
    fun isMetered(context: Context): Boolean {
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return connectivity.isActiveNetworkMetered
    }

    /** Whether the server may be used now without asking. */
    fun mayUseNetwork(context: Context, policy: MobileDataPolicy): Boolean =
        !isMetered(context) || policy == MobileDataPolicy.ALWAYS || allowedThisSession

    /** [isMetered] as it changes. */
    fun meteredFlow(context: Context): Flow<Boolean> = callbackFlow {
        val connectivity = context.getSystemService<ConnectivityManager>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isMetered(context))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(isMetered(context))
            }

            override fun onLost(network: Network) {
                trySend(isMetered(context))
            }
        }
        trySend(isMetered(context))
        try {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connectivity?.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            // Too many callbacks registered: the current state only
        }
        awaitClose { runCatching { connectivity?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
