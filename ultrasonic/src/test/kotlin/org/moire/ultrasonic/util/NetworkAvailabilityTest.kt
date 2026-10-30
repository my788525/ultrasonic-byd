/*
 * NetworkAvailabilityTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Guards the rule that decides whether the music may continue after a network failure.
 *
 * The rule that matters: the decision must **not** depend on `NET_CAPABILITY_VALIDATED`.
 *
 * That flag is set by the captive portal probe of the system, which needs to reach a Google
 * endpoint. On a head unit behind a captive portal, behind a company firewall or on a network
 * that blocks the probe, the connection works fine while the flag stays unset. Relying on it
 * meant that the app never noticed that the network was back and the music never continued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NetworkAvailabilityTest {

    private val application = RuntimeEnvironment.getApplication()

    private fun capabilities(internet: Boolean, validated: Boolean): NetworkCapabilities {
        val capabilities = mock<NetworkCapabilities>()
        whenever(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .thenReturn(internet)
        whenever(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .thenReturn(validated)
        return capabilities
    }

    private fun installConnectivityManager(
        activeNetwork: Network?,
        capabilities: NetworkCapabilities?
    ) {
        val manager = mock<ConnectivityManager>()
        whenever(manager.activeNetwork).thenReturn(activeNetwork)
        if (activeNetwork != null) {
            whenever(manager.getNetworkCapabilities(activeNetwork)).thenReturn(capabilities)
        }
        Shadows.shadowOf(application).setSystemService(Context.CONNECTIVITY_SERVICE, manager)
    }

    @Test
    fun `a network without the validated flag still counts as available`() {
        val network = mock<Network>()
        installConnectivityManager(network, capabilities(internet = true, validated = false))

        val monitor = NetworkMonitor(application)

        assertTrue(monitor.isNetworkAvailable())
    }

    @Test
    fun `a network without internet capability does not count as available`() {
        val network = mock<Network>()
        installConnectivityManager(network, capabilities(internet = false, validated = true))

        val monitor = NetworkMonitor(application)

        assertFalse(monitor.isNetworkAvailable())
    }

    @Test
    fun `the validated flag must not change the decision`() {
        val network = mock<Network>()

        installConnectivityManager(network, capabilities(internet = true, validated = false))
        val withoutProbe = NetworkMonitor(application)

        installConnectivityManager(network, capabilities(internet = true, validated = true))
        val withProbe = NetworkMonitor(application)

        assertTrue(withoutProbe.isNetworkAvailable())
        assertTrue(withProbe.isNetworkAvailable())
    }

    @Test
    fun `no network at all does not count as available`() {
        installConnectivityManager(activeNetwork = null, capabilities = null)

        val monitor = NetworkMonitor(application)

        assertFalse(monitor.isNetworkAvailable())
    }
}
