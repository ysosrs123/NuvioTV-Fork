package com.nuvio.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/** One settings-screen registration; no network requests or persistent route identifiers. */
internal class DiagnosticNetworkMonitor(context: Context, requireRouteFacts: Boolean = Build.VERSION.SDK_INT >= 26) : AutoCloseable {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val tracker = DiagnosticNetworkTracker(requireRouteFacts)
    private var registered = false
    private var closed = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { tracker.available(network) }
        override fun onLost(network: Network) { tracker.lost(network) }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) { tracker.blocked(network, blocked) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Ignore changing bandwidth estimates; include transport/VPN, validation and access policy.
            val supportedTransports = mutableListOf(NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_BLUETOOTH,
                NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN)
            if (Build.VERSION.SDK_INT >= 26) supportedTransports += NetworkCapabilities.TRANSPORT_WIFI_AWARE
            if (Build.VERSION.SDK_INT >= 27) supportedTransports += NetworkCapabilities.TRANSPORT_LOWPAN
            if (Build.VERSION.SDK_INT >= 31) supportedTransports += NetworkCapabilities.TRANSPORT_USB
            val transports = supportedTransports.filter { capabilities.hasTransport(it) }
            val access = listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED, NetworkCapabilities.NET_CAPABILITY_TRUSTED,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .filter { capabilities.hasCapability(it) }
            tracker.capabilities(network, "$transports|$access")
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            // Compare actual observed DNS/proxy/address/route changes without logging their values.
            val signature = listOf(properties.interfaceName, properties.domains, properties.mtu,
                properties.dnsServers.map { it.hostAddress }.sortedBy { it },
                properties.linkAddresses.map { it.toString() }.sorted(),
                properties.routes.map { it.toString() }.sorted(), properties.httpProxy?.toString()).toString()
            tracker.links(network, signature)
        }
    }
    @Synchronized fun start() {
        if (registered || closed || connectivity == null) return
        tracker.start()
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: RuntimeException) {
            tracker.close()
        }
    }
    fun token(): DiagnosticNetworkTracker.Token? = tracker.token(
        runCatching { connectivity?.activeNetwork }.getOrNull())
    @Synchronized override fun close() {
        if (closed) return
        closed = true; tracker.close()
        if (registered) {
            registered = false
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }
}
