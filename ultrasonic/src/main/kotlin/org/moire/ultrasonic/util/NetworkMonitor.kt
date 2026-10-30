/*
 * NetworkMonitor.kt
 * Watches whether the head unit has a usable internet connection.
 *
 * The player stops when the network drops in a tunnel and does not start again on its own.
 * To pick the music up where it stopped, the playback service has to know when the network
 * comes back, which is what this does.
 *
 * Note that [Util.hasUsableNetwork] must not be used for this. It reports whether the
 * connection is metered or cellular, not whether a network exists at all, so it stays true
 * while the head unit is offline.
 */
package org.moire.ultrasonic.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

/**
 * Reports when the network becomes available again.
 *
 * The callbacks run on the main thread, so they can touch the player directly.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    private var registered = false

    /**
     * The callback that was registered with the system.
     *
     * It has to be kept, because only the object that was registered may be unregistered
     * again. Without it the callback would stay registered for the lifetime of the
     * process and the monitor would leak.
     */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * True when a network with internet access is available right now.
     *
     * The check is deliberately **not** based on `NET_CAPABILITY_VALIDATED`.
     *
     * That flag is only set after the captive portal probe of the system succeeded, and the
     * probe needs to reach a Google endpoint. On a head unit that is behind a captive portal,
     * behind a company firewall or on a network that blocks the probe, the connection works
     * perfectly while the flag stays unset. The result was that the app never saw the network
     * come back and the music never continued, although everything was fine.
     *
     * Whether the server can really be reached is decided by the resume itself: preparing the
     * player again either works or it does not, and the retry budget in
     * [org.moire.ultrasonic.service.NetworkRecoveryState] keeps the attempts apart.
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = connectivityManager.getNetworkCapabilities(
                    connectivityManager.activeNetwork
                ) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = connectivityManager.activeNetworkInfo
                info != null && info.isConnected
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read the network state")
            false
        }
    }

    /**
     * Registers [onChanged], which is called with the new availability whenever it changes.
     */
    fun register(onChanged: (Boolean) -> Unit) {
        listeners.addIfAbsent(onChanged)
        if (registered) return
        registered = true

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyListeners(true)

            override fun onLost(network: Network) = notifyListeners(isNetworkAvailable())

            override fun onUnavailable() = notifyListeners(false)

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val available = capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                notifyListeners(available)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, callback)
            }
            networkCallback = callback
        } catch (e: Exception) {
            // Some head unit ROMs refuse the callback. The polling fallback in the playback
            // service covers that case.
            Timber.w(e, "Could not register the network callback")
            registered = false
        }
    }

    /**
     * Stops calling the listeners and releases the callback.
     *
     * The callback has to be unregistered, otherwise it stays registered for the lifetime
     * of the process and keeps the monitor alive. The flag has to be cleared as well, so
     * that a later [register] registers the callback again.
     */
    fun unregisterAll() {
        listeners.clear()
        val callback = networkCallback
        networkCallback = null
        registered = false
        if (callback == null) return

        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // The callback may not be registered any more, for example when the system
            // dropped it. That is not an error.
            Timber.w(e, "Could not unregister the network callback")
        }
    }

    private fun notifyListeners(available: Boolean) {
        mainHandler.post {
            listeners.forEach { listener ->
                try {
                    listener(available)
                } catch (e: Exception) {
                    Timber.w(e, "Network listener failed")
                }
            }
        }
    }
}
