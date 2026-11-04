package com.nuvio.tv.core.di

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.wetrakr.AndroidWeTrakrAuthPersistence
import com.nuvio.tv.data.wetrakr.AndroidWeTrakrSyncStorage
import com.nuvio.tv.data.wetrakr.OkHttpWeTrakrEngine
import com.nuvio.tv.data.wetrakr.WeTrakrApiClient
import com.nuvio.tv.data.wetrakr.WeTrakrAuthRepository
import com.nuvio.tv.data.wetrakr.WeTrakrAuthStore
import com.nuvio.tv.data.wetrakr.WeTrakrConfiguration
import com.nuvio.tv.data.wetrakr.WeTrakrHistoryService
import com.nuvio.tv.data.wetrakr.WeTrakrHttpClient
import com.nuvio.tv.data.wetrakr.WeTrakrScrobbleService
import com.nuvio.tv.data.wetrakr.WeTrakrSyncRepository
import com.nuvio.tv.data.wetrakr.WeTrakrTrackingHistoryWriter
import com.nuvio.tv.data.wetrakr.WeTrakrTrackingLibraryProvider
import com.nuvio.tv.data.wetrakr.WeTrakrTrackingProgressProvider
import com.nuvio.tv.data.wetrakr.WeTrakrTrackingProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
object WeTrakrModule {
    @Provides
    @Singleton
    fun configuration(): WeTrakrConfiguration = WeTrakrConfiguration(
        clientId = BuildConfig.WETRAKR_CLIENT_ID,
        appVersion = BuildConfig.VERSION_NAME
    )

    @Provides
    @Singleton
    fun http(configuration: WeTrakrConfiguration): WeTrakrHttpClient = WeTrakrHttpClient(
        OkHttpWeTrakrEngine(
            OkHttpClient.Builder()
                .dns(IPv4FirstDns())
                .socketFactory(NoDelaySocketFactory)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build(),
            configuration
        )
    )

    @Provides
    @Singleton
    fun authStore(persistence: AndroidWeTrakrAuthPersistence, profileDataStore: ProfileDataStore): WeTrakrAuthStore {
        val store = WeTrakrAuthStore(persistence)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: WeTrakrAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun auth(http: WeTrakrHttpClient, configuration: WeTrakrConfiguration, store: WeTrakrAuthStore) =
        WeTrakrAuthRepository(http, configuration, store)

    @Provides
    @Singleton
    fun api(http: WeTrakrHttpClient, auth: WeTrakrAuthRepository, store: WeTrakrAuthStore) =
        WeTrakrApiClient(http, auth, store)

    @Provides
    @Singleton
    fun syncRepository(
        storage: AndroidWeTrakrSyncStorage,
        auth: WeTrakrAuthStore,
        api: WeTrakrApiClient,
        profiles: ProfileManager
    ) = WeTrakrSyncRepository(storage, auth, api, profiles.activeProfileId, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun scrobbles(api: WeTrakrApiClient, sync: WeTrakrSyncRepository, configuration: WeTrakrConfiguration) =
        WeTrakrScrobbleService(api, sync, configuration.appVersion, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun history(api: WeTrakrApiClient, sync: WeTrakrSyncRepository) = WeTrakrHistoryService(api, sync)

    @Provides
    @IntoSet
    fun trackingProvider(provider: WeTrakrTrackingProvider): TrackingProvider = provider

    @Provides
    @IntoSet
    fun progressProvider(provider: WeTrakrTrackingProgressProvider): TrackingProgressProvider = provider

    @Provides
    @IntoSet
    fun libraryProvider(provider: WeTrakrTrackingLibraryProvider): TrackingLibraryProvider = provider

    @Provides
    @IntoSet
    fun historyWriter(writer: WeTrakrTrackingHistoryWriter): TrackingHistoryWriter = writer
}
