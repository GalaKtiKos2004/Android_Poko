package com.kovtun.pokevault.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive "the internet actually works" signal, one of the sources the screens combine.
 * It is only a hint for the UI (banner, auto-retry): real requests still decide success.
 */
interface ConnectivityObserver {
    val isOnline: Flow<Boolean>
}

/**
 * A network counts as online only when it has INTERNET **and** VALIDATED: the system has checked
 * that it really reaches the internet. Wi-Fi without internet or behind a captive portal
 * has INTERNET but is not VALIDATED.
 */
fun NetworkCapabilities?.hasWorkingInternet(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

@Singleton
class AndroidConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityObserver {

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)

        fun evaluateActive(): Boolean =
            manager.getNetworkCapabilities(manager.activeNetwork).hasWorkingInternet()

        val callback = object : ConnectivityManager.NetworkCallback() {
            // onAvailable is deliberately not treated as "online": validation comes later
            // and is reported through onCapabilitiesChanged.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasWorkingInternet())
            }

            override fun onLost(network: Network) {
                // Another network may still be active (e.g. Wi-Fi lost, mobile data remains).
                trySend(evaluateActive())
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }

        trySend(evaluateActive())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().conflate()
}
