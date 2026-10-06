package com.nuvio.tv.core.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nuvio.tv.core.iptv.SetupLan
import java.net.Inet4Address
import java.net.NetworkInterface

object IptvSetupAddress {

    fun get(context: Context): String? {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return manager?.let { runCatching { fromNetworks(it) }.getOrNull() } ?: runCatching { fromInterfaces() }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun fromNetworks(manager: ConnectivityManager): String? {
        val active = manager.activeNetwork
        val networks = listOfNotNull(active) + manager.allNetworks.filter { it != active }
        val candidates = networks.filter { usable(manager, it) }.flatMap { network ->
            val link = manager.getLinkProperties(network) ?: return@flatMap emptyList()
            link.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().map { link.interfaceName to it.hostAddress }
        }
        return candidates.firstOrNull { (name, address) -> !SetupLan.isVirtualInterface(name) && SetupLan.isLanAddress(address) }?.second
    }

    private fun usable(manager: ConnectivityManager, network: Network): Boolean {
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    private fun fromInterfaces(): String? {
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false) }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList().filterIsInstance<Inet4Address>().map { networkInterface.name to it.hostAddress }
            }
        return SetupLan.preferred(candidates)
    }
}
