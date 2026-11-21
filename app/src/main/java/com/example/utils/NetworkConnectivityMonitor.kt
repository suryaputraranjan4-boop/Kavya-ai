package com.example.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Structured classification of network / provider failures so callers can clearly distinguish
 * strict offline policy blocks, missing validated internet, DNS/connect issues, timeouts,
 * TLS errors, rate limits, auth errors, and server outages.
 */
enum class NetworkFailureKind(val userHint: String) {
    STRICT_OFFLINE_BLOCKED("Strict Offline Mode is enabled; outbound cloud requests are blocked."),
    NO_VALIDATED_INTERNET("No validated internet connection is available."),
    DNS_OR_CONNECT_FAILURE("Could not reach the remote host (DNS or connection failure)."),
    TIMEOUT("The network request timed out."),
    TLS_ERROR("Secure TLS handshake failed."),
    RATE_LIMITED("Service rate limit reached (HTTP 429)."),
    AUTH_ERROR("Authentication rejected (HTTP 401/403)."),
    SERVER_ERROR("Remote service returned a server error (HTTP 5xx)."),
    UNKNOWN("Unexpected network or provider error.")
}

data class NetworkStatusSnapshot(
    val hasActiveNetwork: Boolean = false,
    val hasValidatedInternet: Boolean = false,
    val isCaptivePortal: Boolean = false,
    val isMetered: Boolean = false,
    val transportLabel: String = "NONE",
    val lastCheckedMs: Long = System.currentTimeMillis()
) {
    val isOnlineAndValidated: Boolean
        get() = hasActiveNetwork && hasValidatedInternet && !isCaptivePortal
}

/**
 * Authoritative Android connectivity monitor.
 *
 * Unlike legacy helpers that returned `true` on errors or ignored `NET_CAPABILITY_VALIDATED`
 * and captive portals, this monitor conservatively treats missing permissions, missing
 * `ConnectivityManager`, unvalidated links, or captive portals as offline.
 */
class NetworkConnectivityMonitor private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "NetworkConnectivityMon"

        @Volatile
        private var instance: NetworkConnectivityMonitor? = null

        @Volatile
        internal var testOverrideOnlineState: Boolean? = null

        fun getInstance(context: Context): NetworkConnectivityMonitor {
            val targetCtx = context.applicationContext ?: context
            val existing = instance
            if (existing != null && existing.appContext === targetCtx) return existing
            return synchronized(this) {
                val again = instance
                if (again != null && again.appContext === targetCtx) {
                    again
                } else {
                    NetworkConnectivityMonitor(targetCtx).also { instance = it }
                }
            }
        }

        fun isValidatedOnline(context: Context): Boolean {
            return getInstance(context).refreshNow().isOnlineAndValidated
        }

        fun classifyThrowable(throwable: Throwable?, httpCode: Int? = null): NetworkFailureKind {
            if (throwable is com.example.ai.StrictOfflineBlockedException) {
                return NetworkFailureKind.STRICT_OFFLINE_BLOCKED
            }
            if (httpCode != null) {
                return when (httpCode) {
                    401, 403 -> NetworkFailureKind.AUTH_ERROR
                    429 -> NetworkFailureKind.RATE_LIMITED
                    in 500..599 -> NetworkFailureKind.SERVER_ERROR
                    else -> NetworkFailureKind.UNKNOWN
                }
            }
            var current: Throwable? = throwable
            while (current != null) {
                when (current) {
                    is com.example.ai.StrictOfflineBlockedException -> return NetworkFailureKind.STRICT_OFFLINE_BLOCKED
                    is SocketTimeoutException -> return NetworkFailureKind.TIMEOUT
                    is SSLException -> return NetworkFailureKind.TLS_ERROR
                    is UnknownHostException, is ConnectException -> return NetworkFailureKind.DNS_OR_CONNECT_FAILURE
                    is IOException -> {
                        val msg = current.message.orEmpty().lowercase()
                        if (msg.contains("strict offline") || msg.contains("offline mode")) {
                            return NetworkFailureKind.STRICT_OFFLINE_BLOCKED
                        }
                        if (msg.contains("timeout") || msg.contains("timed out")) {
                            return NetworkFailureKind.TIMEOUT
                        }
                        if (msg.contains("unable to resolve host") || msg.contains("failed to connect") || msg.contains("network is unreachable")) {
                            return NetworkFailureKind.DNS_OR_CONNECT_FAILURE
                        }
                    }
                }
                current = current.cause
            }
            return NetworkFailureKind.UNKNOWN
        }
    }

    private val _status = MutableStateFlow(evaluateCurrentNetwork())
    val status: StateFlow<NetworkStatusSnapshot> = _status.asStateFlow()

    @Volatile
    private var callbackRegistered = false
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerCallbackSafely()
    }

    fun refreshNow(): NetworkStatusSnapshot {
        val evaluated = evaluateCurrentNetwork()
        _status.value = evaluated
        return evaluated
    }

    fun evaluateCurrentNetwork(): NetworkStatusSnapshot {
        val override = testOverrideOnlineState
        if (override != null) {
            return NetworkStatusSnapshot(
                hasActiveNetwork = override,
                hasValidatedInternet = override,
                isCaptivePortal = false,
                isMetered = false,
                transportLabel = if (override) "TEST_OVERRIDE" else "NONE",
                lastCheckedMs = System.currentTimeMillis()
            )
        }

        val hasAccessNetworkState = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAccessNetworkState) {
            Log.w(TAG, "ACCESS_NETWORK_STATE permission missing; treating network as unvalidated.")
            return NetworkStatusSnapshot(
                hasActiveNetwork = false,
                hasValidatedInternet = false,
                isCaptivePortal = false,
                transportLabel = "NO_PERMISSION",
                lastCheckedMs = System.currentTimeMillis()
            )
        }

        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetworkStatusSnapshot(lastCheckedMs = System.currentTimeMillis())

            val activeNetwork = cm.activeNetwork
                ?: return NetworkStatusSnapshot(lastCheckedMs = System.currentTimeMillis())

            val caps = cm.getNetworkCapabilities(activeNetwork)
                ?: return NetworkStatusSnapshot(lastCheckedMs = System.currentTimeMillis())

            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isCaptivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "OTHER"
            }

            NetworkStatusSnapshot(
                hasActiveNetwork = hasInternet,
                hasValidatedInternet = hasInternet && isValidated && !isCaptivePortal,
                isCaptivePortal = isCaptivePortal,
                isMetered = isMetered,
                transportLabel = transport,
                lastCheckedMs = System.currentTimeMillis()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Network capability query failed (${t.javaClass.simpleName}); treating as offline.")
            NetworkStatusSnapshot(lastCheckedMs = System.currentTimeMillis())
        }
    }

    private fun registerCallbackSafely() {
        if (callbackRegistered) return
        val hasPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshNow()
                }

                override fun onLost(network: Network) {
                    refreshNow()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    refreshNow()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
            registeredCallback = callback
            callbackRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register NetworkCallback: ${t.message}")
        }
    }
}
