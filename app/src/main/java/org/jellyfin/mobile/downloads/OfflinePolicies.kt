package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

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
}
