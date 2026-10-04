package com.nuvio.tv.core.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import java.net.InetAddress
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.ServerTrust
import com.nuvio.tv.core.network.withServerTrust
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.sync.SyncClientIdentity
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.mediaserver.AndroidServerPersistence
import com.nuvio.tv.data.mediaserver.AndroidServerResumeImports
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerResumeImports
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object MediaServerModule {
    @Provides
    @Singleton
    fun repository(
        @ApplicationContext context: Context,
        persistence: AndroidServerPersistence,
        identity: SyncClientIdentity,
        profileDataStore: ProfileDataStore
    ): ServerRepository {
        val http = OkHttpClient.Builder()
            .withServerTrust()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
            .also { ServerTrust.closeConnectionsOnWithdrawal(it.connectionPool) }
        val clientIdentity = ServerClientIdentity(
            device = Build.MODEL.orEmpty().ifBlank { "Android TV" },
            version = BuildConfig.VERSION_NAME,
            deviceId = identity::currentClientId
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = ServerRepository(
            persistence = persistence,
            providers = listOf(
                JellyfinProvider(http, clientIdentity),
                EmbyProvider(http, clientIdentity),
                SiloProvider(http, clientIdentity)
            ),
            scope = scope,
            isOnCurrentNetwork = { host -> isOnCurrentNetwork(context, host) }
        )
        scope.launch { profileDataStore.activeProfileId.collect(repository::selectProfile) }
        watchNetworkChanges(context, repository)
        return repository
    }

    /** True when [host] is an address literal inside one of the active network's own subnets. */
    private fun isOnCurrentNetwork(context: Context, host: String): Boolean {
        if (!IP_LITERAL.matches(host)) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val links = runCatching { connectivity.getLinkProperties(connectivity.activeNetwork) }.getOrNull() ?: return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return links.linkAddresses.any { link -> sameSubnet(address, link.address, link.prefixLength) }
    }

    private fun sameSubnet(address: InetAddress, network: InetAddress, prefixLength: Int): Boolean {
        val a = address.address
        val b = network.address
        if (a.size != b.size) return false
        var bits = prefixLength
        for (i in a.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    private val IP_LITERAL = Regex("""^(\d{1,3}\.){3}\d{1,3}$|^\[?[0-9a-fA-F]*:[0-9a-fA-F:]*]?$""")

    private fun watchNetworkChanges(context: Context, repository: ServerRepository) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null

                override fun onAvailable(network: Network) {
                    val previous = current
                    current = network
                    if (previous != null && previous != network) repository.onNetworkChanged()
                }
            })
        }
    }

    @Provides
    fun resumeImports(imports: AndroidServerResumeImports): ServerResumeImports = imports

    @Provides
    @IntoSet
    fun credentialStore(repository: ServerRepository): ProfileScopedCredentialStore = repository
}
