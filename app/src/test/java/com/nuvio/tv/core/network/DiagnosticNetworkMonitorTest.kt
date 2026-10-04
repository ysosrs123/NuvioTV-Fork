package com.nuvio.tv.core.network

import android.content.Context
import android.net.*
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiagnosticNetworkMonitorTest {
    private val context = mockk<Context>()
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val network = mockk<Network>()
    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val caps = mockk<NetworkCapabilities>()
    private val links = mockk<LinkProperties>()
    private var dns = "first"
    @Before fun setup() {
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
        every { manager.activeNetwork } returns network
        every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
        every { caps.hasTransport(any()) } returns false; every { caps.hasCapability(any()) } returns false
        every { links.interfaceName } returns "test"; every { links.domains } answers { dns }
        every { links.mtu } returns 1500; every { links.dnsServers } returns emptyList()
        every { links.linkAddresses } returns emptyList(); every { links.routes } returns emptyList()
        every { links.httpProxy } returns null
    }
    private fun ready() {
        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, caps)
        callback.captured.onLinkPropertiesChanged(network, links)
    }
    @Test fun `one screen lifetime registers and unregisters once`() {
        val m = DiagnosticNetworkMonitor(context); m.start(); m.start(); ready(); assertNotNull(m.token())
        m.close(); m.close(); m.start(); assertNull(m.token())
        verify(exactly = 1) { manager.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
    }
    @Test fun `registration failure stays unavailable without unregistering nonexistent listener`() {
        every { manager.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws SecurityException()
        val m = DiagnosticNetworkMonitor(context); m.start(); assertNull(m.token()); m.close()
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }
    @Test fun `same network link change expires token`() {
        val m = DiagnosticNetworkMonitor(context); m.start(); ready(); val before = m.token()
        dns = "second"; callback.captured.onLinkPropertiesChanged(network, links)
        assertNotEquals(before, m.token()); m.close()
    }
    @Test fun `late callbacks after disposal cannot recreate a usable observation`() {
        val m = DiagnosticNetworkMonitor(context); m.start(); ready(); m.close()
        ready(); assertNull(m.token())
    }

    @Test fun `framework blocked status expires usable observation`() {
        val m = DiagnosticNetworkMonitor(context); m.start(); ready(); val token = m.token()
        callback.captured.onBlockedStatusChanged(network, true); assertNull(m.token())
        callback.captured.onBlockedStatusChanged(network, false); assertNotEquals(token, m.token()); m.close()
    }

}
