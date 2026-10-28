package com.kovtun.pokevault.core

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(AndroidJUnit4::class)
class ConnectivityTest {

    @Test
    fun wifiWithoutValidatedInternetIsNotOnline() {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        assertFalse("INTERNET alone (captive portal / no uplink) is not enough", caps.hasWorkingInternet())

        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertTrue(caps.hasWorkingInternet())
    }

    @Test
    fun noNetworkIsOffline() {
        val none: NetworkCapabilities? = null
        assertFalse(none.hasWorkingInternet())
    }
}
